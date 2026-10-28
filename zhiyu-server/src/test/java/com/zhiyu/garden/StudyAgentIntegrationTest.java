package com.zhiyu.garden;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class StudyAgentIntegrationTest {
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final AtomicReference<String> received=new AtomicReference<>();
    final AtomicReference<String> override=new AtomicReference<>();
    final AtomicInteger calls=new AtomicInteger();
    final AtomicInteger status=new AtomicInteger(200);
    final MemoryGardenRepository repository=new MemoryGardenRepository(mapper);
    GardenController garden; GardenChatController chat; HttpServer server; String auth;
    final List<GardenChatController.Message> history=List.of(new GardenChatController.Message("user","考试资料太多"),
        new GardenChatController.Message("assistant","先说说复习卡点？"));

    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v3/chat/completions",exchange->{
            calls.incrementAndGet();String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);received.set(body);
            String system=mapper.readTree(body).path("messages").path(0).path("content").asText();
            JsonNode plan=mapper.readTree(system.substring(system.lastIndexOf("本轮流程约束：")+"本轮流程约束：".length()));
            String content=override.get()!=null?override.get():mapper.writeValueAsString(Map.of("reply","资料很多时，开始确实可能让人有压力。",
                "needHelp",false,"phase",plan.path("phase").asText(),"activityIds",plan.path("activityIds")));
            byte[] response=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",content)))));
            exchange.sendResponseHeaders(status.get(),response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        garden=new GardenController(repository,true,Clock.systemUTC());
        chat=new GardenChatController(repository,new DoubaoClient(mapper,"synthetic-test-key","synthetic-test-model","http://127.0.0.1:"+server.getAddress().getPort()+"/api/v3"),mapper);
        auth="Bearer "+garden.session(new GardenController.Consent(true)).data().token();
    }
    @AfterEach void close() { if(server!=null)server.stop(0); }
    GardenChatController.Reply ask(boolean cloud,boolean context,boolean feedback,boolean clarified) throws Exception {
        return chat.chat(auth,new GardenChatController.Input("不知道从哪里开始",cloud,context,feedback,clarified?history:List.of())).data();
    }
    String outcome(String owner,String id,String state,Integer score,String feedback) {
        String task=garden.task(owner,new GardenController.TaskInput(null,id,null)).data().tasks.getLast().id;
        garden.updateTask(owner,task,new GardenController.TaskChange(state,score,feedback));return task;
    }
    @Test void cloudClarifiesThenOffersOneActionWithoutCreatingTasks() throws Exception {
        assertEquals("CLARIFY",ask(true,false,false,false).phase());
        var reply=ask(true,false,false,true);assertEquals("doubao",reply.source());
        assertEquals("RECOMMEND",reply.phase());assertEquals(1,reply.activities().size());
        assertFalse(reply.recommendationReason().isBlank());assertFalse(reply.text().contains(reply.activities().getFirst().instructions()));
        assertTrue(garden.garden(auth).data().garden().tasks.isEmpty());
    }
    @Test void feedbackRequiresSeparateConsentAndIsOwnerScopedAndMinimal() throws Exception {
        String taskId=outcome(auth,"exam_outline","SKIPPED",null,"TOO_HARD");
        garden.seed(auth,new GardenController.Seed("owner","mimosa",3,"PRIVATE_OWNER_NOTE","Asia/Shanghai"));
        garden.task(auth,new GardenController.TaskInput(null,null,"PRIVATE_CUSTOM_TITLE"));
        String another="Bearer "+garden.session(new GardenController.Consent(true)).data().token();
        outcome(another,"exam_question","SKIPPED",null,"NOT_HELPFUL");
        var without=ask(true,false,false,true);
        String baseline=received.get();
        assertEquals("exam_outline",without.activities().getFirst().id());
        assertFalse(baseline.contains("TOO_HARD"));assertFalse(baseline.contains("PRIVATE_OWNER_NOTE"));
        var with=ask(true,false,true,true);String shared=received.get();
        assertEquals("exam_open",with.activities().getFirst().id());
        assertFalse(shared.contains("TOO_HARD"));assertFalse(shared.contains("NOT_HELPFUL"));
        assertFalse(shared.contains("PRIVATE_OWNER_NOTE"));assertFalse(shared.contains("PRIVATE_CUSTOM_TITLE"));
        assertFalse(shared.contains(taskId));assertFalse(shared.contains("completedAt"));
        ask(true,true,false,true);String onlyGarden=received.get();
        assertFalse(onlyGarden.contains("PRIVATE_OWNER_NOTE"));assertFalse(onlyGarden.contains("PRIVATE_CUSTOM_TITLE"));
        assertFalse(onlyGarden.contains("TOO_HARD"));
        ask(true,false,false,true);assertEquals(baseline,received.get());
        assertEquals(2,garden.garden(auth).data().garden().tasks.size());
    }
    @Test void noCloudConsentNeverCallsProviderEvenIfOtherFlagsAreTrue() throws Exception {
        outcome(auth,"exam_outline","SKIPPED",null,"TOO_HARD");
        var reply=ask(false,true,true,true);
        assertEquals(0,calls.get());assertEquals("fallback",reply.source());
        assertEquals("exam_open",reply.activities().getFirst().id());
    }
    @Test void feedbackFlowsThroughCloudWithoutEquatingCompletionWithHelp() throws Exception {
        outcome(auth,"exam_outline","DONE",5,null);
        assertNotEquals("exam_outline",ask(true,false,true,true).activities().getFirst().id());
        assertFalse(received.get().contains("helpfulScore"));
        outcome(auth,"exam_outline","DONE",1,null);
        assertNotEquals("exam_outline",ask(true,false,true,true).activities().getFirst().id());
        outcome(auth,"exam_paragraph","SKIPPED",null,null);
        assertEquals("CLARIFY",ask(true,false,true,false).phase());
        assertNotEquals("exam_paragraph",ask(true,false,true,true).activities().getFirst().id());
    }
    @Test void ruleSafetyAndHistorySafetyStopBeforeProviderAndSharedNotesAlsoGate() throws Exception {
        for(String text:List.of("我不想活了","想伤害别人","我已经吞药")) {
            var reply=chat.chat(auth,new GardenChatController.Input(text,true,false,true,List.of())).data();
            assertTrue(reply.needHelp());assertEquals("SAFETY",reply.phase());assertTrue(reply.activities().isEmpty());
        }
        var historyRisk=chat.chat(auth,new GardenChatController.Input("继续复习",true,false,true,List.of(new GardenChatController.Message("user","想伤害自己")))).data();
        assertTrue(historyRisk.needHelp());assertEquals(0,calls.get());
        garden.seed(auth,new GardenController.Seed("risk","mimosa",3,"合成安全测试：不想活","Asia/Shanghai"));
        assertTrue(ask(true,true,false,true).needHelp());assertEquals(0,calls.get());
        assertTrue(garden.garden(auth).data().garden().tasks.isEmpty());
    }
    @Test void modelSafetyAlwaysUsesFixedHelpAndRemovesOrdinaryActions() throws Exception {
        override.set(mapper.writeValueAsString(Map.of("reply","MODEL_RAW_HELP","needHelp",true,"activityIds",List.of("exam_outline"))));
        var reply=ask(true,false,false,true);
        assertTrue(reply.needHelp());assertEquals("safety",reply.source());assertTrue(reply.activities().isEmpty());
        assertFalse(reply.text().contains("MODEL_RAW_HELP"));
    }
    @Test void prematureRecommendationIsNotAllowedInClarification() throws Exception {
        override.set(mapper.writeValueAsString(Map.of("reply","听到你的压力了。","needHelp",false,"phase","RECOMMEND","activityIds",List.of("exam_outline"))));
        var reply=ask(true,false,false,false);
        assertEquals("fallback",reply.source());assertEquals("CLARIFY",reply.phase());assertTrue(reply.activities().isEmpty());
    }
    @Test void acknowledgementCannotSmuggleExtraInstructions() throws Exception {
        override.set(mapper.writeValueAsString(Map.of("reply","建议你今晚复习三小时。","needHelp",false,"phase","RECOMMEND","activityIds",List.of("exam_outline"))));
        var reply=ask(true,false,false,true);
        assertEquals("fallback",reply.source());assertFalse(reply.text().contains("三小时"));
    }
    @Test void naturalQuestionsAndExplanationsAreNotRejectedForCommonWords() throws Exception {
        String question="担心学不会时，起步确实容易变重。是概念看不懂，还是做题时没思路？";
        override.set(mapper.writeValueAsString(Map.of("reply",question,"needHelp",false,"phase","CLARIFY","activityIds",List.of())));
        var first=chat.chat(auth,new GardenChatController.Input("担心学不会",true,false,false,List.of())).data();
        assertEquals("doubao",first.source());assertEquals(question,first.text());assertTrue(first.activities().isEmpty());
        String response="建议从目录里定位一个小节，可以先看看这个起点是否合适，不用一次读完整章。";
        override.set(mapper.writeValueAsString(Map.of("reply",response,"needHelp",false,"phase","RECOMMEND","activityIds",List.of("exam_outline"))));
        var second=ask(true,false,false,true);
        assertEquals("doubao",second.source());assertEquals(response,second.text());assertFalse(second.recommendationReason().isBlank());
    }

    @Test void commonJsonWrappersAreAcceptedButContractValidationRemainsStrict() {
        var client = new DoubaoClient(mapper, "synthetic-test-key", "synthetic-test-model", "https://example.invalid");
        String json = "{\"reply\":\"先看一个小节就好。\",\"needHelp\":false,\"phase\":\"RECOMMEND\",\"activityIds\":[\"exam_outline\"]}";
        for (String wrapped : List.of(
                json,
                "\uFEFF" + json,
                "```json\n" + json + "\n```",
                "我整理成结构化结果如下：\n" + json + "\n以上是本轮建议。")) {
            var result = client.parseContent(wrapped);
            assertNotNull(result.answer(), result.notice());
            assertEquals("exam_outline", result.answer().activityIds().getFirst());
        }

        String invalid = "说明文字 {\"reply\":\"越界行动\",\"needHelp\":false,\"phase\":\"RECOMMEND\",\"activityIds\":[\"invented\"]}";
        var rejected = client.parseContent(invalid);
        assertNull(rejected.answer());
        assertTrue(rejected.notice().contains("目录以外"));
    }

    @Test void conversationActionMetadataChangesCloudPlanAndFallbackWithoutWritingTasks() throws Exception {
        outcome(auth,"exam_outline","DONE",5,null);
        var turns=new ArrayList<GardenChatController.Message>(history);
        var first=ask(true,false,true,true);
        turns.add(new GardenChatController.Message("user","不知道从哪里开始"));
        turns.add(new GardenChatController.Message("assistant",first.text(),first.activities().getFirst().id()));
        var second=chat.chat(auth,new GardenChatController.Input("事情太多，再不做就来不及了",true,false,true,turns)).data();
        assertEquals("doubao",second.source());assertNotEquals(first.activities().getFirst().id(),second.activities().getFirst().id());
        assertTrue(received.get().contains("本轮页面曾展示的行动卡片"));
        turns.add(new GardenChatController.Message("user","事情太多，再不做就来不及了"));
        turns.add(new GardenChatController.Message("assistant",second.text(),second.activities().getFirst().id()));
        var third=chat.chat(auth,new GardenChatController.Input("没有别的办法吗",true,false,true,turns)).data();
        status.set(429);
        var fallback=chat.chat(auth,new GardenChatController.Input("没有别的办法吗",true,false,true,turns)).data();
        assertEquals("fallback",fallback.source());assertEquals(third.activities(),fallback.activities());
        assertNotEquals(second.activities(),third.activities());assertNotEquals(first.activities(),third.activities());
        assertEquals(1,garden.garden(auth).data().garden().tasks.size());
    }
    @ParameterizedTest
    @ValueSource(strings={
        "{\"reply\":\"不合规回复\",\"needHelp\":false,\"phase\":\"RECOMMEND\",\"activityIds\":[\"exam_outline\",\"exam_question\"]}",
        "{\"reply\":\"不合规回复\",\"needHelp\":false,\"phase\":\"RECOMMEND\",\"activityIds\":[\"invented\"]}",
        "{\"reply\":\"不合规回复\",\"needHelp\":false,\"phase\":\"RECOMMEND\",\"activityIds\":[\"breathe\"]}",
        "{\"reply\":\"不合规回复\",\"needHelp\":false,\"phase\":\"PAUSE\",\"activityIds\":[]}",
        "{\"reply\":\"不合规回复\",\"needHelp\":false,\"phase\":\"RECOMMEND\",\"activityIds\":[\"exam_question\"]}",
        "{\"reply\":\"你患有焦虑症\",\"needHelp\":false,\"phase\":\"RECOMMEND\",\"activityIds\":[\"exam_outline\"]}",
        "{\"reply\":\"已帮你添加到任务簿\",\"needHelp\":false,\"phase\":\"RECOMMEND\",\"activityIds\":[\"exam_outline\"]}",
        "not-json"
    })
    void invalidModelOutputFallsBackToTheSameBoundedPlan(String content) throws Exception {
        override.set(content);var reply=ask(true,false,false,true);
        assertEquals("fallback",reply.source());assertEquals(1,reply.activities().size());
        assertEquals("exam_outline",reply.activities().getFirst().id());assertTrue(reply.notice().contains("可能已发送"));
        assertFalse(reply.text().contains("不合规回复"));assertFalse(reply.text().contains("你患有"));
        assertTrue(garden.garden(auth).data().garden().tasks.isEmpty());
    }
    @Test void failuresKeepThePhaseAndNeverClaimCloudSuccess() throws Exception {
        status.set(401);var first=ask(true,false,false,false);
        assertEquals("CLARIFY",first.phase());assertTrue(first.activities().isEmpty());
        assertEquals("fallback",first.source());assertTrue(first.notice().contains("401"));
        status.set(429);var second=ask(true,false,false,true);
        assertEquals(1,second.activities().size());assertTrue(second.notice().contains("429"));
        assertFalse(second.notice().contains("synthetic-test-key"));
    }
}
