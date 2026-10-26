package com.zhiyu.mvp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

@Component
@Profile("memory")
public class MvpStore implements MvpStorePort {
    private final AtomicLong userSequence = new AtomicLong();
    private final AtomicLong checkinSequence = new AtomicLong();
    private final AtomicLong taskSequence = new AtomicLong();
    private final Map<Long, Checkin> checkins = new ConcurrentHashMap<>();
    private final Map<Long, Task> tasks = new ConcurrentHashMap<>();

    public User createAnonymousUser() {
        long id = userSequence.incrementAndGet();
        return new User(id, "同学", true, "2026-01");
    }

    public Checkin upsertCheckin(long userId, CheckinRequest request) {
        Checkin current = checkins.values().stream()
                .filter(item -> item.userId() == userId && item.recordDate().equals(request.recordDate()))
                .findFirst().orElse(null);
        long id = current == null ? checkinSequence.incrementAndGet() : current.id();
        Checkin value = new Checkin(id, userId, request.recordDate(), request.moodScore(), request.stressScore(),
                request.sleepHours(), request.sleepQuality(), request.energyScore(), request.note(),
                current == null ? OffsetDateTime.now() : current.createdAt(), OffsetDateTime.now());
        checkins.put(id, value);
        return value;
    }

    public List<Checkin> listCheckins(long userId, LocalDate from, LocalDate to) {
        return checkins.values().stream()
                .filter(item -> item.userId() == userId && !item.recordDate().isBefore(from) && !item.recordDate().isAfter(to))
                .sorted(Comparator.comparing(Checkin::recordDate).reversed()).toList();
    }

    public Task createTask(long userId, TaskRequest request) {
        String title = request.title() == null || request.title().isBlank() ? "3 分钟呼吸练习" : request.title();
        Task value = new Task(taskSequence.incrementAndGet(), userId, title, request.plannedAt(), "TODO", "USER", null, null);
        tasks.put(value.id(), value);
        return value;
    }

    public List<Task> listTasks(long userId, LocalDate date) {
        return tasks.values().stream()
                .filter(item -> item.userId() == userId && (item.plannedAt() == null || item.plannedAt().toLocalDate().equals(date)))
                .sorted(Comparator.comparing(Task::plannedAt, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }

    public Task updateTask(long userId, long id, String status) {
        Task current = tasks.get(id);
        if (current == null || current.userId() != userId) {
            return null;
        }
        Task value = new Task(current.id(), current.userId(), current.title(), current.plannedAt(), status,
                current.source(), current.helpfulScore(), "DONE".equals(status) ? OffsetDateTime.now() : null);
        tasks.put(id, value);
        return value;
    }

    public TrendSummary summary(long userId, LocalDate from, LocalDate to) {
        List<Checkin> values = listCheckins(userId, from, to);
        BigDecimal mood = average(values.stream().map(item -> BigDecimal.valueOf(item.moodScore())).toList());
        BigDecimal stress = average(values.stream().map(item -> BigDecimal.valueOf(item.stressScore())).toList());
        BigDecimal sleep = average(values.stream().map(Checkin::sleepHours).filter(item -> item != null).toList());
        List<Task> userTasks = listTasks(userId, from);
        int completed = (int) tasks.values().stream().filter(item -> item.userId() == userId && "DONE".equals(item.status()) && inSummaryWindow(item, from, to)).count();
        int total = (int) tasks.values().stream().filter(item -> item.userId() == userId && inSummaryWindow(item, from, to)).count();
        return new TrendSummary(from, to, values.size(), mood, stress, sleep, streak(values, to), completed, total);
    }

    private static boolean inSummaryWindow(Task task, LocalDate from, LocalDate to) {
        return task.plannedAt() == null || (!task.plannedAt().toLocalDate().isBefore(from) && !task.plannedAt().toLocalDate().isAfter(to));
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(values.size()), 2, java.math.RoundingMode.HALF_UP);
    }

    private static int streak(List<Checkin> values, LocalDate end) {
        List<LocalDate> dates = values.stream().map(Checkin::recordDate).sorted(Comparator.reverseOrder()).toList();
        int result = 0;
        LocalDate expected = end;
        for (LocalDate date : dates) {
            if (!date.equals(expected)) break;
            result++;
            expected = expected.minusDays(1);
        }
        return result;
    }

    public record User(long userId, String nickname, boolean anonymous, String policyVersion) {}

    public record CheckinRequest(LocalDate recordDate, int moodScore, int stressScore, BigDecimal sleepHours,
            Integer sleepQuality, Integer energyScore, String note) {}

    public record Checkin(long id, long userId, LocalDate recordDate, int moodScore, int stressScore,
            BigDecimal sleepHours, Integer sleepQuality, Integer energyScore, String note,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    public record TaskRequest(String title, OffsetDateTime plannedAt) {}

    public record Task(long id, long userId, String title, OffsetDateTime plannedAt, String status, String source,
            Integer helpfulScore, OffsetDateTime completedAt) {}

    public record TrendSummary(LocalDate from, LocalDate to, int checkinCount, BigDecimal averageMood,
            BigDecimal averageStress, BigDecimal averageSleepHours, int streakDays, int completedTaskCount,
            int totalTaskCount) {}
}
