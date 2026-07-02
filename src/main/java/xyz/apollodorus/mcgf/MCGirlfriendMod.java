package xyz.apollodorus.mcgf;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.apollodorus.mcgf.ai.ChatBrain;
import xyz.apollodorus.mcgf.ai.MoodManager;
import xyz.apollodorus.mcgf.combat.AbilityManager;
import xyz.apollodorus.mcgf.command.GfCommands;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;
import xyz.apollodorus.mcgf.entity.GirlfriendEntities;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.net.ConfigUpdatePayload;
import xyz.apollodorus.mcgf.net.ModNetworking;
import xyz.apollodorus.mcgf.net.TaskCancelPayload;
import xyz.apollodorus.mcgf.util.GfFinder;

/** Common entrypoint: config, entity, networking, commands, chat routing. */
public class MCGirlfriendMod implements ModInitializer {
    public static final String MOD_ID = "mcgf";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static ChatBrain BRAIN;

    @Override
    public void onInitialize() {
        ConfigManager.load();
        GirlfriendEntities.register();
        xyz.apollodorus.mcgf.block.ModBlocks.register();
        xyz.apollodorus.mcgf.item.ModItems.register();
        xyz.apollodorus.mcgf.screen.ModScreens.register();
        xyz.apollodorus.mcgf.entity.DownedManager.load();
        ModNetworking.registerCommon();
        BRAIN = new ChatBrain();

        GfCommands.register();

        // In-game config panel (C2S): apply the LLM/TTS connection settings the owner edited in her
        // panel, then persist them — so players never have to hand-edit config/mcgf.json. The handler
        // runs on the server thread; LlmClient/TtsClient read ConfigManager live, so it takes effect at once.
        ServerPlayNetworking.registerGlobalReceiver(ConfigUpdatePayload.ID, (payload, context) -> {
            GirlfriendConfig cfg = ConfigManager.get();
            cfg.llm.baseURL = payload.llmBaseURL().trim();
            cfg.llm.apiKey = payload.llmApiKey().trim();
            cfg.llm.model = payload.llmModel().trim();
            cfg.tts.enabled = payload.ttsEnabled();
            cfg.tts.url = payload.ttsUrl().trim();
            cfg.tts.refAudioPath = payload.ttsRefAudioPath();
            cfg.tts.promptText = payload.ttsPromptText();
            cfg.behavior.autoGatherWood = payload.autoGatherWood();
            cfg.behavior.autoPickup = payload.autoPickup();
            cfg.behavior.autoGatherCrops = payload.autoGatherCrops();
            cfg.behavior.autoStorage = payload.autoStorage();
            cfg.behavior.autoLight = payload.autoLight();
            cfg.behavior.autoFish = payload.autoFish();
            cfg.behavior.autoFarm = payload.autoFarm();
            ConfigManager.save(cfg);
            context.player().sendMessage(Text.literal("「" + cfg.persona.displayName + "」的接口配置已保存~"), false);
        });

        // Cancel a queued job from the panel's task list (C2S); pinned to the panel's companion by id.
        ServerPlayNetworking.registerGlobalReceiver(TaskCancelPayload.ID, (payload, context) -> {
            if (context.player().getEntityWorld().getEntityById(payload.entityId()) instanceof GirlfriendEntity gf
                && gf.isOwner(context.player())) {
                gf.cancelTask(payload.index());
            }
        });

        // Route a nearby owner's normal chat to the LLM brain.
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String text = message.getContent().getString();
            if (text == null || text.isBlank() || text.startsWith("!") || text.startsWith("/")) return;
            handlePlayerChat(sender, text);
        });

        // Drive proactive mood / environment awareness once the server is ticking.
        ServerTickEvents.END_SERVER_TICK.register(MoodManager::onServerTick);
        // Drive 达妮娅's world-anchored abilities (蚀域 domain, transient bridge/pillar blocks, black hole).
        ServerTickEvents.END_SERVER_TICK.register(AbilityManager::onServerTick);

        // When the owner attacks a mob, nearby girlfriends pile on (incl. neutral/passive ones).
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient() || !ConfigManager.get().behavior.assistOwnerAttacks) return ActionResult.PASS;
            if (!(entity instanceof LivingEntity living) || entity instanceof PlayerEntity) return ActionResult.PASS;
            double reach = ConfigManager.get().behavior.guardRadius + 8.0;
            for (GirlfriendEntity gf : GirlfriendEntity.ACTIVE) {
                if (gf.isRemoved() || !gf.isAlive() || !gf.isCombatEnabled()) continue;
                if (!gf.isOwner(player) || gf.getEntityWorld() != world || gf == entity) continue;
                if (gf.squaredDistanceTo(player) <= reach * reach) gf.markAssistTarget(living);
            }
            return ActionResult.PASS;
        });

        // 玩家亲自打开（右键）某个箱子后，她就不再感知/带路去那个箱子了——别再老催着去看一个你已经翻过的箱子。
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient() || hand != Hand.MAIN_HAND || hitResult == null) return ActionResult.PASS;
            BlockPos pos = hitResult.getBlockPos();
            BlockState st = world.getBlockState(pos);
            if (!isContainerBlock(st)) return ActionResult.PASS;
            for (GirlfriendEntity gf : GirlfriendEntity.ACTIVE) {
                if (gf.isRemoved() || !gf.isOwner(player) || gf.getEntityWorld() != world) continue;
                gf.ignoreChest(pos);
                for (Direction d : Direction.Type.HORIZONTAL) {   // 大箱子的另一半也一并忽略
                    BlockPos n = pos.offset(d);
                    if (world.getBlockState(n).isOf(st.getBlock())) gf.ignoreChest(n);
                }
            }
            return ActionResult.PASS;
        });

        // When the OWNER takes a hit, every nearby companion of his drops what it's doing and
        // rushes to his side (top-priority RushToOwnerGoal); a living attacker is also marked so
        // she punishes it once she arrives. AFTER_DAMAGE only fires server-side for real damage.
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, takenDamage, blocked) -> {
            if (takenDamage <= 0.0f || !(entity instanceof ServerPlayerEntity player)) return;
            LivingEntity attacker = source.getAttacker() instanceof LivingEntity le ? le : null;
            for (GirlfriendEntity gf : GirlfriendEntity.ACTIVE) {
                if (gf.isRemoved() || !gf.isAlive()) continue;
                if (!gf.isOwner(player) || gf.getEntityWorld() != player.getEntityWorld()) continue;
                if (gf.squaredDistanceTo(player) <= 32.0 * 32.0) gf.markOwnerAttacked(attacker);
            }
        });

        // Make sure every player always holds their 重逢符 (bond charm) — it's the only way to
        // summon + bind her, so a player without one would be locked out. Give one on join if they
        // have none. This keys off the player's own inventory (which is per-save) instead of a global
        // "given once" flag, so it reliably fires for a brand-new player AND on first entry into a
        // freshly-created world — the old global flag skipped the gift in every world after the first.
        // Deferred one tick via server.execute so it lands cleanly after the join/spawn sequence.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!ConfigManager.get().behavior.giveCharmOnFirstJoin) return;
            ServerPlayerEntity sp = handler.player;
            server.execute(() -> {
                if (!sp.isAlive() || sp.isRemoved()) return;
                if (!playerHasItem(sp, xyz.apollodorus.mcgf.item.ModItems.REVIVE_CHARM)) {
                    sp.giveItemStack(new ItemStack(xyz.apollodorus.mcgf.item.ModItems.REVIVE_CHARM));
                }
                // 寻路信标也在出生时给予；之后玩家可在达妮娅背包的专属信标槽里无限拿取。
                if (!playerHasItem(sp, xyz.apollodorus.mcgf.item.ModItems.PATH_BEACON)) {
                    sp.giveItemStack(new ItemStack(xyz.apollodorus.mcgf.item.ModItems.PATH_BEACON));
                }
            });
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (BRAIN != null) BRAIN.shutdown();
            AbilityManager.flushAll();
            xyz.apollodorus.mcgf.entity.GirlfriendEntity.ACTIVE.clear();
        });

        LOGGER.info("[mcgf] Minecraft Girlfriend initialized.");
    }

    private static void handlePlayerChat(ServerPlayerEntity sender, String text) {
        double radius = ConfigManager.get().behavior.chatRadius;
        GirlfriendEntity gf = GfFinder.nearest(sender, radius);
        if (gf == null) return;
        // Claim an unowned girlfriend the first time the player talks to her.
        if (gf.getOwnerUuid() == null) gf.setOwnerUuid(sender.getUuid());
        if (BRAIN != null) BRAIN.handleChat(sender, gf, text);
    }

    /** True if the block is a chest/barrel she perceives — used to stop perceiving one the player opened. */
    private static boolean isContainerBlock(BlockState st) {
        return st.isOf(Blocks.CHEST) || st.isOf(Blocks.TRAPPED_CHEST) || st.isOf(Blocks.BARREL);
    }

    /** True if the player already holds {@code item} anywhere in their inventory. */
    private static boolean playerHasItem(ServerPlayerEntity sp, net.minecraft.item.Item item) {
        var inv = sp.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isOf(item)) return true;
        }
        return false;
    }
}
