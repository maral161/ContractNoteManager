package com.contractnotemanager.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultMatcher;

import com.fasterxml.jackson.databind.JsonNode;

/** The full flow: import → send to market → traded → drop contract notes → confirmed → allocated. */
class WorkflowApiTest extends IntegrationTest {

    private static final Path SAMPLES = Path.of("../docs/samples/contract-notes");

    @BeforeEach
    void importOrders() throws Exception {
        runImport();
    }

    @Test
    void contractNotesConfirmTheMatchingTradedOrders() throws Exception {
        long abb = orderId("ABB");
        long apple = orderId("Apple Inc");
        long barclays = orderId("Barclays PLC");
        for (long id : List.of(abb, apple, barclays)) {
            advance(id, "NEW", status().isOk());
            advance(id, "ON_MARKET", status().isOk());
        }
        JsonNode traded = getJson("/api/v1/orders/" + abb);
        assertThat(traded.path("status").asText()).isEqualTo("TRADED");
        assertThat(traded.path("tradedDate").isMissingNode()).isFalse();
        // Traded waits for the contract note: the blue button is refused
        advance(abb, "TRADED", status().isConflict());

        JsonNode results = upload(List.of(abb, apple, barclays),
                "abn-amro_abb.pdf", "ubs_apple.pdf", "swedbank_barclays.pdf");
        assertThat(results.get(0).path("outcome").asText()).isEqualTo("MATCHED");
        assertThat(results.get(1).path("outcome").asText()).isEqualTo("MATCHED");
        assertThat(results.get(2).path("outcome").asText()).isEqualTo("UNMATCHED");
        assertThat(results.get(2).path("message").asText())
                .contains("price differs (123.47 vs 123.57)")
                .contains("settlement amount differs by 9.76 GBP");

        JsonNode abbOrder = getJson("/api/v1/orders/" + abb);
        assertThat(abbOrder.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(abbOrder.path("noteMatched").asBoolean()).isTrue();
        assertThat(abbOrder.path("price").decimalValue()).isEqualByComparingTo("435.52"); // nothing overwritten
        assertThat(abbOrder.path("commission").decimalValue()).isEqualByComparingTo("1200");
        JsonNode note = getJson("/api/v1/orders/" + abb + "/contract-note");
        assertThat(note.path("broker").asText()).isEqualTo("ABN Amro");
        assertThat(note.path("settlementAmount").decimalValue()).isEqualByComparingTo("811809.00");

        JsonNode unmatched = getJson("/api/v1/contract-notes");
        assertThat(unmatched).hasSize(1);
        assertThat(unmatched.get(0).path("isin").asText()).isEqualTo("GB0031348658");
        assertThat(getJson("/api/v1/contract-notes/count").path("unmatched").asInt()).isEqualTo(1);

        // the same PDF twice is refused
        assertThat(upload(List.of(abb), "abn-amro_abb.pdf").get(0).path("outcome").asText()).isEqualTo("DUPLICATE");

        // filters: only matched orders, by traded date
        JsonNode matched = getJson("/api/v1/orders?noteMatch=MATCHED&dateType=TRADED&from="
                + java.time.LocalDate.now() + "&to=" + java.time.LocalDate.now());
        assertThat(matched.path("totalElements").asInt()).isEqualTo(2);

        // confirmed → allocated, then locked for edits
        advance(abb, "CONFIRMED", status().isOk());
        assertThat(getJson("/api/v1/orders/" + abb).path("status").asText()).isEqualTo("ALLOCATED");
        mvc.perform(patch("/api/v1/orders/" + abb).contentType(MediaType.APPLICATION_JSON)
                .content(jsonOf("version", getJson("/api/v1/orders/" + abb).path("version").asInt(), "price", 1)))
                .andExpect(status().isConflict());

        // correcting the Barclays order price makes the note match on "match again"
        int version = getJson("/api/v1/orders/" + barclays).path("version").asInt();
        mvc.perform(patch("/api/v1/orders/" + barclays).contentType(MediaType.APPLICATION_JSON)
                .content(jsonOf("version", version, "price", "123.47")))
                .andExpect(status().isOk());
        long noteId = unmatched.get(0).path("id").asLong();
        JsonNode rematch = json.readTree(mvc.perform(post("/api/v1/contract-notes/" + noteId + "/rematch"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(rematch.path("outcome").asText()).isEqualTo("MATCHED"); // 12,100.06 vs 12,100.10
        assertThat(getJson("/api/v1/contract-notes")).isEmpty();

        // deleting a matched order puts its note back on the unmatched list
        mvc.perform(delete("/api/v1/orders/" + apple)).andExpect(status().isNoContent());
        JsonNode back = getJson("/api/v1/contract-notes");
        assertThat(back).hasSize(1);
        assertThat(back.get(0).path("reason").asText()).contains("was deleted");

        JsonNode history = getJson("/api/v1/orders/" + abb + "/status-history");
        assertThat(history.findValuesAsText("toStatus"))
                .containsExactly("NEW", "ON_MARKET", "TRADED", "CONFIRMED", "ALLOCATED");
        assertThat(history.get(3).path("trigger").asText()).isEqualTo("CONTRACT_NOTE");
    }

    @Test
    void notesOnlyMatchTheTickedOrders() throws Exception {
        long abb = orderId("ABB");
        advance(abb, "NEW", status().isOk());
        advance(abb, "ON_MARKET", status().isOk());
        JsonNode result = upload(List.of(orderId("Tesla Inc")), "abn-amro_abb.pdf").get(0);
        assertThat(result.path("outcome").asText()).isEqualTo("UNMATCHED");
        assertThat(result.path("message").asText()).contains("No selected order with ISIN CH0012221716");
    }

    @Test
    void unreadablePdfGoesToTheUnmatchedList() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "unreadable.pdf", "application/pdf",
                "%PDF-1.4 broken".getBytes());
        JsonNode result = json.readTree(mvc.perform(multipart("/api/v1/contract-notes").file(file))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get(0);
        assertThat(result.path("outcome").asText()).isEqualTo("EXTRACTION_FAILED");
        assertThat(getJson("/api/v1/contract-notes").get(0).path("status").asText()).isEqualTo("EXTRACTION_FAILED");

        MockMultipartFile notPdf = new MockMultipartFile("files", "note.txt", "text/plain", "hello".getBytes());
        JsonNode invalid = json.readTree(mvc.perform(multipart("/api/v1/contract-notes").file(notPdf))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get(0);
        assertThat(invalid.path("outcome").asText()).isEqualTo("INVALID_FILE");
    }

    @Test
    void editModalSavesQuantitiesPriceCommissionBrokerAndOwner() throws Exception {
        long abb = orderId("ABB");
        JsonNode order = getJson("/api/v1/orders/" + abb);
        long alpha = order.path("allocations").get(0).path("portfolioId").asLong();
        long beta = order.path("allocations").get(1).path("portfolioId").asLong();
        long owner = getJson("/api/v1/owners").get(0).path("id").asLong();
        String body = """
                {"version": %d, "price": 436.00, "commission": 1000, "brokerName": "ABN Amro", "ownerId": %d,
                 "allocations": [{"portfolioId": %d, "quantity": 1700}, {"portfolioId": %d, "quantity": 100}]}
                """.formatted(order.path("version").asInt(), owner, alpha, beta);
        JsonNode saved = json.readTree(mvc.perform(patch("/api/v1/orders/" + abb)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(saved.path("quantity").decimalValue()).isEqualByComparingTo("1800");
        assertThat(saved.path("amount").decimalValue()).isEqualByComparingTo("784800.00"); // 1,800 × 436
        assertThat(saved.path("counterpart").asText()).isEqualTo("ABN Amro");
        assertThat(saved.path("locallyModified").asBoolean()).isTrue();
        JsonNode a = saved.path("allocations").get(0);
        assertThat(a.path("value").decimalValue()).isEqualByComparingTo("1700");
        assertThat(a.path("originalValue").decimalValue()).isEqualByComparingTo("1761");
        assertThat(a.path("commission").decimalValue()).isEqualByComparingTo("944");   // 1,000 × 1,700 / 1,800
        assertThat(saved.path("allocations").get(1).path("commission").decimalValue()).isEqualByComparingTo("56");
        assertThat(a.path("targetQuantity").decimalValue()).isEqualByComparingTo("1227"); // 2,927 − 1,700

        // saving with an old version is refused
        mvc.perform(patch("/api/v1/orders/" + abb).contentType(MediaType.APPLICATION_JSON)
                .content(jsonOf("version", order.path("version").asInt(), "price", 1)))
                .andExpect(status().isConflict());
        // whole units only for shares
        mvc.perform(patch("/api/v1/orders/" + abb).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\": %d, \"allocations\": [{\"portfolioId\": %d, \"quantity\": 1.5}]}"
                        .formatted(saved.path("version").asInt(), alpha)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bulkActionsReportEveryOrder() throws Exception {
        long abb = orderId("ABB");
        long tesla = orderId("Tesla Inc");
        advance(tesla, "NEW", status().isOk());
        advance(tesla, "ON_MARKET", status().isOk()); // Tesla is Traded: cannot move without a note
        JsonNode result = json.readTree(mvc.perform(post("/api/v1/orders/bulk").contentType(MediaType.APPLICATION_JSON)
                .content(jsonOf("ids", List.of(abb, tesla), "action", "ADVANCE_STATUS")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(result.path("done").asInt()).isEqualTo(1);
        assertThat(result.path("skipped").asInt()).isEqualTo(1);
        assertThat(result.path("items").get(1).path("message").asText()).startsWith("Waiting for contract note");

        JsonNode deleted = json.readTree(mvc.perform(post("/api/v1/orders/bulk").contentType(MediaType.APPLICATION_JSON)
                .content(jsonOf("ids", List.of(abb, tesla), "action", "DELETE")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(deleted.path("done").asInt()).isEqualTo(2);
        assertThat(getJson("/api/v1/orders").path("totalElements").asInt()).isEqualTo(8);
    }

    private void advance(long id, String expected, ResultMatcher result) throws Exception {
        mvc.perform(post("/api/v1/orders/" + id + "/status/advance").contentType(MediaType.APPLICATION_JSON)
                .content(jsonOf("expectedStatus", expected)))
                .andExpect(result);
    }

    private JsonNode upload(List<Long> orderIds, String... sampleFiles) throws Exception {
        var request = multipart("/api/v1/contract-notes");
        for (String name : sampleFiles) {
            request.file(new MockMultipartFile("files", name, "application/pdf", Files.readAllBytes(SAMPLES.resolve(name))));
        }
        orderIds.forEach(id -> request.param("orderIds", String.valueOf(id)));
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }
}
