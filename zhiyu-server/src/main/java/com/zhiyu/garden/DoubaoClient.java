package com.zhiyu.garden;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Provider boundary: no request bodies, keys or provider errors are logged. */
@Service
public class DoubaoClient {
    private final ObjectMapper mapper;
    private final String key, model, baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public DoubaoClient(ObjectMapper mapper, @Value("${ai.doubao.api-key:}") String key,
            @Value("${ai.doubao.model:}") String model,
            @Value("${ai.doubao.base-url:https://ark.cn-beijing.volces.com/api/v3}") String baseUrl) {
        this.mapper=mapper; this.key=key.trim(); this.model=model.trim(); this.baseUrl=baseUrl.replaceAll("/+$","");
    }
    public boolean configured() { return !key.isBlank() && !model.isBlank(); }
    public List<String> missingConfig() {
        List<String> missing=new ArrayList<>();
        if(key.isBlank()) missing.add("ARK_API_KEY");
        if(model.isBlank()) missing.add("ARK_MODEL");
        return missing;
    }
    public record Answer(String reply, boolean needHelp, List<String> activityIds, String phase) {}
    public record Result(Answer answer, String notice) {}
    public record ToolCall(String id, String name, String arguments) {}
    public record AgentResult(String content, List<ToolCall> toolCalls, String notice) {}

    private static Result failed(String notice) { return new Result(null,notice); }
    private static AgentResult failedAgent(String notice) { return new AgentResult("",List.of(),notice); }

    public Answer complete(List<Map<String,String>> messages) {
        return completeDetailed(messages).answer();
    }
    public Result completeDetailed(List<Map<String,String>> messages) {
        List<Map<String,Object>> generic = messages.stream().map(message -> {
            Map<String,Object> converted = new LinkedHashMap<>();
            converted.putAll(message);
            return converted;
        }).toList();
        AgentResult result = completeAgent(generic, List.of());
        if (!result.notice().isBlank()) return failed(result.notice());
        if (!result.toolCalls().isEmpty()) return failed("豆包返回了当前流程不允许的工具调用。");
        return parseContent(result.content());
    }

    /** One provider turn. Tool execution stays in the application, never in the provider. */
    public AgentResult completeAgent(List<Map<String,Object>> messages, List<Map<String,Object>> tools) {
        return completeAgent(messages, tools, "auto");
    }

    /**
     * One provider turn with an explicit tool-choice policy. The caller controls when a
     * planning tool is required; the provider still chooses which registered tool to call.
     */
    public AgentResult completeAgent(List<Map<String,Object>> messages, List<Map<String,Object>> tools, String toolChoice) {
        if (!configured()) return failedAgent("后端缺少豆包配置。");
        try {
            Map<String,Object> payload = new LinkedHashMap<>();
            payload.put("model", model); payload.put("messages", messages); payload.put("max_tokens", 900); payload.put("stream", false);
            if (!tools.isEmpty()) {
                payload.put("tools", tools);
                payload.put("tool_choice", toolChoice == null || toolChoice.isBlank() ? "auto" : toolChoice);
            }
            var request = HttpRequest.newBuilder(URI.create(baseUrl+"/chat/completions"))
                .timeout(Duration.ofSeconds(25)).header("Authorization","Bearer "+key)
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
            var response = http.send(request,HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()!=200) return failedAgent(switch(response.statusCode()) {
                case 401 -> "方舟鉴权失败（401），请检查 API Key 是否有效。";
                case 403 -> "方舟拒绝访问（403），请检查账号及模型调用权限。";
                case 404 -> "方舟资源不存在（404），请核对 model 值和接口地址。";
                case 429 -> "方舟限制了请求（429），请检查调用额度及频率。";
                case 400 -> "方舟不接受当前请求（400），请核对模型 ID 和接口参数兼容性。";
                default -> "方舟返回服务错误（HTTP "+response.statusCode()+").";
            });
            if (response.body().length()>100_000) return failedAgent("豆包响应过大，未显示。");
            var root=mapper.readTree(response.body());
            String finish=root.path("choices").path(0).path("finish_reason").asText();
            if ("length".equals(finish)) return failedAgent("豆包输出达到长度上限，回复未完成；需调整模型输出预算。");
            if (!"stop".equals(finish) && !"tool_calls".equals(finish)) return failedAgent("豆包未返回完整回复，可能被过滤或与接口格式不兼容。");
            var message=root.path("choices").path(0).path("message");
            String content=message.path("content").isTextual() ? message.path("content").asText().trim() : "";
            List<ToolCall> calls = new ArrayList<>();
            for (var node : message.path("tool_calls")) {
                String id = node.path("id").asText("");
                String name = node.path("function").path("name").asText("");
                String arguments = node.path("function").path("arguments").isTextual()
                    ? node.path("function").path("arguments").asText() : node.path("function").path("arguments").toString();
                if (id.isBlank() || name.isBlank()) return failedAgent("豆包返回了不完整的工具调用。");
                calls.add(new ToolCall(id, name, arguments));
            }
            if (content.isBlank() && calls.isEmpty()) return failedAgent("豆包没有返回可处理的内容。");
            return new AgentResult(content, List.copyOf(calls), "");
        } catch(InterruptedException error) { Thread.currentThread().interrupt(); return failedAgent("豆包请求已中断。"); }
        catch(java.net.http.HttpTimeoutException error) { return failedAgent("豆包连接或回复超时，请稍后重试。"); }
        catch(com.fasterxml.jackson.core.JsonProcessingException error) { return failedAgent("豆包返回内容无法解析为应用需要的 JSON 格式。"); }
        catch(java.io.IOException error) { return failedAgent("后端无法连接方舟，请检查网络和接口地址。"); }
        catch(Exception error) { return failedAgent("豆包请求处理失败，请检查后端配置与接口兼容性。"); }
    }

