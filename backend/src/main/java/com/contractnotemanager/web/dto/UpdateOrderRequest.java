package com.contractnotemanager.web.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Save from the edit modal. Only the editable fields exist here; a null field means "unchanged".
 * The order quantity is the total of {@code allocations}.
 */
public record UpdateOrderRequest(
        @NotNull Integer version,
        BigDecimal price,
        BigDecimal commission,
        Long brokerId,
        String brokerName,
        Long ownerId,
        @Valid List<AllocationInput> allocations) {

    public record AllocationInput(@NotNull Long portfolioId, @NotNull BigDecimal quantity) {
    }
}
