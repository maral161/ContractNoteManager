package com.contractnotemanager.contractnote;

import static com.contractnotemanager.TestOrders.abb;
import static com.contractnotemanager.TestOrders.apple;
import static com.contractnotemanager.TestOrders.barclays;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.contractnotemanager.TestOrders;
import com.contractnotemanager.domain.ContractNoteStatus;
import com.contractnotemanager.domain.Order;
import com.contractnotemanager.domain.OrderStatus;

/** The rules: ISIN + Buy/Sell exact, then 6 checks – 6 → Matched, 4–5 → Partially matched, else No match. */
class ContractNoteMatcherTest {

    private final List<Order> traded = List.of(abb(OrderStatus.TRADED), apple(OrderStatus.TRADED),
            barclays(OrderStatus.TRADED));

    private static ContractNoteMatcher.Result match(ContractNoteExtraction note, List<Order> orders) {
        return ContractNoteMatcher.match(NoteFields.from(note), orders, "selected");
    }

    @Test
    void abnAmroNoteMatchesAbb() {
        var result = match(SampleNotes.ABN_AMRO_ABB, traded);
        assertThat(result.status()).isEqualTo(ContractNoteStatus.MATCHED);
        assertThat(result.score()).isEqualTo(6);
        assertThat(result.order().getAsset().getName()).isEqualTo("ABB");
        // Sharpfin's amount excludes commission: 810,609.00 + 1,200 = 811,809.00 vs 811,809.28
        assertThat(result.checks()).allMatch(ContractNoteMatcher.Check::ok);
    }

    @Test
    void ubsNoteAsPrintedDoesNotAddUp() {
        var result = match(SampleNotes.UBS_APPLE, traded);
        assertThat(result.status()).isEqualTo(ContractNoteStatus.NO_MATCH);
        assertThat(result.score()).isEqualTo(3); // currency, quantity, price
        assertThat(result.reason()).contains("only 3 of 6").contains("note adds up differs (12934 vs calc. 102934.45)");
    }

    @Test
    void correctedUbsNoteIsPartiallyMatchedBecauseOfTheCommission() {
        var result = match(SampleNotes.UBS_APPLE_CORRECTED, traded);
        assertThat(result.status()).isEqualTo(ContractNoteStatus.PARTIALLY_MATCHED);
        assertThat(result.score()).isEqualTo(5);
        assertThat(result.order().getAsset().getName()).isEqualTo("Apple Inc");
        assertThat(result.reason()).isEqualTo("5 of 6 checks pass – commission differs (960 vs 0)");
    }

    @Test
    void swedbankNoteDiffersInPriceCommissionAndAmount() {
        var result = match(SampleNotes.SWEDBANK_BARCLAYS, traded);
        assertThat(result.status()).isEqualTo(ContractNoteStatus.NO_MATCH);
        assertThat(result.score()).isEqualTo(3); // currency, quantity, note adds up
        assertThat(result.reason()).contains("price differs (123.47 vs 123.57)");
    }

    @Test
    void swedbankNoteMatchesOnceTheOrderHasPriceAndCommission() {
        Order corrected = TestOrders.order(4, "Barclays PLC", "GB0031348658", "buy", "98", "123.47", "-12100.06",
                "134", "GBP", OrderStatus.TRADED);
        assertThat(match(SampleNotes.SWEDBANK_BARCLAYS, List.of(corrected)).status())
                .isEqualTo(ContractNoteStatus.MATCHED);
    }

    @Test
    void buySellMustAgree() {
        var buyNote = new ContractNoteExtraction("ABB", "CH0012221716", "SEK", "1864", "435.52", "810609.00",
                "ABN Amro", "1200", "buy", "", List.of());
        var result = match(buyNote, traded);
        assertThat(result.status()).isEqualTo(ContractNoteStatus.NO_MATCH);
        assertThat(result.reason()).contains("No selected buy order with ISIN CH0012221716");
    }

    @Test
    void noteWithoutBuySellIsNoMatch() {
        var noSide = new ContractNoteExtraction("ABB", "CH0012221716", "SEK", "1864", "435.52", "810609.00",
                "ABN Amro", "1200", "", "", List.of());
        assertThat(match(noSide, traded).reason()).isEqualTo("Buy/Sell is not stated on the contract note");
    }

    @Test
    void priceMustBeExact() {
        Order o = TestOrders.order(9, "ABB", "CH0012221716", "sell", "1864", "435.53", "811809.28", "1200", "SEK",
                OrderStatus.TRADED);
        var result = match(SampleNotes.ABN_AMRO_ABB, List.of(o));
        assertThat(result.status()).isEqualTo(ContractNoteStatus.PARTIALLY_MATCHED);
        assertThat(result.reason()).contains("price differs (435.52 vs 435.53)");
    }

    @Test
    void settlementAmountMayDifferByOneUnit() {
        Order within = TestOrders.order(9, "ABB", "CH0012221716", "sell", "1864", "435.52", "811810.00", "1200",
                "SEK", OrderStatus.TRADED);
        Order outside = TestOrders.order(9, "ABB", "CH0012221716", "sell", "1864", "435.52", "811810.01", "1200",
                "SEK", OrderStatus.TRADED);
        assertThat(match(SampleNotes.ABN_AMRO_ABB, List.of(within)).status()).isEqualTo(ContractNoteStatus.MATCHED);
        assertThat(match(SampleNotes.ABN_AMRO_ABB, List.of(outside)).status())
                .isEqualTo(ContractNoteStatus.PARTIALLY_MATCHED);
    }

    @Test
    void orderMustBeTraded() {
        var result = match(SampleNotes.ABN_AMRO_ABB, List.of(abb(OrderStatus.ON_MARKET)));
        assertThat(result.status()).isEqualTo(ContractNoteStatus.NO_MATCH);
        assertThat(result.reason()).isEqualTo("Sell ABB 1864 @ 435.52 is On market, not Traded yet");
    }

    @Test
    void twoEqualOrdersAreNotMatchedAutomatically() {
        Order second = abb(OrderStatus.TRADED);
        second.setId(99L);
        var result = match(SampleNotes.ABN_AMRO_ABB, List.of(abb(OrderStatus.TRADED), second));
        assertThat(result.status()).isEqualTo(ContractNoteStatus.NO_MATCH);
        assertThat(result.reason()).startsWith("2 selected orders fit equally well");
    }

    @Test
    void bestOfSeveralOrdersWins() {
        Order worse = TestOrders.order(8, "ABB", "CH0012221716", "sell", "1000", "435.52", "435520", "0", "SEK",
                OrderStatus.TRADED);
        var result = match(SampleNotes.ABN_AMRO_ABB, List.of(worse, abb(OrderStatus.TRADED)));
        assertThat(result.status()).isEqualTo(ContractNoteStatus.MATCHED);
        assertThat(result.order().getId()).isEqualTo(1L);
    }

    @Test
    void incompleteNoteIsNoMatch() {
        var missingPrice = new ContractNoteExtraction("ABB", "CH0012221716", "SEK", "1864", "", "810609.00",
                "ABN Amro", "1200", "sell", "", List.of());
        assertThat(match(missingPrice, traded).reason()).isEqualTo("Check the values read from the PDF: Price is missing");
    }
}
