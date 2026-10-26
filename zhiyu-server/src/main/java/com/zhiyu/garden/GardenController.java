package com.zhiyu.garden;

import com.zhiyu.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/garden")
public class GardenController {
    private static final Set<String> PLANTS = Set.of("daisy", "sunflower", "mimosa", "moss", "cactus", "dandelion");
    private final GardenRepository repository;
    private final boolean memory;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public GardenController(GardenRepository repository, Environment environment) {
        this(repository, environment.matchesProfiles("memory"), Clock.systemUTC());
    }
    GardenController(GardenRepository repository, boolean memory, Clock clock) {
        this.repository = repository; this.memory = memory; this.clock = clock;
    }
    public record Consent(@AssertTrue boolean accepted) {}
    public record Session(String token, String storageMode) {}
    public record Snapshot(GardenState garden, List<ActivityCatalog.Activity> activities, String storageMode) {}
    public record Seed(@NotBlank @Size(max=80) String requestId, @NotBlank String plant,
                       @NotNull @Min(1) @Max(5) Integer intensity, @Size(max=1000) String note, @NotBlank String timezone) {}
    public record ThoughtInput(@Size(max=1000) String situation, @Size(max=1000) String automaticThought,
            @Size(max=1000) String evidenceFor, @Size(max=1000) String evidenceAgainst, @Size(max=1000) String balancedThought) {}
    public record TaskInput(String entryId, String activityId, @Size(max=120) String title) {}
    public record TaskChange(@NotBlank String status, @Min(1) @Max(5) Integer helpfulScore,
            @jakarta.validation.constraints.Pattern(regexp="TOO_HARD|NOT_HELPFUL") String feedback) {
        public TaskChange(String status, Integer helpfulScore) { this(status, helpfulScore, null); }
    }
    public record Reflect(@NotNull @Min(1) @Max(5) Integer intensity, @NotBlank String change, @Size(max=1000) String note) {}

