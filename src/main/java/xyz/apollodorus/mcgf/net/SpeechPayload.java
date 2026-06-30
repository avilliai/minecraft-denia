package xyz.apollodorus.mcgf.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import xyz.apollodorus.mcgf.MCGirlfriendMod;

/**
 * Server -> client: a line the girlfriend just "said". The client shows it as a
 * floating bubble above the entity and plays voice for it. When {@code voice} is
 * non-empty it names a bundled clip cue (e.g. "perceive", "defeat1") to play
 * instead of GPT-SoVITS TTS; an empty cue means "synthesize the text via TTS".
 */
public record SpeechPayload(int entityId, String text, String voice) implements CustomPayload {
    public static final CustomPayload.Id<SpeechPayload> ID =
        new CustomPayload.Id<>(Identifier.of(MCGirlfriendMod.MOD_ID, "speech"));

    public static final PacketCodec<RegistryByteBuf, SpeechPayload> CODEC = PacketCodec.tuple(
        PacketCodecs.VAR_INT, SpeechPayload::entityId,
        PacketCodecs.STRING, SpeechPayload::text,
        PacketCodecs.STRING, SpeechPayload::voice,
        SpeechPayload::new
    );

    @Override
    public CustomPayload.Id<SpeechPayload> getId() {
        return ID;
    }

    /**
     * Split a reply on "||" into trimmed, non-blank segments (capped at 5, mirroring the prompt's
     * "最多5条" rule). A line with no "||" yields a single segment (the whole text), so non-burst
     * callers are unaffected. Shared by the server (one chat line per segment) and the client
     * (timed bubble burst).
     */
    public static java.util.List<String> segments(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null) return out;
        for (String part : text.split("\\|\\|")) {
            String s = part.trim();
            if (!s.isEmpty()) out.add(s);
            if (out.size() >= 5) break;
        }
        if (out.isEmpty()) {
            String s = text.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** The reply with "||" separators removed, for synthesizing a single voice clip from the whole reply. */
    public static String stripBars(String text) {
        if (text == null) return "";
        return text.replace("||", " ").replaceAll("\\s+", " ").trim();
    }
}
