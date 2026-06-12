package xyz.apollodorus.mcgf.client.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Calls the GPT-SoVITS HTTP endpoint (GET /tts with text + reference-audio
 * params, matching the user's python sample) and plays the returned WAV through
 * Java Sound. Fully client-side; failures degrade silently so the game never
 * stalls. The endpoint and all params come from config/mcgf.json.
 */
public final class TtsClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcgf/tts");
    // HTTP/1.1 for the same reason as LlmClient: avoid the HTTP/2 h2c upgrade against a
    // cleartext http:// endpoint (matches the python sample, which uses HTTP/1.1).
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .build();
    // Single worker so lines play one after another instead of overlapping.
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcgf-tts");
        t.setDaemon(true);
        return t;
    });

    private TtsClient() {}

    public static void speak(String text) {
        GirlfriendConfig.Tts cfg = ConfigManager.get().tts;
        if (!cfg.enabled || text == null || text.isBlank()) return;
        EXEC.submit(() -> {
            try {
                String url = buildUrl(cfg, text);
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(5, cfg.requestTimeoutSeconds)))
                    .GET()
                    .build();
                HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() / 100 == 2) {
                    AudioPlayback.play(resp.body());
                } else {
                    LOGGER.warn("[mcgf] TTS HTTP {}", resp.statusCode());
                }
            } catch (Exception e) {
                LOGGER.warn("[mcgf] TTS failed: {}", e.toString());
            }
        });
    }

    private static String buildUrl(GirlfriendConfig.Tts cfg, String text) {
        StringBuilder sb = new StringBuilder(cfg.url);
        sb.append(cfg.url.contains("?") ? '&' : '?');
        sb.append("text=").append(enc(text));
        sb.append("&text_lang=").append(enc(cfg.textLang));
        sb.append("&ref_audio_path=").append(enc(cfg.refAudioPath));
        sb.append("&prompt_text=").append(enc(cfg.promptText));
        sb.append("&prompt_lang=").append(enc(cfg.promptLang));
        if (cfg.extraParams != null) {
            for (Map.Entry<String, String> e : cfg.extraParams.entrySet()) {
                sb.append('&').append(enc(e.getKey())).append('=').append(enc(e.getValue()));
            }
        }
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
