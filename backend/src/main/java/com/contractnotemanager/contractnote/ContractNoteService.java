package com.contractnotemanager.contractnote;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.contractnotemanager.domain.Broker;
import com.contractnotemanager.domain.ContractNote;
import com.contractnotemanager.domain.ContractNoteStatus;
import com.contractnotemanager.domain.Order;
import com.contractnotemanager.domain.OrderAllocation;
import com.contractnotemanager.domain.OrderStatus;
import com.contractnotemanager.domain.OrderStatusHistory;
import com.contractnotemanager.domain.StatusTrigger;
import com.contractnotemanager.repository.BrokerRepository;
import com.contractnotemanager.repository.ContractNoteRepository;
import com.contractnotemanager.repository.OrderRepository;
import com.contractnotemanager.repository.OrderStatusHistoryRepository;
import com.contractnotemanager.service.AllocationMath;
import com.contractnotemanager.web.dto.ContractNoteDto;
import com.contractnotemanager.web.dto.UpdateNoteRequest;
import com.contractnotemanager.web.dto.UploadResult;
import com.contractnotemanager.web.error.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Upload → Claude reads the PDF → six checks against the orders → Matched / Partially matched / No match.
 * A match confirms the order. A partial match is linked to its order, which can take the note's values.
 * Open notes are evaluated again whenever orders change ("re-evaluate").
 */
@Service
public class ContractNoteService {

    private static final Logger log = LoggerFactory.getLogger(ContractNoteService.class);
    /** Notes that are not matched yet and are re-evaluated when orders change. */
    private static final List<ContractNoteStatus> REEVALUATED = List.of(ContractNoteStatus.PARTIALLY_MATCHED,
            ContractNoteStatus.NO_MATCH);
    private static final List<ContractNoteStatus> OPEN = List.of(ContractNoteStatus.PARTIALLY_MATCHED,
            ContractNoteStatus.NO_MATCH, ContractNoteStatus.EXTRACTION_FAILED);

    private final ContractNoteRepository notes;
    private final OrderRepository orders;
    private final OrderStatusHistoryRepository history;
    private final BrokerRepository brokers;
    private final ContractNoteExtractor extractor;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public ContractNoteService(ContractNoteRepository notes, OrderRepository orders,
            OrderStatusHistoryRepository history, BrokerRepository brokers, ContractNoteExtractor extractor,
            ObjectMapper json, PlatformTransactionManager txManager) {
        this.notes = notes;
        this.orders = orders;
        this.history = history;
        this.brokers = brokers;
        this.extractor = extractor;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
    }

    // ---------------------------------------------------------------- upload

    /** Handles the PDFs dropped in the bulk-action area; the ticked orders are the match candidates. */
    public List<UploadResult> upload(List<MultipartFile> files, List<Long> orderIds) {
        List<UploadResult> results = new ArrayList<>();
        for (MultipartFile file : files) {
            String fileName = file.getOriginalFilename() == null ? "contract-note.pdf" : file.getOriginalFilename();
            try {
                results.add(processFile(fileName, file.getBytes(), orderIds == null ? List.of() : orderIds));
            } catch (IOException e) {
                results.add(new UploadResult(fileName, UploadResult.Outcome.INVALID_FILE, null, null, null,
                        "The file could not be read"));
            }
        }
        return results;
    }

    UploadResult processFile(String fileName, byte[] content, List<Long> orderIds) {
        if (content.length < 5 || !new String(Arrays.copyOf(content, 5), StandardCharsets.US_ASCII).equals("%PDF-")) {
            return new UploadResult(fileName, UploadResult.Outcome.INVALID_FILE, null, null, null, "Not a PDF file");
        }
        String sha = sha256(content);
        var duplicate = notes.findByFileSha256(sha);
        if (duplicate.isPresent()) {
            return new UploadResult(fileName, UploadResult.Outcome.DUPLICATE, duplicate.get().getId(), null, null,
                    "This PDF was already uploaded (" + duplicate.get().getFileName() + ")");
        }
        ContractNote note = new ContractNote();
        note.setFileName(fileName);
        note.setFileSha256(sha);
        note.setFileContent(content);

        NoteFields fields;
        try {
            fields = read(note, content, fileName);
        } catch (ExtractionException e) {
            note.setStatus(ContractNoteStatus.EXTRACTION_FAILED);
            note.setUnmatchedReason("The PDF could not be read: " + e.getMessage());
            ContractNote saved = notes.save(note);
            return new UploadResult(fileName, UploadResult.Outcome.EXTRACTION_FAILED, saved.getId(), null, null,
                    saved.getUnmatchedReason());
        }
        return tx.execute(status -> {
            List<Order> candidates = orderIds.isEmpty() ? List.of()
                    : orders.findAllWithAssetByIdIn(orderIds).stream()
                            .filter(o -> notes.findByOrderId(o.getId()).isEmpty())
                            .toList();
            return finish(note, ContractNoteMatcher.match(fields, candidates, "selected"));
        });
    }

