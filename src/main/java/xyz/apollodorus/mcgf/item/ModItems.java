package xyz.apollodorus.mcgf.item;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import xyz.apollodorus.mcgf.MCGirlfriendMod;

/** Registers the mod's items: the revive charm, 达妮娅's 形态一 signature weapon (bubble wand), and the 寻路信标. */
public final class ModItems {
    private ModItems() {}

    public static final Item REVIVE_CHARM = register("revive_charm",
        key -> new ReviveCharmItem(new Item.Settings().registryKey(key).maxCount(16)));

    /** 形态一专武「泡泡杖」— purely cosmetic (held in hand); her bubble combo is driven by the attack goal. */
    public static final Item BUBBLE_WAND = register("bubble_wand",
        key -> new Item(new Item.Settings().registryKey(key).maxCount(1).rarity(net.minecraft.util.Rarity.RARE)));

    /** 「寻路信标」— thrown to mark a spot 达妮娅 will lead the way to (ender-pearl-style, no teleport). */
    public static final Item PATH_BEACON = register("path_beacon",
        key -> new PathBeaconItem(new Item.Settings().registryKey(key).maxCount(16)));

    private static Item register(String name, java.util.function.Function<RegistryKey<Item>, Item> factory) {
        RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(MCGirlfriendMod.MOD_ID, name));
        return Registry.register(Registries.ITEM, key, factory.apply(key));
    }

    /** Touch to force class-init (and thus registration) at the right time + add the beacon to a creative tab. */
    public static void register() {
        ItemGroupEvents.modifyEntriesEvent(
                RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.ofVanilla("ingredients")))
            .register(entries -> entries.add(PATH_BEACON));
    }
}

