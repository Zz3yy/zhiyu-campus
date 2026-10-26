package com.zhiyu.garden;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class GardenControllerTest {
    private final MemoryGardenRepository repository = new MemoryGardenRepository(new ObjectMapper().findAndRegisterModules());
    private GardenController at(String instant) { return new GardenController(repository, true, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)); }
    private final GardenController api = at("2026-10-15T10:00:00Z");
    private String session() { return "Bearer " + api.session(new GardenController.Consent(true)).data().token(); }
    private GardenController.Seed seed(String request) { return new GardenController.Seed(request,"mimosa",4,"合成测试：考试压力","Asia/Shanghai"); }

    @Test void independentSeedsAndRetryArePersistedWithoutOverwriting() {
        String auth = session(); api.seed(auth, seed("one")); api.seed(auth,seed("two")); api.seed(auth,seed("one"));
        var garden = api.garden(auth).data().garden();
        assertEquals(2,garden.entries.size()); assertEquals("2026-10-15", garden.entries.getFirst().date);
        garden.entries.clear(); assertEquals(2,api.garden(auth).data().garden().entries.size());
    }
    @Test void anotherSessionCannotMutateOrReadAnEntry() {
        String a=session(), b=session(); String id=api.seed(a,seed("one")).data().entries.getFirst().id;
        assertTrue(api.garden(b).data().garden().entries.isEmpty());
        var error=assertThrows(ResponseStatusException.class,()->api.task(b,new GardenController.TaskInput(id,"breathe",null)));
        assertEquals(404,error.getStatusCode().value());
        assertThrows(ResponseStatusException.class,()->api.thought(b,id,new GardenController.ThoughtInput("","","","","")));
        assertThrows(ResponseStatusException.class,()->api.deleteEntry(b,id));
    }
    @Test void addingAndCompletingActivityTwiceCannotDuplicateGrowth() {
        String auth=session(); String entry=api.seed(auth,seed("one")).data().entries.getFirst().id;
        var input=new GardenController.TaskInput(entry,"breathe",null);
        api.task(auth,input); var state=api.task(auth,input).data(); assertEquals(1,state.tasks.size());
        String id=state.tasks.getFirst().id;
        api.updateTask(auth,id,new GardenController.TaskChange("DONE",4));
        String completed=api.garden(auth).data().garden().tasks.getFirst().completedAt;
        at("2026-10-16T10:00:00Z").updateTask(auth,id,new GardenController.TaskChange("DONE",5));
        var task=api.garden(auth).data().garden().tasks.getFirst();
        assertEquals(completed,task.completedAt); assertEquals(4,task.helpfulScore);
        assertThrows(ResponseStatusException.class,()->api.updateTask(auth,id,new GardenController.TaskChange("SKIPPED",null)));
    }
    @Test void reflectionUsesEntryTimezoneAtMidnightAndAllowsHarderFeelings() {
        var before=at("2026-10-15T15:59:00Z"); String auth=session();
        String id=before.seed(auth,seed("one")).data().entries.getFirst().id;
        var reflection=new GardenController.Reflect(5,"HARDER","还是有点难受");
        assertThrows(ResponseStatusException.class,()->before.reflect(auth,id,reflection));
        var state=at("2026-10-15T16:01:00Z").reflect(auth,id,reflection).data();
        assertEquals("2026-10-16",state.entries.getFirst().reflection.date());
        assertEquals(5,state.entries.getFirst().reflection.intensity());
    }
    @Test void deletionCascadesAndEntireDeletionRevokesCredential() {
        String auth=session(); String id=api.seed(auth,seed("one")).data().entries.getFirst().id;
        api.task(auth,new GardenController.TaskInput(id,"breathe",null));
        api.thought(auth,id,new GardenController.ThoughtInput("测试","担心失败","","","先做一小步"));
        var state=api.deleteEntry(auth,id).data(); assertTrue(state.entries.isEmpty());assertTrue(state.tasks.isEmpty());
        api.erase(auth);
        assertEquals(401,assertThrows(ResponseStatusException.class,()->api.garden(auth)).getStatusCode().value());
    }
    @Test void invalidConsentAndUnknownActivityCannotCreateData() {
        assertThrows(ResponseStatusException.class,()->api.session(new GardenController.Consent(false)));
        String auth=session();
        assertThrows(ResponseStatusException.class,()->api.task(auth,new GardenController.TaskInput(null,"made-up","title")));
        assertTrue(api.garden(auth).data().garden().tasks.isEmpty());
    }
    @Test void endedActionsAllowExplicitRepeatButPendingAndCompletionRetriesStayIdempotent() {
        String auth=session(); String entry=api.seed(auth,seed("repeat")).data().entries.getFirst().id;
        var input=new GardenController.TaskInput(entry,"exam_outline",null);
        String first=api.task(auth,input).data().tasks.getFirst().id;
        api.updateTask(auth,first,new GardenController.TaskChange("DONE",4));
        String second=api.task(auth,input).data().tasks.getLast().id;
        assertNotEquals(first,second); assertEquals(2,api.task(auth,input).data().tasks.size());
        api.updateTask(auth,second,new GardenController.TaskChange("SKIPPED",null));
        var state=api.task(auth,input).data();
        assertEquals(3,state.tasks.size());
        assertEquals(1,state.tasks.stream().filter(t->t.status.equals("DONE")).count());
        assertEquals(1,api.task(auth,input).data().tasks.stream().filter(t->t.status.equals("TODO")).count());
    }
}
