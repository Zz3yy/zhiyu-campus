package com.zhiyu.garden;

import java.time.*;
import java.util.*;

/** Bounded, explainable rules; not a learned profile or a clinical assessment. */
final class StudyAgentPolicy {
    private StudyAgentPolicy() {}
    private static final List<String> EFFORT = List.of("exam_open", "exam_outline", "exam_paragraph", "exam_question");
    record Feedback(String activityId, String status, Integer helpfulScore, String feedback) {}
    record Plan(String phase, ActivityCatalog.Activity action, String guidance, String reason) {
        Plan(String phase, ActivityCatalog.Activity action, String guidance) { this(phase, action, guidance, ""); }
        List<ActivityCatalog.Activity> activities() { return action == null ? List.of() : List.of(action); }
        List<String> activityIds() { return action == null ? List.of() : List.of(action.id()); }
    }

    // Only catalog study actions, within seven rolling days; no task titles, notes or identity.
    static List<Feedback> recentFeedback(GardenState garden, Instant now) {
        record Ended(GardenState.Task task, Instant time, int index) {}
        List<Ended> ended = new ArrayList<>();
        for (int i = 0; i < garden.tasks.size(); i++) {
            var task = garden.tasks.get(i);
            if (task.activityId == null || !EFFORT.contains(task.activityId) || !("DONE".equals(task.status) || "SKIPPED".equals(task.status))) continue;
            try {
                Instant time = Instant.parse(task.completedAt);
                if (!time.isBefore(now.minus(Duration.ofDays(7))) && !time.isAfter(now)) ended.add(new Ended(task, time, i));
            } catch (DateTimeException | NullPointerException ignored) { /* Older incomplete records are not evidence. */ }
        }
        return ended.stream().sorted(Comparator.comparing(Ended::time).thenComparingInt(Ended::index).reversed()).limit(8)
            .map(e -> new Feedback(e.task.activityId, e.task.status,
                "DONE".equals(e.task.status) && e.task.helpfulScore != null && e.task.helpfulScore >= 1 && e.task.helpfulScore <= 5 ? e.task.helpfulScore : null,
                Set.of("TOO_HARD", "NOT_HELPFUL").contains(Objects.toString(e.task.feedback, "")) ? e.task.feedback : null)).toList();
    }

