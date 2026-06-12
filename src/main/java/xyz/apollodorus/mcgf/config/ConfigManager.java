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
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile GirlfriendConfig config = new GirlfriendConfig();

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
                save(fresh);
                config = fresh;
                LOGGER.info("[mcgf] wrote default config to {}", file);
                return config;
            }
            String json = Files.readString(file, StandardCharsets.UTF_8);
            GirlfriendConfig loaded = GSON.fromJson(json, GirlfriendConfig.class);
            if (loaded == null) loaded = new GirlfriendConfig();
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
