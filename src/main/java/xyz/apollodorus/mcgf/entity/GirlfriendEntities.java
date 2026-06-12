package xyz.apollodorus.mcgf.entity;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import xyz.apollodorus.mcgf.MCGirlfriendMod;
import xyz.apollodorus.mcgf.entity.projectile.PathBeaconEntity;
import xyz.apollodorus.mcgf.entity.projectile.VoidShardEntity;

/** Registers the girlfriend entity type and its default attributes. */
public final class GirlfriendEntities {
    public static final RegistryKey<EntityType<?>> GIRLFRIEND_KEY =
        RegistryKey.of(RegistryKeys.ENTITY_TYPE, Identifier.of(MCGirlfriendMod.MOD_ID, "girlfriend"));

    public static final EntityType<GirlfriendEntity> GIRLFRIEND =
        EntityType.Builder.create(GirlfriendEntity::new, SpawnGroup.CREATURE)
            .dimensions(0.6f, 1.8f)
            .maxTrackingRange(10)
            .build(GIRLFRIEND_KEY);

    public static final RegistryKey<EntityType<?>> VOID_SHARD_KEY =
        RegistryKey.of(RegistryKeys.ENTITY_TYPE, Identifier.of(MCGirlfriendMod.MOD_ID, "void_shard"));

    /** 达妮娅's thrown void-matter shard (ranged 1a / a3). Rendered as the flying 虚质方块 item. */
    public static final EntityType<VoidShardEntity> VOID_SHARD =
        EntityType.Builder.<VoidShardEntity>create(VoidShardEntity::new, SpawnGroup.MISC)
            .dimensions(0.3f, 0.3f)
            .maxTrackingRange(8)
            .trackingTickInterval(10)
            .build(VOID_SHARD_KEY);

    public static final RegistryKey<EntityType<?>> PATH_BEACON_KEY =
        RegistryKey.of(RegistryKeys.ENTITY_TYPE, Identifier.of(MCGirlfriendMod.MOD_ID, "path_beacon"));

    /** The thrown 寻路信标 (ender-pearl-style lob, no teleport). Rendered as the flying 寻路信标 item. */
    public static final EntityType<PathBeaconEntity> PATH_BEACON =
        EntityType.Builder.<PathBeaconEntity>create(PathBeaconEntity::new, SpawnGroup.MISC)
            .dimensions(0.3f, 0.3f)
            .maxTrackingRange(10)
            .trackingTickInterval(10)
            .build(PATH_BEACON_KEY);

    private GirlfriendEntities() {}

    public static void register() {
        Registry.register(Registries.ENTITY_TYPE, GIRLFRIEND_KEY, GIRLFRIEND);
        FabricDefaultAttributeRegistry.register(GIRLFRIEND, GirlfriendEntity.createAttributes());
        Registry.register(Registries.ENTITY_TYPE, VOID_SHARD_KEY, VOID_SHARD);
        Registry.register(Registries.ENTITY_TYPE, PATH_BEACON_KEY, PATH_BEACON);
    }
}
