package com.contractnotemanager.contractnote;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The parsed and checked values of a contract note. {@code errors} block matching
 * (e.g. a missing price or an invalid ISIN); {@code warnings} are only shown.
 */
public record NoteFields(
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
        List<String> errors,
        List<String> warnings) {

    /** Parses and checks raw text values (from Claude or from a manual correction). */
    public static NoteFields parse(String instrumentName, String isin, String currency, String quantity, String price,
            String settlementAmount, String broker, String commission, String side, String tradeDate,
            List<String> extractionWarnings) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (extractionWarnings != null) {
            extractionWarnings.stream().filter(w -> w != null && !w.isBlank()).forEach(warnings::add);
        }
        String name = blankToNull(instrumentName);
        String isinValue = blankToNull(isin) == null ? null : isin.replace(" ", "").toUpperCase();
        String currencyValue = blankToNull(currency) == null ? null : currency.trim().toUpperCase();
        String brokerValue = blankToNull(broker);
        BigDecimal qty = number("Quantity", quantity, errors);
        BigDecimal px = number("Price", price, errors);
        BigDecimal settle = number("Settlement amount", settlementAmount, errors);
        BigDecimal comm = number("Commission", commission, errors);

        if (name == null) {
            errors.add("Name is missing");
        }
        if (isinValue == null) {
            errors.add("ISIN is missing");
        } else if (!Isin.isValid(isinValue)) {
            errors.add("ISIN " + isinValue + " is not valid (format or check digit)");
        }
        if (currencyValue == null) {
            errors.add("Currency is missing");
        } else if (!currencyValue.matches("[A-Z]{3}")) {
            errors.add("Currency '" + currencyValue + "' is not a 3-letter code");
        }
        if (brokerValue == null) {
            errors.add("Broker is missing");
        }
        if (qty != null && px != null && settle != null) {
            BigDecimal gross = qty.multiply(px);
            BigDecimal tolerance = (comm == null ? BigDecimal.ZERO : comm.abs()).add(BigDecimal.ONE);
            if (gross.subtract(settle.abs()).abs().compareTo(tolerance) > 0) {
                warnings.add("Quantity × price (" + gross.stripTrailingZeros().toPlainString()
                        + ") differs from the settlement amount (" + settle.toPlainString() + ")");
            }
        }
        String sideValue = blankToNull(side) == null ? null : side.trim().toLowerCase();
        if (sideValue != null && !sideValue.equals("buy") && !sideValue.equals("sell")) {
            sideValue = null;
        }
        LocalDate date = null;
        if (blankToNull(tradeDate) != null) {
            try {
                date = LocalDate.parse(tradeDate.trim());
            } catch (RuntimeException e) {
                warnings.add("Trade date '" + tradeDate + "' could not be read");
            }
        }
        return new NoteFields(name, isinValue, currencyValue, qty, px, settle == null ? null : settle.abs(),
                brokerValue, comm, sideValue, date, errors, warnings);
    }

    public static NoteFields from(ContractNoteExtraction e) {
        return parse(e.instrumentName(), e.isin(), e.currency(), e.quantity(), e.price(), e.settlementAmount(),
                e.broker(), e.commission(), e.side(), e.tradeDate(), e.warnings());
    }

    public boolean isComplete() {
        return errors.isEmpty();
    }

    private static BigDecimal number(String label, String text, List<String> errors) {
        if (blankToNull(text) == null) {
            errors.add(label + " is missing");
            return null;
        }
        String cleaned = text.trim().replace(" ", "").replace(" ", "").replace("'", "");
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            errors.add(label + " '" + text + "' is not a number");
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