    public Result parseContent(String rawContent) {
        try {
            String content = rawContent == null ? "" : rawContent.trim();
            if(content.startsWith("```")) content=content.replaceFirst("^```(?:json)?\\s*","").replaceFirst("\\s*```$","");
            var value=mapper.readTree(content);
            if (value==null || !value.path("reply").isTextual() || !value.path("needHelp").isBoolean() || !value.path("activityIds").isArray()) return failed("豆包回复格式不符合应用要求。");
            String reply=value.path("reply").asText().trim();
            if(reply.isBlank() || reply.length()>600) return failed("豆包回复为空或超出展示长度。");
            if(value.path("needHelp").asBoolean()) return new Result(new Answer(reply,true,List.of(),"SAFETY"),"");
            String phase=value.path("phase").asText();
            if(!Set.of("CLARIFY","RECOMMEND","PAUSE").contains(phase)) return failed("豆包回复缺少有效的流程阶段。");
            if(reply.matches("(?s).*(你患有|你得了|你有抑郁症|你有焦虑症|保证治愈|保证治好|已.*(添加|加入|创建).*(任务|任务簿)|已.*(联系|通知|报警)).*")) return failed("豆包回复触发应用内容检查，未显示原回复。");
            if(reply.matches("(?s).*(复习|学习|做题|刷题).{0,12}([一二两三四五六七八九十0-9]+小时|[四五六七八九十]|[4-9]|[1-9][0-9]+)分钟.*")
                || reply.matches("(?s).*(复习|学习|做题|刷题).{0,12}[一二两三四五六七八九十0-9]+小时.*")
                || reply.matches("(?s).*(再做一个|另外再|然后再|接着再|第二个任务).*"))
                return failed("豆包回复增加了额外任务或负担，改用本轮引导。");
            List<String> ids=new ArrayList<>();
            if(value.path("activityIds").size()>1) return failed("豆包一次推荐了多个行动，改用受限流程。");
            for(var node:value.path("activityIds")) {
                if(!node.isTextual() || ActivityCatalog.STUDY.stream().noneMatch(a->a.id().equals(node.asText())))
                    return failed("豆包推荐了复习目录以外的行动。");
                ids.add(node.asText());
            }
            return new Result(new Answer(reply,false,ids,phase),"");
        } catch(com.fasterxml.jackson.core.JsonProcessingException error) { return failed("豆包返回内容无法解析为应用需要的 JSON 格式。"); }
        catch(Exception error) { return failed("豆包回复处理失败，请检查返回格式。"); }
    }
}
