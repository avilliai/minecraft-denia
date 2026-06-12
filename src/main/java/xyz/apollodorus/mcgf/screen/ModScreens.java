package xyz.apollodorus.mcgf.screen;

import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import xyz.apollodorus.mcgf.MCGirlfriendMod;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

/** Registers the companion panel's screen-handler type and opens it for the owner. */
public final class ModScreens {
    private ModScreens() {}

    public static final ScreenHandlerType<CompanionScreenHandler> COMPANION =
        new ExtendedScreenHandlerType<CompanionScreenHandler, CompanionScreenData>(
            CompanionScreenHandler::new, CompanionScreenData.CODEC);

    public static void register() {
        Registry.register(Registries.SCREEN_HANDLER,
            Identifier.of(MCGirlfriendMod.MOD_ID, "companion"), COMPANION);
    }

    /** Open her panel for the owner, sending affection / activity for the header. */
    public static void open(ServerPlayerEntity player, GirlfriendEntity gf) {
        String name = ConfigManager.get().persona.displayName;
        player.openHandledScreen(new ExtendedScreenHandlerFactory<CompanionScreenData>() {
            @Override
            public CompanionScreenData getScreenOpeningData(ServerPlayerEntity p) {
                return new CompanionScreenData(gf.getId(), gf.getAffection(), name, gf.getActivity(),
                    String.join("\n", gf.taskLabels()));
            }

            @Override
            public Text getDisplayName() { return Text.literal(name); }

            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity p) {
                return new CompanionScreenHandler(syncId, inv, new CompanionInventory(gf), getScreenOpeningData(player));
            }
        });
    }
}
