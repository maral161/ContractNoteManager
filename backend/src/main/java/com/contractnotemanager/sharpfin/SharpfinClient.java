package com.contractnotemanager.sharpfin;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.contractnotemanager.config.SharpfinProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Talks to the Sharpfin API with a session login ({@code /api/sessions}).
 * The session cookie (or token, if {@code sharpfin.login.token-field} is set) is kept per import.
 */
@Component
public class SharpfinClient {

    private static final Logger log = LoggerFactory.getLogger(SharpfinClient.class);

    private final SharpfinProperties props;
    private final ObjectMapper mapper;

    public SharpfinClient(SharpfinProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public SharpfinSession openSession() {
        if (!props.hasCredentials()) {
            throw new SharpfinException(
                    "Sharpfin credentials missing: set SHARPFIN_USERNAME and SHARPFIN_PASSWORD", 0);
        }
        if (props.baseUrl().startsWith("http://") && !isLocal(props.baseUrl())) {
            throw new SharpfinException("Sharpfin must be reached over HTTPS: " + props.baseUrl(), 0);
        }
        HttpSessionImpl session = new HttpSessionImpl();
        session.login();
        return session;
    }

    /** Top-level field names of a JSON answer (never the values, which may hold tokens). */
    private String fieldNames(String body) {
        try {
            JsonNode json = mapper.readTree(body == null || body.isBlank() ? "{}" : body);
            List<String> names = new ArrayList<>();
            json.fieldNames().forEachRemaining(names::add);
            return names.isEmpty() ? "none" : String.join(", ", names);
        } catch (IOException e) {
            return "not JSON";
        }
    }

    /** First text value of the given field anywhere in the JSON (depth-first), e.g. the user's name. */
    private static String firstText(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode direct = node.get(field);
        if (direct != null && direct.isValueNode() && !direct.asText().isBlank()) {
            return direct.asText();
        }
        for (JsonNode child : node) {
            if (child.isContainerNode()) {
                String found = firstText(child, field);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String shorten(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }

    private static boolean isLocal(String url) {
        return url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1");
    }

    private final class HttpSessionImpl implements SharpfinSession {
        private final HttpClient http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .proxy(ProxySelector.getDefault())
                .connectTimeout(props.timeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        private String bearerToken;
        private String lastUrl;
        private String sessionUser;

        @Override
        public String sessionUser() {
            return sessionUser;
        }

        /** Asks Sharpfin who this session belongs to (GET /api/sessions), only for display and the log. */
        private String whoAmI(String sessionPath) {
            try {
                HttpResponse<String> response = http.send(baseRequest(sessionPath).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 300) {
                    log.info("Sharpfin GET {} → HTTP {} (session user unknown)", sessionPath, response.statusCode());
                    return null;
                }
                JsonNode json = parse(response.body());
                String name = firstText(json, "name");
                String email = firstText(json, "email");
                String user = name == null && email == null ? null
                        : (name == null ? "" : name) + (email == null ? "" : (name == null ? "" : " ") + "<" + email + ">");
                log.info("Sharpfin session user: {} (answer fields: {})", user == null ? "unknown" : user,
                        fieldNames(response.body()));
                return user;
            } catch (IOException | RuntimeException e) {
                log.info("Could not read the Sharpfin session user: {}", e.getMessage());
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        @Override
        public String lastRequestUrl() {
            return lastUrl;
        }

        void login() {
            SharpfinProperties.Login login = props.login();
            Map<String, String> body = Map.of(
                    login.usernameField(), props.username(),
                    login.passwordField(), props.password());
            HttpResponse<String> response;
            try {
                HttpRequest request = baseRequest(login.path())
                        .method(login.method(), HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build();
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
                log.info("Sharpfin {} {}{} (fields: {}, {}) → HTTP {}", login.method(), props.baseUrl(), login.path(),
                        login.usernameField(), login.passwordField(), response.statusCode());
            } catch (IOException e) {
                throw new SharpfinException("Cannot reach Sharpfin at " + props.baseUrl() + ": " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SharpfinException("Sharpfin login interrupted", e);
            }
            if (response.statusCode() >= 300) {
                // Sharpfin's answer explains what is wrong with the request; our request (password) is never logged
                String answer = shorten(response.body());
                log.warn("Sharpfin login failed: HTTP {} {} {} – answer: {}", response.statusCode(), login.method(),
                        login.path(), answer);
                throw new SharpfinException("Sharpfin login failed (HTTP " + response.statusCode() + ")"
                        + (answer.isEmpty() ? "" : ": " + answer), response.statusCode());
            }
            String tokenField = login.tokenField();
            if (tokenField != null && !tokenField.isBlank()) {
                JsonNode json = parse(response.body());
                JsonNode token = json.get(tokenField);
                if (token == null || token.asText().isBlank()) {
                    throw new SharpfinException("Sharpfin login answer has no '" + tokenField + "'", response.statusCode());
                }
                bearerToken = token.asText();
            }
            List<String> cookies = response.headers().allValues("set-cookie").stream()
                    .map(c -> c.split("=", 2)[0].trim())
                    .toList();
            log.info("Logged in to Sharpfin at {} (cookies set: {}, answer fields: {})", props.baseUrl(),
                    cookies.isEmpty() ? "none" : String.join(", ", cookies), fieldNames(response.body()));
            sessionUser = whoAmI(login.path());
        }

        @Override
        public JsonNode ordersPage(String dateType, LocalDate from, LocalDate to, int page) {
            String query = "type=instrument&date_type=" + URLEncoder.encode(dateType, StandardCharsets.UTF_8)
                    + "&page=" + page
                    + "&page_size=" + props.pageSize()
                    + "&no_of_elements=0&sort_field=booked_date&sort_direction=asc"
                    + "&from_date=" + from + "&to_date=" + to
                    + "&status=all&owner_key=all&active_orders=true&include_deleted=false";
            return getJson("/api/orders/paginated?" + query);
        }

        @Override
        public JsonNode orderDetails(String orderKey) {
            String key = URLEncoder.encode(orderKey, StandardCharsets.UTF_8);
            return getJson("/api/orders/" + key + "?calculate_allocations=true");
        }

        private JsonNode getJson(String pathAndQuery) {
            HttpResponse<String> response = send(pathAndQuery);
            if (response.statusCode() == 401) {
                log.info("Sharpfin session expired, logging in again");
                login();
                response = send(pathAndQuery);
            }
            if (response.statusCode() >= 300) {
                String answer = shorten(response.body());
                log.warn("Sharpfin answered HTTP {} for GET {} – answer: {}", response.statusCode(), pathAndQuery, answer);
                throw new SharpfinException("Sharpfin answered HTTP " + response.statusCode() + " for "
                        + pathAndQuery.replaceAll("\\?.*", "") + (answer.isEmpty() ? "" : ": " + answer),
                        response.statusCode());
            }
            return parse(response.body());
        }

        private HttpResponse<String> send(String pathAndQuery) {
            try {
                HttpResponse<String> response = http.send(baseRequest(pathAndQuery).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                lastUrl = props.baseUrl() + pathAndQuery;
                log.info("Sharpfin GET {} → HTTP {} ({} bytes)", lastUrl, response.statusCode(),
                        response.body() == null ? 0 : response.body().length());
                return response;
            } catch (IOException e) {
                throw new SharpfinException("Cannot reach Sharpfin: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SharpfinException("Sharpfin request interrupted", e);
            }
        }

        private HttpRequest.Builder baseRequest(String pathAndQuery) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(props.baseUrl() + pathAndQuery))
                    .timeout(props.timeout())
                    .header("Content-type", "application/json")
                    .header("Accept", "application/json");
            if (bearerToken != null) {
                builder.header("Authorization", "Bearer " + bearerToken);
            }
            return builder;
        }

        private JsonNode parse(String body) {
            try {
                return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
            } catch (IOException e) {
                throw new SharpfinException("Sharpfin answered with invalid JSON", e);
            }
        }

        @Override
        public void close() {
            try {
                HttpRequest request = baseRequest(props.login().path()).DELETE().build();
                http.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (IOException | RuntimeException e) {
                log.debug("Sharpfin logout failed (ignored): {}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            http.close();
        }
    }
}
