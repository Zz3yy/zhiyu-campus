package com.zhiyu.garden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoubaoIntegrationTest {
    @Test void localGuideClarifiesAndUsesExplicitTaskFeedbackWithoutCallingProvider() throws Exception {
        ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
        var repository=new MemoryGardenRepository(mapper);
        var garden=new GardenController(repository,true,Clock.systemUTC());
        var client=new DoubaoClient(mapper,"","","https://example.invalid");
        var chat=new GardenChatController(repository,client,mapper);
        String auth="Bearer "+garden.session(new GardenController.Consent(true)).data().token();

        var first=chat.chat(auth,new GardenChatController.Input("考试压力很大，一直不想打开资料",false,false,List.of())).data();
        assertTrue(first.text().contains("更像是"));
        assertTrue(first.activities().isEmpty());

        var recommendation=chat.chat(auth,new GardenChatController.Input("不知道从哪里开始",false,false,
            List.of(new GardenChatController.Message("user","考试压力很大，一直不想打开资料")))).data();
        assertEquals("exam_outline",recommendation.activities().getFirst().id());

        String taskId=garden.task(auth,new GardenController.TaskInput(null,"exam_paragraph",null)).data().tasks.getFirst().id;
        garden.updateTask(auth,taskId,new GardenController.TaskChange("SKIPPED",null,"TOO_HARD"));
        var adjusted=chat.chat(auth,new GardenChatController.Input("不知道从哪里开始",false,false,
            List.of(new GardenChatController.Message("user","考试压力很大")))).data();
        assertEquals("exam_outline",adjusted.activities().getFirst().id());
        assertTrue(adjusted.recommendationReason().contains("降低门槛"));

        var task=garden.garden(auth).data().garden().tasks.getFirst();
        assertEquals("TOO_HARD",task.feedback);
        assertNull(task.helpfulScore);

        String secondTask=garden.task(auth,new GardenController.TaskInput(null,"exam_outline",null)).data().tasks.getLast().id;
        garden.updateTask(auth,secondTask,new GardenController.TaskChange("SKIPPED",null,"NOT_HELPFUL"));
        var changedAgain=chat.chat(auth,new GardenChatController.Input("内容太多，拆不开",false,false,
            List.of(new GardenChatController.Message("user","复习任务很多")))).data();
        assertNotEquals("exam_outline",changedAgain.activities().getFirst().id());
        assertTrue(changedAgain.recommendationReason().contains("避开"));
    }

    @Test void providerContractConsentIsolationAndFailureFallback() throws Exception {
        ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
        AtomicInteger calls=new AtomicInteger(); AtomicReference<String> received=new AtomicReference<>(); AtomicInteger status=new AtomicInteger(200);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v3/chat/completions", exchange->{
            calls.incrementAndGet();received.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            var request=mapper.readTree(received.get());
            String system=request.path("messages").path(0).path("content").asText();
            var plan=mapper.readTree(system.substring(system.lastIndexOf("本轮流程约束：")+"本轮流程约束：".length()));
            String content=mapper.writeValueAsString(Map.of("reply","听起来你有些紧张。","needHelp",false,"phase",plan.path("phase").asText(),"activityIds",plan.path("activityIds")));
            byte[] response=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",content)))));
            exchange.sendResponseHeaders(status.get(),response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        try {
            var repository=new MemoryGardenRepository(mapper);
            var garden=new GardenController(repository,true,Clock.systemUTC());
            var client=new DoubaoClient(mapper,"test-key","test-model","http://127.0.0.1:"+server.getAddress().getPort()+"/api/v3");
            var chat=new GardenChatController(repository,client,mapper);
            String a="Bearer "+garden.session(new GardenController.Consent(true)).data().token();
            String b="Bearer "+garden.session(new GardenController.Consent(true)).data().token();
            garden.seed(a,new GardenController.Seed("a","mimosa",3,"OWNER_NOTE","Asia/Shanghai"));
            garden.seed(b,new GardenController.Seed("b","mimosa",3,"OTHER_USER_SECRET","Asia/Shanghai"));
            assertEquals("fallback",chat.chat(a,new GardenChatController.Input("考试紧张",false,true,List.of())).data().source());
            assertEquals(0,calls.get());
            var reply=chat.chat(a,new GardenChatController.Input("考试紧张 联系13812345678",true,false,List.of())).data();
            assertEquals("doubao",reply.source()); assertTrue(reply.activities().isEmpty());
            assertEquals("CLARIFY",reply.phase());
            assertFalse(received.get().contains("OWNER_NOTE"));assertFalse(received.get().contains("13812345678"));
            chat.chat(a,new GardenChatController.Input("考试紧张",true,true,List.of()));
            assertFalse(received.get().contains("OWNER_NOTE"));assertFalse(received.get().contains("OTHER_USER_SECRET"));
            assertTrue(received.get().contains("test-model"));
            int before=calls.get();
            assertTrue(chat.chat(a,new GardenChatController.Input("我不想活了",true,false,List.of())).data().needHelp());
            assertEquals(before,calls.get());
            status.set(401);
            var failed=chat.chat(a,new GardenChatController.Input("压力很大",true,false,List.of())).data();
            assertEquals("fallback",failed.source());
            assertTrue(failed.notice().contains("401"));
            assertFalse(failed.notice().contains("test-key"));
            status.set(429);
            assertTrue(chat.chat(a,new GardenChatController.Input("压力很大",true,false,List.of())).data().notice().contains("429"));
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->chat.status("Bearer AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
        } finally {server.stop(0);}
    }
    @Test void missingConfigNeverClaimsLiveAi() {
        var client=new DoubaoClient(new ObjectMapper(),"","","https://ark.cn-beijing.volces.com/api/v3");
        assertFalse(client.configured());assertNull(client.complete(List.of()));
        assertEquals(List.of("ARK_API_KEY","ARK_MODEL"),client.missingConfig());
        var partial=new DoubaoClient(new ObjectMapper(),"secret","","https://ark.cn-beijing.volces.com/api/v3");
        assertEquals(List.of("ARK_MODEL"),partial.missingConfig());
    }

    @Test void providerResponseTimeoutIsDistinctAndConfigurable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/chat/completions", exchange -> {
            try { Thread.sleep(500); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            try {
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (java.io.IOException ignored) {
                // The client has already timed out.
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var client = new DoubaoClient(new ObjectMapper(), "test-key", "test-model",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3", 1_000, 100);
            var result = client.completeAgent(List.of(Map.of("role", "user", "content", "test")), List.of());
            assertEquals("豆包回复超时，请稍后重试。", result.notice());
        } finally { server.stop(0); }
    }
}
