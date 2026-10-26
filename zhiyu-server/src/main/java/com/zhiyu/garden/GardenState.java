package com.zhiyu.garden;

import java.util.ArrayList;
import java.util.List;

/** One anonymous garden. Access is exclusively through a hashed bearer credential. */
public class GardenState {
    public String policyVersion = "garden-2026-10";
    public String consentedAt;
    public List<Entry> entries = new ArrayList<>();
    public List<Task> tasks = new ArrayList<>();

    public static class Entry {
        public String id;
        public String requestId;
        public String plant;
        public int intensity;
        public String note;
        public String createdAt;
        public String date;
        public String timezone;
        public Thought thought;
        public Reflection reflection;
    }
    public record Thought(String situation, String automaticThought, String evidenceFor, String evidenceAgainst, String balancedThought) {}
    public record Reflection(int intensity, String change, String note, String date) {}
    public static class Task {
        public String id;
        public String entryId;
        public String activityId;
        public String title;
        public String status = "TODO";
        public String createdAt;
        public String completedAt;
        public Integer helpfulScore;
        public String feedback;
    }
}
