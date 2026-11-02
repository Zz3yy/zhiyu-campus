package com.zhiyu.garden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class SpeechClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> result = new AtomicReference<>("{\"result\":{\"text\":\"复习资料太多，不知道从哪里开始。\"}}");
    private final AtomicReference<String> code = new AtomicReference<>("20000000");
    private final AtomicInteger status = new AtomicInteger(200), calls = new AtomicInteger(), delay = new AtomicInteger();
    private final AtomicBoolean delayBody = new AtomicBoolean();
    private final AtomicReference<String> received = new AtomicReference<>();
    private final AtomicReference<com.sun.net.httpserver.Headers> headers = new AtomicReference<>();
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/recognize", exchange -> {
            calls.incrementAndGet(); headers.set(exchange.getRequestHeaders());
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                byte[] body = result.get().getBytes(StandardCharsets.UTF_8);
                if (delay.get() > 0 && !delayBody.get()) Thread.sleep(delay.get());
                if (!code.get().isEmpty()) exchange.getResponseHeaders().set("X-Api-Status-Code", code.get());
                exchange.sendResponseHeaders(status.get(), body.length);
                if (delayBody.get()) {
                    exchange.getResponseBody().write(body, 0, 1); exchange.getResponseBody().flush(); Thread.sleep(delay.get());
                    exchange.getResponseBody().write(body, 1, body.length - 1);
                } else exchange.getResponseBody().write(body);
            } catch (Exception ignored) { } finally { exchange.close(); }
        }); server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private SpeechClient client(String key, String app, String token, long timeout) {
        return new SpeechClient(mapper, key, app, token, "http://127.0.0.1:" + server.getAddress().getPort() + "/recognize", timeout);
    }
    static byte[] wav(int samples) {
        var b = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46464952).putInt(b.capacity() - 8).putInt(0x45564157).putInt(0x20746d66).putInt(16)
            .putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16)
            .putInt(0x61746164).putInt(samples * 2);
        return b.array();
    }
    @Test void sendsOnlyAudioAndGenericIdentityWithNewConsoleAuth() throws Exception {
        byte[] audio = wav(16000);
        assertTrue(client("synthetic-key", "", "", 2000).configured());
        assertEquals("复习资料太多，不知道从哪里开始。", client("synthetic-key", "", "", 2000).transcribe(audio));
        var body = mapper.readTree(received.get());
        assertEquals(Set.of("user", "audio", "request"), mapper.convertValue(body, Map.class).keySet());
        assertEquals("zhiyu-voice", body.path("user").path("uid").asText());
        assertArrayEquals(audio, Base64.getDecoder().decode(body.path("audio").path("data").asText()));
        assertEquals("synthetic-key", headers.get().getFirst("X-Api-Key"));
        assertNull(headers.get().getFirst("X-Api-Access-Key"));
        assertEquals("volc.bigasr.auc_turbo", headers.get().getFirst("X-Api-Resource-Id"));
        assertEquals("-1", headers.get().getFirst("X-Api-Sequence"));
    }
    @Test void legacyAuthAndMissingConfigAreDistinct() {
        assertFalse(client("", "", "", 2000).configured());
        assertFalse(client("", "synthetic-app", "", 2000).configured());
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> client("", "", "", 2000).transcribe(wav(16000))).getStatusCode().value());
        assertEquals(0, calls.get());
        client("", "synthetic-app", "synthetic-token", 2000).transcribe(wav(16000));
        assertEquals("synthetic-app", headers.get().getFirst("X-Api-App-Key"));
        assertEquals("synthetic-token", headers.get().getFirst("X-Api-Access-Key"));
        assertNull(headers.get().getFirst("X-Api-Key"));
    }
    @Test void upstreamFailuresNeverExposeProviderBody() {
        var client = client("synthetic-key", "", "", 2000);
        result.set("PRIVATE_UPSTREAM_DETAILS"); status.set(403);
        var denied = assertThrows(ResponseStatusException.class, () -> client.transcribe(wav(16000)));
        assertEquals(503, denied.getStatusCode().value()); assertFalse(denied.getReason().contains("PRIVATE_UPSTREAM"));
        status.set(429);
        assertEquals(429, assertThrows(ResponseStatusException.class, () -> client.transcribe(wav(16000))).getStatusCode().value());
        status.set(200); code.set(""); result.set("{\"result\":{\"text\":\"伪成功\"}}");
        assertEquals(502, assertThrows(ResponseStatusException.class, () -> client.transcribe(wav(16000))).getStatusCode().value());
    }
    @Test void silenceMalformedOversizedAndLongTranscriptsFailClearly() {
        var client = client("synthetic-key", "", "", 2000);
        code.set("20000003");
        assertEquals(422, assertThrows(ResponseStatusException.class, () -> client.transcribe(wav(16000))).getStatusCode().value());
        code.set("20000000"); result.set("not-json");
        assertEquals(502, assertThrows(ResponseStatusException.class, () -> client.transcribe(wav(16000))).getStatusCode().value());
        result.set("{\"result\":{\"text\":\"\"}}");
        assertEquals(422, assertThrows(ResponseStatusException.class, () -> client.transcribe(wav(16000))).getStatusCode().value());
        result.set("{\"result\":{\"text\":\"" + "字".repeat(1001) + "\"}}");
        assertEquals(422, assertThrows(ResponseStatusException.class, () -> client.transcribe(wav(16000))).getStatusCode().value());
        result.set("x".repeat(128_001));
        assertEquals(502, assertThrows(ResponseStatusException.class, () -> client.transcribe(wav(16000))).getStatusCode().value());
    }
    @Test void timeoutAndInsecureRemoteEndpointAreRejected() {
        delay.set(400);
        assertEquals(504, assertThrows(ResponseStatusException.class, () -> client("synthetic-key", "", "", 100).transcribe(wav(16000))).getStatusCode().value());
        assertThrows(IllegalArgumentException.class, () -> new SpeechClient(mapper, "", "", "", "http://example.com/speech", 2000));
    }
    @Test void wavValidationUsesActualBytesAndDuration() {
        SpeechController.validateWav(wav(480000));
        assertThrows(ResponseStatusException.class, () -> SpeechController.validateWav(wav(480001)));
        assertThrows(ResponseStatusException.class, () -> SpeechController.validateWav(wav(10)));
        var forged = wav(16000); forged[24] = 0;
        assertThrows(ResponseStatusException.class, () -> SpeechController.validateWav(forged));
        var wrongLength = Arrays.copyOf(wav(16000), 40000);
        assertThrows(ResponseStatusException.class, () -> SpeechController.validateWav(wrongLength));
        assertThrows(ResponseStatusException.class, () -> SpeechController.validateWav("webm masquerading as wav".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void deadlineAlsoBoundsStalledResponseBodyAfterHeaders() {
        delayBody.set(true); delay.set(400);
        assertEquals(504, assertThrows(ResponseStatusException.class,
            () -> client("synthetic-key", "", "", 100).transcribe(wav(16000))).getStatusCode().value());
    }
}
