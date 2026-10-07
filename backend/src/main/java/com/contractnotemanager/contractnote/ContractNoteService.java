package com.contractnotemanager.contractnote;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.contractnotemanager.domain.ContractNote;
import com.contractnotemanager.domain.ContractNoteStatus;
import com.contractnotemanager.domain.Order;
import com.contractnotemanager.domain.OrderStatus;
import com.contractnotemanager.domain.OrderStatusHistory;
import com.contractnotemanager.domain.StatusTrigger;
import com.contractnotemanager.repository.ContractNoteRepository;
import com.contractnotemanager.repository.OrderRepository;
import com.contractnotemanager.repository.OrderStatusHistoryRepository;
import com.contractnotemanager.web.dto.ContractNoteDto;
import com.contractnotemanager.web.dto.UpdateNoteRequest;
import com.contractnotemanager.web.dto.UploadResult;
import com.contractnotemanager.web.error.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Upload → Claude reads the PDF → checks → match against the ticked orders (plan section 4.4.2).
 * Nothing on the order is overwritten by the note; a match only links the note and confirms the order.
 */
@Service
public class ContractNoteService {

    private static final Logger log = LoggerFactory.getLogger(ContractNoteService.class);
    private static final List<ContractNoteStatus> OPEN = List.of(ContractNoteStatus.UNMATCHED,
            ContractNoteStatus.EXTRACTION_FAILED);

    private final ContractNoteRepository notes;
    private final OrderRepository orders;
    private final OrderStatusHistoryRepository history;
    private final ContractNoteExtractor extractor;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public ContractNoteService(ContractNoteRepository notes, OrderRepository orders,
            OrderStatusHistoryRepository history, ContractNoteExtractor extractor, ObjectMapper json,
            PlatformTransactionManager txManager) {
        this.notes = notes;
        this.orders = orders;
        this.history = history;
        this.extractor = extractor;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
    }

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

        ContractNoteExtractor.Result extraction;
        try {
            extraction = extractor.extract(content, fileName);
        } catch (ExtractionException e) {
            note.setStatus(ContractNoteStatus.EXTRACTION_FAILED);
            note.setUnmatchedReason("The PDF could not be read: " + e.getMessage());
            ContractNote saved = notes.save(note);
            return new UploadResult(fileName, UploadResult.Outcome.EXTRACTION_FAILED, saved.getId(), null, null,
                    saved.getUnmatchedReason());
        }
        NoteFields fields = NoteFields.from(extraction.fields());
        note.setExtractionModel(extraction.model());
        note.setExtractionJson(toJson(extraction.fields()));

        return tx.execute(status -> {
            apply(note, fields);
            List<Order> candidates = orderIds.isEmpty() ? List.of()
                    : orders.findAllWithAssetByIdIn(orderIds).stream()
                            .filter(o -> notes.findByOrderId(o.getId()).isEmpty())
                            .toList();
            ContractNoteMatcher.Result match = ContractNoteMatcher.match(fields, candidates, "selected");
            return finish(note, match, fileName);
        });
    }

    /** "Match again" from the unmatched list: tries every TRADED order without a note. */
    public UploadResult rematch(Long id) {
        return tx.execute(status -> {
            ContractNote note = find(id);
            requireOpen(note);
            NoteFields fields = fieldsOf(note);
            List<Order> candidates = fields.isin() == null ? List.of()
                    : orders.findMatchCandidates(OrderStatus.TRADED, fields.isin());
            return finish(note, ContractNoteMatcher.match(fields, candidates, "traded"), note.getFileName());
        });
    }

    private UploadResult finish(ContractNote note, ContractNoteMatcher.Result match, String fileName) {
        if (!match.matched()) {
            note.setStatus(ContractNoteStatus.UNMATCHED);
            note.setUnmatchedReason(match.reason());
            notes.save(note);
            return new UploadResult(fileName, UploadResult.Outcome.UNMATCHED, note.getId(), null, null,
                    "Not matched: " + match.reason());
        }
        Order order = match.order();
        note.setStatus(ContractNoteStatus.MATCHED);
        note.setUnmatchedReason(null);
        note.setOrder(order);
        note.setMatchedAt(Instant.now());
        notes.save(note);
        history.save(new OrderStatusHistory(order.getId(), order.getStatus(), OrderStatus.CONFIRMED,
                StatusTrigger.CONTRACT_NOTE, "Matched with contract note " + fileName));
        order.setStatus(OrderStatus.CONFIRMED);
        orders.save(order);
        log.info("Contract note {} matched with order {}", fileName, order.getId());
        String label = ContractNoteMatcher.label(order);
        return new UploadResult(fileName, UploadResult.Outcome.MATCHED, note.getId(), order.getId(), label,
                "Matched with " + label + ", order confirmed");
    }

    public List<ContractNoteDto> listOpen() {
        return tx.execute(s -> notes.findByStatusInOrderByCreatedAtDesc(OPEN).stream().map(this::toDto).toList());
    }

    public long countOpen() {
        return notes.countByStatusIn(OPEN);
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

    /** Corrects the values read from an unmatched note; matching is triggered separately ("Match again"). */
    public ContractNoteDto update(Long id, UpdateNoteRequest req) {
        return tx.execute(s -> {
            ContractNote note = find(id);
            requireOpen(note);
            NoteFields fields = NoteFields.parse(req.instrumentName(), req.isin(), req.currency(), req.quantity(),
                    req.price(), req.settlementAmount(), req.broker(), req.commission(), req.side(),
                    note.getTradeDate() == null ? null : note.getTradeDate().toString(), List.of());
            apply(note, fields);
            note.setStatus(ContractNoteStatus.UNMATCHED);
            note.setUnmatchedReason(fields.isComplete() ? "Values corrected, not matched yet"
                    : "Check the values: " + String.join("; ", fields.errors()));
            return toDto(notes.save(note));
        });
    }

    public void delete(Long id) {
        tx.executeWithoutResult(s -> {
            ContractNote note = find(id);
            requireOpen(note);
            notes.delete(note);
        });
    }

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
                order == null ? null : order.getId(),
                order == null ? null : ContractNoteMatcher.label(order),
                n.getMatchedAt(), n.getCreatedAt(), n.getExtractionModel());
    }

    private void requireOpen(ContractNote note) {
        if (note.getStatus() == ContractNoteStatus.MATCHED) {
            throw ApiException.conflict("This contract note is matched; delete the order to start over");
        }
    }

    private ContractNote find(Long id) {
        return notes.findById(id).orElseThrow(() -> ApiException.notFound("Contract note", id));
    }

    private String toJson(ContractNoteExtraction e) {
        try {
            return json.writeValueAsString(e);
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
