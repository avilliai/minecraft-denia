package xyz.apollodorus.mcgf.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import xyz.apollodorus.mcgf.MCGirlfriendMod;

/**
 * Client -> server: the LLM + TTS connection settings and behavior toggles edited in the in-game config
 * panel, so players never have to hand-edit config/mcgf.json. The server applies these to the live
 * {@link xyz.apollodorus.mcgf.config.GirlfriendConfig} and persists the file (see {@link MCGirlfriendMod}).
 *
 * <p>The record keeps an ergonomic field-per-toggle shape (so the screen + receiver read/write plain
 * booleans), but the wire codec packs all eight booleans into a single VAR_INT bitmask — {@link PacketCodec#tuple}
 * only has overloads up to 12 (codec, getter) pairs, and that ceiling was already reached. Packing keeps us
 * well under it and leaves room for more toggles without touching the wire arity again.
 */
public record ConfigUpdatePayload(
        String llmBaseURL, String llmApiKey, String llmModel,
        boolean ttsEnabled, String ttsUrl, String ttsRefAudioPath, String ttsPromptText,
        boolean autoGatherWood, boolean autoPickup, boolean autoGatherCrops,
        boolean autoStorage, boolean autoLight, boolean autoFish, boolean autoFarm
) implements CustomPayload {

    public static final CustomPayload.Id<ConfigUpdatePayload> ID =
        new CustomPayload.Id<>(Identifier.of(MCGirlfriendMod.MOD_ID, "config_update"));

    public static final PacketCodec<RegistryByteBuf, ConfigUpdatePayload> CODEC = PacketCodec.tuple(
        PacketCodecs.STRING, ConfigUpdatePayload::llmBaseURL,
        PacketCodecs.STRING, ConfigUpdatePayload::llmApiKey,
        PacketCodecs.STRING, ConfigUpdatePayload::llmModel,
        PacketCodecs.STRING, ConfigUpdatePayload::ttsUrl,
        PacketCodecs.STRING, ConfigUpdatePayload::ttsRefAudioPath,
        PacketCodecs.STRING, ConfigUpdatePayload::ttsPromptText,
        PacketCodecs.VAR_INT, ConfigUpdatePayload::flags,
        ConfigUpdatePayload::fromWire
    );

    /** Pack the eight booleans into a bitmask for the wire. */
    private int flags() {
        return (ttsEnabled ? 1 : 0)
            | (autoGatherWood ? 2 : 0)
            | (autoPickup ? 4 : 0)
            | (autoGatherCrops ? 8 : 0)
            | (autoStorage ? 16 : 0)
            | (autoLight ? 32 : 0)
            | (autoFish ? 64 : 0)
            | (autoFarm ? 128 : 0);
    }

    /** Rebuild from the six strings + the packed bitmask. */
    private static ConfigUpdatePayload fromWire(String llmBaseURL, String llmApiKey, String llmModel,
                                                String ttsUrl, String ttsRefAudioPath, String ttsPromptText,
                                                int flags) {
        return new ConfigUpdatePayload(
            llmBaseURL, llmApiKey, llmModel,
            (flags & 1) != 0, ttsUrl, ttsRefAudioPath, ttsPromptText,
            (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0,
            (flags & 16) != 0, (flags & 32) != 0, (flags & 64) != 0, (flags & 128) != 0);
    }

    @Override
    public CustomPayload.Id<ConfigUpdatePayload> getId() {
        return ID;
    }
}
