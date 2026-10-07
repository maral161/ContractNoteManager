package com.contractnotemanager.service;

import java.util.List;

import com.contractnotemanager.domain.Order;
import com.contractnotemanager.domain.OrderAllocation;
import com.contractnotemanager.web.dto.AllocationDto;
import com.contractnotemanager.web.dto.OrderDto;

final class OrderMapper {

    private OrderMapper() {
    }

    static OrderDto toDto(Order o, boolean withAllocations) {
        List<AllocationDto> allocations = withAllocations
                ? o.getAllocations().stream().map(OrderMapper::toDto).toList()
                : null;
        return new OrderDto(
                o.getId(),
                o.getSfKey(),
                o.getAsset().getName(),
                o.getAsset().getIsin(),
                o.getAsset().getType(),
                o.getAsset().getQtyDecimals(),
                o.getSide(),
                o.getStatus(),
                o.getStatus().label(),
                o.getStatus().advanceAction().orElse(null),
                o.getOrderType(),
                o.isAmountOrder() ? null : o.getValue(),
                o.getValue(),
                o.getPrice(),
                o.getSettlementAmount(),
                o.getCommission(),
                o.getCurrencyCode(),
                o.getBookedDate(),
                o.getTradedDate(),
                o.getSettlementDate(),
                o.getValidTo(),
                o.getOwner() == null ? null : o.getOwner().getId(),
                o.getOwner() == null ? null : o.getOwner().getName(),
                o.getBroker() == null ? null : o.getBroker().getId(),
                o.getBroker() == null ? null : o.getBroker().getName(),
                o.getCustody().getId(),
                o.getCustody().getName(),
                o.getSource(),
                o.getComment(),
                o.getNoteMatched() > 0,
                o.isLocallyModified(),
                o.isSyncConflict(),
                o.isDetailsMissing(),
                o.getStatus().isEditable(),
                o.getVersion(),
                allocations);
    }

    static AllocationDto toDto(OrderAllocation a) {
        return new AllocationDto(a.getId(), a.getPortfolio().getId(), a.getPortfolio().getName(), a.getValue(),
                a.getOriginalValue(), a.getCommission(), a.getPortfolioQuantity(), a.getPortfolioWeight(),
                a.getTargetQuantity(), a.getTargetWeight(), a.getOrderWeight());
    }

    static String label(Order o) {
        return capitalize(o.getSide()) + " " + o.getAsset().getName();
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
