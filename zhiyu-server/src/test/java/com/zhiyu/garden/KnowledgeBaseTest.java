package com.zhiyu.garden;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeBaseTest {
    static KnowledgeBase reviewed() {
        var docs = KnowledgeBase.corpus();
        return new KnowledgeBase(null, docs, docs.stream().map(d -> new KnowledgeBase.Review(d.id(), d.contentHash(), "合成审核员（仅测试）", "2026-10-01")).toList());
    }
    @Test void pendingAndStaleReviewsNeverReachGeneration() {
        var docs = KnowledgeBase.corpus();
        assertEquals(150, docs.size());
        var kb = new KnowledgeBase(null, docs, List.of(new KnowledgeBase.Review(docs.getFirst().id(), "stale-hash", "测试", "2026-10-01")));
        assertEquals("NO_APPROVED_CONTENT", kb.search("打开复习资料", Set.of("exam_open")).status());
        assertTrue(kb.catalog().stream().allMatch(c -> c.reviewStatus().equals("PENDING")));
    }
    @Test void retrievalRespectsPolicyAndReturnsAuditableEvidence() {
        var result = reviewed().search("只打开一份复习资料", Set.of("exam_open"));
        assertEquals("MATCHED", result.status());
        assertEquals("memory-hybrid", result.mode());
        assertTrue(result.citations().stream().anyMatch(c -> c.activityId().equals("exam_open")));
        assertTrue(result.citations().stream().allMatch(c -> c.activityId().isEmpty() || c.activityId().equals("exam_open")));
        assertTrue(result.citations().stream().allMatch(c -> c.reviewStatus().equals("APPROVED") && !c.source().isBlank()));
        assertTrue(result.citations().size() <= 3);
        assertEquals("NO_MATCH", reviewed().search("", Set.of()).status());
    }
    @Test void databaseFailureDoesNotPretendToBeVectorSuccess() {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate() {
            @Override public int update(String sql, Object... args) {
                throw new org.springframework.dao.DataAccessResourceFailureException("synthetic");
            }
        };
        var kb = new KnowledgeBase(jdbc, KnowledgeBase.corpus(), List.of());
        assertEquals("UNAVAILABLE", kb.search("复习", Set.of()).status());
    }
    @Test void pendingKnowledgeFallsBackBeforeCallingProviderAndKeepsConfirmationBoundary() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var repo = new MemoryGardenRepository(mapper);
        var garden = new GardenController(repo, true, Clock.systemUTC());
        var client = new DoubaoClient(mapper, "synthetic", "synthetic", "https://example.invalid") {
            @Override public AgentResult completeAgent(List<Map<String,Object>> messages, List<Map<String,Object>> tools, String choice) {
                fail("Pending evidence must not reach the model"); return null;
            }
        };
        var chat = new GardenChatController(repo, client, mapper);
        chat.setKnowledgeBase(new KnowledgeBase(null, KnowledgeBase.corpus(), List.of()));
        String auth = "Bearer " + garden.session(new GardenController.Consent(true)).data().token();
        var reply = chat.chat(auth, new GardenChatController.Input("不知道从哪里开始", true, false,
            List.of(new GardenChatController.Message("user", "资料太多")))).data();
        assertEquals("fallback", reply.source());
        assertTrue(reply.notice().contains("NO_APPROVED_CONTENT"));
        assertTrue(reply.citations().isEmpty());
        assertEquals("PENDING", reply.activitySources().getFirst().reviewStatus());
        assertNotNull(reply.confirmationToken());
        assertTrue(garden.garden(auth).data().garden().tasks.isEmpty());
    }
    @Test void retrievedContextMustBeCitedAndCannotAuthorizeAnInventedCitation() throws Exception {
        for (boolean forged : List.of(false, true)) {
            var mapper = new ObjectMapper().findAndRegisterModules();
            var repo = new MemoryGardenRepository(mapper);
            var garden = new GardenController(repo, true, Clock.systemUTC());
            var client = new DoubaoClient(mapper, "synthetic", "synthetic", "https://example.invalid") {
                int calls;
                @Override public AgentResult completeAgent(List<Map<String,Object>> messages, List<Map<String,Object>> tools, String choice) {
                    assertTrue(messages.stream().anyMatch(m -> m.get("content").toString().contains("本轮检索资料")));
                    if (++calls == 1) return new AgentResult("", List.of(
                        new ToolCall("k", "search_knowledge_base", "{\"query\":\"复习启动 打开资料\"}"),
                        new ToolCall("s", "search_activities", "{\"blocker\":\"START\"}"),
                        new ToolCall("p", "propose_action", "{\"activityId\":\"exam_open\",\"reason\":\"先从打开资料开始。\"}")), "");
                    var proposal = messages.stream().filter(m -> "tool".equals(m.get("role")))
                        .map(m -> {
                            try { return mapper.readTree(m.get("content").toString()); }
                            catch (Exception error) { return mapper.createObjectNode(); }
                        }).filter(node -> node.path("requiresUserConfirmation").asBoolean()).findFirst().orElseThrow();
                    assertTrue(proposal.path("citationIds").isArray());
                    assertFalse(proposal.path("citationIds").isEmpty());
                    String citationId = proposal.path("citationIds").get(0).asText();
                    return new AgentResult("{\"reply\":\"可以从这个小起点开始。\",\"needHelp\":false,\"phase\":\"RECOMMEND\",\"activityIds\":[\"exam_open\"],\"citationIds\":[\""
                        + (forged ? "fabricated" : citationId) + "\"]}", List.of(), "");
                }
            };
            var chat = new GardenChatController(repo, client, mapper);
            chat.setKnowledgeBase(reviewed());
            String auth = "Bearer " + garden.session(new GardenController.Consent(true)).data().token();
            var reply = chat.chat(auth, new GardenChatController.Input("只打开一份复习资料", true, false,
                List.of(new GardenChatController.Message("user", "资料太多")))).data();
            assertEquals(forged ? "fallback" : "doubao", reply.source());
            assertEquals(forged ? 0 : 1, reply.citations().size());
            assertTrue(garden.garden(auth).data().garden().tasks.isEmpty());
        }
    }
}
