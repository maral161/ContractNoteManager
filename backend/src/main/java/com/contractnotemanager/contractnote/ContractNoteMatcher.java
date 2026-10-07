package com.contractnotemanager.contractnote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.contractnotemanager.domain.Order;
import com.contractnotemanager.domain.OrderStatus;

/**
 * Matching rules (plan section 4.4.2). A candidate must be TRADED and agree on ISIN, currency,
 * quantity (amount orders: amount), exact price, settlement amount within ±1 currency unit
 * (absolute values) and side when the note states it. Exactly one candidate is a match.
 */
public final class ContractNoteMatcher {

    static final BigDecimal SETTLEMENT_TOLERANCE = BigDecimal.ONE;

    private ContractNoteMatcher() {
    }

    public record Result(Order order, String reason) {
        public boolean matched() {
            return order != null;
        }
    }

    /**
     * @param candidates orders without a contract note: the ticked orders on upload, or every TRADED
     *                   order (plus same-ISIN orders, for the explanation) on "match again"
     * @param scope      wording for the explanation, e.g. "selected" or "traded"
     */
    public static Result match(NoteFields note, List<Order> candidates, String scope) {
        if (!note.isComplete()) {
            return new Result(null, "Check the values read from the PDF: " + String.join("; ", note.errors()));
        }
        List<Order> matches = candidates.stream()
                .filter(o -> o.getStatus() == OrderStatus.TRADED)
                .filter(o -> differences(note, o).isEmpty())
                .toList();
        if (matches.size() == 1) {
            return new Result(matches.get(0), null);
        }
        if (matches.size() > 1) {
            return new Result(null, matches.size() + " " + scope + " orders match this note equally ("
                    + String.join(", ", matches.stream().map(ContractNoteMatcher::label).toList())
                    + "); tick only the right one and upload again");
        }
        List<Order> sameIsin = candidates.stream()
                .filter(o -> note.isin().equalsIgnoreCase(o.getAsset().getIsin()))
                .toList();
        if (sameIsin.isEmpty()) {
            return new Result(null, "No " + scope + " order with ISIN " + note.isin() + " (" + note.instrumentName() + ")");
        }
        Order closest = sameIsin.stream()
                .min(Comparator.comparing((Order o) -> o.getStatus() != OrderStatus.TRADED)
                        .thenComparing(o -> differences(note, o).size()))
                .orElseThrow();
        List<String> reasons = new ArrayList<>();
        if (closest.getStatus() != OrderStatus.TRADED) {
            reasons.add("order is " + closest.getStatus().label() + ", not Traded");
        }
        reasons.addAll(differences(note, closest));
        return new Result(null, label(closest) + ": " + String.join("; ", reasons));
    }

    /** Every rule the order breaks; empty when note and order agree. */
    static List<String> differences(NoteFields note, Order o) {
        List<String> diffs = new ArrayList<>();
        if (!note.isin().equalsIgnoreCase(o.getAsset().getIsin())) {
            diffs.add("ISIN differs (" + note.isin() + " vs " + o.getAsset().getIsin() + ")");
        }
        if (!note.currency().equalsIgnoreCase(o.getCurrencyCode())) {
            diffs.add("currency differs (" + note.currency() + " vs " + o.getCurrencyCode() + ")");
        }
        if (o.isAmountOrder()) {
            if (note.settlementAmount().subtract(o.getValue().abs()).abs().compareTo(SETTLEMENT_TOLERANCE) > 0) {
                diffs.add("amount differs (" + plain(note.settlementAmount()) + " vs " + plain(o.getValue()) + ")");
            }
        } else if (note.quantity().compareTo(o.getValue()) != 0) {
            diffs.add("quantity differs (" + plain(note.quantity()) + " vs " + plain(o.getValue()) + ")");
        }
        if (o.getPrice() == null || note.price().compareTo(o.getPrice()) != 0) {
            diffs.add("price differs (" + plain(note.price()) + " vs " + plain(o.getPrice()) + ")");
        }
        if (o.getSettlementAmount() != null) {
            BigDecimal diff = note.settlementAmount().subtract(o.getSettlementAmount().abs()).abs();
            if (diff.compareTo(SETTLEMENT_TOLERANCE) > 0) {
                diffs.add("settlement amount differs by " + diff.setScale(2, RoundingMode.HALF_UP).toPlainString()
                        + " " + o.getCurrencyCode());
            }
        }
        if (note.side() != null && !note.side().equalsIgnoreCase(o.getSide())) {
            diffs.add("side differs (" + note.side() + " vs " + o.getSide() + ")");
        }
        return diffs;
    }

    static String label(Order o) {
        String side = o.getSide() == null ? "" : Character.toUpperCase(o.getSide().charAt(0)) + o.getSide().substring(1);
        return side + " " + o.getAsset().getName() + " " + plain(o.getValue()) + " @ " + plain(o.getPrice());
    }

    private static String plain(BigDecimal value) {
        return value == null ? "–" : value.stripTrailingZeros().toPlainString();
    }
}
