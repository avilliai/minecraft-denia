package xyz.apollodorus.mcgf.entity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-owner companion bond state, persisted to config/mcgf-downed.json so it
 * survives restarts. Keyed by the owner's UUID — one companion per owner. Tracks
 * three things:
 * <ul>
 *   <li>{@code downed} — she died; the charm can revive her (for an XP cost that
 *       grows by one level each time, via {@code reviveCount}).</li>
 *   <li>{@code stored} — she was captured alive into the charm; the charm releases
 *       her again, free.</li>
 * </ul>
 */
public final class DownedManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcgf/downed");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private DownedManager() {}

    /** Serialized state of a companion, enough to rebuild her (dead or captured-alive). */
    public static final class Snapshot {
        public String inventory = "";
        public int affection;
        public double x, y, z;
        public double health;
        public boolean hasHome;
        public int homeX, homeY, homeZ;
        // 新增：单独保存各装备槽，key = slot.getName()
        public Map<String, String> equipment = new HashMap<>();
    }

    private static final class Store {
        Map<String, Snapshot> downed = new HashMap<>();      // dead → revivable
        Map<String, Integer> reviveCount = new HashMap<>();
        Map<String, Snapshot> stored = new HashMap<>();      // captured alive → releasable
    }

    private static volatile Store store = new Store();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("mcgf-downed.json");
    }

    public static void load() {
        Path file = path();
        try {
            if (Files.notExists(file)) { store = new Store(); return; }
            Store s = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Store.class);
            store = s != null ? s : new Store();
            if (store.downed == null) store.downed = new HashMap<>();
            if (store.reviveCount == null) store.reviveCount = new HashMap<>();
            if (store.stored == null) store.stored = new HashMap<>();
        } catch (Exception e) {
            LOGGER.warn("[mcgf] failed to load bond store: {}", e.toString());
            store = new Store();
        }
    }

    private static void save() {
        try {
            Path file = path();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(store), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[mcgf] failed to save bond store: {}", e.toString());
        }
    }

    // --- downed (dead → revive) ---

    public static synchronized void setDowned(UUID owner, Snapshot snap) {
        store.downed.put(owner.toString(), snap);
        save();
    }

    public static Snapshot getDowned(UUID owner) {
        return store.downed.get(owner.toString());
    }

    public static boolean isDowned(UUID owner) {
        return store.downed.containsKey(owner.toString());
    }

    public static synchronized void clearDowned(UUID owner) {
        if (store.downed.remove(owner.toString()) != null) save();
    }

    public static int getReviveCount(UUID owner) {
        return store.reviveCount.getOrDefault(owner.toString(), 0);
    }

    public static synchronized void incrementReviveCount(UUID owner) {
        store.reviveCount.merge(owner.toString(), 1, Integer::sum);
        save();
    }

    // --- stored (captured alive → release) ---

    public static synchronized void setStored(UUID owner, Snapshot snap) {
        store.stored.put(owner.toString(), snap);
        save();
    }

    public static Snapshot getStored(UUID owner) {
        return store.stored.get(owner.toString());
    }

    public static boolean isStored(UUID owner) {
        return store.stored.containsKey(owner.toString());
    }

    public static synchronized void clearStored(UUID owner) {
        if (store.stored.remove(owner.toString()) != null) save();
    }
}
