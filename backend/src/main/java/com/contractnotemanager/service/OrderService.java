package com.contractnotemanager.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.contractnotemanager.domain.Broker;
import com.contractnotemanager.domain.ContractNoteStatus;
import com.contractnotemanager.domain.Order;
import com.contractnotemanager.domain.OrderAllocation;
import com.contractnotemanager.domain.OrderStatus;
import com.contractnotemanager.domain.OrderStatusHistory;
import com.contractnotemanager.domain.Portfolio;
import com.contractnotemanager.domain.StatusTrigger;
import com.contractnotemanager.importer.OrderPayloadMapper;
import com.contractnotemanager.repository.BrokerRepository;
import com.contractnotemanager.repository.ContractNoteRepository;
import com.contractnotemanager.repository.OrderRepository;
import com.contractnotemanager.repository.OrderStatusHistoryRepository;
import com.contractnotemanager.repository.OwnerRepository;
import com.contractnotemanager.repository.PortfolioRepository;
import com.contractnotemanager.web.dto.BulkRequest;
import com.contractnotemanager.web.dto.BulkResult;
import com.contractnotemanager.web.dto.OrderDto;
import com.contractnotemanager.web.dto.OrderFilter;
import com.contractnotemanager.web.dto.StatusHistoryDto;
import com.contractnotemanager.web.dto.UpdateOrderRequest;
import com.contractnotemanager.web.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Orders: list, edit (plan 4.4), status workflow (4.4.1), bulk actions, delete and revert. */
@Service
public class OrderService {

    private final OrderRepository orders;
    private final OrderStatusHistoryRepository history;
    private final ContractNoteRepository notes;
    private final BrokerRepository brokers;
    private final OwnerRepository owners;
    private final PortfolioRepository portfolios;
    private final OrderPayloadMapper payloadMapper;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public OrderService(OrderRepository orders, OrderStatusHistoryRepository history, ContractNoteRepository notes,
            BrokerRepository brokers, OwnerRepository owners, PortfolioRepository portfolios,
            OrderPayloadMapper payloadMapper, ObjectMapper json, PlatformTransactionManager txManager) {
        this.orders = orders;
        this.history = history;
        this.notes = notes;
        this.brokers = brokers;
        this.owners = owners;
        this.portfolios = portfolios;
        this.payloadMapper = payloadMapper;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
    }

    @Transactional(readOnly = true)
    public Page<OrderDto> search(OrderFilter filter, Pageable pageable) {
        return orders.findAll(OrderSpecifications.of(filter), pageable).map(o -> OrderMapper.toDto(o, false));
    }

    @Transactional(readOnly = true)
    public OrderDto get(Long id) {
        return OrderMapper.toDto(find(id), true);
    }

    @Transactional(readOnly = true)
    public List<StatusHistoryDto> statusHistory(Long id) {
        find(id);
        return history.findByOrderIdOrderByChangedAtAscIdAsc(id).stream()
                .map(h -> new StatusHistoryDto(h.getFromStatus(), h.getToStatus(), h.getChangedAt(), h.getTrigger(),
                        h.getNote()))
                .toList();
    }

    /** Saves the edit modal: price, commission, broker, owner and the allocation quantities. */
    @Transactional
    public OrderDto update(Long id, UpdateOrderRequest req) {
        Order o = find(id);
        if (req.version() != o.getVersion()) {
            throw ApiException.conflict("The order was changed in the meantime. Please reload it.");
        }
        if (!o.getStatus().isEditable()) {
            throw ApiException.conflict("The order is " + o.getStatus().label()
                    + " and can no longer be edited (locked after the contract note match)");
        }
        if (req.price() != null) {
            if (req.price().signum() <= 0) {
                throw ApiException.badRequest("Price must be greater than 0");
            }
            o.setPrice(req.price());
        }
        if (req.commission() != null) {
            if (req.commission().signum() < 0) {
                throw ApiException.badRequest("Commission cannot be negative");
            }
            o.setCommission(req.commission());
        }
        if (req.brokerId() != null) {
            o.setBroker(brokers.findById(req.brokerId())
                    .orElseThrow(() -> ApiException.badRequest("Unknown broker " + req.brokerId())));
        } else if (req.brokerName() != null && !req.brokerName().isBlank()) {
            String name = req.brokerName().trim();
            if (name.length() > 200) {
                throw ApiException.badRequest("Broker name is too long (max 200 characters)");
            }
            o.setBroker(brokers.findFirstByNameIgnoreCase(name).orElseGet(() -> {
                Broker b = new Broker();
                b.setName(name);
                return brokers.save(b);
            }));
        }
        if (req.ownerId() != null) {
            o.setOwner(owners.findById(req.ownerId())
                    .orElseThrow(() -> ApiException.badRequest("Unknown owner " + req.ownerId())));
        }
        if (req.allocations() != null) {
            applyAllocations(o, req.allocations());
        }
        List<BigDecimal> quantities = o.getAllocations().stream().map(OrderAllocation::getValue).toList();
        List<BigDecimal> commissions = AllocationMath.splitCommission(o.getCommission(), quantities);
        for (int i = 0; i < o.getAllocations().size(); i++) {
            o.getAllocations().get(i).setCommission(commissions.get(i));
        }
        o.setSettlementAmount(AllocationMath.settlementAmount(o.isAmountOrder(), o.isSell(), o.getPrice(), o.getValue()));
        o.setLocallyModified(true);
        orders.saveAndFlush(o);
        return OrderMapper.toDto(o, true);
    }

