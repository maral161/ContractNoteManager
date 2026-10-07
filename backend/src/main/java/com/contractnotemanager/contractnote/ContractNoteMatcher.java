package com.contractnotemanager.contractnote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.contractnotemanager.domain.ContractNoteStatus;
import com.contractnotemanager.domain.Order;
import com.contractnotemanager.domain.OrderStatus;

/**
 * Matching rules. ISIN and Buy/Sell must agree exactly; then six checks are scored:
 * <ol>
 * <li>currency</li>
 * <li>quantity (amount orders: quantity × price against the order amount, ±1)</li>
 * <li>price (exact)</li>
 * <li>commission (note against order)</li>
 * <li>settlement amount within ±1 – Sharpfin's order amount excludes commission, so the note's
 * commission is taken out first (sell: amount + commission, buy: amount − commission)</li>
 * <li>the note adds up: price × quantity + commission (buy) / − commission (sell) = settlement amount, ±1</li>
 * </ol>
 * 6 of 6 → MATCHED, 4–5 → PARTIALLY_MATCHED, otherwise NO_MATCH. Only TRADED orders are candidates.
 */
public final class ContractNoteMatcher {

    public static final int CHECK_COUNT = 6;
    static final int PARTIAL_MINIMUM = 4; // more than 50 % of the six checks
    static final BigDecimal TOLERANCE = BigDecimal.ONE;

    private ContractNoteMatcher() {
    }

    /**
     * One of the six checks, with the values compared (shown in the UI). {@code field} names the order
     * property the note can overwrite (quantity, price, commission), null for the others.
     */
    public record Check(String name, boolean ok, String noteValue, String orderValue, String field) {
        Check(String name, boolean ok, String noteValue, String orderValue) {
            this(name, ok, noteValue, orderValue, null);
        }
    }

    public record Result(ContractNoteStatus status, Order order, int score, List<Check> checks, String reason) {
        public boolean linked() {
            return order != null;
        }
    }

    /**
     * @param candidates orders without a matched contract note (the ticked orders on upload, all orders on re-evaluation)
     * @param scope      wording for the explanation, e.g. "selected" or "traded"
     */
    public static Result match(NoteFields note, List<Order> candidates, String scope) {
        if (!note.isComplete()) {
            return noMatch("Check the values read from the PDF: " + String.join("; ", note.errors()));
        }
        if (note.side() == null) {
            return noMatch("Buy/Sell is not stated on the contract note");
        }
        List<Order> sameInstrument = candidates.stream()
                .filter(o -> note.isin().equalsIgnoreCase(o.getAsset().getIsin()))
                .filter(o -> note.side().equalsIgnoreCase(o.getSide()))
                .toList();
        if (sameInstrument.isEmpty()) {
            boolean otherSide = candidates.stream().anyMatch(o -> note.isin().equalsIgnoreCase(o.getAsset().getIsin()));
            return noMatch(otherSide
                    ? "No " + scope + " " + note.side() + " order with ISIN " + note.isin()
                            + " (only the opposite side exists)"
                    : "No " + scope + " order with ISIN " + note.isin() + " (" + note.instrumentName() + ")");
        }
        List<Order> traded = sameInstrument.stream().filter(o -> o.getStatus() == OrderStatus.TRADED).toList();
        if (traded.isEmpty()) {
            Order o = sameInstrument.get(0);
            return noMatch(label(o) + " is " + o.getStatus().label() + ", not Traded yet");
        }

        record Scored(Order order, List<Check> checks, int score) {
        }
        List<Scored> scored = traded.stream()
                .map(o -> {
                    List<Check> checks = checks(note, o);
                    return new Scored(o, checks, (int) checks.stream().filter(Check::ok).count());
                })
                .sorted(Comparator.comparingInt(Scored::score).reversed())
                .toList();
        Scored best = scored.get(0);
        long tied = scored.stream().filter(s -> s.score() == best.score()).count();
        if (best.score() < PARTIAL_MINIMUM) {
            return new Result(ContractNoteStatus.NO_MATCH, null, best.score(), best.checks(),
                    "Closest: " + label(best.order()) + " – only " + best.score() + " of " + CHECK_COUNT
                            + " checks pass (" + failed(best.checks()) + ")");
        }
        if (tied > 1) {
            return new Result(ContractNoteStatus.NO_MATCH, null, best.score(), best.checks(),
                    tied + " " + scope + " orders fit equally well ("
                            + String.join(", ", scored.stream().filter(s -> s.score() == best.score())
                                    .map(s -> label(s.order())).toList())
                            + "); tick only the right one and upload again");
        }
        if (best.score() == CHECK_COUNT) {
            return new Result(ContractNoteStatus.MATCHED, best.order(), best.score(), best.checks(), null);
        }
        return new Result(ContractNoteStatus.PARTIALLY_MATCHED, best.order(), best.score(), best.checks(),
                best.score() + " of " + CHECK_COUNT + " checks pass – " + failed(best.checks()));
    }

