package xyz.apollodorus.mcgf.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads / saves {@link GirlfriendConfig} from config/mcgf.json. On first run it
 * writes a default file so users can immediately edit the LLM + TTS endpoints.
 */
public final class ConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcgf/config");

    private static final java.lang.reflect.Type STRING_LIST_TYPE =
        new com.google.gson.reflect.TypeToken<java.util.List<String>>() {}.getType();

    // 兼容老配置：单个字符串也接受成单元素列表。这样把某些 String 引导词字段升级为 List<String> 后，
    // 老的 mcgf.json 仍能正常解析，不会因类型不符而整份解析失败、回退默认值（那会连 API key 都丢）。
    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(STRING_LIST_TYPE, new LenientStringList())
        .create();

    private static final class LenientStringList implements com.google.gson.JsonDeserializer<java.util.List<String>> {
        @Override
        public java.util.List<String> deserialize(com.google.gson.JsonElement json, java.lang.reflect.Type type,
                                                  com.google.gson.JsonDeserializationContext ctx) {
            java.util.List<String> out = new java.util.ArrayList<>();
            if (json == null || json.isJsonNull()) return out;
            if (json.isJsonArray()) {
                for (com.google.gson.JsonElement e : json.getAsJsonArray()) {
                    if (e != null && !e.isJsonNull()) out.add(e.getAsString());
                }
            } else if (json.isJsonPrimitive()) {
                out.add(json.getAsString());   // 老配置里的单条字符串 → 单元素列表
            }
            return out;
        }
    }

    private static volatile GirlfriendConfig config = new GirlfriendConfig();

    /**
     * Bump whenever the built-in persona / prompt defaults change. On load, a config whose
     * {@code configVersion} is lower has its persona + prompt text refreshed from the new defaults
     * (its llm / tts / behavior settings are kept), then is re-stamped to this version. That's what
     * lets edits to the prompt defaults reach an existing mcgf.json without the user deleting it.
     */
    public static final int CURRENT_CONFIG_VERSION = 8;

    private ConfigManager() {}

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("mcgf.json");
    }

    public static GirlfriendConfig get() {
        return config;
    }

    /** Load from disk; create defaults if missing. Safe to call again to reload. */
    public static GirlfriendConfig load() {
        Path file = path();
        try {
            if (Files.notExists(file)) {
                GirlfriendConfig fresh = new GirlfriendConfig();
                fresh.configVersion = CURRENT_CONFIG_VERSION;
                save(fresh);
                config = fresh;
                LOGGER.info("[mcgf] wrote default config to {}", file);
                return config;
            }
            String json = Files.readString(file, StandardCharsets.UTF_8);
            GirlfriendConfig loaded = GSON.fromJson(json, GirlfriendConfig.class);
            if (loaded == null) loaded = new GirlfriendConfig();
            // Refresh persona/prompt text from new defaults if this file predates them (before the
            // env-key override below, so the key is never written to disk — matching prior behavior).
            maybeMigrate(loaded, file);
            // Allow overriding the API key via env var without editing the file.
            String envKey = System.getenv("MC_BOT_API_KEY");
            if (envKey == null) envKey = System.getenv("OPENAI_API_KEY");
            if (envKey != null && !envKey.isBlank()) loaded.llm.apiKey = envKey;
            config = loaded;
            LOGGER.info("[mcgf] loaded config from {}", file);
            return config;
        } catch (Exception e) {
            LOGGER.error("[mcgf] failed to load config, using defaults", e);
            config = new GirlfriendConfig();
            return config;
        }
    }

    /**
     * When {@code loaded.configVersion < CURRENT_CONFIG_VERSION}, overwrite only the persona / prompt
     * TEXT fields from the current built-in defaults and keep everything else (llm endpoint/key/model,
     * tts, behavior, persona.displayName/skinTexture). Re-saves and stamps the new version, so it runs
     * at most once per bump. Note: any prompt text the user hand-edited in the json is intentionally
     * replaced on a version bump — prompts are iterated in code, not the json.
     */
    private static void maybeMigrate(GirlfriendConfig loaded, Path file) {
        if (loaded == null || loaded.configVersion >= CURRENT_CONFIG_VERSION) return;
        GirlfriendConfig def = new GirlfriendConfig();
        if (loaded.persona != null) loaded.persona.joinIntro = def.persona.joinIntro;
        if (loaded.llm != null) {
            loaded.llm.systemPrompt = def.llm.systemPrompt;
            loaded.llm.ephemeralSystem = def.llm.ephemeralSystem;
            loaded.llm.proactiveUserPrompt = def.llm.proactiveUserPrompt;
            loaded.llm.systemPromptForm2 = def.llm.systemPromptForm2;
            loaded.llm.ephemeralSystemForm2 = def.llm.ephemeralSystemForm2;
        }
        loaded.prompts = def.prompts;
        int from = loaded.configVersion;
        loaded.configVersion = CURRENT_CONFIG_VERSION;
        save(loaded);
        LOGGER.info("[mcgf] migrated config v{} -> v{} ({}): refreshed persona/prompts, kept llm/tts/behavior",
            from, CURRENT_CONFIG_VERSION, file.getFileName());
    }

    public static void save(GirlfriendConfig cfg) {
        Path file = path();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(cfg), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("[mcgf] failed to save config", e);
        }
    }
}
