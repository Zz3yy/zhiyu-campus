package com.zhiyu.garden;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StudyAgentPolicyTest {
    private static final List<GardenChatController.Message> HISTORY=List.of(new GardenChatController.Message("user","合成测试：复习资料太多"));
    private StudyAgentPolicy.Plan plan(String text, String id, String status, Integer score, String feedback) {
        return StudyAgentPolicy.decide(text,HISTORY,List.of(new StudyAgentPolicy.Feedback(id,status,score,feedback)));
    }
    @Test void firstTurnClarifiesAndSecondTurnRecommendsExactlyOneAction() {
        var first=StudyAgentPolicy.decide("我要复习",List.of(),List.of());
        assertEquals("CLARIFY",first.phase());assertTrue(first.activities().isEmpty());
        var next=StudyAgentPolicy.decide("资料太多",HISTORY,List.of());
        assertEquals("RECOMMEND",next.phase());assertEquals(1,next.activities().size());
        assertEquals("exam_outline",next.action().id());assertFalse(next.reason().isBlank());
        assertFalse(next.guidance().contains(next.action().instructions()));
    }
    @Test void difficultActionsStepDownAndTheSmallestActionDoesNotLoop() {
        assertEquals("exam_outline",plan("继续","exam_paragraph","SKIPPED",null,"TOO_HARD").action().id());
        assertEquals("exam_open",plan("继续","exam_outline","SKIPPED",null,"TOO_HARD").action().id());
        var smallest=plan("继续","exam_open","SKIPPED",null,"TOO_HARD");
        assertNull(smallest.action());assertEquals("CLARIFY",smallest.phase());
    }
    @Test void unhelpfulActionsAndLowScoresAreNotRepeated() {
        for(String id:List.of("exam_open","exam_outline","exam_paragraph","exam_question")) {
            assertNotEquals(id,plan("继续",id,"SKIPPED",null,"NOT_HELPFUL").action().id());
            assertNotEquals(id,plan("继续",id,"DONE",1,null).action().id());
        }
    }
    @Test void exhaustedCatalogAsksRatherThanRecyclingUnhelpfulActions() {
        var feedback=ActivityCatalog.STUDY.stream().map(a->new StudyAgentPolicy.Feedback(a.id(),"SKIPPED",null,"NOT_HELPFUL")).toList();
        var result=StudyAgentPolicy.decide("继续",HISTORY,feedback);
        assertEquals("CLARIFY",result.phase());assertTrue(result.activities().isEmpty());
    }
    @Test void completionAndScoresDoNotAutomaticallyIncreaseEffortOrClaimBenefit() {
        for(Integer score:new Integer[]{null,3,4,5}) {
            var result=plan("继续","exam_open","DONE",score,null);
            assertEquals("exam_open",result.action().id());
            assertFalse(result.guidance().contains("治愈"));
            if(score==null) assertTrue(result.reason().contains("不推断效果"));
        }
    }
    @Test void ordinarySkipAsksWithoutInferringCauseAndOffersAnAlternative() {
        var feedback=List.of(new StudyAgentPolicy.Feedback("exam_outline","SKIPPED",null,null));
        assertTrue(StudyAgentPolicy.decide("复习",List.of(),feedback).guidance().contains("不会猜测原因"));
        var result=StudyAgentPolicy.decide("换个起点",HISTORY,feedback);
        assertNotEquals("exam_outline",result.action().id());
        assertFalse(result.guidance().contains("懒惰"));
    }
    @Test void refusalPausesAndLatestBlockerAndTimeBudgetWin() {
        assertEquals("PAUSE",StudyAgentPolicy.decide("想先休息",HISTORY,List.of()).phase());
        assertEquals("exam_question",StudyAgentPolicy.decide("主要担心学不会",HISTORY,List.of()).action().id());
        assertEquals(1,StudyAgentPolicy.decide("担心学不会，只有1分钟",HISTORY,List.of()).action().minutes());
        var history=List.of(new GardenChatController.Message("user","我只有一分钟"));
        assertEquals(1,StudyAgentPolicy.decide("担心学不会",history,List.of()).action().minutes());
    }
    @Test void feedbackIsBoundedRecentTypedAndIgnoresCustomAndBrokenRecords() {
        Instant now=Instant.parse("2026-10-23T00:00:00Z"); var garden=new GardenState();
        for(int i=0;i<12;i++) { var t=new GardenState.Task();t.activityId="exam_open";t.status="DONE";t.completedAt=now.minusSeconds(i).toString();garden.tasks.add(t); }
        var custom=new GardenState.Task();custom.status="DONE";custom.completedAt=now.toString();garden.tasks.add(custom);
        var old=new GardenState.Task();old.activityId="exam_question";old.status="DONE";old.completedAt=now.minus(Duration.ofDays(8)).toString();garden.tasks.add(old);
        var malformed=new GardenState.Task();malformed.activityId="exam_outline";malformed.status="DONE";garden.tasks.add(malformed);
        var future=new GardenState.Task();future.activityId="exam_question";future.status="DONE";future.completedAt=now.plusSeconds(10).toString();garden.tasks.add(future);
        var feedback=StudyAgentPolicy.recentFeedback(garden,now);
        assertEquals(8,feedback.size());assertTrue(feedback.stream().allMatch(f->f.activityId().equals("exam_open")));
    }
    @Test void newerHelpfulOutcomeSupersedesOldNegativeFeedback() {
        var feedback=List.of(new StudyAgentPolicy.Feedback("exam_outline","DONE",5,null),new StudyAgentPolicy.Feedback("exam_outline","SKIPPED",null,"NOT_HELPFUL"));
        assertEquals("exam_outline",StudyAgentPolicy.decide("还是上次，再做一次",HISTORY,feedback).action().id());
    }
    @Test void screenshotConversationChangesWithCurrentIntentDespitePastHighScore() {
        var feedback=List.of(new StudyAgentPolicy.Feedback("exam_outline","DONE",5,null));
        var history=new ArrayList<GardenChatController.Message>();
        var first=StudyAgentPolicy.decide("担心学不会",history,feedback);
        assertEquals("CLARIFY",first.phase());assertFalse(first.guidance().contains("你更像"));
        history.add(new GardenChatController.Message("user","担心学不会"));
        history.add(new GardenChatController.Message("assistant",first.guidance()));
        var second=StudyAgentPolicy.decide("不知道从哪里开始",history,feedback);
        assertNotEquals("exam_outline",second.action().id());
        history.add(new GardenChatController.Message("user","不知道从哪里开始"));
        history.add(new GardenChatController.Message("assistant",second.guidance(),second.action().id()));
        var urgent=StudyAgentPolicy.decide("我的事情太多了 再不做下去的话就来不及了",history,feedback);
        assertNotEquals(second.action().id(),urgent.action().id());assertTrue(urgent.guidance().contains("来不及"));
        history.add(new GardenChatController.Message("user","事情太多，来不及"));
        history.add(new GardenChatController.Message("assistant",urgent.guidance(),urgent.action().id()));
        var other=StudyAgentPolicy.decide("没有别的办法吗",history,feedback);
        assertNotEquals(urgent.action().id(),other.action().id());assertNotEquals(second.action().id(),other.action().id());
        assertTrue(other.guidance().contains("换"));
    }
    @Test void alternativesEventuallyClarifyInsteadOfCyclingAndRespectTime() {
        var history=new ArrayList<GardenChatController.Message>(HISTORY);
        var ids=new HashSet<String>();
        for(int i=0;i<4;i++) {
            var result=StudyAgentPolicy.decide("没有别的办法吗",history,List.of());
            assertEquals("RECOMMEND",result.phase());assertTrue(ids.add(result.action().id()));
            history.add(new GardenChatController.Message("user","没有别的办法吗"));
            history.add(new GardenChatController.Message("assistant",result.guidance(),result.action().id()));
        }
        assertTrue(StudyAgentPolicy.decide("没有别的办法吗",history,List.of()).activities().isEmpty());
        var limited=List.of(new GardenChatController.Message("user","只有一分钟"),new GardenChatController.Message("assistant","起点","exam_outline"));
        assertEquals("exam_open",StudyAgentPolicy.decide("换个办法",limited,List.of()).action().id());
    }
    @Test void currentFearOutranksOldSuccessAndRejectedActionStaysExcluded() {
        var feedback=List.of(new StudyAgentPolicy.Feedback("exam_outline","DONE",5,null));
        assertEquals("exam_question",StudyAgentPolicy.decide("担心学不会",HISTORY,feedback).action().id());
        var history=List.of(new GardenChatController.Message("user","复习"),new GardenChatController.Message("assistant","方案","exam_question"),
            new GardenChatController.Message("user","换一个"),new GardenChatController.Message("assistant","新方案","exam_paragraph"));
        assertNotEquals("exam_question",StudyAgentPolicy.decide("担心学不会",history,feedback).action().id());
    }
}