    /** The six checks for one order. */
    static List<Check> checks(NoteFields n, Order o) {
        List<Check> checks = new ArrayList<>();
        boolean sell = "sell".equalsIgnoreCase(n.side());
        BigDecimal commission = n.commission();
        BigDecimal gross = n.quantity().multiply(n.price());

        checks.add(new Check("Currency", n.currency().equalsIgnoreCase(o.getCurrencyCode()), n.currency(),
                o.getCurrencyCode()));

        if (o.isAmountOrder()) {
            boolean ok = within(gross, o.getValue().abs());
            checks.add(new Check("Amount (quantity × price)", ok, plain(gross.setScale(2, RoundingMode.HALF_UP)),
                    plain(o.getValue())));
        } else {
            checks.add(new Check("Quantity", n.quantity().compareTo(o.getValue()) == 0, plain(n.quantity()),
                    plain(o.getValue()), "quantity"));
        }

        checks.add(new Check("Price", o.getPrice() != null && n.price().compareTo(o.getPrice()) == 0,
                plain(n.price()), plain(o.getPrice()), "price"));

        BigDecimal orderCommission = o.getCommission() == null ? BigDecimal.ZERO : o.getCommission();
        checks.add(new Check("Commission", commission.compareTo(orderCommission) == 0, plain(commission),
                plain(orderCommission), "commission"));

        // Sharpfin's settlement amount does not include commission: compare without it
        BigDecimal noteWithoutCommission = sell ? n.settlementAmount().add(commission)
                : n.settlementAmount().subtract(commission);
        BigDecimal orderAmount = o.getSettlementAmount() == null ? null : o.getSettlementAmount().abs();
        checks.add(new Check("Settlement amount (±1, excl. commission)",
                orderAmount != null && within(noteWithoutCommission, orderAmount),
                plain(noteWithoutCommission.setScale(2, RoundingMode.HALF_UP)), plain(orderAmount)));

        BigDecimal expected = sell ? gross.subtract(commission) : gross.add(commission);
        checks.add(new Check("Note adds up (price × quantity " + (sell ? "−" : "+") + " commission)",
                within(expected, n.settlementAmount()),
                plain(n.settlementAmount()), "calc. " + plain(expected.setScale(2, RoundingMode.HALF_UP))));
        return checks;
    }

    private static boolean within(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs().compareTo(TOLERANCE) <= 0;
    }

    private static String failed(List<Check> checks) {
        return checks.stream().filter(c -> !c.ok())
                .map(c -> c.name().replaceAll(" \\(.*\\)", "").toLowerCase() + " differs (" + c.noteValue() + " vs "
                        + c.orderValue() + ")")
                .reduce((a, b) -> a + "; " + b).orElse("");
    }

    private static Result noMatch(String reason) {
        return new Result(ContractNoteStatus.NO_MATCH, null, 0, List.of(), reason);
    }

    static String label(Order o) {
        String side = o.getSide() == null ? "" : Character.toUpperCase(o.getSide().charAt(0)) + o.getSide().substring(1);
        return side + " " + o.getAsset().getName() + " " + plain(o.getValue()) + " @ " + plain(o.getPrice());
    }

    static String plain(BigDecimal value) {
        return value == null ? "–" : value.stripTrailingZeros().toPlainString();
    }
}
