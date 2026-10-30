package com.zhiyu.garden;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("memory")
class GardenHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    private String credential() throws Exception {
        String body=mvc.perform(post("/api/garden/session").contentType("application/json").content("{\"accepted\":true}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer "+mapper.readTree(body).path("data").path("token").asText();
    }
    @Test void rejectsForgedLegacyIdentityAndRetiresOldApi() throws Exception {
        mvc.perform(get("/api/garden").header("X-User-Id","1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/checkins").header("X-User-Id","1")).andExpect(status().isNotFound());
    }
    @Test void validatesRequiredConsentScoresAndTextLengths() throws Exception {
        mvc.perform(post("/api/garden/session").contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        String auth=credential();
        mvc.perform(post("/api/garden/entries").header("Authorization",auth).contentType("application/json")
            .content("{\"requestId\":\"one\",\"plant\":\"mimosa\",\"intensity\":8,\"timezone\":\"Asia/Shanghai\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/garden").header("Authorization",auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.garden.entries.length()").value(0));
    }
    @Test void chatAcceptsIndependentConsentAndDefaultsMissingConsentToFalse() throws Exception {
        String auth=credential();
        mvc.perform(post("/api/garden/chat").header("Authorization",auth).contentType("application/json")
            .content("{\"text\":\"复习资料很多\",\"cloudConsent\":false,\"history\":[]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.phase").value("CLARIFY"))
            .andExpect(jsonPath("$.data.activities.length()").value(0));
        mvc.perform(post("/api/garden/chat").header("Authorization",auth).contentType("application/json")
            .content("{\"text\":\"资料太多\",\"cloudConsent\":false,\"includeGardenContext\":true,\"includeActionFeedback\":true,\"history\":[{\"role\":\"user\",\"text\":\"考试有压力\"}]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.phase").value("RECOMMEND"))
            .andExpect(jsonPath("$.data.source").value("fallback"))
            .andExpect(jsonPath("$.data.activities.length()").value(1));
        mvc.perform(get("/api/garden").header("Authorization",auth)).andExpect(jsonPath("$.data.garden.tasks.length()").value(0));
    }

    @Test void chatStreamSendsProgressValidatedTextAndFinalReply() throws Exception {
        String auth=credential();
        MvcResult started=mvc.perform(post("/api/garden/chat/stream").header("Authorization",auth).contentType("application/json")
            .content("{\"text\":\"复习资料很多\",\"cloudConsent\":false,\"history\":[]}"))
            .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(started)).andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("event:status")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("event:delta")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("event:complete")));
    }
}
