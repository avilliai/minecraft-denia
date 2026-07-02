package xyz.apollodorus.mcgf.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.apollodorus.mcgf.MCGirlfriendMod;
import xyz.apollodorus.mcgf.client.render.GirlfriendEntityModel;
import xyz.apollodorus.mcgf.client.render.GirlfriendEntityRenderer;
import xyz.apollodorus.mcgf.client.render.ModEntityModelLayers;
import xyz.apollodorus.mcgf.client.tts.ClipSounds;
import xyz.apollodorus.mcgf.client.tts.TtsClient;
import xyz.apollodorus.mcgf.client.tts.VoiceClips;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.entity.GirlfriendEntities;
import xyz.apollodorus.mcgf.net.ClipSoundPayload;
import xyz.apollodorus.mcgf.net.SpeechPayload;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Client entrypoint: entity rendering + speech bubble + TTS playback. */
public class MCGirlfriendClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcgf/client");

    private static final Identifier DEFAULT_SKIN =
        Identifier.of(MCGirlfriendMod.MOD_ID, "textures/entity/girlfriend.png");

    /** Resolved at startup from {@code persona.skinTexture}; see {@link #resolveSkin()}. */
    public static Identifier SKIN = DEFAULT_SKIN;

    /** 形态二 (蚀域/幻灭) skin — swapped in by the renderer while her domain is active. */
    public static Identifier SKIN2 =
        Identifier.of(MCGirlfriendMod.MOD_ID, "textures/entity/girlfriend2.png");

    @Override
    public void onInitializeClient() {
        SKIN = resolveSkin();

        // 虚质方块：半透明壳 + 发光核心 → 用 TRANSLUCENT 渲染层，才能透出内部核心与背后景物。
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlock(
            xyz.apollodorus.mcgf.block.ModBlocks.VOID_BLOCK,
            net.minecraft.client.render.BlockRenderLayer.TRANSLUCENT);

        EntityModelLayerRegistry.registerModelLayer(
            ModEntityModelLayers.GIRLFRIEND, GirlfriendEntityModel::getTexturedModelData);
        EntityRendererRegistry.register(GirlfriendEntities.GIRLFRIEND, GirlfriendEntityRenderer::new);
        // 达妮娅's thrown void shard renders as the flying 虚质方块 item (no custom render code needed).
        EntityRendererRegistry.register(GirlfriendEntities.VOID_SHARD,
            net.minecraft.client.render.entity.FlyingItemEntityRenderer::new);
        // 寻路信标 renders as the flying 寻路信标 item.
        EntityRendererRegistry.register(GirlfriendEntities.PATH_BEACON,
            net.minecraft.client.render.entity.FlyingItemEntityRenderer::new);

        xyz.apollodorus.mcgf.client.screen.CompanionScreen.register();

        ClientPlayNetworking.registerGlobalReceiver(SpeechPayload.ID, (payload, context) ->
            context.client().execute(() -> {
                SpeechBubbleManager.show(payload.entityId(), payload.text());   // renders "||" as a burst
                // A named cue plays the bundled 达妮娅 clip; otherwise synthesize the text via TTS —
                // one clip for the whole reply, with the "||" message separators stripped out.
                if (payload.voice() != null && !payload.voice().isBlank()) {
                    VoiceClips.play(payload.voice());
                } else {
                    TtsClient.speak(SpeechPayload.stripBars(payload.text()));
                }
            }));

        // 达妮娅的形态一连招音效（自带 wav，走 mod 自己的音频通道）：按声源到玩家的距离衰减音量、按方位左右声像，
        // 让 3a/4a 听起来是从"目标那边"传来的。
        ClientPlayNetworking.registerGlobalReceiver(ClipSoundPayload.ID, (payload, context) ->
            context.client().execute(() -> {
                ClientPlayerEntity p = context.client().player;
                if (p == null) return;
                double dx = payload.x() - p.getX();
                double dy = payload.y() - p.getEyeY();
                double dz = payload.z() - p.getZ();
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                float gain = (float) Math.max(0.12, 1.0 - dist / 24.0);   // 24 格外基本听不到
                ClipSounds.play(payload.clip(), gain * payload.volume(), pan(p.getYaw(), dx, dz));
            }));
    }

    /** Stereo pan (-1 left .. +1 right) of a source direction relative to where the player is facing. */
    private static float pan(float yawDeg, double dx, double dz) {
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 0.01) return 0f;
        double yaw = Math.toRadians(yawDeg);
        double rx = Math.cos(yaw), rz = Math.sin(yaw);   // player's right vector in world XZ
        return (float) Math.max(-1.0, Math.min(1.0, (dx * rx + dz * rz) / horiz));
    }

    /**
     * Skin selection from config: empty → built-in; a "namespace:path" resource id
     * → that resource; otherwise an on-disk .png path → loaded as a dynamic texture.
     * Any failure falls back to the built-in skin.
     */
    private static Identifier resolveSkin() {
        String s = ConfigManager.get().persona.skinTexture;
        if (s == null || s.isBlank()) return DEFAULT_SKIN;
        try {
            Path file = Path.of(s);
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    NativeImage image = NativeImage.read(in);
                    NativeImageBackedTexture tex = new NativeImageBackedTexture(() -> "mcgf-skin", image);
                    Identifier id = Identifier.of(MCGirlfriendMod.MOD_ID, "dynamic_skin");
                    MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
                    LOGGER.info("[mcgf] loaded custom skin from {}", file);
                    return id;
                }
            }
            Identifier id = Identifier.tryParse(s);
            if (id != null) return id;
        } catch (Exception e) {
            LOGGER.warn("[mcgf] failed to load skin '{}', using default: {}", s, e.toString());
        }
        return DEFAULT_SKIN;
    }
}
