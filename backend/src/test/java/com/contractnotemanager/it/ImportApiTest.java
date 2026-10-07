package com.contractnotemanager.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;

class ImportApiTest extends IntegrationTest {

    @Test
    void importsAllPagesWithDetails() throws Exception {
        JsonNode run = runImport();
        assertThat(run.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(run.path("expectedCount").asInt()).isEqualTo(10);
        assertThat(run.path("createdCount").asInt()).isEqualTo(10); // 3 pages of 4

        JsonNode abb = getJson("/api/v1/orders/" + orderId("ABB"));
        assertThat(abb.path("status").asText()).isEqualTo("NEW");
        assertThat(abb.path("quantity").decimalValue()).isEqualByComparingTo("1864");
        assertThat(abb.path("amount").decimalValue()).isEqualByComparingTo("811809.28");
        assertThat(abb.path("counterpart").asText()).isEqualTo("BN Amro");   // from the details call
        assertThat(abb.path("ownerName").asText()).isEqualTo("Alex Advisor");
        JsonNode first = abb.path("allocations").get(0);
        assertThat(first.path("portfolioName").asText()).isEqualTo("Portfolio Alpha - 1001");
        assertThat(first.path("portfolioQuantity").decimalValue()).isEqualByComparingTo("2927");
        assertThat(first.path("targetWeight").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(first.path("commission").decimalValue()).isEqualByComparingTo("1134");

        JsonNode fund = getJson("/api/v1/orders/" + orderId("AMF Räntefond Lång"));
        assertThat(fund.path("quantity").isMissingNode()).isTrue(); // amount order: Quantity column stays empty
        assertThat(fund.path("qtyDecimals").asInt()).isEqualTo(6);
    }

    @Test
    void reimportIsIdempotentAndPicksUpChanges() throws Exception {
        runImport();
        assertThat(runImport().path("skippedCount").asInt()).isEqualTo(10);

        SHARPFIN.change("Tesla Inc", o -> o.put("price", "751.00"));
        JsonNode run = runImport();
        assertThat(run.path("updatedCount").asInt()).isEqualTo(1);
        assertThat(getJson("/api/v1/orders/" + orderId("Tesla Inc")).path("price").decimalValue())
                .isEqualByComparingTo("751.00");
        assertThat(getJson("/api/v1/orders?size=100").path("totalElements").asInt()).isEqualTo(10);
    }

    @Test
    void localEditsWinAndAreFlaggedAsConflict() throws Exception {
        runImport();
        long id = orderId("Kinnevik A");
        int version = getJson("/api/v1/orders/" + id).path("version").asInt();
        mvc.perform(patch("/api/v1/orders/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(jsonOf("version", version, "price", 205)))
                .andExpect(status().isOk());

        SHARPFIN.change("Kinnevik A", o -> o.put("price", "199.00"));
        assertThat(runImport().path("conflictCount").asInt()).isEqualTo(1);
        JsonNode order = getJson("/api/v1/orders/" + id);
        assertThat(order.path("price").decimalValue()).isEqualByComparingTo("205");
        assertThat(order.path("syncConflict").asBoolean()).isTrue();

        // revert takes the newest Sharpfin values
        JsonNode reverted = json.readTree(mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/orders/" + id + "/revert"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(reverted.path("price").decimalValue()).isEqualByComparingTo("199.00");
        assertThat(reverted.path("locallyModified").asBoolean()).isFalse();
    }

    @Test
    void sharpfinStatusMovesForwardOnly() throws Exception {
        runImport();
        SHARPFIN.change("Telia Company", o -> o.put("status", "finalized"));
        runImport();
        assertThat(getJson("/api/v1/orders/" + orderId("Telia Company")).path("status").asText())
                .isEqualTo("ALLOCATED");
        SHARPFIN.change("Telia Company", o -> o.put("status", "new"));
        runImport();
        assertThat(getJson("/api/v1/orders/" + orderId("Telia Company")).path("status").asText())
                .isEqualTo("ALLOCATED");
    }

    @Test
    void missingDetailsMakeThePartial() throws Exception {
        SHARPFIN.failDetailsFor("Apple Inc");
        JsonNode run = runImport();
        assertThat(run.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(run.path("detailsMissingCount").asInt()).isEqualTo(1);
        assertThat(run.path("createdCount").asInt()).isEqualTo(10);
        assertThat(getJson("/api/v1/orders/" + orderId("Apple Inc")).path("detailsMissing").asBoolean()).isTrue();

        SHARPFIN.stubAll(); // details work again: the next import fills them in
        runImport();
        assertThat(getJson("/api/v1/orders/" + orderId("Apple Inc")).path("detailsMissing").asBoolean()).isFalse();
    }

    @Test
    void deletedOrderComesBackOnTheNextImport() throws Exception {
        runImport();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/orders/" + orderId("ABB"))).andExpect(status().isNoContent());
        assertThat(runImport().path("createdCount").asInt()).isEqualTo(1);
    }

    @Test
    void sendsTheChosenDateTypeToSharpfin() throws Exception {
        runImport(); // no date type given → booked
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/imports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-07\",\"dateType\":\"SETTLED\"}"))
                .andExpect(status().isOk());
        var paged = com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo("/api/orders/paginated"));
        WIREMOCK.verify(paged.withQueryParam("date_type", com.github.tomakehurst.wiremock.client.WireMock.equalTo("booked")));
        WIREMOCK.verify(paged.withQueryParam("date_type", com.github.tomakehurst.wiremock.client.WireMock.equalTo("settled"))
                .withQueryParam("from_date", com.github.tomakehurst.wiremock.client.WireMock.equalTo("2026-10-01")));
        assertThat(getJson("/api/v1/imports").get(0).path("dateType").asText()).isEqualTo("SETTLED");
        assertThat(getJson("/api/v1/imports").get(0).path("sharpfinUser").asText())
                .isEqualTo("Test User <test@example.com>");
        assertThat(getJson("/api/v1/imports").get(0).path("requestUrl").asText())
                .contains("/api/orders/paginated?type=instrument&date_type=settled")
                .contains("from_date=2026-10-01&to_date=2026-10-07");
    }

    @Test
    void failedLoginIsReported() throws Exception {
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/api/sessions")
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(401)));
        JsonNode run = runImport();
        assertThat(run.path("status").asText()).isEqualTo("FAILED");
        assertThat(run.path("errorMessage").asText()).isEqualTo("Sharpfin login failed (HTTP 401)");
    }
}
