package com.zhiyu.garden;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HybridKnowledgeTest {
    @Test void corpusHasDistinctPassagesAndAuditableMetadata() throws Exception {
        var docs = KnowledgeBase.corpus();
        assertEquals(150, docs.size());
        assertEquals(150, docs.stream().map(KnowledgeBase.Document::content).distinct().count());
        assertEquals(43, docs.stream().filter(d -> d.category().equals("scenario")).count());
        assertEquals(43, docs.stream().filter(d -> d.category().equals("template")).count());
        assertTrue(docs.stream().allMatch(d -> d.contentHash().length() == 64 && !d.sourceRelation().isBlank()));
        var kb = new KnowledgeBase(null, docs, List.of());
        assertTrue(kb.catalog().stream().allMatch(c -> c.reviewStatus().equals("PENDING")));
        try (var stream = getClass().getResourceAsStream("/knowledge/reviews.json")) {
            assertEquals(0, new ObjectMapper().readTree(stream).size(), "Do not promote synthetic reviewers into production");
        }
    }

    @Test void hybridRanksRelevantConceptsAndFiltersBeforeRanking() {
        var kb = KnowledgeBaseTest.reviewed();
        var result = kb.search("间隔重复的基本原理", Set.of(), new KnowledgeBase.Filters(2, "anxiety", "dormitory", "low"));
        assertEquals("MATCHED", result.status());
        assertTrue(result.citations().stream().anyMatch(c -> c.id().startsWith("kb-spacing-")));
        assertTrue(result.hits().stream().allMatch(h -> h.bm25() > 0 && h.fusionScore() > 0 && h.vectorSimilarity() >= 0));
        assertTrue(result.citations().stream().allMatch(c -> c.metadata().timeMinutes() <= 2
            && c.metadata().scenes().contains("dormitory") && c.metadata().targetEmotions().contains("anxiety")
            && !c.metadata().cognitiveLoad().equals("medium")));
        assertEquals("NO_MATCH", kb.search("zzxqv982137", Set.of()).status(), "Hash collisions cannot manufacture a match");
        assertTrue(kb.search("最坏最好最可能", Set.of(), new KnowledgeBase.Filters(1, null, null, null)).citations()
            .stream().noneMatch(c -> c.id().contains("three-outcomes")), "Five-minute template must not leak through a one-minute filter");
    }

    @Test void userConstraintsIgnoreAssistantAndCannotBeRelaxedByTools() {
        assertEquals(KnowledgeBase.Filters.none(), KnowledgeBase.Filters.none().narrow(KnowledgeBase.Filters.none()));
        var history = List.of(new GardenChatController.Message("user", "我在宿舍，只有两分钟"),
            new GardenChatController.Message("assistant", "在图书馆可以花五分钟"));
        var filters = KnowledgeBase.userFilters(history, "太难了，我不想看");
        assertEquals(2, filters.timeLimit()); assertEquals("dormitory", filters.scene()); assertEquals("very_low", filters.cognitiveLoad());
        var narrowed = filters.narrow(new KnowledgeBase.Filters(5, null, null, "medium"));
        assertEquals(filters, narrowed);
        assertThrows(IllegalArgumentException.class, () -> filters.narrow(new KnowledgeBase.Filters(null, null, "library", null)));
        assertEquals("library", KnowledgeBase.userFilters(history, "我现在在图书馆，只有一 分钟").scene());
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeBase.Filters(0, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeBase.Filters(null, "diagnosis", null, null));
    }

    @Test void multiHopReplacesOldEvidenceAndShowsActualSearches() throws Exception {
        for (boolean stale : List.of(false, true)) {
            var mapper = new ObjectMapper(); var repo = new MemoryGardenRepository(mapper);
            var garden = new GardenController(repo, true, Clock.systemUTC());
            var client = new DoubaoClient(mapper, "synthetic", "synthetic", "https://example.invalid") {
                int calls;
                @Override public AgentResult completeAgent(List<Map<String,Object>> messages, List<Map<String,Object>> tools, String choice) {
                    if (++calls == 1) return new AgentResult("", List.of(new ToolCall("k1", "search_knowledge_base", "{\"query\":\"间隔重复的基本原理\"}")), "");
                    if (calls == 2) return new AgentResult("", List.of(new ToolCall("k2", "search_knowledge_base", "{\"query\":\"自我关怀不是放弃目标\",\"cognitive_load\":\"low\"}")), "");
                    return new AgentResult("{\"reply\":\"自我关怀可以与承担学习责任并存，并不保证成绩。\",\"needHelp\":false,\"phase\":\"EXPLAIN\",\"activityIds\":[],\"citationIds\":[\""
                        + (stale ? "kb-spacing-concept" : "kb-self-compassion-concept") + "\"]}", List.of(), "");
                }
            };
            var chat = new GardenChatController(repo, client, mapper); chat.setKnowledgeBase(KnowledgeBaseTest.reviewed());
            String auth = "Bearer " + garden.session(new GardenController.Consent(true)).data().token();
            var reply = chat.chat(auth, new GardenChatController.Input("解释一下间隔重复和自我关怀", true, false, List.of())).data();
            assertEquals(stale ? "fallback" : "doubao", reply.source(), reply.notice());
            assertEquals(3, reply.retrievalTrace().size());
            assertEquals(List.of("search_knowledge_base", "search_knowledge_base"), reply.toolTrace());
            assertTrue(reply.activities().isEmpty()); assertNull(reply.confirmationToken());
        }
    }

    @Test void rejectedSuggestionTriggersLowLoadSearchAndPreservesTwoMinuteBudget() throws Exception {
        var mapper = new ObjectMapper(); var repo = new MemoryGardenRepository(mapper);
        var garden = new GardenController(repo, true, Clock.systemUTC());
        var client = new DoubaoClient(mapper, "synthetic", "synthetic", "https://example.invalid") {
            int calls;
            @Override public AgentResult completeAgent(List<Map<String,Object>> messages, List<Map<String,Object>> tools, String choice) {
                if (++calls == 1) return new AgentResult("", List.of(
                    new ToolCall("k", "search_knowledge_base", "{\"query\":\"太难 降低认知负担\",\"time_limit\":5,\"cognitive_load\":\"medium\"}"),
                    new ToolCall("s", "search_activities", "{\"blocker\":\"START\",\"minutes\":3}"),
                    new ToolCall("p", "propose_action", "{\"activityId\":\"exam_open\",\"reason\":\"先减少阅读负担。\"}")), "");
                return new AgentResult("{\"reply\":\"可以只保留更轻的起点，也可以停下。\",\"needHelp\":false,\"phase\":\"RECOMMEND\",\"activityIds\":[\"exam_open\"],\"citationIds\":[\"activity-exam_open\"]}", List.of(), "");
            }
        };
        var chat = new GardenChatController(repo, client, mapper); chat.setKnowledgeBase(KnowledgeBaseTest.reviewed());
        String auth = "Bearer " + garden.session(new GardenController.Consent(true)).data().token();
        var history = List.of(new GardenChatController.Message("user", "我在宿舍，明天考高数，只有两分钟"),
            new GardenChatController.Message("assistant", "试试读一段", "exam_paragraph"));
        var reply = chat.chat(auth, new GardenChatController.Input("太难了，我不想看", true, false, history)).data();
        assertEquals("doubao", reply.source(), reply.notice());
        assertTrue(reply.retrievalTrace().stream().allMatch(t -> t.result().filters().timeLimit() == 2
            && t.result().filters().cognitiveLoad().equals("very_low") && t.result().filters().scene().equals("dormitory")));
        assertTrue(garden.garden(auth).data().garden().tasks.isEmpty()); assertNotNull(reply.confirmationToken());
    }

    @Test void riskStopsBeforeAnyKnowledgeOrModelCall() throws Exception {
        var mapper = new ObjectMapper(); var repo = new MemoryGardenRepository(mapper);
        var garden = new GardenController(repo, true, Clock.systemUTC());
        var client = new DoubaoClient(mapper, "synthetic", "synthetic", "https://example.invalid") {
            @Override public AgentResult completeAgent(List<Map<String,Object>> messages, List<Map<String,Object>> tools, String choice) {
                fail("Risk must stop before provider"); return null;
            }
        };
        var chat = new GardenChatController(repo, client, mapper); chat.setKnowledgeBase(KnowledgeBaseTest.reviewed());
        String auth = "Bearer " + garden.session(new GardenController.Consent(true)).data().token();
        var reply = chat.chat(auth, new GardenChatController.Input("考试失败，我想伤害自己", true, false, List.of())).data();
        assertEquals("SAFETY", reply.phase()); assertTrue(reply.retrievalTrace().isEmpty()); assertTrue(reply.activities().isEmpty());
    }

    @Test void actionToolCannotReturnThreeMinuteActionForOneMinuteRequest() throws Exception {
        var mapper = new ObjectMapper(); var repo = new MemoryGardenRepository(mapper);
        var garden = new GardenController(repo, true, Clock.systemUTC());
        var client = new DoubaoClient(mapper, "synthetic", "synthetic", "https://example.invalid") {
            int calls;
            @Override public AgentResult completeAgent(List<Map<String,Object>> messages, List<Map<String,Object>> tools, String choice) {
                if (++calls == 1) return new AgentResult("", List.of(
                    new ToolCall("k", "search_knowledge_base", "{\"query\":\"复习启动\"}"),
                    new ToolCall("s", "search_activities", "{\"blocker\":\"FEAR\",\"minutes\":1}"),
                    new ToolCall("p", "propose_action", "{\"activityId\":\"exam_question\",\"reason\":\"读一道熟悉题。\"}")), "");
                assertTrue(messages.stream().filter(m -> m.get("role").equals("tool")).anyMatch(m -> m.get("content").toString().contains("activity_not_in_search_results")));
                return new AgentResult("{\"reply\":\"先读题。\",\"needHelp\":false,\"phase\":\"RECOMMEND\",\"activityIds\":[\"exam_question\"],\"citationIds\":[\"activity-exam_question\"]}", List.of(), "");
            }
        };
        var chat = new GardenChatController(repo, client, mapper); chat.setKnowledgeBase(KnowledgeBaseTest.reviewed());
        String auth = "Bearer " + garden.session(new GardenController.Consent(true)).data().token();
        var reply = chat.chat(auth, new GardenChatController.Input("担心学不会", true, false,
            List.of(new GardenChatController.Message("user", "我明天要考试")))).data();
        assertEquals("fallback", reply.source()); assertTrue(reply.citations().isEmpty());
    }

    @Test void repeatedSearchesStopAtBudgetAndCannotCreateTasks() throws Exception {
        var mapper = new ObjectMapper(); var repo = new MemoryGardenRepository(mapper);
        var garden = new GardenController(repo, true, Clock.systemUTC());
        var client = new DoubaoClient(mapper, "synthetic", "synthetic", "https://example.invalid") {
            @Override public AgentResult completeAgent(List<Map<String,Object>> messages, List<Map<String,Object>> tools, String choice) {
                return new AgentResult("", List.of(new ToolCall("k", "search_knowledge_base", "{\"query\":\"执行意图\"}"),
                    new ToolCall("c", "create_task", "{\"activityId\":\"exam_open\"}")), "");
            }
        };
        var chat = new GardenChatController(repo, client, mapper); chat.setKnowledgeBase(KnowledgeBaseTest.reviewed());
        String auth = "Bearer " + garden.session(new GardenController.Consent(true)).data().token();
        var reply = chat.chat(auth, new GardenChatController.Input("什么是执行意图", true, false, List.of())).data();
        assertEquals("fallback", reply.source()); assertEquals(4, reply.retrievalTrace().size());
        assertTrue(garden.garden(auth).data().garden().tasks.isEmpty());
    }
}
