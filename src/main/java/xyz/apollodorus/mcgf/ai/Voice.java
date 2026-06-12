package xyz.apollodorus.mcgf.ai;

import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

/**
 * Server-side gate for the bundled 达妮娅 voice clips (assets/mcgf/voice/*.wav). The clips are her
 * recorded voice, so they only play when the active persona IS 达妮娅 and canned voice is enabled —
 * any other persona always falls through to an AI-generated line. Even for 达妮娅 there's only a
 * ~50% chance she uses a clip in a matching situation, so it stays varied (the rest goes to the LLM).
 *
 * <p>Each {@code maybeX} speaks a line tagged with the clip cue and returns true when it fired; a
 * false return means the caller should produce its normal AI/text line instead. Call on the server thread.
 */
public final class Voice {
    private Voice() {}

    public static final String DANIYA = "达妮娅";

    private static final String[] PERCEIVE_LINES = {
        "唔…那边，好像有个箱子哦~",
        "我感觉到了…附近藏着东西呢。"
    };
    private static final String[] DEFEAT_LINES = {
        "唔…这次…是我输了呀…",
        "呼…力气，用光了…抱歉…",
        "对不起…我撑不住了…"
    };

    /** Are the 达妮娅 clips currently usable (right persona + enabled)? */
    public static boolean clipsActive() {
        var cfg = ConfigManager.get();
        return cfg.tts.enabled && cfg.tts.cannedVoiceEnabled && DANIYA.equals(cfg.persona.displayName);
    }

    /** ~50% chance to play the chest-perception clip. Returns true if it spoke. */
    public static boolean maybePerceive(GirlfriendEntity gf) {
        if (!clipsActive() || gf.getRandom().nextDouble() >= 0.5) return false;
        String line = PERCEIVE_LINES[gf.getRandom().nextInt(PERCEIVE_LINES.length)];
        SpeechBus.speak(gf, line, "perceive");
        return true;
    }

    /** ~50% chance to play a random defeat/exhaustion clip (defeat1..defeat3). Returns true if it spoke. */
    public static boolean maybeDefeat(GirlfriendEntity gf) {
        if (!clipsActive() || gf.getRandom().nextDouble() >= 0.5) return false;
        int n = gf.getRandom().nextInt(3) + 1;
        String line = DEFEAT_LINES[Math.min(n - 1, DEFEAT_LINES.length - 1)];
        SpeechBus.speak(gf, line, "defeat" + n);
        return true;
    }
}
