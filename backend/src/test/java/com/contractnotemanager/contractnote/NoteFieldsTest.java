package com.contractnotemanager.contractnote;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class NoteFieldsTest {

    @Test
    void parsesNumbersWithSpacesAsThousandsSeparators() {
        NoteFields f = NoteFields.parse("ABB", "CH0012221716", "sek", "1864", "435.52", "811 809.00", "ABN Amro",
                "1200.00", null, null, List.of());
        assertThat(f.settlementAmount()).isEqualByComparingTo("811809.00");
        assertThat(f.currency()).isEqualTo("SEK");
        assertThat(f.errors()).isEmpty();
    }

    @Test
    void settlementAmountIsStoredWithoutSign() {
        NoteFields f = NoteFields.parse("Barclays", "GB0031348658", "GBP", "98", "123.47", "-12100.10", "Swedbank",
                "134", null, null, List.of());
        assertThat(f.settlementAmount()).isEqualByComparingTo(new BigDecimal("12100.10"));
    }

    @Test
    void reportsMissingAndInvalidValues() {
        NoteFields f = NoteFields.parse("", "CH0012221717", "SEKK", "x", "1", "1", "", "0", null, null, List.of());
        assertThat(f.errors()).containsExactlyInAnyOrder(
                "Name is missing",
                "ISIN CH0012221717 is not valid (format or check digit)",
                "Currency 'SEKK' is not a 3-letter code",
                "Broker is missing",
                "Quantity 'x' is not a number");
    }

    @Test
    void keepsExtractionWarnings() {
        NoteFields f = NoteFields.parse("ABB", "CH0012221716", "SEK", "1864", "435.52", "810609", "ABN Amro", "1200",
                "sell", null, List.of("two trades on one note"));
        assertThat(f.errors()).isEmpty();
        assertThat(f.warnings()).containsExactly("two trades on one note");
    }
}
