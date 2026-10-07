package com.contractnotemanager.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.contractnotemanager.domain.OrderAllocation;

class AllocationMathTest {

    @Test
    void splitsCommissionLikeSharpfin() {
        // ABB: 1,200 over 1,761 + 103 → 1,134 + 66 (the screenshot's values)
        List<BigDecimal> parts = AllocationMath.splitCommission(new BigDecimal("1200"),
                List.of(new BigDecimal("1761"), new BigDecimal("103")));
        assertThat(parts).containsExactly(new BigDecimal("1134"), new BigDecimal("66"));
    }

    @Test
    void remainderGoesToTheLargestAllocation() {
        List<BigDecimal> parts = AllocationMath.splitCommission(new BigDecimal("100"),
                List.of(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
        assertThat(parts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100");
    }

    @Test
    void recalculatesPostTradeFiguresFromTheNewQuantity() {
        OrderAllocation a = new OrderAllocation();
        a.setPortfolioQuantity(new BigDecimal("2927"));
        a.setPortfolioWeight(new BigDecimal("12.55"));
        a.setValue(new BigDecimal("1761"));
        AllocationMath.recalculatePostTrade(a, true);
        assertThat(a.getTargetQuantity()).isEqualByComparingTo("1166");
        assertThat(a.getTargetWeight()).isEqualByComparingTo("5.00");
        assertThat(a.getOrderWeight()).isEqualByComparingTo("-7.55");
    }

    @Test
    void settlementAmountIsNegativeForBuys() {
        assertThat(AllocationMath.settlementAmount(false, false, new BigDecimal("123.57"), new BigDecimal("98")))
                .isEqualByComparingTo("-12109.86");
        assertThat(AllocationMath.settlementAmount(false, true, new BigDecimal("435.52"), new BigDecimal("1864")))
                .isEqualByComparingTo("811809.28");
    }

    @Test
    void settlementDateSkipsWeekends() {
        // Wednesday 2026-10-07 + 2 → Friday; Thursday + 2 → Monday
        assertThat(BusinessDays.add(LocalDate.of(2026, 10, 7), 2)).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(BusinessDays.add(LocalDate.of(2026, 10, 8), 2)).isEqualTo(LocalDate.of(2026, 10, 12));
    }
}
