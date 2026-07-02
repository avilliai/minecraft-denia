package xyz.apollodorus.mcgf.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/** Registers the custom payload types shared by client and server. */
public final class ModNetworking {
    private ModNetworking() {}

    public static void registerCommon() {
        PayloadTypeRegistry.playS2C().register(SpeechPayload.ID, SpeechPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ClipSoundPayload.ID, ClipSoundPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ConfigUpdatePayload.ID, ConfigUpdatePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(TaskCancelPayload.ID, TaskCancelPayload.CODEC);
    }
}