    @PostMapping("/session")
    public ApiResponse<Session> session(@Valid @RequestBody Consent consent) {
        if (!consent.accepted()) throw bad("请先阅读并同意使用说明");
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        GardenState state = new GardenState(); state.consentedAt = Instant.now(clock).toString();
        repository.create(hash(token), state);
        return ApiResponse.success(new Session(token, mode()));
    }
    @GetMapping
    public ApiResponse<Snapshot> garden(@RequestHeader(value="Authorization", required=false) String auth) {
        GardenState state = repository.read(credential(auth));
        if (state == null) throw unauthorized();
        return ApiResponse.success(new Snapshot(state, ActivityCatalog.ALL, mode()));
    }
    @PostMapping("/entries")
    public ApiResponse<GardenState> seed(@RequestHeader(value="Authorization", required=false) String auth, @Valid @RequestBody Seed input) {
        if (!PLANTS.contains(input.plant())) throw bad("请选择一种情绪植物");
        ZoneId zone;
        try { zone = ZoneId.of(input.timezone()); } catch (DateTimeException error) { throw bad("时区无效"); }
        return change(auth, state -> {
            if (state.entries.stream().anyMatch(e -> e.requestId.equals(input.requestId()))) return;
            if (state.entries.size() >= 1000) throw bad("花园已达到当前版本容量，请先整理记录");
            GardenState.Entry entry = new GardenState.Entry();
            entry.id = UUID.randomUUID().toString(); entry.requestId = input.requestId(); entry.plant = input.plant();
            entry.intensity = input.intensity(); entry.note = clean(input.note()); entry.timezone = zone.getId();
            entry.createdAt = Instant.now(clock).toString(); entry.date = LocalDate.now(clock.withZone(zone)).toString();
            state.entries.add(entry);
        });
    }
    @PutMapping("/entries/{id}/thought")
    public ApiResponse<GardenState> thought(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable String id, @Valid @RequestBody ThoughtInput input) {
        return change(auth, state -> entry(state,id).thought = new GardenState.Thought(clean(input.situation()),clean(input.automaticThought()),clean(input.evidenceFor()),clean(input.evidenceAgainst()),clean(input.balancedThought())));
    }
    @DeleteMapping("/entries/{id}")
    public ApiResponse<GardenState> deleteEntry(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable String id) {
        return change(auth, state -> { entry(state,id); state.entries.removeIf(e -> e.id.equals(id)); state.tasks.removeIf(t -> id.equals(t.entryId)); });
    }
    @PostMapping("/tasks")
    public ApiResponse<GardenState> task(@RequestHeader(value="Authorization", required=false) String auth, @Valid @RequestBody TaskInput input) {
        return change(auth, state -> {
            if (input.entryId() != null) entry(state, input.entryId());
            String title = clean(input.title());
            if (input.activityId() != null) {
                var activity = ActivityCatalog.ALL.stream().filter(a -> a.id().equals(input.activityId())).findFirst().orElseThrow(() -> bad("行动不存在"));
                title = activity.title();
                if (state.tasks.stream().anyMatch(t -> "TODO".equals(t.status) && Objects.equals(t.entryId,input.entryId()) && Objects.equals(t.activityId,input.activityId()))) return;
            }
            if (title.isBlank()) throw bad("请填写行动名称");
            if (state.tasks.size() >= 2000) throw bad("已达到当前任务容量");
            var task = new GardenState.Task(); task.id = UUID.randomUUID().toString(); task.entryId = input.entryId();
            task.activityId = input.activityId(); task.title = title; task.createdAt = Instant.now(clock).toString(); state.tasks.add(task);
        });
    }
    @PutMapping("/tasks/{id}")
    public ApiResponse<GardenState> updateTask(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable String id, @Valid @RequestBody TaskChange input) {
        if (!Set.of("DONE", "SKIPPED").contains(input.status())) throw bad("任务状态无效");
        return change(auth, state -> {
            var task = state.tasks.stream().filter(t -> t.id.equals(id)).findFirst().orElseThrow(GardenController::missing);
            if (!task.status.equals("TODO")) {
                if (task.status.equals(input.status())) return;
                throw bad("已结束的行动不能修改状态");
            }
            task.status = input.status(); task.helpfulScore = input.helpfulScore(); task.feedback = input.feedback();
            task.completedAt = Instant.now(clock).toString();
        });
    }
    @PostMapping("/entries/{id}/reflection")
    public ApiResponse<GardenState> reflect(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable String id, @Valid @RequestBody Reflect input) {
        if (!Set.of("BETTER", "SAME", "HARDER", "UNSURE").contains(input.change())) throw bad("复盘选项无效");
        return change(auth, state -> {
            var entry = entry(state,id);
            LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(entry.timezone)));
            if (!today.isAfter(LocalDate.parse(entry.date))) throw bad("可以明天再来回看这株植物");
            entry.reflection = new GardenState.Reflection(input.intensity(), input.change(), clean(input.note()), today.toString());
        });
    }
    @DeleteMapping
    public ApiResponse<String> erase(@RequestHeader(value="Authorization", required=false) String auth) {
        String key = credential(auth);
        if (repository.read(key) == null) throw unauthorized();
        repository.delete(key); return ApiResponse.success("已删除花园和会话");
    }
    private ApiResponse<GardenState> change(String auth, Consumer<GardenState> operation) {
        GardenState value = repository.update(credential(auth), operation);
        if (value == null) throw unauthorized();
        return ApiResponse.success(value);
    }
    private String mode() { return memory ? "memory" : "postgres"; }
    private static GardenState.Entry entry(GardenState state, String id) {
        return state.entries.stream().filter(e -> e.id.equals(id)).findFirst().orElseThrow(GardenController::missing);
    }
    private static String clean(String text) { return text == null ? "" : text.trim(); }
    static String credential(String auth) {
        if (auth == null || !auth.matches("Bearer [A-Za-z0-9_-]{43}")) throw unauthorized();
        return hash(auth.substring(7));
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private static ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"花园会话已失效，请重新进入。内存模式重启后数据会清空。"); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"没有找到这株植物或行动"); }
}
