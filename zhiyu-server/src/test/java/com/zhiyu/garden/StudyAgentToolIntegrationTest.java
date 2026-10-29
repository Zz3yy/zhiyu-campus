package com.zhiyu.garden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StudyAgentToolIntegrationTest {
    @Test void queryToolReturnsOnlyTheSeparatelyAuthorizedScope() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> secondRequest = new java.util.concurrent.atomic.AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/chat/completions", exchange -> {
            int turn = calls.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (turn == 2) secondRequest.set(body);
            String content;
            String finish;
            if (turn == 1) {
                finish = "tool_calls";
                content = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", finish, "message", Map.of(
                    "content", "", "tool_calls", List.of(Map.of("id", "call-query", "type", "function",
                        "function", Map.of("name", "query_garden_state", "arguments", "{\"scope\":\"feedback\"}")))
                )))));
            } else {
                finish = "stop";
                var reply = mapper.writeValueAsString(Map.of("reply", "我会参考你明确提供的行动反馈。", "needHelp", false,
                    "phase", "RECOMMEND", "activityIds", List.of("exam_open")));
                content = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", finish,
                    "message", Map.of("content", reply)))));
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var repository = new MemoryGardenRepository(mapper);
            var garden = new GardenController(repository, true, Clock.systemUTC());
            var client = new DoubaoClient(mapper, "synthetic-test-key", "synthetic-test-model",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3");
            var chat = new GardenChatController(repository, client, mapper);
            String auth = "Bearer " + garden.session(new GardenController.Consent(true)).data().token();
            String task = garden.task(auth, new GardenController.TaskInput(null, "exam_outline", null)).data().tasks.getFirst().id;
            garden.updateTask(auth, task, new GardenController.TaskChange("DONE", 4));
            var other = "Bearer " + garden.session(new GardenController.Consent(true)).data().token();
            String otherTask = garden.task(other, new GardenController.TaskInput(null, "exam_question", null)).data().tasks.getFirst().id;
            garden.updateTask(other, otherTask, new GardenController.TaskChange("SKIPPED", null, "NOT_HELPFUL"));
            var reply = chat.chat(auth, new GardenChatController.Input("继续复习", true, false, true,
                List.of(new GardenChatController.Message("user", "资料太多")))).data();
            // Reading feedback alone is not a completed recommendation workflow.
            assertEquals("fallback", reply.source());
            assertTrue(secondRequest.get().contains("exam_outline"));
            assertTrue(secondRequest.get().contains("helpfulScore"));
            assertFalse(secondRequest.get().contains("NOT_HELPFUL"));
            assertFalse(secondRequest.get().contains(otherTask));
        } finally { server.stop(0); }
    }

    @Test void modelUsesSearchAndProposalToolsBeforeReturningAUserConfirmableAction() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> firstToolChoice = new AtomicReference<>();
        AtomicReference<String> secondToolChoice = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/chat/completions", exchange -> {
            int turn = calls.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var request = mapper.readTree(body);
            assertTrue(request.path("tools").size() >= 4);
            if (turn == 1) firstToolChoice.set(request.path("tool_choice").asText());
            if (turn == 2) secondToolChoice.set(request.path("tool_choice").asText());
            String response;
            if (turn == 1) {
                response = mapper.writeValueAsString(Map.of("choices", List.of(Map.of(
                    "finish_reason", "tool_calls", "message", Map.of("content", "", "tool_calls", List.of(Map.of(
                        "id", "call-search", "type", "function", "function", Map.of("name", "search_activities", "arguments", "{\"blocker\":\"OVERWHELMED\",\"minutes\":3}")
                    )))
                ))));
            } else if (turn == 2) {
                response = mapper.writeValueAsString(Map.of("choices", List.of(Map.of(
                    "finish_reason", "tool_calls", "message", Map.of("content", "", "tool_calls", List.of(Map.of(
                        "id", "call-propose", "type", "function", "function", Map.of("name", "propose_action", "arguments", "{\"activityId\":\"exam_paragraph\",\"reason\":\"资料很多时先读一小段，范围更容易收住。\"}")
                    )))
                ))));
            } else {
                var content = mapper.writeValueAsString(Map.of("reply", "我们先把范围缩小一点，试试这个起点。", "needHelp", false,
                    "phase", "RECOMMEND", "activityIds", List.of("exam_paragraph")));
                response = mapper.writeValueAsString(Map.of("choices", List.of(Map.of(
                    "finish_reason", "stop", "message", Map.of("content", content)
                ))));
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var repository = new MemoryGardenRepository(mapper);
            var garden = new GardenController(repository, true, Clock.systemUTC());
            var client = new DoubaoClient(mapper, "synthetic-test-key", "synthetic-test-model",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3");
            var chat = new GardenChatController(repository, client, mapper);
            String auth = "Bearer " + garden.session(new GardenController.Consent(true)).data().token();
            var history = List.of(new GardenChatController.Message("user", "考试资料太多"));
            var reply = chat.chat(auth, new GardenChatController.Input("不知道从哪里开始", true, false, false, history)).data();
            assertEquals("doubao", reply.source());
            assertEquals(List.of("search_activities", "propose_action"), reply.toolTrace());
            assertEquals("required", firstToolChoice.get());
            assertEquals("auto", secondToolChoice.get());
            assertEquals("exam_paragraph", reply.activities().getFirst().id());
            assertEquals(0, garden.garden(auth).data().garden().tasks.size());
        } finally { server.stop(0); }
    }

    @Test void taskCreationIsASeparateConfirmationToolAndIsIdempotent() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var repository = new MemoryGardenRepository(mapper);
        var garden = new GardenController(repository, true, Clock.systemUTC());
        var chat = new GardenChatController(repository, new DoubaoClient(mapper, "", "", "https://example.invalid"), mapper);
        String auth = "Bearer " + garden.session(new GardenController.Consent(true)).data().token();
        assertTrue(garden.garden(auth).data().garden().tasks.isEmpty());
        chat.chat(auth, new GardenChatController.Input("资料太多", false, false, List.of())).data();
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> chat.createTask(auth, new GardenChatController.CreateTaskInput("exam_outline", null, "forged")));
        var recommendation = chat.chat(auth, new GardenChatController.Input("不知道从哪里开始", false, false,
            List.of(new GardenChatController.Message("user", "资料太多")))).data();
        assertNotNull(recommendation.confirmationToken());
        var first = chat.createTask(auth, new GardenChatController.CreateTaskInput("exam_outline", null, recommendation.confirmationToken())).data();
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> chat.createTask(auth, new GardenChatController.CreateTaskInput("exam_outline", null, recommendation.confirmationToken())));
        var second = garden.garden(auth).data().garden();
        assertEquals(1, first.tasks.size());
        assertEquals(1, second.tasks.size());
        assertEquals("exam_outline", second.tasks.getFirst().activityId);
    }

    @Test void confirmationTokenBindsTheSelectedPlant() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var repository = new MemoryGardenRepository(mapper);
        var garden = new GardenController(repository, true, Clock.systemUTC());
        var chat = new GardenChatController(repository, new DoubaoClient(mapper, "", "", "https://example.invalid"), mapper);
        String auth = "Bearer " + garden.session(new GardenController.Consent(true)).data().token();
        String entry = garden.seed(auth, new GardenController.Seed("entry", "mimosa", 3, "合成测试", "Asia/Shanghai"))
            .data().entries.getFirst().id;
        var recommendation = chat.chat(auth, new GardenChatController.Input("不知道从哪里开始", false, false, false,
            List.of(new GardenChatController.Message("user", "资料太多")), entry)).data();
        assertNotNull(recommendation.confirmationToken());
        var result = chat.createTask(auth, new GardenChatController.CreateTaskInput(
            recommendation.activities().getFirst().id(), entry, recommendation.confirmationToken())).data();
        assertEquals(entry, result.tasks.getFirst().entryId);
    }
}
