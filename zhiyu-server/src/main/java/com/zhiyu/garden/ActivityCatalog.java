package com.zhiyu.garden;

import java.util.List;

/** Draft self-help activities. Source links are references, not clinical review or efficacy evidence. */
public final class ActivityCatalog {
    private ActivityCatalog() {}
    public record Activity(String id, String title, int minutes, String method, String instructions, String source, List<String> plants) {}
    private static Activity a(String id, String title, int minutes, String method, String instructions, String source, String... plants) {
        return new Activity(id, title, minutes, method, instructions, source, List.of(plants));
    }
    public static final List<Activity> ALL = List.of(
        a("exam_open", "只打开一份复习资料", 1, "复习启动", "找到一份准备复习的资料并打开，不要求阅读、选章节或做题；打开后就可以停下。", "自拟复习启动微行动", "dandelion", "mimosa"),
        a("exam_outline", "打开资料，只看复习目录", 1, "复习启动", "打开你准备复习的课程资料，只看目录并圈出一个小节；完成后可以停下。", "自拟复习启动微行动", "dandelion", "mimosa"),
        a("exam_paragraph", "只读一小段复习资料", 2, "复习启动", "打开一个你愿意开始的小节，只读第一段。读完就可以停下，不追加目标。", "自拟复习启动微行动", "dandelion", "mimosa"),
        a("exam_question", "读一道熟悉的题", 3, "复习启动", "找一道你见过或相对熟悉的题，只读题目并标出已知条件；暂时不用解完。", "自拟复习启动微行动", "dandelion", "sunflower"),
        a("breathe", "给呼吸两分钟", 2, "舒缓呼吸", "坐稳或站稳，轻柔吸气，再慢慢呼气。不需要憋气或用力；不舒服就停止，恢复自然呼吸。", "https://www.nhs.uk/mental-health/self-help/guides-tools-and-activities/breathing-exercises-for-stress/", "mimosa", "cactus"),
        a("thought", "把担心写成一句话", 3, "CBT 思维记录", "写下当下的想法，区分已发生的事实与自己的预测。若愿意，再写一个更平衡的理解。", "https://www.nhs.uk/every-mind-matters/mental-wellbeing-tips/self-help-cbt-techniques/thought-record/", "mimosa", "dandelion"),
        a("window", "在窗边停留一分钟", 1, "当下觉察", "在安全的窗边或座位上，看见三种颜色，听见两种声音，感觉脚下的支撑。无需站起。", "https://www.who.int/publications/i/item/9789240003927", "moss", "dandelion"),
        a("stretch", "让肩膀歇一会", 2, "日常自我照顾", "在舒适范围内放松肩膀和双手，也可以坐着进行。无需达到固定幅度，不适时停止。", "https://www.who.int/publications/i/item/9789240003927", "moss", "cactus"),
        a("needs", "写下一个在意的界限", 3, "情绪觉察", "写下这件事中自己在意的需要。先不急着回复对方，想一句清楚、尊重双方的表达。", "https://www.who.int/publications/i/item/9789240003927", "cactus"),
        a("next", "把大事拆成第一小步", 3, "行动计划", "选一件手头的事，写下两分钟内能开始的一步。可以只是打开资料或写一个标题。", "https://www.who.int/publications/i/item/9789240003927", "dandelion"),
        a("notice", "记住一个平静瞬间", 2, "当下觉察", "留意此刻让你安稳的一样东西，写下一句话。没有特别感受也没关系。", "https://www.who.int/publications/i/item/9789240003927", "daisy"),
        a("water", "慢慢喝几口水", 2, "日常自我照顾", "按自己的需要喝几口水，留意温度和身体的感觉。不要求固定饮水量。", "https://www.who.int/publications/i/item/9789240003927", "daisy"),
        a("joy", "收好今天的一件小事", 3, "积极体验记录", "把今天让你愉悦的一件小事写下来，留给以后的自己。", "https://www.who.int/publications/i/item/9789240003927", "sunflower"),
        a("share", "向朋友分享一件小事", 3, "社会连接", "如果愿意，给信任的人发一句近况。不要求对方立即回应，也可以先写下不发送。", "https://www.who.int/publications/i/item/9789240003927", "sunflower"),
        a("desk", "整理桌面的一小角", 3, "日常微行动", "只收好眼前三样物品，做完就可以停下。", "https://www.who.int/publications/i/item/9789240003927", "moss", "dandelion"),
        a("walk", "在安全平地慢走两分钟", 2, "当下觉察", "找一小段安全平地慢走，留意脚步；不便行走时可坐着感受身体与椅子的接触。", "https://www.who.int/publications/i/item/9789240003927", "mimosa", "cactus"),
        a("friend", "给信任的人留句话", 3, "社会连接", "可以说：最近有些累，有空时想和你聊聊。是否发送由你决定。", "https://www.who.int/publications/i/item/9789240003927", "moss"),
        a("study", "为学习留两分钟起点", 2, "行动计划", "打开一页材料，只读一小段。完成这一小步即可，不给自己追加目标。", "https://www.who.int/publications/i/item/9789240003927", "dandelion", "mimosa"),
        a("pause", "给自己一小段停顿", 2, "日常自我照顾", "暂时放下通知，看看身边的环境。想一想此刻最需要的是休息、帮助，还是继续。", "https://www.who.int/publications/i/item/9789240003927", "daisy", "sunflower")
    );
    public static final List<Activity> STUDY = ALL.stream().filter(activity -> activity.id().startsWith("exam_")).toList();
}