    static Plan decide(String text, List<GardenChatController.Message> history, List<Feedback> feedback) {
        if (text.matches("(?s).*(先不安排|不想做任务|不要推荐|不需要建议|不想复习|想先休息|想休息|暂时不做|先不做|不想做了).*"))
            return new Plan("PAUSE", null, "可以，先不安排任务。等你想继续时再说，不会自动添加任务。");
        Feedback latest = feedback.isEmpty() ? null : feedback.getFirst();
        String currentBlocker = blocker(text);
        String contextBlocker = currentBlocker;
        if (contextBlocker.isEmpty()) {
            for (int i = history.size() - 1; i >= 0; i--) {
                var message = history.get(i);
                if (message.role().equals("user") && !blocker(message.text()).isEmpty()) {
                    contextBlocker = blocker(message.text()); break;
                }
            }
        }
        boolean change = wantsAlternative(text);
        boolean repeat = !change && text.matches("(?s).*(再做一次|再来一次|还是上次|继续上次|就做刚才|重复刚才).*");
        List<String> shown = shownActions(history);
        String previous = shown.isEmpty() ? null : shown.getLast();
        boolean clarified = history.stream().anyMatch(message -> message.role().equals("user"));
        if (!clarified) {
            String question = switch (currentBlocker) {
                case "FEAR" -> "是某一章的概念没弄懂，还是一看到题就不知道怎么下手？";
                case "URGENT" -> "这些事里，哪一项最先到截止时间？";
                case "OVERWHELMED", "START" -> "你现在要复习哪门课，手边有哪些资料？";
                case "TIRED" -> "你想先休息一下，还是找一个不用费太多精力的起点？";
                default -> latest != null && "SKIPPED".equals(latest.status()) && latest.feedback() == null
                    ? "你上次选择了跳过，我不会猜测原因。这次想换个起点，还是先聊聊哪里卡住了？"
                    : "你更像是不知道从哪里开始，还是担心学不会？说说现在最让你卡住的一件事就好。";
            };
            return new Plan("CLARIFY", null, question);
        }
        List<String> ranking = switch (contextBlocker) {
            case "FEAR" -> List.of("exam_question", "exam_paragraph", "exam_outline", "exam_open");
            case "URGENT" -> List.of("exam_paragraph", "exam_question", "exam_outline", "exam_open");
            case "OVERWHELMED", "START" -> List.of("exam_outline", "exam_paragraph", "exam_question", "exam_open");
            case "TIRED" -> List.of("exam_open", "exam_outline", "exam_paragraph", "exam_question");
            default -> List.of("exam_paragraph", "exam_outline", "exam_question", "exam_open");
        };
        Map<String, Feedback> latestByAction = new LinkedHashMap<>();
        feedback.forEach(item -> latestByAction.putIfAbsent(item.activityId(), item));
        Set<String> avoid = new HashSet<>();
        String lastShown = null;
        for (var message : history) {
            if (message.role().equals("assistant")) {
                var ids = shownActions(List.of(message));
                if (!ids.isEmpty()) lastShown = ids.getLast();
            } else if (lastShown != null && wantsAlternative(message.text())) avoid.add(lastShown);
        }
        latestByAction.values().stream().filter(StudyAgentPolicy::unhelpful).forEach(item -> avoid.add(item.activityId()));
        int effortCeiling = EFFORT.size();
        if (latest != null) {
            if ("TOO_HARD".equals(latest.feedback())) effortCeiling = EFFORT.indexOf(latest.activityId());
            else if ("SKIPPED".equals(latest.status())) avoid.add(latest.activityId());
            // Completion alone never dictates the next action. No implicit escalation without a new blocker.
            else if (!unhelpful(latest) && currentBlocker.isEmpty() && !repeat) effortCeiling = EFFORT.indexOf(latest.activityId()) + 1;
        }
        // Chat metadata is only a preference hint; it never authorizes task writes or supplies task outcomes.
        if (change) avoid.addAll(shown);
        else if (!repeat && previous != null) avoid.add(previous);
        int budget = timeBudget(text);
        if (budget == 0) {
            for (int i = history.size() - 1; i >= 0 && budget == 0; i--)
                if (history.get(i).role().equals("user")) budget = timeBudget(history.get(i).text());
        }
        final int minutes = budget == 0 ? 3 : budget;
        final int ceiling = effortCeiling;
        List<ActivityCatalog.Activity> eligible = ranking.stream()
            .filter(id -> !avoid.contains(id) && EFFORT.indexOf(id) < ceiling)
            .map(id -> ActivityCatalog.STUDY.stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow())
            .filter(a -> a.minutes() <= minutes).toList();
        if (eligible.isEmpty()) return new Plan("CLARIFY", null, change
            ? "这几种小行动都没对上你的需要，我先不重复推了。是步骤本身不适合，还是你更需要把眼前的事情排个先后？"
            : "现有的小行动可能仍然太费力，我先不增加任务。你更想调整哪一步，还是先暂停？");
        String repeatId = previous != null ? previous : latest != null ? latest.activityId() : null;
        String completedId = latest != null && "DONE".equals(latest.status()) ? latest.activityId() : null;
        // Prefer a relevant alternative after completion, but do not force a harder action just for novelty.
        var action = repeat
            ? eligible.stream().filter(a -> a.id().equals(repeatId)).findFirst().orElse(eligible.getFirst())
            : eligible.stream().filter(a -> !a.id().equals(completedId)).findFirst().orElse(eligible.getFirst());
        String reason = switch (action.id()) {
            case "exam_open" -> "只做到打开资料就停，不要求马上阅读，准备动作也可以是这次的起点。";
            case "exam_outline" -> "先从目录里定位一个小节，缩小需要面对的范围。";
            case "exam_question" -> "用一道熟悉题找回切入点，只读题干和条件，不要求解完。";
            default -> "直接接触一小段内容，让这次开始落在具体材料上，不要求读完整章。";
        };
        if ("URGENT".equals(contextBlocker)) reason = "你提到事情多、怕来不及，这次选择一个能落到具体资料上的起点。" + reason;
        if (change) reason = "这次避开刚才展示过的方案。" + reason;
        if (latest != null && "TOO_HARD".equals(latest.feedback())) reason += "考虑到你上次反馈太难，已减少步骤、降低门槛。";
        else if (latest != null && unhelpful(latest)) reason += "已避开你近期反馈没有帮助或评分较低的行动。";
        else if (latest != null && "SKIPPED".equals(latest.status())) reason += "上次跳过的原因不作推断，这次先换个起点。";
        else if (latest != null && "DONE".equals(latest.status()) && latest.helpfulScore() == null) reason += "上次完成但未评分，不推断效果。";
        if (budget > 0) reason += "预计时长不超过你表达的时间意愿。";
        String guidance;
        if (change) guidance = variant(history, "可以换。刚才那条先放下，这次从另一种方式切入。", "有，我们换个起点，不继续重复刚才那条。");
        else guidance = switch (contextBlocker) {
            case "URGENT" -> variant(history, "你担心的是来不及，而不只是打不开资料。这次把起点落到具体内容上，不要求一次处理完所有事。",
                "事情堆在一起时，每一件都像在催你。我们先让眼前这一小步具体一点，再看是否适合继续。");
            case "FEAR" -> variant(history, "担心学不会时，一开始就要求自己做对，会让起步更难。眼前这一步不需要证明自己会了。",
                "我们先把“必须学会”的要求放轻一点。可以接触熟悉的材料，做完这一小步再决定是否继续。");
            case "OVERWHELMED", "START" -> variant(history, "资料多的时候，选起点本身就很费劲。这次只圈定一个小范围，不把整门课都压到现在。",
                "先不用解决整份复习计划。把面前的范围缩小一点，看看这个起点是否合适。");
            case "TIRED" -> "现在精力不多，就别把起步定得太重。这个步骤做完可以停，你也可以选择先休息。";
            default -> variant(history, "这次先留一个具体的小起点。做不做、是否继续，都由你决定。", "我们可以从眼前这一小步试起，不要求接着完成一整章。");
        };
        return new Plan("RECOMMEND", action, guidance, reason);
    }

