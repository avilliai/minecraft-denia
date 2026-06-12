package xyz.apollodorus.mcgf.client.render;

import net.minecraft.client.render.entity.state.BipedEntityRenderState;

/** Render state for the girlfriend; carries the current speech-bubble text + her active form. */
public class GirlfriendRenderState extends BipedEntityRenderState {
    public String bubble;
    public boolean formTwo;   // true → render her 形态二 (蚀域) skin
}
