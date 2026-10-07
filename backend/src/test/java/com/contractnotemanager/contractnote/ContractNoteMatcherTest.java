package com.contractnotemanager.contractnote;

import static com.contractnotemanager.TestOrders.abb;
import static com.contractnotemanager.TestOrders.apple;
import static com.contractnotemanager.TestOrders.barclays;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.contractnotemanager.TestOrders;
import com.contractnotemanager.domain.Order;
import com.contractnotemanager.domain.OrderStatus;

/** The acceptance cases from the plan (section 4.4.2) plus the edge cases of each rule. */
class ContractNoteMatcherTest {

    private final List<Order> traded = List.of(abb(OrderStatus.TRADED), apple(OrderStatus.TRADED),
            barclays(OrderStatus.TRADED));

    @Test
    void abnAmroNoteMatchesAbbWithinOneSek() {
        var result = ContractNoteMatcher.match(NoteFields.from(SampleNotes.ABN_AMRO_ABB), traded, "selected");
        assertThat(result.matched()).isTrue();
        assertThat(result.order().getAsset().getName()).isEqualTo("ABB"); // 811,809.00 vs 811,809.28
    }

    @Test
    void ubsNoteMatchesApple() {
        var result = ContractNoteMatcher.match(NoteFields.from(SampleNotes.UBS_APPLE), traded, "selected");
        assertThat(result.matched()).isTrue();
        assertThat(result.order().getAsset().getName()).isEqualTo("Apple Inc");
    }

    @Test
    void swedbankNoteDoesNotMatchBarclaysBecauseOfPriceAndAmount() {
        var result = ContractNoteMatcher.match(NoteFields.from(SampleNotes.SWEDBANK_BARCLAYS), traded, "selected");
        assertThat(result.matched()).isFalse();
        assertThat(result.reason())
                .contains("Buy Barclays PLC")
                .contains("price differs (123.47 vs 123.57)")
                .contains("settlement amount differs by 9.76 GBP");
    }

    @Test
    void priceMustBeExactEvenWhenAmountIsWithinTolerance() {
        Order o = TestOrders.order(9, "ABB", "CH0012221716", "sell", "1864", "435.53", "811809.28", "SEK",
                OrderStatus.TRADED);
        var result = ContractNoteMatcher.match(NoteFields.from(SampleNotes.ABN_AMRO_ABB), List.of(o), "selected");
        assertThat(result.reason()).contains("price differs (435.52 vs 435.53)");
    }

    @Test
    void decimalsAreComparedByValue() {
        Order o = TestOrders.order(9, "ABB", "CH0012221716", "sell", "1864.000", "435.520", "811809.28", "SEK",
                OrderStatus.TRADED);
        assertThat(ContractNoteMatcher.match(NoteFields.from(SampleNotes.ABN_AMRO_ABB), List.of(o), "x").matched())
                .isTrue();
    }

    @Test
    void settlementAmountMayDifferByAtMostOneUnit() {
        Order within = TestOrders.order(9, "ABB", "CH0012221716", "sell", "1864", "435.52", "811810.00", "SEK",
                OrderStatus.TRADED);
        Order outside = TestOrders.order(9, "ABB", "CH0012221716", "sell", "1864", "435.52", "811810.01", "SEK",
                OrderStatus.TRADED);
        NoteFields note = NoteFields.from(SampleNotes.ABN_AMRO_ABB);
        assertThat(ContractNoteMatcher.match(note, List.of(within), "x").matched()).isTrue();
        assertThat(ContractNoteMatcher.match(note, List.of(outside), "x").reason())
                .contains("settlement amount differs by 1.01 SEK");
    }

    @Test
    void orderMustBeTraded() {
        var result = ContractNoteMatcher.match(NoteFields.from(SampleNotes.ABN_AMRO_ABB),
                List.of(abb(OrderStatus.ON_MARKET)), "selected");
        assertThat(result.matched()).isFalse();
        assertThat(result.reason()).contains("order is On market, not Traded");
    }

    @Test
    void noOrderWithTheIsin() {
        var result = ContractNoteMatcher.match(NoteFields.from(SampleNotes.UBS_APPLE),
                List.of(abb(OrderStatus.TRADED)), "selected");
        assertThat(result.reason()).isEqualTo("No selected order with ISIN US0378331005 (Apple Inc)");
    }

    @Test
    void twoEqualOrdersAreNotMatchedAutomatically() {
        Order second = abb(OrderStatus.TRADED);
        second.setId(99L);
        var result = ContractNoteMatcher.match(NoteFields.from(SampleNotes.ABN_AMRO_ABB),
                List.of(abb(OrderStatus.TRADED), second), "selected");
        assertThat(result.matched()).isFalse();
        assertThat(result.reason()).startsWith("2 selected orders match this note equally");
    }

    @Test
    void sideIsCheckedOnlyWhenTheNoteStatesIt() {
        var withSide = new ContractNoteExtraction("ABB", "CH0012221716", "SEK", "1864", "435.52", "811809.00",
                "ABN Amro", "1200", "buy", "", List.of());
        var result = ContractNoteMatcher.match(NoteFields.from(withSide), List.of(abb(OrderStatus.TRADED)), "x");
        assertThat(result.reason()).contains("side differs (buy vs sell)");
    }

    @Test
    void incompleteNoteIsNotMatched() {
        var missingPrice = new ContractNoteExtraction("ABB", "CH0012221716", "SEK", "1864", "", "811809.00",
                "ABN Amro", "1200", "", "", List.of());
        var result = ContractNoteMatcher.match(NoteFields.from(missingPrice), traded, "selected");
        assertThat(result.reason()).isEqualTo("Check the values read from the PDF: Price is missing");
    }

    @Test
    void amountOrdersCompareTheAmount() {
        Order fund = TestOrders.order(10, "AMF Räntefond Lång", "SE0000739187", "buy", "2362161", "100.35",
                "-2362161", "SEK", OrderStatus.TRADED);
        fund.setOrderType("amount");
        var note = new ContractNoteExtraction("AMF Räntefond Lång", "SE0000739187", "SEK", "23539.67", "100.35",
                "2362161.00", "Swedbank", "0", "", "", List.of());
        assertThat(ContractNoteMatcher.match(NoteFields.from(note), List.of(fund), "x").matched()).isTrue();
    }
}
