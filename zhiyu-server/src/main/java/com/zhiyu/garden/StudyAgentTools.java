package com.zhiyu.garden;

import java.util.List;
import java.util.Map;

/** Tool schemas exposed to the bounded study agent. Each tool is audited locally. */
final class StudyAgentTools {
    private StudyAgentTools() {}

    static List<Map<String, Object>> definitions() {
        return List.of(
            function("query_garden_state", "读取已授权的近期花园资料或复习行动反馈，只返回匿名、最小字段；没有授权时必须跳过。", Map.of(
                "type", "object", "properties", Map.of(
                    "scope", Map.of("type", "string", "enum", List.of("garden", "feedback", "both"))
                ), "additionalProperties", false)),
            function("search_activities", "在服务端审核过的复习启动目录中检索候选行动，只返回当前流程允许的最多三个候选。", Map.of(
                "type", "object", "properties", Map.of(
                    "blocker", Map.of("type", "string", "enum", List.of("FEAR", "URGENT", "OVERWHELMED", "START", "TIRED", "OTHER")),
                    "minutes", Map.of("type", "integer", "minimum", 1, "maximum", 3),
                    "avoidActivityIds", Map.of("type", "array", "items", Map.of("type", "string"))
                ), "required", List.of("blocker"), "additionalProperties", false)),
            function("propose_action", "提出一个候选复习行动及理由；这是无副作用的提议，不会创建任务。", Map.of(
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
