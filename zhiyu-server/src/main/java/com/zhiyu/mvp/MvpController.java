package com.zhiyu.mvp;

import com.zhiyu.common.ApiResponse;
import com.zhiyu.common.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="zhiyu.legacy-api.enabled", havingValue="true")
public class MvpController {
    private final MvpStorePort store;

    public MvpController(MvpStorePort store) {
        this.store = store;
    }

    @PostMapping("/auth/anonymous")
    public ApiResponse<MvpStore.User> anonymous() {
        return ApiResponse.success(store.createAnonymousUser());
    }

    @PostMapping("/checkins")
    public ApiResponse<MvpStore.Checkin> saveCheckin(@RequestHeader(value = "X-User-Id", required = false) String userId,
            @Valid @RequestBody CheckinPayload payload) {
        return ApiResponse.success(store.upsertCheckin(UserContext.requireUserId(userId), payload.toRequest()));
    }

    @GetMapping("/checkins")
    public ApiResponse<List<MvpStore.Checkin>> checkins(@RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        return ApiResponse.success(store.listCheckins(UserContext.requireUserId(userId), start, end));
    }

    @PostMapping("/tasks")
    public ApiResponse<MvpStore.Task> createTask(@RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody MvpStore.TaskRequest request) {
        return ApiResponse.success(store.createTask(UserContext.requireUserId(userId), request));
    }

    @GetMapping("/tasks")
    public ApiResponse<List<MvpStore.Task>> tasks(@RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(store.listTasks(UserContext.requireUserId(userId), date == null ? LocalDate.now() : date));
    }

    @PutMapping("/tasks/{id}/complete")
    public ApiResponse<MvpStore.Task> complete(@RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable long id) {
        return updateTask(userId, id, "DONE");
    }

    @PutMapping("/tasks/{id}/skip")
    public ApiResponse<MvpStore.Task> skip(@RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable long id) {
        return updateTask(userId, id, "SKIPPED");
    }

    @GetMapping("/trends/summary")
    public ApiResponse<MvpStore.TrendSummary> summary(@RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(6) : from;
        return ApiResponse.success(store.summary(UserContext.requireUserId(userId), start, end));
    }

    private ApiResponse<MvpStore.Task> updateTask(String rawUserId, long id, String status) {
        MvpStore.Task task = store.updateTask(UserContext.requireUserId(rawUserId), id, status);
        if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在");
        return ApiResponse.success(task);
    }

    public record CheckinPayload(@NotNull LocalDate recordDate, @Min(1) @Max(5) int moodScore,
            @Min(1) @Max(5) int stressScore, BigDecimal sleepHours, @Min(1) @Max(5) Integer sleepQuality,
            @Min(1) @Max(5) Integer energyScore, String note) {
        MvpStore.CheckinRequest toRequest() {
            return new MvpStore.CheckinRequest(recordDate, moodScore, stressScore, sleepHours, sleepQuality, energyScore, note);
        }
    }
}
