package com.zhiyu.garden;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("memory")
class SpeechHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean SpeechClient speech;
    private String auth() throws Exception {
        var body = mvc.perform(post("/api/garden/session").contentType("application/json").content("{\"accepted\":true}"))
            .andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(body).path("data").path("token").asText();
    }
    private MockMultipartFile audio() { return new MockMultipartFile("audio", "voice.wav", "audio/wav", SpeechClientTest.wav(16000)); }

    @Test void requiresSessionAndIndependentSpeechConsentBeforeProviderCall() throws Exception {
        mvc.perform(get("/api/garden/speech/status")).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/garden/speech/transcriptions").file(audio()).param("consent", "true")).andExpect(status().isUnauthorized());
        String auth = auth();
        mvc.perform(multipart("/api/garden/speech/transcriptions").file(audio()).header("Authorization", auth))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("请先单独同意将录音发送给语音服务商。"));
        verifyNoInteractions(speech);
    }
    @Test void statusReflectsConfigurationAndInvalidAudioNeverCallsProvider() throws Exception {
        String auth = auth();
        mvc.perform(get("/api/garden/speech/status").header("Authorization", auth)).andExpect(jsonPath("$.data.configured").value(false));
        clearInvocations(speech);
        mvc.perform(multipart("/api/garden/speech/transcriptions").file(new MockMultipartFile("audio", "fake.wav", "audio/wav", new byte[200]))
            .param("consent", "true").header("Authorization", auth)).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/garden/speech/transcriptions").file(new MockMultipartFile("audio", "large.wav", "audio/wav", new byte[SpeechController.MAX_BYTES + 1]))
            .param("consent", "true").header("Authorization", auth)).andExpect(status().isPayloadTooLarge());
        mvc.perform(multipart("/api/garden/speech/transcriptions").param("consent", "true").header("Authorization", auth))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(speech);
    }
    @Test void transcriptionHasNoTaskSideEffectAndTextStillUsesChatSafety() throws Exception {
        String auth = auth();
        when(speech.transcribe(any())).thenReturn("复习资料太多，不知道从哪里开始");
        mvc.perform(multipart("/api/garden/speech/transcriptions").file(audio()).param("consent", "true").header("Authorization", auth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.text").value("复习资料太多，不知道从哪里开始"));
        mvc.perform(get("/api/garden").header("Authorization", auth)).andExpect(jsonPath("$.data.garden.tasks.length()").value(0));
        mvc.perform(post("/api/garden/chat").header("Authorization", auth).contentType("application/json")
            .content("{\"text\":\"我不想活了\",\"cloudConsent\":false,\"history\":[]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.phase").value("SAFETY")).andExpect(jsonPath("$.data.activities.length()").value(0));
        mvc.perform(get("/api/garden").header("Authorization", auth)).andExpect(jsonPath("$.data.garden.tasks.length()").value(0));
    }
    @Test void deletedSessionCannotTranscribe() throws Exception {
        String auth = auth(); mvc.perform(delete("/api/garden").header("Authorization", auth)).andExpect(status().isOk());
        mvc.perform(multipart("/api/garden/speech/transcriptions").file(audio()).param("consent", "true").header("Authorization", auth))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(speech);
    }
}
