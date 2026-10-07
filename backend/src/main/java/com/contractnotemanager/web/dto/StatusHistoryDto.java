package com.contractnotemanager.web.dto;

import java.time.Instant;

import com.contractnotemanager.domain.OrderStatus;
import com.contractnotemanager.domain.StatusTrigger;

public record StatusHistoryDto(OrderStatus fromStatus, OrderStatus toStatus, Instant changedAt,
        StatusTrigger trigger, String note) {
}
