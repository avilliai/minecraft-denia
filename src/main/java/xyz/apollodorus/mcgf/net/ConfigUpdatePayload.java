package xyz.apollodorus.mcgf.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import xyz.apollodorus.mcgf.MCGirlfriendMod;

/**
 * Client -> server: the LLM + TTS connection settings edited in the in-game config panel, so players
 * never have to hand-edit config/mcgf.json. The server applies these to the live
 * {@link xyz.apollodorus.mcgf.config.GirlfriendConfig} and persists the file (see
 * {@link MCGirlfriendMod}). Only the connection-critical fields are carried; the rest of the config
 * stays file-only.
 */
public record ConfigUpdatePayload(
        String llmBaseURL, String llmApiKey, String llmModel,
        boolean ttsEnabled, String ttsUrl, String ttsRefAudioPath, String ttsPromptText,
        boolean autoGatherWood, boolean autoPickup
) implements CustomPayload {

    public static final CustomPayload.Id<ConfigUpdatePayload> ID =
        new CustomPayload.Id<>(Identifier.of(MCGirlfriendMod.MOD_ID, "config_update"));

    public static final PacketCodec<RegistryByteBuf, ConfigUpdatePayload> CODEC = PacketCodec.tuple(
        PacketCodecs.STRING, ConfigUpdatePayload::llmBaseURL,
        PacketCodecs.STRING, ConfigUpdatePayload::llmApiKey,
        PacketCodecs.STRING, ConfigUpdatePayload::llmModel,
        PacketCodecs.BOOLEAN, ConfigUpdatePayload::ttsEnabled,
        PacketCodecs.STRING, ConfigUpdatePayload::ttsUrl,
        PacketCodecs.STRING, ConfigUpdatePayload::ttsRefAudioPath,
        PacketCodecs.STRING, ConfigUpdatePayload::ttsPromptText,
        PacketCodecs.BOOLEAN, ConfigUpdatePayload::autoGatherWood,
        PacketCodecs.BOOLEAN, ConfigUpdatePayload::autoPickup,
        ConfigUpdatePayload::new
    );

    @Override
    public CustomPayload.Id<ConfigUpdatePayload> getId() {
        return ID;
    }
}
