package com.zhiyu.mvp;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

@Component
@Profile("!memory")
public class JdbcMvpStore implements MvpStorePort {
    private final JdbcTemplate jdbc;

    public JdbcMvpStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public MvpStore.User createAnonymousUser() {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO users (nickname, anonymous) VALUES (?, TRUE)",
                    new String[] {"id"});
            statement.setString(1, "同学");
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("无法创建匿名用户");
        long userId = key.longValue();
        jdbc.update("INSERT INTO privacy_consents (user_id, policy_version) VALUES (?, ?)", userId, "2026-01");
        return new MvpStore.User(userId, "同学", true, "2026-01");
    }

    public MvpStore.Checkin upsertCheckin(long userId, MvpStore.CheckinRequest request) {
        MvpStore.Checkin current = jdbc.query(
                "SELECT * FROM checkins WHERE user_id = ? AND record_date = ?",
                this::mapCheckin, userId, request.recordDate()).stream().findFirst().orElse(null);
        if (current == null) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement(
                        "INSERT INTO checkins (user_id, record_date, mood_score, stress_score, sleep_hours, sleep_quality, energy_score, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        new String[] {"id"});
                statement.setLong(1, userId);
                statement.setObject(2, request.recordDate());
                statement.setInt(3, request.moodScore());
                statement.setInt(4, request.stressScore());
                statement.setObject(5, request.sleepHours());
                statement.setObject(6, request.sleepQuality());
                statement.setObject(7, request.energyScore());
                statement.setString(8, request.note());
                return statement;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key == null) throw new IllegalStateException("无法保存情绪记录");
            return findCheckin(key.longValue());
        }
        jdbc.update("UPDATE checkins SET mood_score = ?, stress_score = ?, sleep_hours = ?, sleep_quality = ?, energy_score = ?, note = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                request.moodScore(), request.stressScore(), request.sleepHours(), request.sleepQuality(), request.energyScore(), request.note(), current.id());
        return findCheckin(current.id());
    }

    public List<MvpStore.Checkin> listCheckins(long userId, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT * FROM checkins WHERE user_id = ? AND record_date BETWEEN ? AND ? ORDER BY record_date DESC",
                this::mapCheckin, userId, from, to);
    }

    public MvpStore.Task createTask(long userId, MvpStore.TaskRequest request) {
        String title = request.title() == null || request.title().isBlank() ? "3 分钟呼吸练习" : request.title().trim();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO tasks (user_id, title, planned_at, source) VALUES (?, ?, ?, 'USER')",
                    new String[] {"id"});
            statement.setLong(1, userId);
            statement.setString(2, title);
            statement.setObject(3, request.plannedAt());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("无法创建任务");
        return findTask(key.longValue());
    }

    public List<MvpStore.Task> listTasks(long userId, LocalDate date) {
        return jdbc.query("SELECT * FROM tasks WHERE user_id = ? AND (planned_at IS NULL OR planned_at::date = ?) ORDER BY planned_at NULLS LAST, id",
                this::mapTask, userId, date);
    }

    public MvpStore.Task updateTask(long userId, long id, String status) {
        int changed = jdbc.update("UPDATE tasks SET status = ?, completed_at = CASE WHEN ? = 'DONE' THEN CURRENT_TIMESTAMP ELSE NULL END WHERE id = ? AND user_id = ?",
                status, status, id, userId);
        return changed == 0 ? null : findTask(id);
    }

    public MvpStore.TrendSummary summary(long userId, LocalDate from, LocalDate to) {
        List<MvpStore.Checkin> checkins = listCheckins(userId, from, to);
        BigDecimal averageMood = average(checkins.stream().map(item -> BigDecimal.valueOf(item.moodScore())).toList());
        BigDecimal averageStress = average(checkins.stream().map(item -> BigDecimal.valueOf(item.stressScore())).toList());
        BigDecimal averageSleep = average(checkins.stream().map(MvpStore.Checkin::sleepHours).filter(item -> item != null).toList());
        Integer completed = jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id = ? AND status = 'DONE' AND (planned_at IS NULL OR planned_at::date BETWEEN ? AND ?)", Integer.class, userId, from, to);
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id = ? AND (planned_at IS NULL OR planned_at::date BETWEEN ? AND ?)", Integer.class, userId, from, to);
        return new MvpStore.TrendSummary(from, to, checkins.size(), averageMood, averageStress, averageSleep, streak(checkins, to), completed == null ? 0 : completed, total == null ? 0 : total);
    }

    private MvpStore.Checkin findCheckin(long id) {
        return jdbc.query("SELECT * FROM checkins WHERE id = ?", this::mapCheckin, id).stream().findFirst().orElseThrow();
    }

    private MvpStore.Task findTask(long id) {
        return jdbc.query("SELECT * FROM tasks WHERE id = ?", this::mapTask, id).stream().findFirst().orElseThrow();
    }

    private MvpStore.Checkin mapCheckin(ResultSet rs, int row) throws SQLException {
        return new MvpStore.Checkin(rs.getLong("id"), rs.getLong("user_id"), rs.getObject("record_date", LocalDate.class),
                rs.getInt("mood_score"), rs.getInt("stress_score"), rs.getBigDecimal("sleep_hours"), (Integer) rs.getObject("sleep_quality"),
                (Integer) rs.getObject("energy_score"), rs.getString("note"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private MvpStore.Task mapTask(ResultSet rs, int row) throws SQLException {
        return new MvpStore.Task(rs.getLong("id"), rs.getLong("user_id"), rs.getString("title"), rs.getObject("planned_at", OffsetDateTime.class),
                rs.getString("status"), rs.getString("source"), (Integer) rs.getObject("helpful_score"), rs.getObject("completed_at", OffsetDateTime.class));
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(values.size()), 2, java.math.RoundingMode.HALF_UP);
    }

    private static int streak(List<MvpStore.Checkin> values, LocalDate end) {
        List<LocalDate> dates = values.stream().map(MvpStore.Checkin::recordDate).sorted(Comparator.reverseOrder()).toList();
        int result = 0;
        LocalDate expected = end;
        for (LocalDate date : dates) {
            if (!date.equals(expected)) break;
            result++;
            expected = expected.minusDays(1);
        }
        return result;
    }
}
