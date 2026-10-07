package com.contractnotemanager.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.contractnotemanager.domain.ContractNoteStatus;

public record ContractNoteDto(
        Long id,
        String fileName,
        ContractNoteStatus status,
        String reason,
        String instrumentName,
        String isin,
        String currency,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal settlementAmount,
        String broker,
        BigDecimal commission,
        String side,
        LocalDate tradeDate,
        List<String> warnings,
        Integer matchScore,
        int checkCount,
        List<com.contractnotemanager.contractnote.ContractNoteMatcher.Check> checks,
        Long orderId,
        String orderLabel,
        Boolean orderEditable,
        Instant matchedAt,
        Instant createdAt,
        String extractionModel) {
}
