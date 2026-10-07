package com.contractnotemanager.contractnote;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IsinTest {

    @ParameterizedTest
    @ValueSource(strings = {"CH0012221716", "US0378331005", "GB0031348658", "ES0177542018", "US30303M1027",
            "SE0000667925", "SE0000164600", "US88160R1014", "GB00BH4HKS39", "SE0000739187"})
    void acceptsTheIsinsFromTheSampleData(String isin) {
        assertThat(Isin.isValid(isin)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CH0012221717", "US037833100", "us0378331005", "", "XX00000000000"})
    void rejectsWrongCheckDigitOrFormat(String isin) {
        assertThat(Isin.isValid(isin)).isFalse();
    }
}
