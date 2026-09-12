package xyz.apollodorus.mcgf.client.mixin;

import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.collection.DefaultedList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenHandler.class)
public abstract class ScreenHandlerMixin {
    @Shadow @Final public DefaultedList<Slot> slots;

    @Inject(method = "setReceivedStack", at = @At("HEAD"), cancellable = true)
    private void mcgf$safeSetReceivedStack(int slot, ItemStack stack, CallbackInfo ci) {
        if (slot < 0 || slot >= this.slots.size()) {
            ci.cancel();
        }
    }
}