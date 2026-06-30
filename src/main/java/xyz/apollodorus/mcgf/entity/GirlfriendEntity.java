package xyz.apollodorus.mcgf.entity;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.LandPathNodeMaker;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import xyz.apollodorus.mcgf.MCGirlfriendMod;
import xyz.apollodorus.mcgf.combat.AbilityManager;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;
import xyz.apollodorus.mcgf.entity.goal.DaniyaAttackGoal;
import xyz.apollodorus.mcgf.entity.goal.FollowOwnerGoal;
import xyz.apollodorus.mcgf.entity.goal.PerceiveChestGoal;
import xyz.apollodorus.mcgf.entity.goal.PickupItemsGoal;
import xyz.apollodorus.mcgf.entity.goal.ProtectOwnerGoal;
import xyz.apollodorus.mcgf.entity.goal.RushToOwnerGoal;
import xyz.apollodorus.mcgf.entity.goal.WanderNearOwnerGoal;
import xyz.apollodorus.mcgf.entity.goal.WorkGoal;
import xyz.apollodorus.mcgf.entity.work.WorkUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The girlfriend companion — 鸣潮「达妮娅」. A humanoid {@link PathAwareEntity}
 * bound to an owner player. She follows, fights, mines, chops, harvests, gathers
 * loose drops, remembers what her owner needs, and keeps a small inventory + a
 * home. All AI runs server-side; the LLM brain drives chat, mood and high-level
 * commands by mutating the state on this entity.
 */
public class GirlfriendEntity extends PathAwareEntity {

    /** Loaded girlfriends, so the mood ticker can find them without scanning worlds. */
    public static final Set<GirlfriendEntity> ACTIVE = ConcurrentHashMap.newKeySet();