    /** Search stays inside the same bounded catalog and feedback constraints as the fallback plan. */
    static List<ActivityCatalog.Activity> searchCandidates(String text, List<GardenChatController.Message> history, List<Feedback> feedback, int minutes) {
        Plan plan = decide(text, history, feedback);
        if (!"RECOMMEND".equals(plan.phase()) || plan.action() == null) return List.of();
        Set<String> avoid = new HashSet<>(shownActions(history));
        feedback.stream().filter(StudyAgentPolicy::unhelpful).map(Feedback::activityId).forEach(avoid::add);
        feedback.stream().filter(item -> "TOO_HARD".equals(item.feedback())).map(Feedback::activityId).forEach(avoid::add);
        int ceiling = minutes <= 0 ? 3 : Math.min(minutes, 3);
        List<ActivityCatalog.Activity> result = new ArrayList<>();
        result.add(plan.action());
        ActivityCatalog.STUDY.stream()
            .filter(activity -> !activity.id().equals(plan.action().id()))
            .filter(activity -> !avoid.contains(activity.id()))
            .filter(activity -> activity.minutes() <= ceiling)
            .limit(2)
            .forEach(result::add);
        return List.copyOf(result);
    }

    private static String variant(List<GardenChatController.Message> history, String first, String second) {
        String previousText = history.stream().filter(m -> m.role().equals("assistant")).reduce((a,b) -> b).map(GardenChatController.Message::text).orElse("");
        return previousText.equals(first) ? second : first;
    }
    private static boolean wantsAlternative(String text) {
        return text.matches("(?s).*(别的办法|其他办法|其它办法|其他方法|换个|换一个|换一种|换种|不合适|没用|没有帮助|已经做过|做过了|不要这个|别再推荐|别重复).*");
    }
    private static List<String> shownActions(List<GardenChatController.Message> history) {
        List<String> shown = new ArrayList<>();
        for (var message : history) {
            if (!message.role().equals("assistant")) continue;
            if (message.activityId() != null && EFFORT.contains(message.activityId())) shown.add(message.activityId());
            // Compatibility with older pages that only sent the combined prose.
            else ActivityCatalog.STUDY.stream().filter(a -> message.text().contains(a.title())).findFirst().ifPresent(a -> shown.add(a.id()));
        }
        return shown;
    }
    private static String blocker(String text) {
        if (text.matches("(?s).*(来不及|赶不上|截止|到期|时间不够|做不完).*")) return "URGENT";
        if (text.matches("(?s).*(学不会|考不好|怕挂|担心.*成绩|害怕.*考试).*")) return "FEAR";
        if (text.matches("(?s).*(太多|太大|拆不开|任务多|内容多).*")) return "OVERWHELMED";
        if (text.matches("(?s).*(不知道从哪|不知从哪|没头绪|从哪里开始|从哪开始).*")) return "START";
        if (text.matches("(?s).*(累|困|没精神|注意力|专注).*")) return "TIRED";
        return "";
    }
    private static boolean unhelpful(Feedback item) {
        return "NOT_HELPFUL".equals(item.feedback()) || (item.helpfulScore() != null && item.helpfulScore() <= 2);
    }
    private static int timeBudget(String text) {
        var matcher = java.util.regex.Pattern.compile("(?:只有|最多|愿意|先用|先做|可以花|有|能做|能花)\\s*([0-9]+|一|两|二|三)\\s*分钟").matcher(text);
        int budget = 0;
        while (matcher.find()) {
            budget = switch (matcher.group(1)) {
                case "一" -> 1; case "两", "二" -> 2; case "三" -> 3;
                default -> { try { yield Math.min(3, Integer.parseInt(matcher.group(1))); } catch (NumberFormatException e) { yield 3; } }
            };
        }
        return budget;
    }
}
