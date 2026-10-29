package com.zhiyu.garden;

import java.util.List;
import java.util.Map;

/** Tool schemas exposed to the bounded study agent. Each tool is audited locally. */
final class StudyAgentTools {
    private StudyAgentTools() {}

    static List<Map<String, Object>> definitions() {
        return List.of(
            function("search_knowledge_base", "主动检索已审核的考试压力知识。推荐前至少成功调用一次。可根据不适用、太难或无帮助的反馈改写查询再次检索，每轮最多三次；返回真实分数与来源。没有依据时说明缺口，不编造练习。时间和场景不能放宽用户明确限制。", Map.of(
                "type", "object", "properties", Map.of(
                    "query", Map.of("type", "string", "minLength", 1, "maxLength", 300),
                    "emotion", Map.of("type", "string", "enum", List.of("anxiety", "procrastination", "frustration", "confusion")),
                    "time_limit", Map.of("type", "integer", "minimum", 1, "maximum", 60),
                    "scene", Map.of("type", "string", "enum", List.of("dormitory", "library", "classroom", "commute")),
                    "cognitive_load", Map.of("type", "string", "enum", List.of("very_low", "low", "medium"))
                ), "required", List.of("query"), "additionalProperties", false)),
            function("query_garden_state", "读取已授权的近期花园资料或复习行动反馈，只返回匿名、最小字段；没有授权时必须跳过。", Map.of(
                "type", "object", "properties", Map.of(
                    "scope", Map.of("type", "string", "enum", List.of("garden", "feedback", "both"))
                ), "additionalProperties", false)),
            function("search_activities", "先调用 search_knowledge_base，再调用此工具检索最多三个已审核行动及其依据。遵守当前时间、场景、负荷限制；完成此工具前不得调用 propose_action。", Map.of(
                "type", "object", "properties", Map.of(
                    "blocker", Map.of("type", "string", "enum", List.of("FEAR", "URGENT", "OVERWHELMED", "START", "TIRED", "OTHER")),
                    "minutes", Map.of("type", "integer", "minimum", 1, "maximum", 3),
                    "avoidActivityIds", Map.of("type", "array", "items", Map.of("type", "string"))
                ), "required", List.of("blocker"), "additionalProperties", false)),
            function("propose_action", "只能在 search_activities 成功后调用，且只能提出搜索结果中的一个行动；这是无副作用的提议，不会创建任务。", Map.of(
                "type", "object", "properties", Map.of(
                    "activityId", Map.of("type", "string"),
                    "reason", Map.of("type", "string", "maxLength", 240)
                ), "required", List.of("activityId", "reason"), "additionalProperties", false)),
            function("create_task", "创建任务需要用户点击页面确认。本聊天工具调用永远不能执行此工具，只能返回需要确认。", Map.of(
                "type", "object", "properties", Map.of(
                    "activityId", Map.of("type", "string"),
                    "entryId", Map.of("type", "string")
                ), "required", List.of("activityId"), "additionalProperties", false))
        );
    }

    private static Map<String, Object> function(String name, String description, Map<String, Object> parameters) {
        return Map.of("type", "function", "function", Map.of(
            "name", name, "description", description, "parameters", parameters));
    }
}