    /** Synced to clients so the renderer can swap her skin between 形态一 (sweet) and 形态二 (蚀域/幻灭). */
    private static final TrackedData<Boolean> FORM_TWO =
        DataTracker.registerData(GirlfriendEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    private UUID ownerUuid;
    private boolean following;            // true = follow owner, false = stay / wander in place
    private boolean combatEnabled;
    private boolean gatherEnabled;
    private BlockPos homePos;

    private final SimpleInventory inventory = new SimpleInventory(27);
    private final Map<Item, Integer> needs = new LinkedHashMap<>();
    private final List<Task> tasks = new ArrayList<>();   // ordered job queue (active + waiting)
    private Task activeTask;                               // the job WorkGoal is currently executing, or null
    private String activity = "闲着";      // human-readable, fed into the LLM context
    private int affection;                // 好感度 0..100, used as an LLM tone reference
    private int energy;                   // 虚质粒子 0..100 (hidden); fills on attack, spends on 蚀域

    // owner-stationary tracking (drives idle gather / wander)
    private Vec3d lastOwnerPos;
    private int ownerStillTicks;

    // survival timers
    private int regenTimer;
    private int eatCooldown;
    private int gearCooldown;

    // Independent chest perception: she remarks on a noticed container regardless of what she's doing
    // (mining / chopping / following / idle). Only WALKING over to investigate (PerceiveChestGoal) waits
    // for free time — so the spoken heads-up itself is never blocked by a task. Each chest is announced
    // at most once per session.
    private final Set<BlockPos> remarkedChests = new HashSet<>();
    private int chestScanCd;
    private long lastChestRemarkTick = Long.MIN_VALUE / 2;

    // combat assist: when the owner attacks a mob, she piles on for a short while
    private LivingEntity assistTarget;
    private long assistExpireTime;

    // 去看箱子时「带路」状态：她朝箱子走时即便玩家跟过来也不被跟随打断，而是把玩家带到箱子边。
    private boolean leadingChest;

    // 空闲主动采集（如收菜）时的「专心干活」状态：开始后即便玩家走动也不被跟随打断，直到活干完或玩家走太远。
    private boolean idleWorking;

    // owner-attacked: rushing to his side becomes top priority for a short window
    private long rushUntilTime;

    // 寻路信标带路：玩家投掷信标后，临时把寻路目标改到落点、带路过去（崎岖地形帮忙搭路）。
    // 这是个可取消的任务（再投一次替换、/gf stop 清除），战斗仍最高优先级、战后继续前往。
    private BlockPos guideTarget;        // 当前带路目标（落点），null = 没有带路任务
    private boolean guideArrived;        // 是否已抵达目标点（进入周围闲逛 + 30s 计时）
    private long guideLoiterDeadline;    // 抵达后这个时刻还没等到玩家就传送回去

    public GirlfriendEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        this.following = b.followByDefault;
        this.combatEnabled = b.combatByDefault;
        this.gatherEnabled = b.gatherByDefault;
        this.affection = b.affectionDefault;
        this.setCanPickUpLoot(false); // we run our own pickup into the companion inventory
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return PathAwareEntity.createMobAttributes()
            .add(EntityAttributes.MAX_HEALTH, 24.0)
            .add(EntityAttributes.MOVEMENT_SPEED, 0.25)
            .add(EntityAttributes.FOLLOW_RANGE, 48.0)
            .add(EntityAttributes.ATTACK_DAMAGE, 5.0)
            .add(EntityAttributes.ATTACK_SPEED, 4.0)
            .add(EntityAttributes.STEP_HEIGHT, 1.0);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(FORM_TWO, false);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));        // Owner-attacked emergency: outrank melee/follow/work so she breaks off and runs to him.
        this.goalSelector.add(1, new RushToOwnerGoal(this));
        // 防御茧已移除：二形态专注输出，期间不再放置任何虚质方块（茧会把方块糊到她头上、还会卡头）。
        this.goalSelector.add(3, new DaniyaAttackGoal(this));
        // 寻路信标带路：高于跟随/工作，低于战斗/冲向受击玩家——保护玩家仍最高优先级，战后自动继续前往落点。
        this.goalSelector.add(3, new xyz.apollodorus.mcgf.entity.goal.GuideToBeaconGoal(this));
        this.goalSelector.add(4, new FollowOwnerGoal(this));
        this.goalSelector.add(5, new PerceiveChestGoal(this));
        this.goalSelector.add(6, new WorkGoal(this));
        // 背包快满且在家附近时，自动把杂物收进箱子（之前漏注册，所以从不触发）。
        this.goalSelector.add(7, new xyz.apollodorus.mcgf.entity.goal.AutoStorageGoal(this));
        this.goalSelector.add(7, new PickupItemsGoal(this));
        // 黑暗中在脚下铺一块发光虚质方块照明（无控制位，伴随其他行为；之前漏注册，所以从不触发）。
        this.goalSelector.add(7, new xyz.apollodorus.mcgf.entity.goal.AutoLightGoal(this));
        // 慵懒少女：设了 home 后空闲时会去 home 附近的床上打盹（夜晚概率高、白天也会）。高于闲逛/张望。
        this.goalSelector.add(8, new xyz.apollodorus.mcgf.entity.goal.SleepAtHomeGoal(this));
        this.goalSelector.add(9, new WanderNearOwnerGoal(this));
        this.goalSelector.add(10, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
        this.goalSelector.add(11, new LookAroundGoal(this));

        this.targetSelector.add(1, new ProtectOwnerGoal(this));
    }

    /**
     * 让她能像村民那样寻路穿过（并自动开/关）木门——否则原版寻路把关着的门当墙，她就到不了屋外的菜地等
     * 地方（修复「home 附近不破坏 → 不走正门去菜地」）。仅木门；铁门仍需红石、她打不开，符合原版。
     */
    @Override
    protected EntityNavigation createNavigation(World world) {
        MobNavigation nav = new MobNavigation(this, world);
        nav.setCanOpenDoors(true);
        if (nav.getNodeMaker() instanceof LandPathNodeMaker maker) {
            maker.setCanEnterOpenDoors(true); // 关着的木门也算可通行（配合 canOpenDoors → WALKABLE_DOOR）
        }
        return nav;
    }

    @Override
    public void tick() {
        super.tick();
        // In 1.21.11 only PlayerEntity advances its hand-swing (PlayerEntity.tickMovement →
        // tickHandSwing); plain mobs never do. Because she renders through a player-style biped,
        // that left her arm frozen while working — she looked like she mined/fought 用念力. Tick the
        // swing ourselves every frame (client included, where it's drawn) so the swingHand() calls in
        // mining / attacking / chopping / harvesting actually animate the arm.
        this.tickHandSwing();
        if (!(this.getEntityWorld() instanceof ServerWorld sw)) return;
        ACTIVE.add(this);

        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        enforceAttribute(EntityAttributes.ATTACK_DAMAGE, b.attackDamage);
        enforceAttribute(EntityAttributes.MOVEMENT_SPEED, b.baseSpeed);

        // Track whether the owner is parked (drives idle gather / wander).
        PlayerEntity owner = getOwner();
        if (owner != null) {
            Vec3d p = owner.getEntityPos();
            if (lastOwnerPos != null && p.squaredDistanceTo(lastOwnerPos) < 0.02) {
                if (ownerStillTicks < 6000) ownerStillTicks++;
            } else {
                ownerStillTicks = 0;
            }
            lastOwnerPos = p;
        }

        if (--gearCooldown <= 0) {
            gearCooldown = 40;
            gearUp();
            // 形态一时握着专武泡泡杖（出生即附带）：空手且没在挖矿/战斗时自动握上。
            if (!isFormTwo() && getTarget() == null && getTask() == null
                    && getEquippedStack(EquipmentSlot.MAINHAND).isEmpty()) {
                equipSignatureWeapon();
            }
        }
        survival(sw, b);
        tickChestPerception(sw, b);
        // 形态二只在领域生效期间存在：领域到期/异常残留时强制还原一形态（修复"离开领域/复活后仍是二形态"）。
        if (isFormTwo() && !AbilityManager.hasDomain(this)) {
            setFormTwo(false);
            setNoGravity(false);
        }
        tickDomainDeploy(sw, b);
        tickFloat();
        tickVoidAura(sw);
    }

    private static final double FLOAT_HOVER = 3.4; // blocks above ground she hovers in 形态二（在原 2.4 基础上再抬高约一格）

    /**
     * 形态二·身侧虚质粒子（取代之前头顶那顶"冠"）：一层环绕她身体的深蓝/紫虚质粒子壳，随时间转动、上下起伏，
     * 把她整个人裹在虚质气息里。纯服务端 spawnParticles 到客户端，不动模型。粒子数量收敛，避免视觉过载。
     */
    private void tickVoidAura(ServerWorld sw) {
        if (!isFormTwo()) return;
        // 每 3 tick 才刷新一次粒子（降低频率）
        if (sw.getTime() % 3 != 0) return;
        double cx = getX(), cz = getZ();
        double baseY = getY() + getHeight() * 0.45;
        double spin = (sw.getTime() % 50) / 50.0 * Math.PI * 2;
        net.minecraft.particle.DustParticleEffect blue = new net.minecraft.particle.DustParticleEffect(0x3A6BFF, 1.0f);
        net.minecraft.particle.DustParticleEffect violet = new net.minecraft.particle.DustParticleEffect(0x8A3FFF, 1.0f);
        int pts = 5;  // 从 7 降到 5
        double r = 0.62;
        for (int i = 0; i < pts; i++) {
            double a = spin + (Math.PI * 2 / pts) * i;
            double px = cx + Math.cos(a) * r;
            double pz = cz + Math.sin(a) * r;
            double py = baseY + Math.sin(a * 1.5 + spin * 2) * (getHeight() * 0.35);
            sw.spawnParticles((i & 1) == 0 ? blue : violet, px, py, pz, 1, 0.0, 0.0, 0.0, 0.0);
        }
        // 降低虚质雾频率：每 6 tick 一次
        if ((sw.getTime() % 6) == 0) {
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.PORTAL, cx, baseY, cz, 1, 0.3, getHeight() * 0.35, 0.3, 0.01);
        }
        // SCULK_SOUL 也降低频率
        if ((sw.getTime() % 4) == 0) {
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.SCULK_SOUL, cx, baseY + 0.1, cz, 1, 0.25, 0.25, 0.25, 0.0);
        }
    }

    /**
     * 形态二浮空：她离地轻轻悬浮，并且**不需要脚下有方块**——深坑/悬崖上方也照样浮着不掉。每 tick 在 AI 设好水平
     * 速度之后覆盖竖直速度：脚下找得到地面就托到地面上方 {@link #FLOAT_HOVER} 格；找不到地面（深坑/虚空之上）就
     * 原地保持当前高度悬停。**保留水平移动**，只控制竖直分量，所以她可以正常跟随和寻路。每 tick 清零下落距离，
     * 所以领域结束的瞬间也不会摔伤（再叠加 end() 给的缓降）。
     */
    private void tickFloat() {
        if (!isFormTwo()) return;
        World w = getEntityWorld();
        BlockPos base = getBlockPos();
        double groundTop = Double.NaN;
        for (int dy = 0; dy <= 24; dy++) {              // 往下找更远，深坑上方也撑得住
            BlockPos p = base.down(dy);
            if (w.getBlockState(p).isSolidBlock(w, p)) { groundTop = p.getY() + 1.0; break; }
        }
        double cur = getY();
        // 找到地面 → 目标在地面上方 FLOAT_HOVER；没找到 → 保持当前高度（悬停不下坠）。
        double hoverY = Double.isNaN(groundTop) ? cur : groundTop + FLOAT_HOVER;
        Vec3d v = getVelocity();
        double vy;
        if (cur < hoverY - 0.05) vy = Math.min(0.18, (hoverY - cur) * 0.3 + 0.05); // 抬升
        else if (cur > hoverY + 0.5) vy = -0.08;                                    // 太高了缓缓下来
        else vy = Math.sin(w.getTime() * 0.15) * 0.012;                            // 轻微上下起伏
        // 关键：只替换竖直分量，保留水平速度 (v.x, v.z)，这样她能正常水平移动和跟随
        setVelocity(v.x, vy, v.z);
        this.fallDistance = 0.0f;                        // 浮空中不积累下落距离 → 退形态时不摔伤
    }

    /**
     * Auto-unleash the 蚀域 domain when her 虚质粒子 energy is full and hostiles are nearby (her real
     * Wuthering Waves ultimate condition). The domain itself is owned + ticked by {@link AbilityManager},
     * anchored in the world, so it survives even if she later unloads.
     */
    private void tickDomainDeploy(ServerWorld sw, GirlfriendConfig.Behavior b) {
        if (!combatEnabled || !b.domainAutoDeploy) return;
        if (energy < b.domainEnergyCost || AbilityManager.hasDomain(this)) return;
        if (!hasHostilesNear(sw, b.guardRadius)) return;
        if (AbilityManager.deployDomain(this)) {
            energy = 0;
            announceDomainEnter();
        }
    }

    /** Fire an AI-generated 切入幻灭之形 line (form-2 persona is already active by deploy time). */
    private void announceDomainEnter() {
        xyz.apollodorus.mcgf.ai.MoodManager.tryEventProactive(this,
            GirlfriendConfig.pickOne(ConfigManager.get().prompts.domainEnter));
    }

    private boolean hasHostilesNear(ServerWorld sw, double radius) {
        Box box = getBoundingBox().expand(radius);
        return !sw.getOtherEntities(this, box, e -> e instanceof HostileEntity && e.isAlive()).isEmpty();
    }

    /** Manually unleash the domain (e.g. /gf ult). Returns true if it deployed. */
    public boolean tryDeployDomain() {
        if (AbilityManager.hasDomain(this)) return false;
        if (AbilityManager.deployDomain(this)) {
            energy = 0;
            announceDomainEnter();
            return true;
        }
        return false;
    }

    private static final int CHEST_REMARK_GAP = 200; // ~10s minimum between chest remarks
    private static final Predicate<BlockState> IS_CONTAINER =
        st -> st.isOf(Blocks.CHEST) || st.isOf(Blocks.TRAPPED_CHEST) || st.isOf(Blocks.BARREL);

    /**
     * Notice a nearby container and give the owner a heads-up — on its own schedule, fully decoupled
     * from whatever she's doing, so a chest hint is never blocked by a task (mining / chopping /
     * following). Only WALKING over to investigate waits for free time ({@link PerceiveChestGoal},
     * which no longer remarks, to avoid double-speak). Each chest position is announced at most once
     * per session; hushed near her home base and while fighting. The guidance line is configurable
     * (prompts.foundChest).
     */
    private void tickChestPerception(ServerWorld sw, GirlfriendConfig.Behavior b) {
        if (!b.autoPerceiveChests) return;
        if (getTarget() != null) return;                   // mid-fight is not the moment for chest chatter
        if (--chestScanCd > 0) return;
        chestScanCd = 20;                                  // bound the world scan to ~once per second

        long now = sw.getTime();
        if (now - lastChestRemarkTick < CHEST_REMARK_GAP) return;

        BlockPos home = getHomePos();
        double hr2 = b.homePerceiveRadius * b.homePerceiveRadius;
        if (home != null && home.getSquaredDistance(getBlockPos()) <= hr2) return; // hushed near base

        BlockPos found = WorkUtil.findNearestBlock(sw, getBlockPos(), b.perceiveRadius, IS_CONTAINER, remarkedChests);
        if (found == null) return;
        if (home != null && home.getSquaredDistance(found) <= hr2) return;

        remarkedChests.add(found.toImmutable());           // once per chest position (no expiry this session)
        lastChestRemarkTick = now;
        // ~50% her own 达妮娅 perceive clip; otherwise (and for any other persona) an AI line.
        if (!xyz.apollodorus.mcgf.ai.Voice.maybePerceive(this) && MCGirlfriendMod.BRAIN != null) {
            MCGirlfriendMod.BRAIN.proactive(this, GirlfriendConfig.pickOne(ConfigManager.get().prompts.foundChest));
        }
    }

    private void enforceAttribute(RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> attr, double want) {
        EntityAttributeInstance inst = getAttributeInstance(attr);
        if (inst != null && Math.abs(inst.getBaseValue() - want) > 1.0e-4) inst.setBaseValue(want);
    }

    /** Slow natural regen, plus eating food from the backpack when she's hurt. */
    private void survival(ServerWorld sw, GirlfriendConfig.Behavior b) {
        if (getHealth() >= getMaxHealth()) { regenTimer = 0; return; }
        boolean fighting = getTarget() != null;

        if (!fighting && ++regenTimer >= Math.max(1, b.naturalRegenSeconds) * 20) {
            regenTimer = 0;
            heal(1.0f);
        }

        if (eatCooldown > 0) { eatCooldown--; return; }
        if (getHealth() <= getMaxHealth() * b.eatBelowPercent) {
            if (tryEat()) eatCooldown = 60;
        }
    }

    private boolean tryEat() {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (s.isEmpty()) continue;
            FoodComponent food = s.get(DataComponentTypes.FOOD);
            if (food == null) continue;
            if (s.isOf(Items.ROTTEN_FLESH) || s.isOf(Items.PUFFERFISH)
                || s.isOf(Items.SPIDER_EYE) || s.isOf(Items.POISONOUS_POTATO)) continue;
            int nutrition = food.nutrition();
            // A small bite of instant healing, then accelerated regen — like a player who just ate.
            float bite = Math.min((float) getMaxHealth() - getHealth(), nutrition * 0.5f);
            heal(Math.max(1.0f, bite));
            int per = Math.max(1, ConfigManager.get().behavior.eatRegenSecondsPerNutrition);
            int secs = MathHelper.clamp(nutrition * per, 3, 12);
            int amplifier = nutrition >= 6 ? 2 : 1;
            addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, secs * 20, amplifier));
            s.decrement(1);
            if (s.isEmpty()) inventory.setStack(i, ItemStack.EMPTY);
            getEntityWorld().playSound(null, getX(), getY(), getZ(),
                SoundEvents.ENTITY_GENERIC_EAT.value(), SoundCategory.NEUTRAL, 0.7f, 1.0f);
            return true;
        }
        return false;
    }

    /** Auto-wear armor from the backpack and keep a totem in the off-hand. */
    private void gearUp() {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (s.isEmpty()) continue;
            if (s.isOf(Items.TOTEM_OF_UNDYING) && getEquippedStack(EquipmentSlot.OFFHAND).isEmpty()) {
                equipStack(EquipmentSlot.OFFHAND, inventory.removeStack(i, 1));
                continue;
            }
            EquippableComponent eq = s.get(DataComponentTypes.EQUIPPABLE);
            if (eq == null) continue;
            EquipmentSlot slot = eq.slot();
            if (!isWearableArmorSlot(slot)) continue;
            if (getEquippedStack(slot).isEmpty()) {
                equipStack(slot, inventory.removeStack(i, 1));
            }
        }
    }

    private static boolean isWearableArmorSlot(EquipmentSlot slot) {
        return slot == EquipmentSlot.HEAD || slot == EquipmentSlot.CHEST
            || slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET;
    }

    public boolean isLowHealth() {
        return getHealth() <= getMaxHealth() * ConfigManager.get().behavior.selfPreserveBelowPercent;
    }

    @Override
    protected ActionResult interactMob(PlayerEntity player, Hand hand) {
        if (ownerUuid == null) ownerUuid = player.getUuid();
        if (!isOwner(player)) return ActionResult.PASS;
        if (player instanceof ServerPlayerEntity sp) {
            xyz.apollodorus.mcgf.screen.ModScreens.open(sp, this);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public void onDeath(DamageSource source) {
        // Tear down any active 蚀域 first so terrain restores and the owner's domain buff never leaks.
        if (getEntityWorld() instanceof ServerWorld) AbilityManager.endDomainFor(this);
        if (getEntityWorld() instanceof ServerWorld && ownerUuid != null) {
            DownedManager.Snapshot snap = new DownedManager.Snapshot();
            // 装备单独存进 snap.equipment（不再塞进可能已满的背包，免得溢出被丢弃 → 死亡掉东西）。
            // 同时清空装备槽，免得原版死亡把这些装备掉到地上。复活时 ReviveService.restore() 会从
            // snap.equipment 重新穿戴回来。
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack worn = getEquippedStack(slot);
                if (!worn.isEmpty()) {
                    snap.equipment.put(slot.getName(),
                        Registries.ITEM.getId(worn.getItem()) + " " + worn.getCount());
                    equipStack(slot, ItemStack.EMPTY);
                }
            }
            snap.inventory = exportInventory();
            snap.affection = affection;
            snap.x = getX();
            snap.y = getY();
            snap.z = getZ();
            if (homePos != null) {
                snap.hasHome = true;
                snap.homeX = homePos.getX();
                snap.homeY = homePos.getY();
                snap.homeZ = homePos.getZ();
            }
            DownedManager.setDowned(ownerUuid, snap);

            // Hand the owner a 重逢符 so they always have a way to bring her back.
            PlayerEntity owner = getOwner();
            if (ConfigManager.get().behavior.giveReviveCharmOnDeath
                && owner instanceof ServerPlayerEntity sp && !hasReviveCharm(sp)) {
                sp.giveItemStack(new ItemStack(xyz.apollodorus.mcgf.item.ModItems.REVIVE_CHARM));
            }
            // Her own (达妮娅) defeat voice ~half the time; otherwise the usual text death line.
            if (!xyz.apollodorus.mcgf.ai.Voice.maybeDefeat(this)) {
                xyz.apollodorus.mcgf.ai.SpeechBus.speak(this, deathLine());
            }
        }
        super.onDeath(source);
    }

    private static boolean hasReviveCharm(ServerPlayerEntity sp) {
        var inv = sp.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isOf(xyz.apollodorus.mcgf.item.ModItems.REVIVE_CHARM)) return true;
        }
        return false;
    }

    private static final String[] DEATH_LINES = {
        "对不起…一不小心，就死掉了…",
        "呜…眼前都暗下来了…别担心我…",
        "啊…这次没护住自己…抱歉…",
        "好困…就让我先睡一会儿吧…",
        "对不起…下次我会更小心的…"
    };

    private String deathLine() {
        return DEATH_LINES[getRandom().nextInt(DEATH_LINES.length)];
    }

    @Override
    public void remove(RemovalReason reason) {
        ACTIVE.remove(this);
        if (getEntityWorld() instanceof ServerWorld) AbilityManager.endDomainFor(this);
        super.remove(reason);
    }

    // --- affection ---

    public int getAffection() { return affection; }
    public void setAffection(int v) { this.affection = MathHelper.clamp(v, 0, 100); }
    public void addAffection(int delta) { setAffection(affection + delta); }

    // --- 虚质粒子 energy (hidden combat resource) ---

    public int getEnergy() { return energy; }
    public void setEnergy(int v) { this.energy = MathHelper.clamp(v, 0, ConfigManager.get().behavior.domainEnergyCost); }
    public void gainEnergy(int delta) {
        // 二形态领域展开期间，不能回复能量
        if (delta > 0 && AbilityManager.hasDomain(this)) return;
        if (delta > 0) setEnergy(energy + delta);
    }

    // --- 形态 (form 1 = sweet bubble girl; form 2 = 蚀域/幻灭, void kit + 厌世人格) ---

    public boolean isFormTwo() { return this.getDataTracker().get(FORM_TWO); }
    public void setFormTwo(boolean v) { this.getDataTracker().set(FORM_TWO, v); }

    /** 形态一：把专武「泡泡杖」握到主手（若她竟然没有就凭空给一把——出生即附带）。 */
    public void equipSignatureWeapon() {
        ItemStack held = getEquippedStack(EquipmentSlot.MAINHAND);
        if (held.isOf(xyz.apollodorus.mcgf.item.ModItems.BUBBLE_WAND)) return;
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i).isOf(xyz.apollodorus.mcgf.item.ModItems.BUBBLE_WAND)) {
                ItemStack wand = inventory.removeStack(i);
                equipStack(EquipmentSlot.MAINHAND, wand);
                if (!held.isEmpty()) inventory.addStack(held);
                return;
            }
        }
        if (!held.isEmpty()) inventory.addStack(held);
        equipStack(EquipmentSlot.MAINHAND, new ItemStack(xyz.apollodorus.mcgf.item.ModItems.BUBBLE_WAND));
    }

    /** 形态二：空手——把主手物品收回背包。 */
    public void emptyMainHand() {
        ItemStack held = getEquippedStack(EquipmentSlot.MAINHAND);
        if (!held.isEmpty()) {
            equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            inventory.addStack(held);
        }
    }

    /** Human-readable intimacy tier for the current affection — shown in the panel + LLM context. */
    public String affectionTier() { return tierFor(affection); }

    /** Intimacy tier label for an affection value (shared by server context + client panel). */
    public static String tierFor(int affection) {
        if (affection <= 20) return "生疏";
        if (affection <= 40) return "熟络";
        if (affection <= 60) return "亲近";
        if (affection <= 80) return "亲密";
        return "黏人";
    }

    // --- owner binding ---

    public UUID getOwnerUuid() { return ownerUuid; }

    public void setOwnerUuid(UUID uuid) { this.ownerUuid = uuid; }

    public PlayerEntity getOwner() {
        return ownerUuid == null ? null : this.getEntityWorld().getPlayerByUuid(ownerUuid);
    }

    public boolean isOwner(PlayerEntity player) {
        return ownerUuid != null && ownerUuid.equals(player.getUuid());
    }

    // --- mode / toggles ---

    public boolean isFollowing() { return following; }
    public void setFollowing(boolean v) { this.following = v; }

    public boolean isCombatEnabled() { return combatEnabled; }
    public void setCombatEnabled(boolean v) { this.combatEnabled = v; }

    /** Mark a mob the owner just attacked so she joins in (decays after ~10s). Ignores players/self. */
    public void markAssistTarget(LivingEntity target) {
        if (target == null || target == this || target instanceof PlayerEntity) return;
        this.assistTarget = target;
        this.assistExpireTime = getEntityWorld().getTime() + 200L;
    }

    /** The mob she's currently assisting on, or null if none / expired / dead. */
    public LivingEntity getAssistTarget() {
        if (assistTarget == null) return null;
        if (!assistTarget.isAlive() || getEntityWorld().getTime() > assistExpireTime) {
            assistTarget = null;
            return null;
        }
        return assistTarget;
    }

    /**
     * The owner just took damage — sprint to his side as top priority for ~10s. If a
     * hostile dealt the hit, also mark it as an assist target so she punishes it once
     * she arrives (the rush goal gets her there; {@link ProtectOwnerGoal} does the fighting).
     * Null-safe: environmental damage (a fall, drowning) still makes her hurry over.
     */
    public void markOwnerAttacked(LivingEntity attacker) {
        this.rushUntilTime = getEntityWorld().getTime() + 200L;
        if (attacker instanceof net.minecraft.entity.mob.HostileEntity) {
            markAssistTarget(attacker);
        }
    }

    /** True while the owner-attacked rush window is still open. */
    public boolean isRushingToOwner() {
        return getEntityWorld().getTime() < rushUntilTime;
    }

    public boolean isGatherEnabled() { return gatherEnabled; }
    public void setGatherEnabled(boolean v) { this.gatherEnabled = v; }

    /** True while she's walking the owner over to a noticed chest — follow yields so she leads instead of breaking off. */
    public boolean isLeadingChest() { return leadingChest; }
    public void setLeadingChest(boolean v) { this.leadingChest = v; }

    /** True while she's busy on a self-started idle gather (harvest/forage) — follow yields so owner movement
     *  doesn't yank her off it; WorkGoal drops it itself if the owner strays too far. */
    public boolean isIdleWorking() { return idleWorking; }
    public void setIdleWorking(boolean v) { this.idleWorking = v; }

    // --- 寻路信标带路 ---

    /** Retask her to lead the way to {@code pos} (replaces any prior guide). Cleared by stop/come/new beacon. */
    public void setGuideTarget(BlockPos pos) {
        this.guideTarget = pos == null ? null : pos.toImmutable();
        this.guideArrived = false;
        this.guideLoiterDeadline = 0L;
    }

    public BlockPos getGuideTarget() { return guideTarget; }

    public boolean isGuiding() { return guideTarget != null; }

    /** She reached the beacon — start loitering and the 30s "owner didn't show" timer. */
    public void markGuideArrived() {
        if (!guideArrived) {
            guideArrived = true;
            guideLoiterDeadline = getEntityWorld().getTime() + 30L * 20L;
        }
    }

    public boolean isGuideArrived() { return guideArrived; }

    /** True once she's been loitering at the beacon past the 30s grace period. */
    public boolean guideLoiterExpired() {
        return guideArrived && guideLoiterDeadline > 0 && getEntityWorld().getTime() > guideLoiterDeadline;
    }

    public void clearGuide() {
        this.guideTarget = null;
        this.guideArrived = false;
        this.guideLoiterDeadline = 0L;
    }

    public BlockPos getHomePos() { return homePos; }
    public void setHomePos(BlockPos pos) { this.homePos = pos; }

    // --- 睡眠状态 ---

    /**
     * 让她躺在床上睡觉。原版机制。
     */
    public void sleep(BlockPos bedPos) {
        // 关键：设成睡眠姿势(EntityPose.SLEEPING)，BipedEntityRenderer 才会让她躺平、头枕枕头。
        // 之前只设了 sleepingPosition(让 isSleeping() 返回真)却没设姿势，所以她「站」在床上。
        this.setSleepingPosition(bedPos);
        this.setPose(EntityPose.SLEEPING);
        // 沿床身躺、头枕枕头：按床朝向(FACING 指向床头)对齐身体朝向，并居中到床的两格中点，
        // 避免之前那种横躺、半个身子探出床外的情况。
        World w = this.getEntityWorld();
        BlockState bs = w.getBlockState(bedPos);
        double cx = bedPos.getX() + 0.5, cz = bedPos.getZ() + 0.5;
        if (bs.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING)) {
            net.minecraft.util.math.Direction face = bs.get(net.minecraft.state.property.Properties.HORIZONTAL_FACING);
            net.minecraft.block.enums.BedPart part = bs.contains(net.minecraft.block.BedBlock.PART)
                ? bs.get(net.minecraft.block.BedBlock.PART) : net.minecraft.block.enums.BedPart.FOOT;
            BlockPos foot = part == net.minecraft.block.enums.BedPart.HEAD ? bedPos.offset(face.getOpposite()) : bedPos;
            BlockPos head = part == net.minecraft.block.enums.BedPart.HEAD ? bedPos : bedPos.offset(face);
            cx = (foot.getX() + head.getX()) / 2.0 + 0.5;
            cz = (foot.getZ() + head.getZ()) / 2.0 + 0.5;
            // 再往床头方向挪约一个头(0.4格)，躺得更靠枕头一侧。
            cx += face.getOffsetX() * 0.4;
            cz += face.getOffsetZ() * 0.4;
            // 由朝向向量算 yaw（south=0、顺时针）：x=-sin(yaw), z=cos(yaw) → yaw=atan2(-x,z)。
            float yaw = (float) Math.toDegrees(Math.atan2(-face.getOffsetX(), face.getOffsetZ()));   // 头朝床头一侧
            this.setYaw(yaw);
            this.setBodyYaw(yaw);
            this.setHeadYaw(yaw);
        }
        // 原版躺床的高度偏移(0.6875)。
        this.setPosition(cx, bedPos.getY() + 0.6875, cz);
        this.setVelocity(Vec3d.ZERO);
        this.getNavigation().stop();
    }

    /** 从睡眠中醒来。 */
    public void wakeUp() {
        this.setPose(EntityPose.STANDING);   // 之前漏了这步：不复位姿势她会一直保持躺平
        this.clearSleepingPosition();        // 原版清除睡眠位置
        this.setVelocity(Vec3d.ZERO);
    }

    public boolean isOwnerStationary() { return ownerStillTicks > 40; }

    /**
     * Distance at which {@link FollowOwnerGoal} starts pulling her back. While the owner is parked
     * she's let off the leash out to {@code idleRoamRadius}, so her probabilistic idle wandering /
     * foraging isn't yanked back the instant she strays past {@code followStartDistance} — that
     * constant cross-the-line/get-pulled-back was the old "pacing back and forth". The moment the
     * owner moves again, isOwnerStationary() flips false and normal follow distance resumes.
     */
    public double effectiveFollowStartDistance() {
        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        if (isFollowing() && isOwnerStationary()) return Math.max(b.followStartDistance, b.idleRoamRadius);
        return b.followStartDistance;
    }

    // --- inventory / needs / task ---

    public SimpleInventory getInventory() { return inventory; }

    public Map<Item, Integer> getNeeds() { return needs; }

    public void addNeed(Item item, int count) {
        if (item == null || count <= 0) return;
        needs.merge(item, count, Integer::sum);
    }

    /** Reduce outstanding needs after collecting items. Returns true if one was just satisfied. */
    public boolean noteCollected(Item item, int count) {
        Integer want = needs.get(item);
        if (want == null) return false;
        int left = want - count;
        if (left <= 0) {
            needs.remove(item);
            return true;
        }
        needs.put(item, left);
        return false;
    }

    /** The job WorkGoal is currently executing (it sets this), or null when idle / only waiting jobs queued. */
    public Task getTask() { return activeTask; }

    /** WorkGoal marks which queued job it's actively working — drives the "she's busy" checks in other goals. */
    public void setActiveTask(Task t) { this.activeTask = t; }

    /** The full ordered job queue (the active one + those still waiting for a reachable target). */
    public List<Task> getTasks() { return tasks; }

    /** Queue a commanded job (mine/chop/harvest/obtain). */
    public void enqueueTask(Task t) { if (t != null) tasks.add(t); }

    /** Finish the active job: drop it from the queue and clear the active slot. */
    public void completeActiveTask() {
        if (activeTask != null) tasks.remove(activeTask);
        activeTask = null;
    }

    /** Cancel the queued job at {@code index} (panel 取消). Returns false if out of range. */
    public boolean cancelTask(int index) {
        if (index < 0 || index >= tasks.size()) return false;
        Task removed = tasks.remove(index);
        if (removed == activeTask) activeTask = null;
        return true;
    }

    /** Stop all work (used by stop/come): clear the whole queue + any beacon guide. */
    public void clearTask() { tasks.clear(); activeTask = null; clearGuide(); this.activity = "闲着"; }

    /** Display labels for the panel task list, in queue order ("·进行中" marks the active one). */
    public List<String> taskLabels() {
        List<String> out = new ArrayList<>();
        for (Task t : tasks) out.add(t.label() + (t == activeTask ? " ·进行中" : ""));
        return out;
    }

    public String getActivity() { return activity; }
    public void setActivity(String a) { this.activity = a; }

    public int countItem(Item item) {
        int n = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (!s.isEmpty() && s.isOf(item)) n += s.getCount();
        }
        return n;
    }

    /** Add a stack to the companion inventory; returns the unfit remainder. */
    public ItemStack addToInventory(ItemStack stack) {
        return inventory.addStack(stack);
    }

    /** Hand up to {@code count} of an item to the owner. Returns how many were given. */
    public int giveToOwner(Item item, int count) {
        PlayerEntity owner = getOwner();
        if (owner == null) return 0;
        int given = 0;
        for (int i = 0; i < inventory.size() && given < count; i++) {
            ItemStack s = inventory.getStack(i);
            if (s.isEmpty() || !s.isOf(item)) continue;
            int take = Math.min(s.getCount(), count - given);
            ItemStack out = s.copyWithCount(take);
            owner.giveItemStack(out);
            s.decrement(take);
            if (s.isEmpty()) inventory.setStack(i, ItemStack.EMPTY);
            given += take;
        }
        return given;
    }

    // --- persistence (Codec-free string form; component data is intentionally dropped) ---

    @Override
    public void writeData(WriteView view) {
        super.writeData(view);
        if (ownerUuid != null) view.putString("Owner", ownerUuid.toString());
        view.putBoolean("Following", following);
        view.putBoolean("Combat", combatEnabled);
        view.putBoolean("Gather", gatherEnabled);
        view.putInt("Affection", affection);
        view.putInt("Energy", energy);
        if (homePos != null) {
            view.putBoolean("HasHome", true);
            view.putInt("HomeX", homePos.getX());
            view.putInt("HomeY", homePos.getY());
            view.putInt("HomeZ", homePos.getZ());
        }
        view.putString("Inv", serializeInventory());
        view.putString("Needs", serializeNeeds());
        view.putString("Tasks", serializeTasks());
    }

    @Override
    public void readData(ReadView view) {
        super.readData(view);
        view.getOptionalString("Owner").ifPresent(s -> {
            try { ownerUuid = UUID.fromString(s); } catch (IllegalArgumentException ignored) {}
        });
        following = view.getBoolean("Following", following);
        combatEnabled = view.getBoolean("Combat", combatEnabled);
        gatherEnabled = view.getBoolean("Gather", gatherEnabled);
        affection = view.getInt("Affection", affection);
        energy = MathHelper.clamp(view.getInt("Energy", 0), 0, ConfigManager.get().behavior.domainEnergyCost);
        if (view.getBoolean("HasHome", false)) {
            homePos = new BlockPos(view.getInt("HomeX", 0), view.getInt("HomeY", 0), view.getInt("HomeZ", 0));
        }
        view.getOptionalString("Inv").ifPresent(this::deserializeInventory);
        view.getOptionalString("Needs").ifPresent(this::deserializeNeeds);
        view.getOptionalString("Tasks").ifPresent(this::deserializeTasks);
    }

    /** Public string form of the backpack, used by the revive snapshot. */
    public String exportInventory() { return serializeInventory(); }

    /** Restore the backpack from a string produced by {@link #exportInventory()}. */
    public void importInventory(String data) { deserializeInventory(data); }

    private String serializeInventory() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(Registries.ITEM.getId(s.getItem())).append(' ').append(s.getCount());
        }
        return sb.toString();
    }

    private void deserializeInventory(String data) {
        inventory.clear();
        if (data == null || data.isBlank()) return;
        for (String part : data.split(";")) {
            String[] kv = part.trim().split(" ");
            if (kv.length != 2) continue;
            Item item = Registries.ITEM.get(Identifier.tryParse(kv[0]));
            if (item == Items.AIR) continue;
            try { inventory.addStack(new ItemStack(item, Integer.parseInt(kv[1]))); }
            catch (NumberFormatException ignored) {}
        }
    }

    private String serializeNeeds() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Item, Integer> e : needs.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(Registries.ITEM.getId(e.getKey())).append(' ').append(e.getValue());
        }
        return sb.toString();
    }

    private void deserializeNeeds(String data) {
        needs.clear();
        if (data == null || data.isBlank()) return;
        for (String part : data.split(";")) {
            String[] kv = part.trim().split(" ");
            if (kv.length != 2) continue;
            Item item = Registries.ITEM.get(Identifier.tryParse(kv[0]));
            if (item == Items.AIR) continue;
            try { needs.put(item, Integer.parseInt(kv[1])); }
            catch (NumberFormatException ignored) {}
        }
    }

    private String serializeTasks() {
        StringBuilder sb = new StringBuilder();
        for (Task t : tasks) {
            if (sb.length() > 0) sb.append(';');
            String item = t.item == null ? "-" : Registries.ITEM.getId(t.item).toString();
            sb.append(t.kind.name()).append(' ').append(item).append(' ').append(t.remaining);
        }
        return sb.toString();
    }

    private void deserializeTasks(String data) {
        tasks.clear();
        activeTask = null;
        if (data == null || data.isBlank()) return;
        for (String part : data.split(";")) {
            String[] kv = part.trim().split(" ");
            if (kv.length != 3) continue;
            try {
                Task.Kind kind = Task.Kind.valueOf(kv[0]);
                int count = Integer.parseInt(kv[2]);
                Item item = "-".equals(kv[1]) ? null : Registries.ITEM.get(Identifier.tryParse(kv[1]));
                Task t = switch (kind) {
                    case MINE -> Task.mine(count);
                    case CHOP -> Task.chop(count);
                    case HARVEST -> Task.harvest();
                    case OBTAIN -> (item == null || item == Items.AIR) ? null : Task.obtain(item, count);
                };
                if (t != null) tasks.add(t);
            } catch (Exception ignored) {}
        }
    }

    // --- companion lifecycle ---

    /** She never despawns naturally — she's a companion, not an ambient mob. */
    @Override
    public boolean cannotDespawn() { return true; }

    @Override
    public boolean canImmediatelyDespawn(double distanceSquared) { return false; }

    /**
     * How far to sink her into a boat seat. A boat parks a passenger at {@code height/3}
     * (~0.6 for her 1.8 height) above the hull; that height is calibrated for the seated
     * render pose. She renders standing, so without this she hovered ~half a block above
     * the boat. {@link #getVehicleAttachmentPos} subtracts this from the seat, dropping her
     * down into the hull so she's actually sitting in the boat.
     */
    private static final double BOAT_SEAT_SINK = 0.5;

    @Override
    public Vec3d getVehicleAttachmentPos(Entity vehicle) {
        if (vehicle instanceof AbstractBoatEntity) {
            return new Vec3d(0.0, BOAT_SEAT_SINK, 0.0);
        }
        return super.getVehicleAttachmentPos(vehicle);
    }

    // --- task model ---

    /** A high-level commanded job the {@link WorkGoal} executes. */
    public static final class Task {
        public enum Kind { OBTAIN, MINE, CHOP, HARVEST }

        public final Kind kind;
        public final Item item;   // for OBTAIN
        public int remaining;     // remaining count (HARVEST ignores it)

        private Task(Kind kind, Item item, int remaining) {
            this.kind = kind;
            this.item = item;
            this.remaining = remaining;
        }

        public static Task mine(int count) { return new Task(Kind.MINE, null, count); }
        public static Task chop(int count) { return new Task(Kind.CHOP, null, count); }
        public static Task harvest() { return new Task(Kind.HARVEST, null, Integer.MAX_VALUE); }
        public static Task obtain(Item item, int count) { return new Task(Kind.OBTAIN, item, count); }

        /** Short human label for the panel task list. */
        public String label() {
            return switch (kind) {
                case MINE -> "挖矿 ×" + remaining;
                case CHOP -> "砍树 ×" + remaining;
                case HARVEST -> "收庄稼";
                case OBTAIN -> "收集 " + WorkUtil.displayName(item) + " ×" + remaining;
            };
        }
    }
}
