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
}
