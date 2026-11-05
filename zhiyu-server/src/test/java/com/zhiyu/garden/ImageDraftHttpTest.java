package com.zhiyu.garden;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("memory")
class ImageDraftHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean ImageDraftClient client;
    private String auth() throws Exception {
        var body = mvc.perform(post("/api/garden/session").contentType("application/json").content("{\"accepted\":true}"))
            .andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(body).path("data").path("token").asText();
    }
    private byte[] picture(String format) throws Exception {
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream(); ImageIO.write(image, format, output); return output.toByteArray();
    }
    private MockMultipartFile image() throws Exception { return new MockMultipartFile("image", "note.png", "image/png", picture("png")); }

    @Test void sessionAndIndependentImageConsentAreRequired() throws Exception {
        mvc.perform(get("/api/garden/images/status")).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/garden/images/drafts").file(image()).param("consent", "true")).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/garden/images/drafts").file(image()).header("Authorization", auth()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("请先单独同意将本次图片发送给豆包识别。"));
        verifyNoInteractions(client);
    }
    @Test void validatesActualImageBytesSizeAndPixelDimensionsBeforeProvider() throws Exception {
        String auth = auth();
        mvc.perform(get("/api/garden/images/status").header("Authorization", auth)).andExpect(jsonPath("$.data.configured").value(false));
        clearInvocations(client);
        for (byte[] bytes : new byte[][] {new byte[]{1,2,3}, picture("gif"), Arrays.copyOf(picture("png"), 40)}) {
            mvc.perform(multipart("/api/garden/images/drafts").file(new MockMultipartFile("image", "fake.png", "image/png", bytes))
                .header("Authorization", auth).param("consent", "true")).andExpect(status().isBadRequest());
        }
        mvc.perform(multipart("/api/garden/images/drafts").file(new MockMultipartFile("image", "huge.jpg", "image/jpeg", new byte[ImageDraftController.MAX_BYTES + 1]))
            .header("Authorization", auth).param("consent", "true")).andExpect(status().isPayloadTooLarge());
        mvc.perform(multipart("/api/garden/images/drafts").param("consent", "true").header("Authorization", auth)).andExpect(status().isBadRequest());
        // Forge only PNG dimensions and CRC: reject before allocating/decompressing a huge raster.
        byte[] oversized = picture("png"); ByteBuffer.wrap(oversized).putInt(16, 10000);
        var crc = new CRC32(); crc.update(oversized, 12, 17); ByteBuffer.wrap(oversized).putInt(29, (int)crc.getValue());
        assertThrows(ResponseStatusException.class, () -> ImageDraftController.normalizedJpeg(oversized));
        verifyNoInteractions(client);
    }
    @Test void stripsJpegMetadataBeforeProviderAndDoesNotCreateTasks() throws Exception {
        String auth = auth();
        byte[] jpeg = picture("jpeg"), tag = "Exif\0\0PRIVATE_GPS_OR_COMMENT".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var source = new ByteArrayOutputStream(); source.write(jpeg, 0, 2); source.write(0xff); source.write(0xe1);
        source.write((tag.length + 2) >> 8); source.write((tag.length + 2) & 255); source.write(tag); source.write(jpeg, 2, jpeg.length - 2);
        when(client.extract(any())).thenAnswer(invocation -> {
            byte[] sent = invocation.getArgument(0);
            assertFalse(new String(sent, java.nio.charset.StandardCharsets.ISO_8859_1).contains("PRIVATE_GPS_OR_COMMENT"));
            assertNotNull(ImageIO.read(new ByteArrayInputStream(sent)));
            return "复习资料太多，不知道从哪里开始";
        });
        mvc.perform(multipart("/api/garden/images/drafts").file(new MockMultipartFile("image", "private-name.jpg", "application/octet-stream", source.toByteArray()))
            .header("Authorization", auth).param("consent", "true")).andExpect(status().isOk()).andExpect(jsonPath("$.data.text").value("复习资料太多，不知道从哪里开始"));
        mvc.perform(get("/api/garden").header("Authorization", auth)).andExpect(jsonPath("$.data.garden.tasks.length()").value(0));
    }
    @Test void pngBecomesJpegAndRiskTextStillEntersExistingSafetyFlowOnlyAfterSend() throws Exception {
        assertEquals((byte)0xff, ImageDraftController.normalizedJpeg(picture("png"))[0]);
        String auth = auth(); when(client.extract(any())).thenReturn("我不想活了");
        mvc.perform(multipart("/api/garden/images/drafts").file(image()).header("Authorization", auth).param("consent", "true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.text").value("我不想活了"));
        mvc.perform(post("/api/garden/chat").header("Authorization", auth).contentType("application/json")
            .content("{\"text\":\"我不想活了\",\"cloudConsent\":false,\"history\":[]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.phase").value("SAFETY")).andExpect(jsonPath("$.data.activities.length()").value(0));
        mvc.perform(get("/api/garden").header("Authorization", auth)).andExpect(jsonPath("$.data.garden.tasks.length()").value(0));
    }
    @Test void deletedSessionCannotRecognizeImages() throws Exception {
        String auth = auth(); mvc.perform(delete("/api/garden").header("Authorization", auth)).andExpect(status().isOk());
        mvc.perform(multipart("/api/garden/images/drafts").file(image()).header("Authorization", auth).param("consent", "true")).andExpect(status().isUnauthorized());
        verifyNoInteractions(client);
    }
}
