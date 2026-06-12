package xyz.apollodorus.mcgf.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.entity.DownedManager;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.entity.ReviveService;

import java.util.UUID;

/**
 * 「重逢符」— the bond device, one per player. A single right-click does the right
 * thing for the player's current bond state:
 * <ol>
 *   <li>her companion is alive &amp; loaded → <b>capture</b> her into the charm;</li>
 *   <li>she's stored in the charm → <b>release</b> her (free);</li>
 *   <li>she's dead → <b>revive</b> her for an escalating XP cost;</li>
 *   <li>no companion at all → <b>summon + bind</b> a new one (the first-spawn flow).</li>
 * </ol>
 * The charm itself is never consumed — it's a reusable personal device.
 */
public class ReviveCharmItem extends Item {
    public ReviveCharmItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult use(World world, PlayerEntity player, Hand hand) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity sp)) return ActionResult.SUCCESS;
        ServerWorld sw = (ServerWorld) world;
        UUID id = sp.getUuid();

        // 1) A live companion of mine is loaded → tuck her into the charm.
        GirlfriendEntity live = findOwned(sp);
        if (live != null) {
            ReviveService.capture(sp, live);
            sp.sendMessage(Text.literal("把达妮娅收进符里了，路上当心~"), true);
            return ActionResult.SUCCESS;
        }

        // 2) She's stored in the charm → let her back out.
        if (DownedManager.isStored(id)) {
            GirlfriendEntity gf = ReviveService.release(sw, sp);
            if (gf == null) return ActionResult.FAIL;
            sp.sendMessage(Text.literal("达妮娅回到了你身边。"), true);
            return ActionResult.SUCCESS;
        }

        // 3) She's dead → revive her (XP cost grows each time).
        if (DownedManager.isDowned(id)) {
            int cost = ConfigManager.get().behavior.reviveBaseCost + DownedManager.getReviveCount(id);
            if (sp.experienceLevel < cost) {
                sp.sendMessage(Text.literal("经验不够呢，需要 " + cost + " 级。"), true);
                return ActionResult.FAIL;
            }
            sp.addExperienceLevels(-cost);
            GirlfriendEntity gf = ReviveService.revive(sw, sp);
            if (gf == null) {
                sp.addExperienceLevels(cost); // refund on failure
                return ActionResult.FAIL;
            }
            DownedManager.incrementReviveCount(id);
            return ActionResult.SUCCESS;
        }

        // 4) No companion anywhere → summon + bind a new one.
        GirlfriendEntity gf = ReviveService.summonNew(sw, sp);
        if (gf == null) return ActionResult.FAIL;
        sp.sendMessage(Text.literal("你呼唤出了达妮娅，她从此与你同行。"), true);
        return ActionResult.SUCCESS;
    }

    /** Her nearest loaded, living companion owned by this player in the same world, or null. */
    private static GirlfriendEntity findOwned(ServerPlayerEntity sp) {
        GirlfriendEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (GirlfriendEntity gf : GirlfriendEntity.ACTIVE) {
            if (gf.isRemoved() || !gf.isAlive()) continue;
            if (!gf.isOwner(sp) || gf.getEntityWorld() != sp.getEntityWorld()) continue;
            double d = gf.squaredDistanceTo(sp);
            if (d < bestSq) { bestSq = d; best = gf; }
        }
        return best;
    }
}
