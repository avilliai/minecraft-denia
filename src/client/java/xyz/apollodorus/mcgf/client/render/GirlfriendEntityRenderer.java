package xyz.apollodorus.mcgf.client.render;

import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.util.Identifier;
import xyz.apollodorus.mcgf.client.MCGirlfriendClient;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

/**
 * Renders 达妮娅 as a slim humanoid via a BIPED model + our own {@link GirlfriendRenderState}.
 *
 * <p>This deliberately does NOT use {@code PlayerEntityModel}/{@code PlayerEntityRenderState}:
 * that path let the vanilla player-skin pipeline replace her texture with Steve (reproduced
 * even with ETF/EMF disabled). Extending {@link BipedEntityRenderer} still gives held-item
 * rendering for free, and {@link #getTexture} points at her (config-overridable) skin, which
 * now binds because she is no longer treated as a player.
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
    public Identifier getTexture(GirlfriendRenderState state) {
        return state.formTwo ? MCGirlfriendClient.SKIN2 : MCGirlfriendClient.SKIN;
    }
}
