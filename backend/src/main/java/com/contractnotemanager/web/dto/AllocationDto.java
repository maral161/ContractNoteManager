package com.contractnotemanager.web.dto;

import java.math.BigDecimal;

public record AllocationDto(
        Long id,
        Long portfolioId,
        String portfolioName,
        BigDecimal value,
        BigDecimal originalValue,
        BigDecimal commission,
        BigDecimal portfolioQuantity,
        BigDecimal portfolioWeight,
        BigDecimal targetQuantity,
        BigDecimal targetWeight,
        BigDecimal orderWeight) {
}
