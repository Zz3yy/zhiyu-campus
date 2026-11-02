package com.zhiyu.garden;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Speech boundary: never log audio, transcription, credentials or upstream errors. */
@Service
public class SpeechClient {
    private static final int MAX_RESPONSE_BYTES = 128_000;
    private final ObjectMapper mapper;
    private final String apiKey, appId, accessToken;
    private final URI endpoint;
    private final HttpClient http;
    private final Duration timeout;

    public SpeechClient(ObjectMapper mapper,
            @Value("${ai.speech.api-key:}") String apiKey,
            @Value("${ai.speech.app-id:}") String appId,
            @Value("${ai.speech.access-token:}") String accessToken,
            @Value("${ai.speech.endpoint:https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash}") String endpoint,
            @Value("${ai.speech.request-timeout-ms:30000}") long timeoutMillis) {
        this.mapper = mapper;
        this.apiKey = apiKey.trim(); this.appId = appId.trim(); this.accessToken = accessToken.trim();
        this.endpoint = URI.create(endpoint);
        // Plain HTTP is reserved for local contract tests. Never forward credentials via redirects.
        boolean loopback = Set.of("127.0.0.1", "localhost", "[::1]").contains(this.endpoint.getHost());
        if ((!"https".equals(this.endpoint.getScheme()) && !(loopback && "http".equals(this.endpoint.getScheme())))
                || this.endpoint.getUserInfo() != null || timeoutMillis < 1 || timeoutMillis > 60_000)
            throw new IllegalArgumentException("Invalid speech endpoint or timeout");
        this.timeout = Duration.ofMillis(timeoutMillis);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public boolean configured() { return !apiKey.isBlank() || (!appId.isBlank() && !accessToken.isBlank()); }

    public String transcribe(byte[] wav) {
        if (!configured()) throw failure(HttpStatus.SERVICE_UNAVAILABLE, "语音转写尚未配置，可以继续打字。");
        try {
            var payload = Map.of("user", Map.of("uid", "zhiyu-voice"),
                "audio", Map.of("data", Base64.getEncoder().encodeToString(wav)),
                "request", Map.of("model_name", "bigmodel", "enable_itn", true, "enable_punc", true));
            var builder = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json")
                .header("X-Api-Resource-Id", "volc.bigasr.auc_turbo")
                .header("X-Api-Request-Id", UUID.randomUUID().toString()).header("X-Api-Sequence", "-1");
            if (!apiKey.isBlank()) builder.header("X-Api-Key", apiKey);
            else builder.header("X-Api-App-Key", appId).header("X-Api-Access-Key", accessToken);
            // Bound both the response body and the full wait, including a stalled body after headers.
            var pending = http.sendAsync(builder.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build(),
                ignored -> limitedBody());
            HttpResponse<byte[]> response;
            try { response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException error) {
                pending.cancel(true); throw new HttpTimeoutException("Speech deadline exceeded");
            } catch (InterruptedException error) { pending.cancel(true); throw error;
            } catch (ExecutionException error) {
                if (error.getCause() instanceof HttpTimeoutException timeoutError) throw timeoutError;
                throw error;
            }
            if (response.statusCode() == 401 || response.statusCode() == 403)
                throw failure(HttpStatus.SERVICE_UNAVAILABLE, "语音服务暂不可用，请检查服务开通与后端配置；可以继续打字。");
            if (response.statusCode() == 429)
                throw failure(HttpStatus.TOO_MANY_REQUESTS, "语音服务繁忙，请稍后重试或继续打字。");
            if (response.statusCode() != 200 || response.body().length > MAX_RESPONSE_BYTES)
                throw failure(HttpStatus.BAD_GATEWAY, "语音服务暂时未能完成转写，请重试或继续打字。");
            String code = response.headers().firstValue("X-Api-Status-Code").orElse("");
            if (Set.of("20000003", "45000002").contains(code)) throw noSpeech();
            if (!"20000000".equals(code))
                throw failure(HttpStatus.BAD_GATEWAY, "语音服务未能识别这段录音，请重试或继续打字。");
            var root = mapper.readTree(new String(response.body(), StandardCharsets.UTF_8));
            var textNode = root.path("result").path("text");
            if (!textNode.isTextual()) throw failure(HttpStatus.BAD_GATEWAY, "语音服务返回了无法使用的结果，请重试。");
            String text = textNode.asText().trim();
            if (text.isBlank()) throw noSpeech();
            if (text.length() > 1000) throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "转写文字超过 1000 字，请分成更短的录音。");
            return text;
        } catch (HttpTimeoutException error) {
            throw failure(HttpStatus.GATEWAY_TIMEOUT, "语音转写等待超时，可以重试或继续打字；录音可能已发送给服务商。");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "语音转写已中断，请重试。");
        } catch (ResponseStatusException error) { throw error;
        } catch (Exception error) {
            throw failure(HttpStatus.BAD_GATEWAY, "暂时无法完成语音转写，请重试或继续打字。");
        }
    }

    private static ResponseStatusException noSpeech() {
        return failure(HttpStatus.UNPROCESSABLE_ENTITY, "没有识别到清晰的话语，请靠近麦克风再试，或继续打字。");
    }
    private static HttpResponse.BodySubscriber<byte[]> limitedBody() {
        return new HttpResponse.BodySubscriber<>() {
            private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
            private Flow.Subscription subscription;
            private int received;
            private boolean failed;
            public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
            public void onNext(List<ByteBuffer> buffers) {
                if (failed) return;
                for (var buffer : buffers) {
                    received += buffer.remaining();
                    if (received > MAX_RESPONSE_BYTES) {
                        failed = true; subscription.cancel();
                        delegate.onError(new java.io.IOException("Speech response too large")); return;
                    }
                }
                delegate.onNext(buffers);
            }
            public void onError(Throwable error) { if (!failed) delegate.onError(error); }
            public void onComplete() { if (!failed) delegate.onComplete(); }
        };
    }
    private static ResponseStatusException failure(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
}
