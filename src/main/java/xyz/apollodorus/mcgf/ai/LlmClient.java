package xyz.apollodorus.mcgf.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Thin OpenAI-compatible chat-completions client over java.net.http. All calls
 * are blocking and meant to be run on the shared brain executor, never on the
 * server thread.
 */
public final class LlmClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcgf/llm");
    private static final Gson GSON = new Gson();

    // Pin to HTTP/1.1. The default client negotiates HTTP/2, and against a cleartext
    // http:// relay that means an h2c upgrade — during which the JDK drops the POST body.
    // The server then sees an empty body and returns 422, which we'd map to a null reply
    // ("网络好像有点问题"). HTTP/1.1 sends the body normally, exactly like Python requests/curl.
    private final HttpClient http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    /**
     * Run one chat-completions request. Returns the assistant {@code message}
     * object (with {@code content} and optional {@code tool_calls}), or null on
     * failure. If a tools request fails, it retries once WITHOUT tools — some
     * relays/models reject the tool schema with an HTTP 400, and a degraded reply
     * beats a dead conversation.
     */
    public JsonObject complete(JsonArray messages, JsonArray tools) {
        GirlfriendConfig.Llm cfg = ConfigManager.get().llm;
        JsonObject result = sendOnce(cfg, messages, tools);
        if (result == null && tools != null && !tools.isEmpty()) {
            LOGGER.warn("[mcgf] tools request failed; retrying once without tools");
            result = sendOnce(cfg, messages, null);
        }
        return result;
    }

    private JsonObject sendOnce(GirlfriendConfig.Llm cfg, JsonArray messages, JsonArray tools) {
        boolean withTools = tools != null && !tools.isEmpty();

        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model);
        body.add("messages", messages);
        body.addProperty("temperature", cfg.temperature);
        if (withTools) {
            body.add("tools", tools);
            body.addProperty("tool_choice", "auto");
        }

        String url = trimTrailingSlash(cfg.baseURL) + "/chat/completions";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(5, cfg.requestTimeoutSeconds)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                LOGGER.warn("[mcgf] LLM HTTP {} (model={}, msgs={}, tools={}): {}",
                    resp.statusCode(), cfg.model, messages.size(), withTools, abbreviate(resp.body()));
                return null;
            }
            JsonObject root = GSON.fromJson(resp.body(), JsonObject.class);
            JsonArray choices = root == null ? null : root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                LOGGER.warn("[mcgf] LLM empty/invalid response: {}", abbreviate(resp.body()));
                return null;
            }
            return choices.get(0).getAsJsonObject().getAsJsonObject("message");
        } catch (Exception e) {
            LOGGER.warn("[mcgf] LLM request failed (model={}, msgs={}): {}", cfg.model, messages.size(), e.toString());
            return null;
        }
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