    private void applyAllocations(Order o, List<UpdateOrderRequest.AllocationInput> inputs) {
        int maxDecimals = o.isAmountOrder() ? 2 : (o.getAsset().getQtyDecimals() == null ? 0 : o.getAsset().getQtyDecimals());
        Set<Long> seen = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (UpdateOrderRequest.AllocationInput in : inputs) {
            if (!seen.add(in.portfolioId())) {
                throw ApiException.badRequest("A portfolio appears twice in the allocations");
            }
            if (in.quantity().signum() < 0) {
                throw ApiException.badRequest("Quantities cannot be negative");
            }
            if (in.quantity().stripTrailingZeros().scale() > maxDecimals) {
                throw ApiException.badRequest(maxDecimals == 0
                        ? "Quantities must be whole numbers for " + o.getAsset().getName()
                        : "Quantities can have at most " + maxDecimals + " decimals");
            }
            total = total.add(in.quantity());
        }
        if (total.signum() <= 0) {
            throw ApiException.badRequest("The total quantity must be greater than 0");
        }
        Map<Long, OrderAllocation> existing = new HashMap<>();
        o.getAllocations().forEach(a -> existing.put(a.getPortfolio().getId(), a));
        o.getAllocations().removeIf(a -> !seen.contains(a.getPortfolio().getId()));
        for (UpdateOrderRequest.AllocationInput in : inputs) {
            OrderAllocation a = existing.get(in.portfolioId());
            if (a == null) {
                Portfolio portfolio = portfolios.findById(in.portfolioId())
                        .orElseThrow(() -> ApiException.badRequest("Unknown portfolio " + in.portfolioId()));
                a = new OrderAllocation();
                a.setPortfolio(portfolio);
                a.setStatus("new");
                o.addAllocation(a);
            }
            a.setValue(in.quantity());
            AllocationMath.recalculatePostTrade(a, o.isSell());
        }
        o.setValue(total);
    }

    /** Blue button: moves the order one step forward (never backwards, never into CONFIRMED). */
    @Transactional
    public OrderDto advanceStatus(Long id, OrderStatus expected) {
        Order o = find(id);
        advance(o, expected);
        return OrderMapper.toDto(o, false);
    }

    private void advance(Order o, OrderStatus expected) {
        if (o.getStatus() != expected) {
            throw ApiException.conflict("The order is already " + o.getStatus().label() + ". Please reload.");
        }
        OrderStatus next = o.getStatus().nextByUser().orElseThrow(() -> ApiException.conflict(
                o.getStatus() == OrderStatus.TRADED
                        ? "Waiting for contract note: a Traded order is confirmed by matching its contract note"
                        : "The order is already Allocated"));
        if (next == OrderStatus.TRADED) {
            LocalDate today = LocalDate.now();
            int settlementDays = o.getAsset().getSettlementDuration() == null ? 2 : o.getAsset().getSettlementDuration();
            o.setTradedDate(today);
            o.setSettlementDate(BusinessDays.add(today, settlementDays));
        }
        history.save(new OrderStatusHistory(o.getId(), o.getStatus(), next, StatusTrigger.USER, null));
        o.setStatus(next);
        orders.save(o);
    }

    /** Deletes the order; a matched contract note goes back to the unmatched list. */
    @Transactional
    public void delete(Long id) {
        Order o = find(id);
        notes.findByOrderId(id).ifPresent(note -> {
            note.setOrder(null);
            note.setMatchedAt(null);
            note.setStatus(ContractNoteStatus.UNMATCHED);
            note.setUnmatchedReason("The matched order (" + OrderMapper.label(o) + ") was deleted");
            notes.saveAndFlush(note);
        });
        orders.delete(o);
    }

    /** Discards local edits and restores the last imported Sharpfin values. */
    @Transactional
    public OrderDto revert(Long id) {
        Order o = find(id);
        if (!o.getStatus().isEditable()) {
            throw ApiException.conflict("The order is " + o.getStatus().label() + " and can no longer be changed");
        }
        if (o.getRawPayload() == null) {
            throw ApiException.badRequest("No imported values to revert to");
        }
        try {
            payloadMapper.applyOrder(o, json.readTree(o.getRawPayload()), !o.isDetailsMissing());
        } catch (IOException e) {
            throw new IllegalStateException("Stored payload is not valid JSON", e);
        }
        o.setLocallyModified(false);
        o.setSyncConflict(false);
        orders.saveAndFlush(o);
        return OrderMapper.toDto(o, true);
    }

    /** Runs the action for every ticked order, each in its own transaction, and reports per order. */
    public BulkResult bulk(BulkRequest req) {
        List<BulkResult.Item> items = new ArrayList<>();
        for (Long id : req.ids()) {
            String label = "Order " + id;
            try {
                label = tx.execute(s -> OrderMapper.label(find(id)));
                switch (req.action()) {
                    case DELETE -> tx.executeWithoutResult(s -> delete(id));
                    case ADVANCE_STATUS -> tx.executeWithoutResult(s -> {
                        Order o = find(id);
                        advance(o, o.getStatus());
                    });
                }
                items.add(new BulkResult.Item(id, label, true, null));
            } catch (ApiException e) {
                items.add(new BulkResult.Item(id, label, false, e.getMessage()));
            }
        }
        int done = (int) items.stream().filter(BulkResult.Item::done).count();
        return new BulkResult(done, items.size() - done, items);
    }

    private Order find(Long id) {
        return orders.findById(id).orElseThrow(() -> ApiException.notFound("Order", id));
    }
}
