package xyz.apollodorus.mcgf.ai;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.net.ClipSoundPayload;
import xyz.apollodorus.mcgf.net.SpeechPayload;

/**
 * Delivers a girlfriend line to players: shown in chat and pushed to clients as
 * a {@link SpeechPayload} so they render a bubble + play voice. Must be called on
 * the server thread.
 */
public final class SpeechBus {
    private SpeechBus() {}

    public static void speak(GirlfriendEntity gf, String text) {
        speak(gf, text, "");
    }

    /**
     * Speak a line, optionally tagged with a bundled voice-clip cue (e.g. "perceive",
     * "defeat1"). When the cue is non-empty the client plays that clip instead of TTS.
     */
    public static void speak(GirlfriendEntity gf, String text, String voiceCue) {
        if (text == null || text.isBlank()) return;
        MinecraftServer server = gf.getEntityWorld().getServer();
        if (server == null) return;

        String name = ConfigManager.get().persona.displayName;
        // She may reply as a burst of short messages separated by "||" — show each as its own chat line
        // (a line without "||" is a single segment, so all other callers are unaffected).
        for (String seg : SpeechPayload.segments(text)) {
            server.getPlayerManager().broadcast(Text.literal("<" + name + "> " + seg), false);
        }

        // The client receives the whole reply with bars intact: it renders the bubbles as a timed
        // burst and synthesizes ONE voice clip from the bar-stripped text, so there's no overlap.
        SpeechPayload payload = new SpeechPayload(gf.getId(), text, voiceCue == null ? "" : voiceCue);
        for (ServerPlayerEntity p : PlayerLookup.tracking(gf)) {
            ServerPlayNetworking.send(p, payload);
        }
        // Make sure the owner hears her even if just outside tracking range.
        if (gf.getOwner() instanceof ServerPlayerEntity owner
            && !PlayerLookup.tracking(gf).contains(owner)) {
            ServerPlayNetworking.send(owner, payload);
        }
    }

    /**
     * Play a bundled WAV clip ({@code assets/mcgf/sounds/<clip>.wav}) positioned at {@code at} for every
     * player near the gf — her custom 形态一 combo sounds, attenuated/panned client-side. Server-thread only.
     */
    public static void playClip(GirlfriendEntity gf, String clip, Vec3d at, float volume) {
        if (clip == null || clip.isBlank() || at == null) return;
        MinecraftServer server = gf.getEntityWorld().getServer();
        if (server == null) return;
        ClipSoundPayload payload = new ClipSoundPayload(clip, at.x, at.y, at.z, volume);
        for (ServerPlayerEntity p : PlayerLookup.tracking(gf)) {
            ServerPlayNetworking.send(p, payload);
        }
        if (gf.getOwner() instanceof ServerPlayerEntity owner && !PlayerLookup.tracking(gf).contains(owner)) {
            ServerPlayNetworking.send(owner, payload);
        }
    }
}
