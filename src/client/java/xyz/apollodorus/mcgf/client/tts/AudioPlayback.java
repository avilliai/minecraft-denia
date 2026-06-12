package xyz.apollodorus.mcgf.client.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.io.ByteArrayInputStream;

/**
 * Shared WAV playback through Java Sound, used by both the GPT-SoVITS {@link TtsClient} and the
 * bundled {@link VoiceClips}. Blocks until the clip finishes (run it on a worker thread). Any
 * failure degrades silently so the game never stalls.
 */
public final class AudioPlayback {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcgf/audio");

    private AudioPlayback() {}

    public static void play(byte[] wav) {
        if (wav == null || wav.length == 0) return;
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            Clip clip = AudioSystem.getClip();
            clip.open(in);
            clip.start();
            long ms = clip.getMicrosecondLength() / 1000L;
            Thread.sleep(ms + 200L);
            clip.close();
        } catch (Exception e) {
            LOGGER.warn("[mcgf] audio playback failed: {}", e.toString());
        }
    }
}
