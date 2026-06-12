package xyz.apollodorus.mcgf.client.render;

import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import xyz.apollodorus.mcgf.MCGirlfriendMod;

public final class ModEntityModelLayers {
    public static final EntityModelLayer GIRLFRIEND =
        new EntityModelLayer(Identifier.of(MCGirlfriendMod.MOD_ID, "girlfriend"), "main");

    private ModEntityModelLayers() {}
}
