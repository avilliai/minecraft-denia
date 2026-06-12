package xyz.apollodorus.mcgf.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import xyz.apollodorus.mcgf.entity.projectile.PathBeaconEntity;

/**
 * 「寻路信标」— thrown like an ender pearl (a gentle lob) but it never teleports the player. Where it
 * lands a beam of light rises and, ~3s later, 达妮娅 is retasked to lead the way to that spot. See
 * {@link PathBeaconEntity}. Stacks; consumed on throw (kept in creative).
 */
public class PathBeaconItem extends Item {
    public PathBeaconItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        world.playSound(user, user.getX(), user.getY(), user.getZ(),
            SoundEvents.ENTITY_ENDER_PEARL_THROW, SoundCategory.PLAYERS, 0.6f, 1.3f);
        if (world instanceof ServerWorld sw) {
            PathBeaconEntity beacon = new PathBeaconEntity(world, user);
            beacon.setVelocity(user, user.getPitch(), user.getYaw(), 0.0f, 1.4f, 1.0f);
            sw.spawnEntity(beacon);
        }
        if (!user.getAbilities().creativeMode) stack.decrement(1);
        return ActionResult.SUCCESS;
    }
}
