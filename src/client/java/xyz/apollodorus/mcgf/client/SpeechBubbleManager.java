package xyz.apollodorus.mcgf.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side store of the line each girlfriend entity is currently "saying",
 * with an auto-expiry so bubbles fade after a few seconds.
 */
public final class SpeechBubbleManager {
    private record Bubble(String text, long expiresAt) {}

    private static final Map<Integer, Bubble> BUBBLES = new ConcurrentHashMap<>();
    private static final long BASE_MS = 2500;
    private static final long PER_CHAR_MS = 90;

    private SpeechBubbleManager() {}

    public static void show(int entityId, String text) {
        if (text == null || text.isBlank()) return;
        long ttl = BASE_MS + (long) text.length() * PER_CHAR_MS;
        BUBBLES.put(entityId, new Bubble(text, System.currentTimeMillis() + ttl));
    }

    /** Active bubble text for the entity, or null if none/expired. */
    public static String get(int entityId) {
        Bubble b = BUBBLES.get(entityId);
        if (b == null) return null;
        if (System.currentTimeMillis() > b.expiresAt()) {
            BUBBLES.remove(entityId);
            return null;
        }
        return b.text();
    }
}
