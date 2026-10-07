package com.contractnotemanager.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.contractnotemanager.domain.OrderAllocation;

/**
 * Calculations behind the edit modal (plan section 4.4). The frontend mirrors these formulas
 * so the figures update while typing; the backend stores the result on save.
 */
public final class AllocationMath {

    private AllocationMath() {
    }

    /**
     * Splits the order commission over the allocations by quantity. Whole commissions are split in whole
     * units, others in cents; the rounding remainder goes to the largest allocation so the parts add up.
     */
    public static List<BigDecimal> splitCommission(BigDecimal total, List<BigDecimal> quantities) {
        List<BigDecimal> parts = new ArrayList<>();
        BigDecimal sum = quantities.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total == null || sum.signum() == 0) {
            quantities.forEach(q -> parts.add(BigDecimal.ZERO));
            return parts;
        }
        int scale = total.stripTrailingZeros().scale() <= 0 ? 0 : 2;
        int largest = 0;
        for (int i = 0; i < quantities.size(); i++) {
            parts.add(total.multiply(quantities.get(i)).divide(sum, scale, RoundingMode.HALF_UP));
            if (quantities.get(i).compareTo(quantities.get(largest)) > 0) {
                largest = i;
            }
        }
        BigDecimal remainder = total.setScale(scale, RoundingMode.HALF_UP)
                .subtract(parts.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        parts.set(largest, parts.get(largest).add(remainder));
        return parts;
    }

    /**
     * Scales the allocation quantities to a new order total, keeping their proportions. Rounded to the
     * asset's quantity decimals; the rounding remainder goes to the largest allocation.
     */
    public static List<BigDecimal> scaleQuantities(List<BigDecimal> current, BigDecimal newTotal, int decimals) {
        List<BigDecimal> scaled = new ArrayList<>();
        BigDecimal sum = current.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (current.isEmpty()) {
            return scaled;
        }
        int largest = 0;
        for (int i = 0; i < current.size(); i++) {
            BigDecimal share = sum.signum() == 0
                    ? (i == 0 ? newTotal : BigDecimal.ZERO)
                    : newTotal.multiply(current.get(i)).divide(sum, decimals, RoundingMode.HALF_UP);
            scaled.add(share);
            if (current.get(i).compareTo(current.get(largest)) > 0) {
                largest = i;
            }
        }
        BigDecimal remainder = newTotal.subtract(scaled.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        scaled.set(largest, scaled.get(largest).add(remainder));
        return scaled;
    }

    /**
     * Recalculates the post-trade figures from the pre-trade holding and the new quantity:
     * post = pre − qty (sell) or pre + qty (buy); post % = pre % × post / pre; order weight = post % − pre %.
     */
    public static void recalculatePostTrade(OrderAllocation a, boolean sell) {
        BigDecimal pre = a.getPortfolioQuantity();
        BigDecimal preWeight = a.getPortfolioWeight();
        if (pre == null || preWeight == null || pre.signum() == 0) {
            return; // no holding known: figures stay as imported (or empty)
        }
        BigDecimal post = sell ? pre.subtract(a.getValue()) : pre.add(a.getValue());
        BigDecimal postWeight = preWeight.multiply(post).divide(pre, 2, RoundingMode.HALF_UP);
        a.setTargetQuantity(post);
        a.setTargetWeight(postWeight);
        a.setOrderWeight(postWeight.subtract(preWeight));
    }

    /** Settlement amount as Sharpfin shows it: positive for sells, negative for buys. */
    public static BigDecimal settlementAmount(boolean amountOrder, boolean sell, BigDecimal price, BigDecimal value) {
        BigDecimal gross = amountOrder || price == null
                ? value
                : price.multiply(value).setScale(2, RoundingMode.HALF_UP);
        return sell ? gross : gross.negate();
    }
}
