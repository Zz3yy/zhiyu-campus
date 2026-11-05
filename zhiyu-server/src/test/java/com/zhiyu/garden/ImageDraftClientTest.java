package com.zhiyu.garden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class ImageDraftClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> content = new AtomicReference<>("{\"text\":\"我担心学不会，不知道从哪里开始\"}");
    private final AtomicReference<String> raw = new AtomicReference<>(), received = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200), calls = new AtomicInteger(), delay = new AtomicInteger();
    private final AtomicBoolean stalledBody = new AtomicBoolean();
    private final AtomicReference<String> finish = new AtomicReference<>("stop");
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet(); received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                String json = raw.get() != null ? raw.get() : mapper.writeValueAsString(Map.of("choices", List.of(Map.of(
                    "finish_reason", finish.get(), "message", Map.of("content", content.get())))));
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                if (delay.get() > 0 && !stalledBody.get()) Thread.sleep(delay.get());
                exchange.sendResponseHeaders(status.get(), bytes.length);
                if (stalledBody.get()) { exchange.getResponseBody().write(bytes, 0, 1); exchange.getResponseBody().flush(); Thread.sleep(delay.get()); exchange.getResponseBody().write(bytes, 1, bytes.length - 1); }
                else exchange.getResponseBody().write(bytes);
            } catch (Exception ignored) { } finally { exchange.close(); }
        }); server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private ImageDraftClient client(String key, String model, long timeout) {
        return new ImageDraftClient(mapper, key, model, "http://127.0.0.1:" + server.getAddress().getPort(), timeout);
    }
    private int failure(ImageDraftClient client) { return assertThrows(ResponseStatusException.class, () -> client.extract(new byte[]{1,2,3})).getStatusCode().value(); }

    @Test void sendsInlineImageWithoutToolsHistoryOrGardenIdentityAndPreservesRiskWords() throws Exception {
        var client = client("synthetic-key", "synthetic-vision", 2000);
        content.set("{\"text\":\"我不想活了\"}");
        assertEquals("我不想活了", client.extract(new byte[]{1,2,3}));
        var body = mapper.readTree(received.get());
        assertEquals(Set.of("model", "messages", "max_tokens", "stream"), mapper.convertValue(body, Map.class).keySet());
        assertEquals("synthetic-vision", body.path("model").asText());
        assertEquals(2, body.path("messages").size());
        var parts = body.path("messages").path(1).path("content");
        assertEquals("image_url", parts.path(1).path("type").asText());
        assertEquals("data:image/jpeg;base64,AQID", parts.path(1).path("image_url").path("url").asText());
        assertFalse(received.get().contains("synthetic-key"));
        assertFalse(body.has("tools"));
    }
    @Test void missingConfigDoesNotCallProvider() {
        assertFalse(client("", "synthetic", 2000).configured());
        assertFalse(client("synthetic", "", 2000).configured());
        assertEquals(503, failure(client("", "", 2000))); assertEquals(0, calls.get());
    }
    @Test void authModelAndQuotaErrorsAreSanitized() {
        var client = client("synthetic", "synthetic", 2000); raw.set("PRIVATE_PROVIDER_ERROR");
        for (int code : List.of(400, 401, 403, 404)) {
            status.set(code);
            var error = assertThrows(ResponseStatusException.class, () -> client.extract(new byte[]{1}));
            assertEquals(503, error.getStatusCode().value()); assertFalse(error.getReason().contains("PRIVATE_PROVIDER"));
        }
        status.set(429); assertEquals(429, failure(client));
        status.set(500); assertEquals(502, failure(client));
    }
    @Test void blankMalformedLongAndTruncatedResultsNeverBecomeDrafts() {
        var client = client("synthetic", "synthetic", 2000);
        content.set("{\"text\":\"\"}"); assertEquals(422, failure(client));
        content.set("{\"text\":\"" + "字".repeat(1001) + "\"}"); assertEquals(422, failure(client));
        content.set("not json"); assertEquals(502, failure(client));
        content.set("{\"text\":\"草稿\",\"diagnosis\":true}"); assertEquals(502, failure(client));
        content.set("{\"text\":\"草稿\"}"); finish.set("length"); assertEquals(502, failure(client));
        finish.set("stop"); raw.set("bad JSON"); assertEquals(502, failure(client));
        raw.set("x".repeat(64_001)); assertEquals(502, failure(client));
    }
    @Test void deadlineBoundsStalledBodyAndHeadersAndRemoteHttpIsRejected() {
        delay.set(400); assertEquals(504, failure(client("synthetic", "synthetic", 100)));
        stalledBody.set(true); assertEquals(504, failure(client("synthetic", "synthetic", 100)));
        assertThrows(IllegalArgumentException.class, () -> new ImageDraftClient(mapper, "", "", "http://example.com", 1000));
    }
}
