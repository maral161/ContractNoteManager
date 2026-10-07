package com.contractnotemanager.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.contractnotemanager.contractnote.ContractNoteExtractor;
import com.contractnotemanager.contractnote.ContractNoteExtraction;
import com.contractnotemanager.contractnote.ExtractionException;
import com.contractnotemanager.contractnote.SampleNotes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;

/** Spring context with a real (embedded) PostgreSQL, a fake Sharpfin and a fake PDF reader. */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
@Import(IntegrationTest.FakeExtractorConfig.class)
abstract class IntegrationTest {

    static final WireMockServer WIREMOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIREMOCK.start();
    }

    static final FakeSharpfin SHARPFIN = new FakeSharpfin(WIREMOCK);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("sharpfin.base-url", WIREMOCK::baseUrl);
        registry.add("sharpfin.username", () -> FakeSharpfin.USERNAME);
        registry.add("sharpfin.password", () -> FakeSharpfin.PASSWORD);
        registry.add("sharpfin.page-size", () -> FakeSharpfin.PAGE_SIZE);
    }

    @TestConfiguration
    static class FakeExtractorConfig {
        /** Returns what the sample PDFs contain, chosen by file name; "unreadable" fails like an API error. */
        @Bean
        @Primary
        ContractNoteExtractor fakeExtractor() {
            Map<String, ContractNoteExtraction> byName = new java.util.LinkedHashMap<>();
            byName.put("ubs-corrected", SampleNotes.UBS_APPLE_CORRECTED);
            byName.put("abn", SampleNotes.ABN_AMRO_ABB);
            byName.put("ubs", SampleNotes.UBS_APPLE);
            byName.put("swedbank", SampleNotes.SWEDBANK_BARCLAYS);
            return (pdf, fileName) -> {
                if (fileName.contains("unreadable")) {
                    throw new ExtractionException("Claude API error: overloaded");
                }
                return byName.entrySet().stream()
                        .filter(e -> fileName.toLowerCase().contains(e.getKey()))
                        .findFirst()
                        .map(e -> new ContractNoteExtractor.Result(e.getValue(), "claude-opus-5-5"))
                        .orElseThrow(() -> new ExtractionException("unknown test file " + fileName));
            };
        }
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE contract_note, order_status_history, order_allocation, orders, portfolio, broker, "
                + "owner, asset, custody, import_run RESTART IDENTITY CASCADE");
        SHARPFIN.reset();
    }

    protected JsonNode runImport() throws Exception {
        String body = mvc.perform(post("/api/v1/imports").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromDate\":\"2026-10-07\",\"toDate\":\"2026-10-07\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    protected JsonNode getJson(String url) throws Exception {
        return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    protected long orderId(String assetName) throws Exception {
        for (JsonNode o : getJson("/api/v1/orders?size=100").path("content")) {
            if (o.path("assetName").asText().equals(assetName)) {
                return o.path("id").asLong();
            }
        }
        throw new AssertionError("No order " + assetName);
    }

    protected static String jsonOf(Object... keyValues) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            Object v = keyValues[i + 1];
            sb.append('"').append(keyValues[i]).append("\":")
                    .append(v instanceof String s ? "\"" + s + "\"" : v instanceof List<?> l ? l.toString() : v);
        }
        return sb.append('}').toString();
    }
}
