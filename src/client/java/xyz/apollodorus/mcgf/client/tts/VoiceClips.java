package xyz.apollodorus.mcgf.client.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plays the bundled 达妮娅 voice clips packaged under {@code /assets/mcgf/voice/}. The server names
 * a cue ("perceive", "defeat1"…) in the speech payload; we map it to a wav on the classpath and play
 * it through {@link AudioPlayback}. Client-side only; a missing or unreadable clip degrades silently.
 */
public final class VoiceClips {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcgf/voice");
    // Single worker so clips play one at a time rather than overlapping.
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcgf-voice");
        t.setDaemon(true);
        return t;
    });

    private VoiceClips() {}

    public static void play(String cue) {
        if (cue == null || cue.isBlank()) return;
        String safe = cue.replaceAll("[^a-z0-9_]", ""); // cues are simple ascii ids; keep the path safe
        if (safe.isEmpty()) return;
        EXEC.submit(() -> {
            byte[] bytes = read("/assets/mcgf/voice/" + safe + ".wav");
            if (bytes != null) AudioPlayback.play(bytes);
        });
    }

    private static byte[] read(String resource) {
        try (InputStream in = VoiceClips.class.getResourceAsStream(resource)) {
            if (in == null) {
                LOGGER.warn("[mcgf] voice clip not found: {}", resource);
                return null;
            }
            return in.readAllBytes();
        } catch (Exception e) {
            LOGGER.warn("[mcgf] voice clip read failed {}: {}", resource, e.toString());
            return null;
        }
    }
}
