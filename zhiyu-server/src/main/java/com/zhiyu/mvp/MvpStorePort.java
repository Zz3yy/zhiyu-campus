package com.zhiyu.mvp;

import java.time.LocalDate;
import java.util.List;

public interface MvpStorePort {
    MvpStore.User createAnonymousUser();

    MvpStore.Checkin upsertCheckin(long userId, MvpStore.CheckinRequest request);

    List<MvpStore.Checkin> listCheckins(long userId, LocalDate from, LocalDate to);

    MvpStore.Task createTask(long userId, MvpStore.TaskRequest request);

    List<MvpStore.Task> listTasks(long userId, LocalDate date);

    MvpStore.Task updateTask(long userId, long id, String status);

    MvpStore.TrendSummary summary(long userId, LocalDate from, LocalDate to);
}
