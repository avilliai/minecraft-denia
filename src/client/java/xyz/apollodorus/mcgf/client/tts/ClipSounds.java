package xyz.apollodorus.mcgf.client.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plays the bundled positional combat clips packaged under {@code /assets/mcgf/sounds/} (1a–4a — 达妮娅's
 * 形态一 combo hits). The server names a clip + a world position in a {@link xyz.apollodorus.mcgf.net.ClipSoundPayload};
 * the client computes a distance gain + L/R pan and plays the wav through {@link AudioPlayback}. A small pool
 * (not single-threaded like {@link VoiceClips}) so back-to-back combo hits can overlap. Client-side only;
 * a missing/unreadable clip degrades silently.
 */
public final class ClipSounds {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcgf/clipsound");
    private static final AtomicInteger N = new AtomicInteger();
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "mcgf-clip-" + N.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private ClipSounds() {}

    public static void play(String clip, float gain, float pan) {
        if (clip == null || clip.isBlank() || gain <= 0.0f) return;
        String safe = clip.replaceAll("[^a-z0-9_]", ""); // clip ids are simple ascii (1a..4a); keep the path safe
        if (safe.isEmpty()) return;
        EXEC.submit(() -> {
            byte[] bytes = read("/assets/mcgf/sounds/" + safe + ".wav");
            if (bytes != null) AudioPlayback.play(bytes, gain, pan);
        });
    }

    private static byte[] read(String resource) {
        try (InputStream in = ClipSounds.class.getResourceAsStream(resource)) {
            if (in == null) {
                LOGGER.warn("[mcgf] combat clip not found: {}", resource);
                return null;
            }
            return in.readAllBytes();
        } catch (Exception e) {
            LOGGER.warn("[mcgf] combat clip read failed {}: {}", resource, e.toString());
            return null;
        }
    }
}
