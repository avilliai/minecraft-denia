package xyz.apollodorus.mcgf.client.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.ByteArrayInputStream;

/**
 * Shared WAV playback through Java Sound, used by the GPT-SoVITS {@link TtsClient}, the bundled
 * {@link VoiceClips}, and the positional combat {@link ClipSounds}. Blocks until the clip finishes
 * (run it on a worker thread). Any failure degrades silently so the game never stalls.
 *
 * <p>Whatever the source WAV encoding (32-bit float, 24-bit PCM, …), it is transcoded to 16-bit signed
 * PCM before opening the {@link Clip} — a {@code Clip} cannot open float/24-bit lines directly, which is
 * why such files played silently before.
 */
public final class AudioPlayback {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcgf/audio");

    private AudioPlayback() {}

    public static void play(byte[] wav) {
        play(wav, 1.0f, 0.0f);
    }

    /**
     * Play a WAV with a 0..1 {@code gain} and a -1..1 stereo {@code pan} (negative = left). The gain/pan
     * controls degrade silently if the line doesn't support them (e.g. a mono device), so the clip still
     * plays at full volume rather than failing.
     */
    public static void play(byte[] wav, float gain, float pan) {
        if (wav == null || wav.length == 0) return;
        AudioInputStream in = null;
        try {
            in = toPcm16(AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav)));
            Clip clip = AudioSystem.getClip();
            clip.open(in);
            applyGain(clip, gain);
            applyPan(clip, pan);
            clip.start();
            long ms = clip.getMicrosecondLength() / 1000L;
            Thread.sleep(ms + 200L);
            clip.close();
        } catch (Exception e) {
            LOGGER.warn("[mcgf] audio playback failed: {}", e.toString());
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    /** Transcode any readable WAV (32-bit float, 24-bit, …) to 16-bit signed PCM, which a Clip can always open. */
    private static AudioInputStream toPcm16(AudioInputStream raw) {
        AudioFormat src = raw.getFormat();
        if (src.getEncoding() == AudioFormat.Encoding.PCM_SIGNED && src.getSampleSizeInBits() == 16) return raw;
        AudioFormat target = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, src.getSampleRate(), 16,
            src.getChannels(), src.getChannels() * 2, src.getSampleRate(), false);
        return AudioSystem.isConversionSupported(target, src) ? AudioSystem.getAudioInputStream(target, raw) : raw;
    }

    private static void applyGain(Clip clip, float gain) {
        try {
            if (gain >= 1.0f) return;
            float g = Math.max(0.0f, Math.min(1.0f, gain));
            FloatControl ctl = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            float dB = g <= 0.0001f ? ctl.getMinimum() : (float) (20.0 * Math.log10(g));
            ctl.setValue(Math.max(ctl.getMinimum(), Math.min(ctl.getMaximum(), dB)));
        } catch (Exception ignored) {}
    }

    private static void applyPan(Clip clip, float pan) {
        try {
            if (Math.abs(pan) < 0.01f) return;
            FloatControl ctl = (FloatControl) clip.getControl(FloatControl.Type.PAN);
            ctl.setValue(Math.max(-1.0f, Math.min(1.0f, pan)));
        } catch (Exception ignored) {}
    }
}
