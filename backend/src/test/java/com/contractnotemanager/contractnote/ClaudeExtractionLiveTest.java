package com.contractnotemanager.contractnote;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.contractnotemanager.config.ClaudeProperties;

/**
 * Sends the three sample PDFs to the real Claude API and checks what is read.
 * Opt-in (costs a few cents): ANTHROPIC_API_KEY=... ./mvnw test -Dtest=ClaudeExtractionLiveTest -Dclaude.it=true
 */
@EnabledIfSystemProperty(named = "claude.it", matches = "true")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class ClaudeExtractionLiveTest {

    private final ClaudeContractNoteExtractor extractor = new ClaudeContractNoteExtractor(
            new ClaudeProperties(System.getenv("ANTHROPIC_API_KEY"), "claude-opus-5-5", 8000));

    @ParameterizedTest
    @CsvSource({
            "abn-amro_abb.pdf,      CH0012221716, sell, SEK, 1864, 435.52, 810609.00, ABN Amro, 1200",
            "ubs_apple.pdf,         US0378331005, sell, USD, 473,  219.65, 12934.00,  UBS,      960",
            "swedbank_barclays.pdf, GB0031348658, buy,  GBP, 98,   123.47, 12234.06,  Swedbank, 134"})
    void readsTheSampleNotes(String file, String isin, String side, String currency, String quantity, String price,
            String settlement, String broker, String commission) throws Exception {
        byte[] pdf = Files.readAllBytes(Path.of("../docs/samples/contract-notes", file));
        NoteFields f = NoteFields.from(extractor.extract(pdf, file).fields());

        assertThat(f.errors()).isEmpty();
        assertThat(f.isin()).isEqualTo(isin);
        assertThat(f.side()).isEqualTo(side);
        assertThat(f.currency()).isEqualTo(currency);
        assertThat(f.quantity()).isEqualByComparingTo(quantity);
        assertThat(f.price()).isEqualByComparingTo(price);
        assertThat(f.settlementAmount()).isEqualByComparingTo(settlement);
        assertThat(f.broker()).containsIgnoringCase(broker); // the broker, never the counterparty
        assertThat(f.commission()).isEqualByComparingTo(commission);
    }
}
