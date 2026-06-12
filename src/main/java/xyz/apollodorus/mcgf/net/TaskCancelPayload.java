package xyz.apollodorus.mcgf.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import xyz.apollodorus.mcgf.MCGirlfriendMod;

/**
 * Client -> server: cancel the queued job at {@code index} of the girlfriend {@code entityId}
 * (the panel "取消" button). The entity id pins it to the companion whose panel was open, so it
 * works even with more than one companion around.
 */
public record TaskCancelPayload(int entityId, int index) implements CustomPayload {

    public static final CustomPayload.Id<TaskCancelPayload> ID =
        new CustomPayload.Id<>(Identifier.of(MCGirlfriendMod.MOD_ID, "task_cancel"));

    public static final PacketCodec<RegistryByteBuf, TaskCancelPayload> CODEC = PacketCodec.tuple(
        PacketCodecs.VAR_INT, TaskCancelPayload::entityId,
        PacketCodecs.VAR_INT, TaskCancelPayload::index,
        TaskCancelPayload::new
    );

    @Override
    public CustomPayload.Id<TaskCancelPayload> getId() {
        return ID;
    }
}
