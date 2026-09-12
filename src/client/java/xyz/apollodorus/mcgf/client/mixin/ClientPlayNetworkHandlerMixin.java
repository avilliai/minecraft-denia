package xyz.apollodorus.mcgf.client.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onScreenHandlerSlotUpdate", at = @At("HEAD"), cancellable = true)
    private void mcgf$guardSlotUpdateBounds(ScreenHandlerSlotUpdateS2CPacket packet, CallbackInfo ci) {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client == null || client.player == null) return;
        int syncId = packet.getSyncId();
        int slot = packet.getSlot();
        if (slot < 0) return;

        net.minecraft.screen.ScreenHandler handler = null;
        if (syncId == 0) {
            handler = client.player.playerScreenHandler;
        } else if (client.player.currentScreenHandler != null && client.player.currentScreenHandler.syncId == syncId) {
            handler = client.player.currentScreenHandler;
        }

        if (handler != null && slot >= handler.slots.size()) {
            ci.cancel();
        }
    }
}