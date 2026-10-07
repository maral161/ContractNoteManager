package com.contractnotemanager.importer;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.contractnotemanager.domain.Order;
import com.contractnotemanager.domain.OrderStatus;
import com.contractnotemanager.domain.OrderStatusHistory;
import com.contractnotemanager.domain.StatusTrigger;
import com.contractnotemanager.repository.OrderRepository;
import com.contractnotemanager.repository.OrderStatusHistoryRepository;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Upserts one Sharpfin order (plan section 4.3):
 * <ul>
 * <li>new order → created</li>
 * <li>Sharpfin version not higher → skipped</li>
 * <li>locally edited, or already confirmed by a contract note → local values kept, flagged as conflict</li>
 * <li>otherwise → overwritten with the Sharpfin values</li>
 * </ul>
 * The status only ever moves forward.
 */
@Service
public class OrderImporter {

    private final OrderRepository orders;
    private final OrderStatusHistoryRepository history;
    private final OrderPayloadMapper mapper;

    public OrderImporter(OrderRepository orders, OrderStatusHistoryRepository history, OrderPayloadMapper mapper) {
        this.orders = orders;
        this.history = history;
        this.mapper = mapper;
    }

    @Transactional
    public ImportOutcome importOrder(JsonNode payload, boolean withDetails) {
        String key = OrderPayloadMapper.text(payload, "key");
        if (key == null) {
            throw new IllegalArgumentException("Order without key");
        }
        Optional<OrderStatus> remoteStatus = OrderStatus.fromSharpfin(OrderPayloadMapper.text(payload, "status"));
        Order existing = orders.findBySfKey(key).orElse(null);

        if (existing == null) {
            Order order = new Order();
            mapper.applyOrder(order, payload, withDetails);
            order.setStatus(remoteStatus.orElse(OrderStatus.NEW));
            orders.saveAndFlush(order);
            history.save(new OrderStatusHistory(order.getId(), null, order.getStatus(), StatusTrigger.IMPORT,
                    "Imported from Sharpfin"));
            return ImportOutcome.CREATED;
        }

        int remoteVersion = payload.path("version").asInt(0);
        boolean detailsNowAvailable = existing.isDetailsMissing() && withDetails;
        if (remoteVersion <= existing.getSfVersion() && !detailsNowAvailable) {
            return ImportOutcome.SKIPPED;
        }

        boolean keepLocalValues = existing.isLocallyModified() || !existing.getStatus().isEditable();
        if (keepLocalValues && remoteVersion <= existing.getSfVersion()) {
            return ImportOutcome.SKIPPED; // only the details are new; nothing changed in Sharpfin
        }

        ImportOutcome outcome;
        if (keepLocalValues) {
            // keep local values; remember the newest Sharpfin version for "revert" and comparison
            existing.setRawPayload(payload.toString());
            existing.setSfVersion(remoteVersion);
            existing.setSfStatus(OrderPayloadMapper.text(payload, "status"));
            existing.setSyncConflict(true);
            outcome = ImportOutcome.CONFLICT;
        } else {
            mapper.applyOrder(existing, payload, withDetails);
            existing.setSyncConflict(false);
            outcome = ImportOutcome.UPDATED;
        }

        remoteStatus.filter(s -> s.isAfter(existing.getStatus())).ifPresent(s -> {
            history.save(new OrderStatusHistory(existing.getId(), existing.getStatus(), s, StatusTrigger.IMPORT,
                    "Status changed in Sharpfin to " + existing.getSfStatus()));
            existing.setStatus(s);
        });
        return outcome;
    }
}
