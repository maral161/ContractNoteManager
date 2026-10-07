package com.contractnotemanager.contractnote;

import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.contractnotemanager.config.ClaudeProperties;

/**
 * Sends the PDF to the Claude API (official Java SDK) and gets the fields back as structured output,
 * so the answer always has the {@link ContractNoteExtraction} shape. One request per PDF.
 */
@Component
public class ClaudeContractNoteExtractor implements ContractNoteExtractor {

    private static final Logger log = LoggerFactory.getLogger(ClaudeContractNoteExtractor.class);

    static final String SYSTEM_PROMPT = """
            You read contract notes (trade confirmations) that brokers send after executing a securities trade.
            They come from different brokers, with different layouts and in different languages, for example
            English, Swedish (Avräkningsnota: Värdepapper, Antal, Pris, Avräkningsbelopp, Courtage, Mäklare, Motpart)
            or German (Auftragsbestätigung: Wertpapier, Anzahl, Kurs, Abrechnungsbetrag, Courtage,
            Geschäftsvermittler, Gegenpartei).

            Buy/Sell is often stated as "Order side", "Köporder"/"Säljorder", "Köp"/"Sälj", "Kauf"/"Verkauf" or
            "Art des Geschäfts". Extract the trade data exactly as printed. The broker is the executing broker or bank, never the
            counterparty or client that the note is addressed to. Return numbers as plain decimals: remove
            thousands separators (spaces, apostrophes, commas used as thousands separators), use '.' as the decimal
            mark and drop any sign. Do not calculate or correct values; if a value is missing or illegible, return
            an empty string and explain it in warnings.""";

    private static final String INSTRUCTION =
            "Extract instrument name, ISIN, currency, quantity, price, settlement amount, broker and commission "
                    + "(and side and trade date if printed) from this contract note.";

    private final ClaudeProperties props;
    private volatile AnthropicClient client;

    public ClaudeContractNoteExtractor(ClaudeProperties props) {
        this.props = props;
    }

    @Override
    public Result extract(byte[] pdf, String fileName) {
        if (!props.hasApiKey()) {
            throw new ExtractionException("ANTHROPIC_API_KEY is not set, so the PDF cannot be read");
        }
        DocumentBlockParam document = DocumentBlockParam.builder()
                .source(Base64PdfSource.builder().data(Base64.getEncoder().encodeToString(pdf)).build())
                .title(fileName)
                .build();
        StructuredMessageCreateParams<ContractNoteExtraction> params = MessageCreateParams.builder()
                .model(props.model())
                .maxTokens(props.maxTokens())
                .system(SYSTEM_PROMPT)
                .outputConfig(ContractNoteExtraction.class)
                .addUserMessageOfBlockParams(List.of(
                        ContentBlockParam.ofDocument(document),
                        ContentBlockParam.ofText(TextBlockParam.builder().text(INSTRUCTION).build())))
                // if a safety classifier declines, the API retries on a fallback model by itself
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();
        StructuredMessage<ContractNoteExtraction> message;
        try {
            message = client().messages().create(params);
        } catch (AnthropicException e) {
            log.warn("Claude could not read {}: {}", fileName, e.getMessage());
            throw new ExtractionException("Claude API error: " + e.getMessage(), e);
        }
        if (message.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            throw new ExtractionException("Claude declined to read this document");
        }
        if (message.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent()) {
            throw new ExtractionException("Claude's answer was cut off; please retry");
        }
        ContractNoteExtraction fields = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .findFirst()
                .orElseThrow(() -> new ExtractionException("Claude returned no data for this document"));
        return new Result(fields, message.model().toString());
    }

    private AnthropicClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = AnthropicOkHttpClient.builder().apiKey(props.apiKey()).build();
                }
            }
        }
        return client;
    }
}
