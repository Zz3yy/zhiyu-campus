package com.zhiyu.garden;

import com.zhiyu.common.ApiResponse;
import java.io.IOException;
import java.nio.*;
import java.util.concurrent.Semaphore;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/garden/speech")
public class SpeechController {
    public static final int MAX_SECONDS = 30;
    public static final int MAX_BYTES = 44 + 16000 * 2 * MAX_SECONDS;
    private final GardenRepository repository;
    private final SpeechClient client;
    private final Semaphore slots = new Semaphore(2);

    public SpeechController(GardenRepository repository, SpeechClient client) { this.repository = repository; this.client = client; }
    public record Status(String provider, boolean configured, int maxSeconds) {}
    public record Transcript(String text, String provider) {}

    @GetMapping("/status")
    public ApiResponse<Status> status(@RequestHeader(value="Authorization", required=false) String auth) {
        own(auth);
        return ApiResponse.success(new Status("火山引擎语音识别", client.configured(), MAX_SECONDS));
    }

    @PostMapping(value="/transcriptions", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Transcript> transcribe(@RequestHeader(value="Authorization", required=false) String auth,
            @RequestParam(defaultValue="false") boolean consent, @RequestPart("audio") MultipartFile audio) throws IOException {
        own(auth);
        if (!consent) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先单独同意将录音发送给语音服务商。");
        if (audio.isEmpty() || audio.getSize() > MAX_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "请提供 30 秒以内的单声道 WAV 录音。");
        byte[] wav = audio.getBytes();
        validateWav(wav);
        if (!slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "当前语音请求较多，请稍后重试。");
        try {
            // No garden context, chat history or user identity is sent to the speech provider.
            return ApiResponse.success(new Transcript(client.transcribe(wav), "火山引擎语音识别"));
        } finally { slots.release(); }
    }

    /** Accept only the bounded PCM format produced by our recorder; do not trust MIME or duration fields. */
    static void validateWav(byte[] wav) {
        if (wav.length < 44 || wav.length > MAX_BYTES) throw invalidAudio();
        var bytes = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.getInt(0) != 0x46464952 || bytes.getInt(8) != 0x45564157 || bytes.getInt(12) != 0x20746d66
                || bytes.getInt(16) != 16 || bytes.getShort(20) != 1 || bytes.getShort(22) != 1
                || bytes.getInt(24) != 16000 || bytes.getInt(28) != 32000 || bytes.getShort(32) != 2
                || bytes.getShort(34) != 16 || bytes.getInt(36) != 0x61746164
                || bytes.getInt(4) != wav.length - 8 || bytes.getInt(40) != wav.length - 44
                || (wav.length - 44) % 2 != 0 || wav.length - 44 < 3200) throw invalidAudio();
    }
    private static ResponseStatusException invalidAudio() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "录音格式或时长无效，请使用页面录制 30 秒以内的 WAV 语音。");
    }
    private void own(String auth) {
        if (repository.read(GardenController.credential(auth)) == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "花园会话已失效，请重新进入。");
    }
}
