package com.zhiyu.garden;

import com.zhiyu.common.ApiResponse;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/garden/images")
public class ImageDraftController {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MAX_SIDE = 4096;
    public static final long MAX_PIXELS = 12_000_000;
    private final GardenRepository repository;
    private final ImageDraftClient client;
    private final Semaphore slots = new Semaphore(2);
    public ImageDraftController(GardenRepository repository, ImageDraftClient client) { this.repository = repository; this.client = client; }
    public record Status(boolean configured, int maxBytes, int maxSide, long maxPixels) {}
    public record Draft(String text) {}

    @GetMapping("/status")
    public ApiResponse<Status> status(@RequestHeader(value="Authorization", required=false) String auth) {
        own(auth);
        // Configuration presence alone cannot prove that the selected model supports image input.
        return ApiResponse.success(new Status(client.configured(), MAX_BYTES, MAX_SIDE, MAX_PIXELS));
    }
    @PostMapping(value="/drafts", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Draft> extract(@RequestHeader(value="Authorization", required=false) String auth,
            @RequestParam(defaultValue="false") boolean consent, @RequestPart("image") MultipartFile image) throws IOException {
        own(auth);
        if (!consent) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先单独同意将本次图片发送给豆包识别。");
        if (image.isEmpty() || image.getSize() > MAX_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "请选择一张不超过 5 MB 的 JPG 或 PNG 图片。");
        if (!slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "图片识别请求较多，请稍后再试。");
        try {
            byte[] jpeg = normalizedJpeg(image.getBytes());
            // No history, garden details, filenames, EXIF metadata or anonymous session token leave this boundary.
            return ApiResponse.success(new Draft(client.extract(jpeg)));
        } finally { slots.release(); }
    }

    /** Decode validated pixels and re-encode without EXIF/GPS metadata; all streams stay in memory. */
    static byte[] normalizedJpeg(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalidImage();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidImage();
            var reader = readers.next();
            try {
                if (!Set.of("jpeg", "png").contains(reader.getFormatName().toLowerCase(Locale.ROOT))) throw invalidImage();
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE || (long)width * height > MAX_PIXELS)
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片尺寸过大，请裁剪卡点部分：最长边不超过 4096 像素，总像素不超过 1200 万。");
                var decoded = reader.read(0);
                var rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                var graphics = rgb.createGraphics();
                try { graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, width, height); graphics.drawImage(decoded, 0, 0, null);
                } finally { graphics.dispose(); decoded.flush(); }
                try (var buffer = new ByteArrayOutputStream(); var output = new MemoryCacheImageOutputStream(buffer)) {
                    if (!ImageIO.write(rgb, "jpeg", output)) throw invalidImage();
                    output.flush();
                    if (buffer.size() > MAX_BYTES) throw invalidImage();
                    return buffer.toByteArray();
                } finally { rgb.flush(); }
            } finally { reader.dispose(); }
        } catch (ResponseStatusException e) { throw e;
        } catch (IOException | RuntimeException e) { throw invalidImage(); }
    }
    private static ResponseStatusException invalidImage() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取这张图片，请选择完整的 JPG 或 PNG 图片。");
    }
    private void own(String auth) {
        if (repository.read(GardenController.credential(auth)) == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "花园会话已失效，请重新进入。");
    }
}