    /** Claude reads the PDF; the fields are stored on the note. Runs outside a transaction (it takes seconds). */
    private NoteFields read(ContractNote note, byte[] content, String fileName) {
        ContractNoteExtractor.Result extraction = extractor.extract(content, fileName);
        NoteFields fields = NoteFields.from(extraction.fields());
        note.setExtractionModel(extraction.model());
        note.setExtractionJson(toJson(extraction.fields()));
        apply(note, fields);
        return fields;
    }

    // ---------------------------------------------------------------- re-evaluation

    public record ReevaluationSummary(int evaluated, int matched, int partiallyMatched, int noMatch) {
    }

    /** Evaluates one note again against all orders it could belong to. */
    public UploadResult reevaluate(Long id) {
        return tx.execute(status -> {
            ContractNote note = find(id);
            requireNotMatched(note);
            if (note.getStatus() == ContractNoteStatus.EXTRACTION_FAILED) {
                throw ApiException.conflict("The PDF has not been read yet – use 'Read PDF again' first");
            }
            return evaluate(note);
        });
    }

    /** Evaluates every partially matched and unmatched note again, e.g. after orders were changed or imported. */
    public ReevaluationSummary reevaluateOpen() {
        return tx.execute(status -> {
            Map<ContractNoteStatus, Integer> counts = new EnumMap<>(ContractNoteStatus.class);
            List<ContractNote> open = new ArrayList<>(notes.findByStatusInOrderByCreatedAtDesc(REEVALUATED));
            open.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt())); // oldest note first
            for (ContractNote note : open) {
                ContractNote fresh = find(note.getId());
                if (!REEVALUATED.contains(fresh.getStatus())) {
                    continue; // changed while looping (another note took its order)
                }
                evaluate(fresh);
                counts.merge(fresh.getStatus(), 1, Integer::sum);
            }
            ReevaluationSummary summary = new ReevaluationSummary(open.size(),
                    counts.getOrDefault(ContractNoteStatus.MATCHED, 0),
                    counts.getOrDefault(ContractNoteStatus.PARTIALLY_MATCHED, 0),
                    counts.getOrDefault(ContractNoteStatus.NO_MATCH, 0));
            if (!open.isEmpty()) {
                log.info("Re-evaluated {} contract notes: {} matched, {} partially matched, {} no match",
                        summary.evaluated(), summary.matched(), summary.partiallyMatched(), summary.noMatch());
            }
            return summary;
        });
    }

    private UploadResult evaluate(ContractNote note) {
        NoteFields fields = fieldsOf(note);
        List<Order> candidates = fields.isin() == null ? List.of() : orders.findMatchCandidates(fields.isin(), note.getId());
        return finish(note, ContractNoteMatcher.match(fields, candidates, "traded"));
    }

    private UploadResult finish(ContractNote note, ContractNoteMatcher.Result result) {
        note.setStatus(result.status());
        note.setUnmatchedReason(result.reason());
        note.setMatchScore(result.checks().isEmpty() ? null : result.score());
        note.setMatchChecks(result.checks().isEmpty() ? null : toJson(result.checks()));
        note.setOrder(result.order());
        note.setMatchedAt(result.status() == ContractNoteStatus.MATCHED ? Instant.now() : null);
        notes.saveAndFlush(note);

        String fileName = note.getFileName();
        Order order = result.order();
        return switch (result.status()) {
            case MATCHED -> {
                history.save(new OrderStatusHistory(order.getId(), order.getStatus(), OrderStatus.CONFIRMED,
                        StatusTrigger.CONTRACT_NOTE, "Matched with contract note " + fileName));
                order.setStatus(OrderStatus.CONFIRMED);
                orders.save(order);
                log.info("Contract note {} matched with order {}", fileName, order.getId());
                String label = ContractNoteMatcher.label(order);
                yield new UploadResult(fileName, UploadResult.Outcome.MATCHED, note.getId(), order.getId(), label,
                        "Matched with " + label + ", order confirmed");
            }
            case PARTIALLY_MATCHED -> {
                String label = ContractNoteMatcher.label(order);
                yield new UploadResult(fileName, UploadResult.Outcome.PARTIALLY_MATCHED, note.getId(), order.getId(),
                        label, "Partially matched with " + label + ": " + result.reason());
            }
            default -> new UploadResult(fileName, UploadResult.Outcome.NO_MATCH, note.getId(), null, null,
                    "No match: " + result.reason());
        };
    }

    // ---------------------------------------------------------------- update the order from the note

    /** Order properties a contract note can overwrite. */
    public enum OrderField {
        PRICE, QUANTITY, COMMISSION, BROKER
    }

    /**
     * Overwrites the chosen properties of a partially matched note's order with the note's values
     * (all four when none are given), then evaluates the note again – it becomes Matched when all checks pass.
     */
    public UploadResult applyToOrder(Long id, java.util.Set<OrderField> chosen) {
        java.util.Set<OrderField> fields = chosen == null || chosen.isEmpty()
                ? java.util.EnumSet.allOf(OrderField.class) : chosen;
        return tx.execute(status -> {
            ContractNote note = find(id);
            if (note.getStatus() != ContractNoteStatus.PARTIALLY_MATCHED || note.getOrder() == null) {
                throw ApiException.conflict("Only a partially matched note can update its order");
            }
            Order order = note.getOrder();
            if (!order.getStatus().isEditable()) {
                throw ApiException.conflict("The order is " + order.getStatus().label() + " and can no longer be changed");
            }
            if (fields.contains(OrderField.PRICE)) {
                order.setPrice(note.getPrice());
            }
            if (fields.contains(OrderField.COMMISSION)) {
                order.setCommission(note.getCommission());
            }
            if (fields.contains(OrderField.QUANTITY) && !order.isAmountOrder()
                    && note.getQuantity().compareTo(order.getValue()) != 0) {
                int decimals = order.getAsset().getQtyDecimals() == null ? 0 : order.getAsset().getQtyDecimals();
                List<BigDecimal> scaled = AllocationMath.scaleQuantities(
                        order.getAllocations().stream().map(OrderAllocation::getValue).toList(),
                        note.getQuantity(), decimals);
                for (int i = 0; i < scaled.size(); i++) {
                    OrderAllocation a = order.getAllocations().get(i);
                    a.setValue(scaled.get(i));
                    AllocationMath.recalculatePostTrade(a, order.isSell());
                }
                order.setValue(note.getQuantity());
            }
            if (fields.contains(OrderField.BROKER) && note.getBroker() != null) {
                order.setBroker(brokers.findFirstByNameIgnoreCase(note.getBroker()).orElseGet(() -> {
                    Broker b = new Broker();
                    b.setName(note.getBroker());
                    return brokers.save(b);
                }));
            }
            List<BigDecimal> commissions = AllocationMath.splitCommission(order.getCommission(),
                    order.getAllocations().stream().map(OrderAllocation::getValue).toList());
            for (int i = 0; i < commissions.size(); i++) {
                order.getAllocations().get(i).setCommission(commissions.get(i));
            }
            order.setSettlementAmount(AllocationMath.settlementAmount(order.isAmountOrder(), order.isSell(),
                    order.getPrice(), order.getValue()));
            order.setLocallyModified(true);
            orders.saveAndFlush(order);
            log.info("Order {} updated from contract note {} ({})", order.getId(), note.getFileName(), fields);
            return evaluate(note);
        });
    }

    // ---------------------------------------------------------------- read again, correct, list, delete

    /** Lets Claude read the stored PDF again (e.g. after a failed read), then evaluates the note. */
    public UploadResult reread(Long id) {
        ContractNote note = tx.execute(s -> {
            ContractNote n = find(id);
            requireNotMatched(n);
            n.getFileContent(); // load the PDF while the transaction is open
            return n;
        });
        try {
            read(note, note.getFileContent(), note.getFileName());
        } catch (ExtractionException e) {
            return tx.execute(s -> {
                ContractNote n = find(id);
                n.setStatus(ContractNoteStatus.EXTRACTION_FAILED);
                n.setUnmatchedReason("The PDF could not be read: " + e.getMessage());
                notes.save(n);
                return new UploadResult(n.getFileName(), UploadResult.Outcome.EXTRACTION_FAILED, n.getId(), null,
                        null, n.getUnmatchedReason());
            });
        }
        return tx.execute(s -> {
            ContractNote n = find(id);
            n.setExtractionModel(note.getExtractionModel());
            n.setExtractionJson(note.getExtractionJson());
            apply(n, fieldsOf(note));
            return evaluate(n);
        });
    }

    /** Corrects the values read from the PDF and evaluates the note again. */
    public UploadResult update(Long id, UpdateNoteRequest req) {
        return tx.execute(s -> {
            ContractNote note = find(id);
            requireNotMatched(note);
            NoteFields fields = NoteFields.parse(req.instrumentName(), req.isin(), req.currency(), req.quantity(),
                    req.price(), req.settlementAmount(), req.broker(), req.commission(), req.side(),
                    note.getTradeDate() == null ? null : note.getTradeDate().toString(), List.of());
            apply(note, fields);
            return evaluate(note);
        });
    }

    public List<ContractNoteDto> list(ContractNoteStatus status) {
        List<ContractNoteStatus> statuses = status == null ? List.of(ContractNoteStatus.values()) : List.of(status);
        return tx.execute(s -> notes.findByStatusInOrderByCreatedAtDesc(statuses).stream().map(this::toDto).toList());
    }

    public Map<String, Long> counts() {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (ContractNoteStatus status : ContractNoteStatus.values()) {
            counts.put(status.name(), notes.countByStatusIn(List.of(status)));
        }
        counts.put("open", notes.countByStatusIn(OPEN));
        return counts;
    }

    public ContractNoteDto get(Long id) {
        return tx.execute(s -> toDto(find(id)));
    }

    public ContractNoteDto forOrder(Long orderId) {
        return tx.execute(s -> notes.findByOrderId(orderId).map(this::toDto)
                .orElseThrow(() -> ApiException.notFound("Contract note for order", orderId)));
    }

    public NamedFile file(Long id) {
        return tx.execute(s -> {
            ContractNote note = find(id);
            return new NamedFile(note.getFileName(), note.getFileContent());
        });
    }

    public record NamedFile(String name, byte[] content) {
    }

    public void delete(Long id) {
        tx.executeWithoutResult(s -> {
            ContractNote note = find(id);
            requireNotMatched(note);
            notes.delete(note);
        });
    }

    /** Called when an order is deleted: its note is no longer linked and will be evaluated again. */
    public void unlinkOrder(Long orderId, String orderLabel) {
        notes.findByOrderId(orderId).ifPresent(note -> {
            note.setOrder(null);
            note.setMatchedAt(null);
            note.setStatus(ContractNoteStatus.NO_MATCH);
            note.setUnmatchedReason("The linked order (" + orderLabel + ") was deleted");
            notes.saveAndFlush(note);
        });
    }

    // ---------------------------------------------------------------- helpers

    private void apply(ContractNote note, NoteFields f) {
        note.setInstrumentName(f.instrumentName());
        note.setIsin(f.isin());
        note.setCurrencyCode(f.currency());
        note.setQuantity(f.quantity());
        note.setPrice(f.price());
        note.setSettlementAmount(f.settlementAmount());
        note.setBroker(f.broker());
        note.setCommission(f.commission());
        note.setSide(f.side());
        note.setTradeDate(f.tradeDate());
        note.setWarnings(f.warnings().isEmpty() ? null : String.join("\n", f.warnings()));
    }

    private NoteFields fieldsOf(ContractNote n) {
        return NoteFields.parse(n.getInstrumentName(), n.getIsin(), n.getCurrencyCode(), str(n.getQuantity()),
                str(n.getPrice()), str(n.getSettlementAmount()), n.getBroker(), str(n.getCommission()), n.getSide(),
                n.getTradeDate() == null ? null : n.getTradeDate().toString(), List.of());
    }

    private static String str(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private ContractNoteDto toDto(ContractNote n) {
        Order order = n.getOrder();
        return new ContractNoteDto(n.getId(), n.getFileName(), n.getStatus(), n.getUnmatchedReason(),
                n.getInstrumentName(), n.getIsin(), n.getCurrencyCode(), n.getQuantity(), n.getPrice(),
                n.getSettlementAmount(), n.getBroker(), n.getCommission(), n.getSide(), n.getTradeDate(),
                n.getWarnings() == null ? List.of() : List.of(n.getWarnings().split("\n")),
                n.getMatchScore(), ContractNoteMatcher.CHECK_COUNT, checksOf(n),
                order == null ? null : order.getId(),
                order == null ? null : ContractNoteMatcher.label(order),
                order == null ? null : order.getStatus().isEditable(),
                n.getMatchedAt(), n.getCreatedAt(), n.getExtractionModel());
    }

    private List<ContractNoteMatcher.Check> checksOf(ContractNote n) {
        if (n.getMatchChecks() == null) {
            return List.of();
        }
        try {
            return json.readValue(n.getMatchChecks(), new TypeReference<List<ContractNoteMatcher.Check>>() {
            });
        } catch (IOException e) {
            return List.of();
        }
    }

    private void requireNotMatched(ContractNote note) {
        if (note.getStatus() == ContractNoteStatus.MATCHED) {
            throw ApiException.conflict("This contract note is matched; delete the order to start over");
        }
    }

    private ContractNote find(Long id) {
        return notes.findById(id).orElseThrow(() -> ApiException.notFound("Contract note", id));
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
