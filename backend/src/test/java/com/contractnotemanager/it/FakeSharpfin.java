package com.contractnotemanager.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * A fake Sharpfin API on WireMock: session login (cookie), paginated order list and order details.
 * Data: src/test/resources/sharpfin/orders-details.json (anonymised copy of the 2026-10-07 orders).
 */
public class FakeSharpfin {

    public static final String USERNAME = "test-user";
    public static final String PASSWORD = "test-password";
    public static final int PAGE_SIZE = 4;

    private final WireMockServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ObjectNode> orders = new ArrayList<>();

    public FakeSharpfin(WireMockServer server) {
        this.server = server;
        reset();
    }

    public final void reset() {
        orders.clear();
        try (InputStream in = getClass().getResourceAsStream("/sharpfin/orders-details.json")) {
            ArrayNode all = (ArrayNode) mapper.readTree(in);
            all.forEach(n -> orders.add((ObjectNode) n));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        stubAll();
    }

    public List<ObjectNode> orders() {
        return orders;
    }

    public ObjectNode order(String assetName) {
        return orders.stream().filter(o -> o.path("asset").path("name").asText().equals(assetName)).findFirst()
                .orElseThrow();
    }

    /** Simulates a change in Sharpfin: bumps the version and applies the edit. */
    public void change(String assetName, java.util.function.Consumer<ObjectNode> edit) {
        ObjectNode o = order(assetName);
        edit.accept(o);
        o.put("version", o.path("version").asInt() + 1);
        stubAll();
    }

    public void failDetailsFor(String assetName) {
        server.stubFor(get(urlPathEqualTo("/api/orders/"
                + URLEncoder.encode(order(assetName).path("key").asText(), StandardCharsets.UTF_8)))
                .atPriority(1)
                .willReturn(aResponse().withStatus(500)));
    }

    public void stubAll() {
        server.resetAll();
        server.stubFor(post(urlPathEqualTo("/api/sessions"))
                .withRequestBody(equalToJson("{\"email\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .willReturn(aResponse().withStatus(200).withHeader("Set-Cookie", "sid=abc123; Path=/")
                        .withHeader("Content-Type", "application/json").withBody("{}")));
        server.stubFor(post(urlPathEqualTo("/api/sessions")).atPriority(10)
                .willReturn(aResponse().withStatus(401)));
        server.stubFor(delete(urlPathEqualTo("/api/sessions")).willReturn(aResponse().withStatus(204)));
        // without the session cookie every data call is rejected
        server.stubFor(get(urlPathMatching("/api/orders.*")).atPriority(10).willReturn(aResponse().withStatus(401)));

        int pages = Math.max(1, (orders.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        for (int page = 1; page <= pages; page++) {
            ObjectNode body = mapper.createObjectNode();
            body.put("no_of_pages", pages);
            body.put("no_of_elements", orders.size());
            body.put("page_size", PAGE_SIZE);
            body.put("page", page);
            ArrayNode list = body.putArray("orders");
            orders.subList((page - 1) * PAGE_SIZE, Math.min(page * PAGE_SIZE, orders.size())).forEach(o -> {
                ObjectNode listItem = o.deepCopy();
                listItem.set("broker", mapper.createObjectNode()); // the list has no broker details
                for (var a : listItem.withArray("allocation")) {
                    ((ObjectNode) a).remove(List.of("commission", "portfolio_weight", "portfolio_quantity",
                            "order_weight", "target_quantity", "target_weight"));
                }
                list.add(listItem);
            });
            server.stubFor(get(urlPathEqualTo("/api/orders/paginated"))
                    .withQueryParam("page", equalTo(String.valueOf(page)))
                    .withQueryParam("type", equalTo("instrument"))
                    .withCookie("sid", equalTo("abc123"))
                    .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body.toString())));
        }
        for (ObjectNode o : orders) {
            String key = o.path("key").asText();
            server.stubFor(get(urlPathEqualTo("/api/orders/" + URLEncoder.encode(key, StandardCharsets.UTF_8)))
                    .withQueryParam("calculate_allocations", equalTo("true"))
                    .withCookie("sid", equalTo("abc123"))
                    .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(o.toString())));
        }
    }
}
