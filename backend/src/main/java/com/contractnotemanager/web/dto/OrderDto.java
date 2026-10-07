package com.contractnotemanager.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.contractnotemanager.domain.OrderStatus;

/** One order as shown in the table; {@code allocations} and the context fields are only set on the detail call. */
public record OrderDto(
        Long id,
        String sfKey,
        String assetName,
        String isin,
        String assetType,
        Integer qtyDecimals,
        String side,
        OrderStatus status,
        String statusLabel,
        String advanceAction,
        String orderType,
        BigDecimal quantity,
        BigDecimal value,
        BigDecimal price,
        BigDecimal amount,
        BigDecimal commission,
        String currency,
        LocalDate bookedDate,
        LocalDate tradedDate,
        LocalDate settlementDate,
        LocalDate validTo,
        Long ownerId,
        String ownerName,
        Long brokerId,
        String counterpart,
        Long custodyId,
        String custodyName,
        String source,
        String comment,
        boolean noteMatched,
        String noteStatus,
        boolean locallyModified,
        boolean syncConflict,
        boolean detailsMissing,
        boolean editable,
        int version,
        List<AllocationDto> allocations) {
}
