package xyz.apollodorus.mcgf.screen;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;

/**
 * Opening payload for the companion panel: enough for the client to render the
 * affection bar / intimacy tier / current activity without looking the entity up.
 * Sent by Fabric's {@link net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType}
 * when the owner right-clicks her.
 */
public record CompanionScreenData(int entityId, int affection, String name, String activity, String tasksData) {
    public static final PacketCodec<RegistryByteBuf, CompanionScreenData> CODEC = PacketCodec.tuple(
        PacketCodecs.VAR_INT, CompanionScreenData::entityId,
        PacketCodecs.VAR_INT, CompanionScreenData::affection,
        PacketCodecs.STRING, CompanionScreenData::name,
        PacketCodecs.STRING, CompanionScreenData::activity,
        PacketCodecs.STRING, CompanionScreenData::tasksData,
        CompanionScreenData::new);
}
