package com.contractnotemanager.web.dto;

import java.time.LocalDate;
import java.util.List;

import com.contractnotemanager.domain.OrderStatus;

/** Toolbar filters of the orders table (plan section 5.2). */
public record OrderFilter(
        DateType dateType,
        LocalDate from,
        LocalDate to,
        String asset,
        String portfolio,
        List<Long> owner,
        List<OrderStatus> status,
        List<Long> custody,
        NoteMatch noteMatch) {

    public enum DateType {
        BOOKED, TRADED, SETTLED
    }

    public enum NoteMatch {
        ALL, MATCHED, UNMATCHED
    }
}
