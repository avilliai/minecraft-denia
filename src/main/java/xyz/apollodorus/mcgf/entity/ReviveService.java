package xyz.apollodorus.mcgf.entity;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import xyz.apollodorus.mcgf.MCGirlfriendMod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds / rebuilds a companion next to her owner for the 重逢符 (bond charm):
 * <ul>
 *   <li>{@link #summonNew} — first use of a fresh charm: spawn + bind a new companion.</li>
 *   <li>{@link #capture} — put a live companion away into the charm (snapshot + discard).</li>
 *   <li>{@link #release} — let a captured-alive companion back out (restores her health too).</li>
 *   <li>{@link #revive} — bring back a dead companion (caller charges the XP cost).</li>
 * </ul>
 * Shared by {@link xyz.apollodorus.mcgf.item.ReviveCharmItem} and the commands.
 */
public final class ReviveService {
    private ReviveService() {}

    // 召唤/收回冷却：避免频繁召唤/收回时每次都触发语音
    private static final Map<UUID, Long> LAST_CAPTURE_TIME = new HashMap<>();
    private static final Map<UUID, Long> LAST_RELEASE_TIME = new HashMap<>();
    private static final long VOICE_COOLDOWN = 200L; // 10秒冷却

    /** Dead → respawn at the owner, full health. Caller has already charged XP + clears nothing. */
    public static GirlfriendEntity revive(ServerWorld world, ServerPlayerEntity owner) {
        DownedManager.Snapshot snap = DownedManager.getDowned(owner.getUuid());
        if (snap == null) return null;
        GirlfriendEntity gf = spawn(world, owner);
        if (gf == null) return null;
        restore(gf, owner, snap, /*restoreHealth=*/ false);
        DownedManager.clearDowned(owner.getUuid());
        // 复活语音多样化（基于游戏内语音风格）
        String[] revivePrompts = {
            "你刚刚倒下、又被玩家用力量带了回来。向他真诚道谢，表达失而复得的感动与依赖。",
            "你从虚无中醒来，发现玩家在身边。有点茫然，然后轻声说：「还好……你还在。」",
            "你睁开眼，看到玩家。有点虚弱但又安心，说：「对不起……让你担心了。」",
            "你复活了，头还有点晕。揉揉眼睛，说：「呼……差点就真的睡过去了。」",
            "你回来了，看着玩家，轻声说：「谢谢……我还能继续陪着你。」",
            "你醒来，有点迷糊：「我……倒下了吗？抱歉，下次会更小心的。」"
        };
        int index = world.getRandom().nextInt(revivePrompts.length);
        speak(gf, revivePrompts[index]);
        return gf;
    }

    /** Captured-alive → respawn at the owner, restoring her exact health. Free. */
    public static GirlfriendEntity release(ServerWorld world, ServerPlayerEntity owner) {
        DownedManager.Snapshot snap = DownedManager.getStored(owner.getUuid());
        if (snap == null) return null;
        GirlfriendEntity gf = spawn(world, owner);
        if (gf == null) return null;
        restore(gf, owner, snap, /*restoreHealth=*/ true);
        DownedManager.clearStored(owner.getUuid());

        // 检查冷却时间：频繁释放时不触发语音
        UUID ownerId = owner.getUuid();
        long now = world.getTime();
        Long lastRelease = LAST_RELEASE_TIME.get(ownerId);
        if (lastRelease == null || now - lastRelease > VOICE_COOLDOWN) {
            speak(gf, "你从符里被放了出来，回到玩家身边，轻松自然地打个招呼、表达陪伴的心情");
            LAST_RELEASE_TIME.put(ownerId, now);
        }
        return gf;
    }

    /** Fresh charm → spawn + bind a brand-new companion (the first-spawn flow). */
    public static GirlfriendEntity summonNew(ServerWorld world, ServerPlayerEntity owner) {
        GirlfriendEntity gf = spawn(world, owner);
        if (gf == null) return null;
        gf.setOwnerUuid(owner.getUuid());
        gf.setFollowing(true);
        // 第一次召唤总是触发语音（重要时刻）
        speak(gf, "你第一次被玩家召唤到这个世界、来到他身边，温柔地做个自我介绍、表达愿意一直陪着他");
        return gf;
    }

    /** Live companion → fold her state into the charm and remove the entity. */
    public static boolean capture(ServerPlayerEntity owner, GirlfriendEntity gf) {
        DownedManager.setStored(owner.getUuid(), snapshotOf(gf));

        // 检查冷却时间：频繁收回时不触发语音
        UUID ownerId = owner.getUuid();
        long now = gf.getEntityWorld().getTime();
        Long lastCapture = LAST_CAPTURE_TIME.get(ownerId);
        if (lastCapture == null || now - lastCapture > VOICE_COOLDOWN) {
            speak(gf, "你被玩家收进符里了，温柔地说一句简短的话（比如'那我先歇一会儿咯~'），然后消失");
            LAST_CAPTURE_TIME.put(ownerId, now);
        }

        gf.discard();
        return true;
    }

    private static GirlfriendEntity spawn(ServerWorld world, ServerPlayerEntity owner) {
        GirlfriendEntity gf = GirlfriendEntities.GIRLFRIEND.spawn(world, owner.getBlockPos(), SpawnReason.COMMAND);
        if (gf != null) {
            // 重逢符唤出/复活的她永远是甜美一形态：清掉残留的二形态/能量/浮空，免得"二形态死亡后唤出还是二形态"。
            gf.setFormTwo(false);
            gf.setNoGravity(false);
            gf.setEnergy(0);
        }
        return gf;
    }


    private static String itemToString(ItemStack stack) {
        return net.minecraft.registry.Registries.ITEM.getId(stack.getItem()) + " " + stack.getCount();
    }

    private static ItemStack itemFromString(String data) {
        String[] kv = data.split(" ");
        if (kv.length != 2) return ItemStack.EMPTY;
        net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM
            .get(net.minecraft.util.Identifier.tryParse(kv[0]));
        if (item == net.minecraft.item.Items.AIR) return ItemStack.EMPTY;
        try {
            return new ItemStack(item, Integer.parseInt(kv[1]));
        } catch (NumberFormatException e) {
            return ItemStack.EMPTY;
        }
    }
    /** Snapshot a live companion (folding her worn gear into the backpack so it travels along). */
    private static DownedManager.Snapshot snapshotOf(GirlfriendEntity gf) {
        DownedManager.Snapshot snap = new DownedManager.Snapshot();
        // 单独保存装备栏，不动背包
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack worn = gf.getEquippedStack(slot);
            if (!worn.isEmpty()) {
                snap.equipment.put(slot.getName(), itemToString(worn));
            }
        }
        snap.inventory = gf.exportInventory();
        snap.affection = gf.getAffection();
        snap.health = gf.getHealth();
        snap.x = gf.getX(); snap.y = gf.getY(); snap.z = gf.getZ();
        BlockPos home = gf.getHomePos();
        if (home != null) {
            snap.hasHome = true;
            snap.homeX = home.getX(); snap.homeY = home.getY(); snap.homeZ = home.getZ();
        }
        return snap;
    }

    private static void restore(GirlfriendEntity gf, ServerPlayerEntity owner,
                                DownedManager.Snapshot snap, boolean restoreHealth) {
        gf.setOwnerUuid(owner.getUuid());
        gf.setFollowing(true);
        gf.importInventory(snap.inventory);
        gf.setAffection(snap.affection);
        if (snap.hasHome) gf.setHomePos(new BlockPos(snap.homeX, snap.homeY, snap.homeZ));
        if (restoreHealth && snap.health > 0.0) {
            gf.setHealth((float) Math.min(snap.health, gf.getMaxHealth()));
        }
        // 还原装备栏
        if (snap.equipment != null) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                String data = snap.equipment.get(slot.getName());
                if (data != null) {
                    gf.equipStack(slot, itemFromString(data));
                }
            }
        }
    }

    private static void speak(GirlfriendEntity gf, String situation) {
        if (MCGirlfriendMod.BRAIN != null) MCGirlfriendMod.BRAIN.proactive(gf, situation);
    }
}
