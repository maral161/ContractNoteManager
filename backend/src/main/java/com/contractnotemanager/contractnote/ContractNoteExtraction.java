package com.contractnotemanager.contractnote;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * The fields Claude reads from a contract-note PDF (structured output). Numbers are strings so that
 * nothing is rounded on the way; they are parsed to BigDecimal afterwards.
 */
public record ContractNoteExtraction(
        @JsonPropertyDescription("Name of the security / instrument as printed (e.g. 'ABB', 'Apple Inc'). Empty string if missing.")
        String instrumentName,
        @JsonPropertyDescription("12-character ISIN, uppercase, no spaces. Empty string if missing.")
        String isin,
        @JsonPropertyDescription("3-letter ISO currency code of price and settlement amount, e.g. SEK, USD, GBP. Empty string if missing.")
        String currency,
        @JsonPropertyDescription("Number of units traded, plain decimal without thousands separators, '.' as decimal mark, no sign.")
        String quantity,
        @JsonPropertyDescription("Price per unit, plain decimal without thousands separators, '.' as decimal mark.")
        String price,
        @JsonPropertyDescription("Settlement amount exactly as printed but as a plain decimal: no thousands separators "
                + "(e.g. '811 809.00' becomes '811809.00'), '.' as decimal mark, no sign.")
        String settlementAmount,
        @JsonPropertyDescription("Name of the executing broker or bank (labels such as Broker, Broker Name, Mäklare, "
                + "Geschäftsvermittler). Never the counterparty/client (Motpart, Gegenpartei, Counterparty).")
        String broker,
        @JsonPropertyDescription("Commission / brokerage fee (Commission, Courtage, Provision) as a plain decimal; '0' if the note states none.")
        String commission,
        @JsonPropertyDescription("'buy' or 'sell' if the note states the direction (Buy/Sell, Köp/Sälj, Kauf/Verkauf), otherwise empty string.")
        String side,
        @JsonPropertyDescription("Trade date as YYYY-MM-DD if printed, otherwise empty string.")
        String tradeDate,
        @JsonPropertyDescription("Short notes about anything uncertain: illegible values, several trades on one note, "
                + "values that had to be guessed. Empty list if everything was clear.")
        List<String> warnings) {
}
