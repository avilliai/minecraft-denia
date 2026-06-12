package xyz.apollodorus.mcgf.block;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import xyz.apollodorus.mcgf.MCGirlfriendMod;

/**
 * The mod's blocks — currently just 「虚质方块」(void_block), the all-purpose "virtual matter"
 * 达妮娅 conjures. It is what she bridges/pillars with while pathing, what tiles the floor + sky
 * of her 蚀域 domain, and what her thrown void shards / sky meteors are made of.
 *
 * <p>Registration mirrors {@link xyz.apollodorus.mcgf.item.ModItems}: the static field triggers
 * class-init registration of the block + its {@link BlockItem}; {@link #register()} both forces that
 * init and adds the item to the vanilla Building Blocks creative tab. The block is a glowing, opaque
 * full cube (so it's a valid bridge/floor block) with a soft, muffled wool-like sound — she conjures
 * and dismisses these constantly (bridging, the domain floor, sky decor), so a loud crystalline chime
 * would be grating; a quiet group keeps the void matter feeling ethereal.
 */
public final class ModBlocks {
    private ModBlocks() {}

    public static final Block VOID_BLOCK = registerBlock("void_block",
        AbstractBlock.Settings.create()
            .mapColor(MapColor.PURPLE)
            .strength(1.5f, 6.0f)
            .sounds(BlockSoundGroup.WOOL)
            .nonOpaque()                  // 半透明壳：让相邻方块/内部核心透出来，并允许背面渲染
            .luminance(state -> 12));

    private static Block registerBlock(String name, AbstractBlock.Settings settings) {
        Identifier id = Identifier.of(MCGirlfriendMod.MOD_ID, name);
        RegistryKey<Block> blockKey = RegistryKey.of(RegistryKeys.BLOCK, id);
        Block block = new Block(settings.registryKey(blockKey));
        Registry.register(Registries.BLOCK, blockKey, block);

        RegistryKey<Item> itemKey = RegistryKey.of(RegistryKeys.ITEM, id);
        BlockItem item = new BlockItem(block,
            new Item.Settings().registryKey(itemKey).useBlockPrefixedTranslationKey());
        Registry.register(Registries.ITEM, itemKey, item);
        return block;
    }

    /** Touch to force class-init (registration) + add the block to a creative tab. */
    public static void register() {
        ItemGroupEvents.modifyEntriesEvent(
                RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.ofVanilla("building_blocks")))
            .register(entries -> entries.add(VOID_BLOCK));
    }
}
