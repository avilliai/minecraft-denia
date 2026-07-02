package xyz.apollodorus.mcgf.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import xyz.apollodorus.mcgf.MCGirlfriendMod;

/**
 * Server -> client: play a bundled WAV clip ({@code assets/mcgf/sounds/<clip>.wav}) positioned at
 * {@code (x,y,z)}, attenuated (and panned) by the listener's distance/angle on the client.
 *
 * <p>Used for 达妮娅's custom 形态一 combo-hit sounds. The vanilla sound engine only plays OGG, but her
 * clips are WAV — so, like the bundled voice clips, they go through the mod's own client audio path
 * ({@link xyz.apollodorus.mcgf.client.tts.AudioPlayback}). The position lets hits 3a/4a sound like they
 * come from the target rather than from her.
 */
public record ClipSoundPayload(String clip, double x, double y, double z, float volume) implements CustomPayload {
    public static final CustomPayload.Id<ClipSoundPayload> ID =
        new CustomPayload.Id<>(Identifier.of(MCGirlfriendMod.MOD_ID, "clip_sound"));

    public static final PacketCodec<RegistryByteBuf, ClipSoundPayload> CODEC = PacketCodec.tuple(
        PacketCodecs.STRING, ClipSoundPayload::clip,
        PacketCodecs.DOUBLE, ClipSoundPayload::x,
        PacketCodecs.DOUBLE, ClipSoundPayload::y,
        PacketCodecs.DOUBLE, ClipSoundPayload::z,
        PacketCodecs.FLOAT, ClipSoundPayload::volume,
        ClipSoundPayload::new
    );

    @Override
    public CustomPayload.Id<ClipSoundPayload> getId() {
        return ID;
    }
}
