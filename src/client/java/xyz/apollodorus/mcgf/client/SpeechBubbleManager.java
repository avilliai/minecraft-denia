package xyz.apollodorus.mcgf.client;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import xyz.apollodorus.mcgf.net.SpeechPayload;

/**
 * Client-side store of the line each girlfriend entity is currently "saying".
 * A reply may arrive as several short messages separated by "||"; we show them as a
 * timed burst — one segment at a time — and let the whole thing auto-expire after the
 * last one. A line with no "||" is a single segment, i.e. the original one-bubble behavior.
 */
public final class SpeechBubbleManager {
    /** A scheduled burst: segment i is visible during [startAt[i], startAt[i+1]); the whole thing dies at endMs. */
    private record Burst(String[] texts, long[] startAt, long t0, long endMs) {}

    private static final Map<Integer, Burst> BUBBLES = new ConcurrentHashMap<>();
    private static final long BASE_MS = 2500;
    private static final long PER_CHAR_MS = 90;
    /** Each non-final burst segment stays at least this long before advancing to the next. */
    private static final long MIN_SEG_MS = 1300;
    /** Dwell added to each non-final segment on top of its per-char reading time. */
    private static final long SEG_PAD_MS = 650;

    private SpeechBubbleManager() {}

    public static void show(int entityId, String text) {
        if (text == null || text.isBlank()) return;
        List<String> segs = SpeechPayload.segments(text);
        if (segs.isEmpty()) return;
        String[] texts = segs.toArray(new String[0]);
        long[] startAt = new long[texts.length];
        long acc = 0;
        for (int i = 0; i < texts.length; i++) {
            startAt[i] = acc;
            // The last segment lingers the usual BASE + per-char tail so the final message stays
            // readable; earlier segments advance after a shorter, length-scaled beat.
            long dur = (i == texts.length - 1)
                ? BASE_MS + (long) texts[i].length() * PER_CHAR_MS
                : Math.max(MIN_SEG_MS, SEG_PAD_MS + (long) texts[i].length() * PER_CHAR_MS);
            acc += dur;
        }
        long now = System.currentTimeMillis();
        BUBBLES.put(entityId, new Burst(texts, startAt, now, now + acc));
    }

    /** The segment the entity is currently "saying", or null if none/expired. */
    public static String get(int entityId) {
        Burst b = BUBBLES.get(entityId);
        if (b == null) return null;
        long now = System.currentTimeMillis();
        if (now >= b.endMs()) {
            BUBBLES.remove(entityId);
            return null;
        }
        long elapsed = now - b.t0();
        String current = b.texts()[0];
        for (int i = 0; i < b.startAt().length; i++) {
            if (elapsed >= b.startAt()[i]) current = b.texts()[i];
            else break;
        }
        return current;
    }
}
