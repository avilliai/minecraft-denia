package xyz.apollodorus.mcgf.client.render;

import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Arm;
import net.minecraft.util.Identifier;
import xyz.apollodorus.mcgf.client.MCGirlfriendClient;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

/**
 * Renders ??? as a slim humanoid via a BIPED model + our own {@link GirlfriendRenderState}.
 */
public class GirlfriendEntityRenderer
        extends BipedEntityRenderer<GirlfriendEntity, GirlfriendRenderState, GirlfriendEntityModel> {

    public GirlfriendEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new GirlfriendEntityModel(ctx.getPart(ModEntityModelLayers.GIRLFRIEND)), 0.5f);
    }

    @Override
    public GirlfriendRenderState createRenderState() {
        return new GirlfriendRenderState();
    }

    @Override
    public void updateRenderState(GirlfriendEntity entity, GirlfriendRenderState state, float tickDelta) {
        super.updateRenderState(entity, state, tickDelta);
        state.formTwo = entity.isFormTwo();
    }

    @Override
    protected BipedEntityModel.ArmPose getArmPose(GirlfriendEntity entity, Arm arm) {
        ItemStack stack = entity.getStackInArm(arm);
        if (!stack.isEmpty()) {
            if (stack.isOf(Items.FISHING_ROD)) {
                return BipedEntityModel.ArmPose.ITEM;
            }
            if (stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW)) {
                if (entity.isAttacking()) {
                    return stack.isOf(Items.CROSSBOW) ? BipedEntityModel.ArmPose.CROSSBOW_HOLD : BipedEntityModel.ArmPose.BOW_AND_ARROW;
                }
            }
        }
        return super.getArmPose(entity, arm);
    }

    @Override
    public Identifier getTexture(GirlfriendRenderState state) {
        return state.formTwo ? MCGirlfriendClient.SKIN2 : MCGirlfriendClient.SKIN;
    }
}
