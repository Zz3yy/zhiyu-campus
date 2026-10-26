package com.zhiyu.mvp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class MvpStoreTest {
    @Test
    void upsertCheckinKeepsOneRecordPerDay() {
        MvpStore store = new MvpStore();
        MvpStore.User user = store.createAnonymousUser();
        LocalDate today = LocalDate.now();

        MvpStore.Checkin first = store.upsertCheckin(user.userId(), new MvpStore.CheckinRequest(today, 2, 4, null, null, null, "第一次"));
        MvpStore.Checkin second = store.upsertCheckin(user.userId(), new MvpStore.CheckinRequest(today, 4, 2, null, null, null, "更新"));

        assertEquals(first.id(), second.id());
        assertEquals(1, store.listCheckins(user.userId(), today, today).size());
        assertEquals(4, second.moodScore());
        assertEquals("更新", second.note());
    }

    @Test
    void summaryCountsUnscheduledTasksAsCurrentWindowTasks() {
        MvpStore store = new MvpStore();
        MvpStore.User user = store.createAnonymousUser();
        LocalDate today = LocalDate.now();

        MvpStore.Task task = store.createTask(user.userId(), new MvpStore.TaskRequest("三分钟呼吸练习", null));
        assertNotNull(store.updateTask(user.userId(), task.id(), "DONE"));

        MvpStore.TrendSummary summary = store.summary(user.userId(), today.minusDays(6), today);

        assertEquals(1, summary.completedTaskCount());
        assertEquals(1, summary.totalTaskCount());
    }
}
