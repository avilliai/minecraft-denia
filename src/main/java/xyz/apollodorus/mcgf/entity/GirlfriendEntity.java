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
    private BlockPos garrisonPos;         // 驻守锚点：非 null = 被命令留守此区域，不离开 garrisonRadius，优先于跟随

    private final SimpleInventory inventory = new SimpleInventory(27);
    private final Map<Item, Integer> needs = new LinkedHashMap<>();
    // 玩家明确说「不要采集」的物品（如不要铜矿）——顺手采集会跳过；玩家重新要它时自动解除。
    private final Set<Item> gatherBlacklist = new HashSet<>();
    private final List<Task> tasks = new ArrayList<>();   // ordered job queue (active + waiting)
    private Task activeTask;                               // the job WorkGoal is currently executing, or null
    private String activity = "闲着";      // human-readable, fed into the LLM context
    private int affection;                // 好感度 0..100, used as an LLM tone reference
    private int energy;                   // 虚质粒子 0..100 (hidden); fills on attack, spends on 蚀域
    private boolean inDefensiveCocoon;    // 防御茧期间暂停二形态悬浮、原地缩在茧里回血

    // owner-stationary tracking (drives idle gather / wander)
    private Vec3d lastOwnerPos;
    private int ownerStillTicks;

    // 注意力机制：以「主人在一片区域逗留」而非「死站不动」为闲事触发门槛，使她不被主人走几步打断；
    // 自主活动用 selfBusyUntil 做一个不断续期的承诺窗口，让跟随在主人仍逗留近旁时暂时让位（见 FollowOwnerGoal）。
    private Vec3d ownerAnchor;        // 主人逗留区域的锚点
    private long ownerAnchorTick;     // 锚点最近一次（重）设的游戏刻
    private long selfBusyUntil;       // 自主活动每 tick 续到的承诺截止刻（瞬态，不入存档）
    private long freeRoamUntil;       // AI 指令（go_fishing/tend_farm）放她就近自由开工的窗口（瞬态）

    // survival timers
    private int regenTimer;
    private int eatCooldown;
    private int gearCooldown;
    private long lastProjectileWallTick = Long.MIN_VALUE / 2;   // 远程防御墙的内置冷却

    // Independent chest perception: she remarks on a noticed container regardless of what she's doing
    // (mining / chopping / following / idle). Only WALKING over to investigate (PerceiveChestGoal) waits
    // for free time — so the spoken heads-up itself is never blocked by a task. Each chest is announced
    // at most once per session.
    private final Set<BlockPos> remarkedChests = new HashSet<>();
    // 玩家已亲自打开过的箱子：她不再感知它（既不再播报，也不再带路过去）。
    private final Set<BlockPos> openedChests = new HashSet<>();
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
        // 二形态低血量防御茧：危急时把自己围进虚质里缓慢回血（10分钟一次）。高于攻击，低于冲向受击主人。
        this.goalSelector.add(2, new xyz.apollodorus.mcgf.entity.goal.DefensiveCocoonGoal(this));
        this.goalSelector.add(3, new DaniyaAttackGoal(this));
        // 寻路信标带路：高于跟随/工作，低于战斗/冲向受击玩家——保护玩家仍最高优先级，战后自动继续前往落点。
        this.goalSelector.add(3, new xyz.apollodorus.mcgf.entity.goal.GuideToBeaconGoal(this));
        // 驻守：被命令留守某区域时把她拉回锚点；注册在跟随之前，驻守时跟随会让位（驻守优先于跟随）。
        this.goalSelector.add(4, new xyz.apollodorus.mcgf.entity.goal.GarrisonGoal(this));
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
        // 自主活动·钓鱼/打理菜地：与打盹同级（都是「主人在附近逗留时的悠闲事」），注册在打盹之后→平局时打盹优先。
        this.goalSelector.add(8, new xyz.apollodorus.mcgf.entity.goal.FishingGoal(this));
        this.goalSelector.add(8, new xyz.apollodorus.mcgf.entity.goal.TendFarmGoal(this));
        this.goalSelector.add(9, new WanderNearOwnerGoal(this));
        this.goalSelector.add(10, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
        this.goalSelector.add(11, new LookAroundGoal(this));
        // 安静发呆/赏景：最低优先级，只在别的都不想动时偶尔冒一下（配合沉默感知的「安静模式」）。
        this.goalSelector.add(12, new xyz.apollodorus.mcgf.entity.goal.QuietIdleGoal(this));

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

            // 逗留锚点：主人离锚点超过 idleRoamRadius 就重置（说明他在转移阵地），否则锚点稳定。
            // isOwnerLoitering() 据锚点稳定时长判断——主人在一片区域里走走停停，仍算「在附近逗留」，
            // 她就不会被几步移动从「自己的事」上拽走。
            double roam = b.idleRoamRadius;
            if (ownerAnchor == null || p.squaredDistanceTo(ownerAnchor) > roam * roam) {
                ownerAnchor = p;
                ownerAnchorTick = sw.getTime();
            }
        } else {
            ownerAnchor = null;   // 主人离线/不在 → 清掉，免得旧锚点被当成「在逗留」
        }

        if (--gearCooldown <= 0) {
            gearCooldown = 40;
            gearUp();
            // 形态一时握着专武泡泡杖（出生即附带）：空手且没在挖矿/战斗/做自己的事时自动握上。
            // 加 !isSelfBusy() 门：钓鱼等自主活动握着别的道具（如鱼竿）时不抢着换杖。
            if (!isFormTwo() && !isSelfBusy() && getTarget() == null && getTask() == null
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
        if (!isFormTwo() || inDefensiveCocoon) return;   // 围茧时不悬浮，原地缩在茧里
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
        if (!shouldAutoDeploy(sw, b)) return;
        if (AbilityManager.deployDomain(this)) {
            energy = 0;
            announceDomainEnter();
        }
    }

    /**
     * 蚀域不再「能量一满就开」——1形态是常态。即便能量满了，也只在局面真的吃紧时才认真起来：附近 10 格内
     * 怪够多（&ge; {@code domainMinHostiles}，默认至少 4 只）、或有强怪（最大生命 &gt; {@code domainStrongHostileHealth}，
     * 如铁傀儡/劫掠兽/凋灵）、或玩家/她自己状态不好。手动 /denia ult（{@link #tryDeployDomain}）不受此限。
     */
    private boolean shouldAutoDeploy(ServerWorld sw, GirlfriendConfig.Behavior b) {
        List<Entity> hostiles = sw.getOtherEntities(this, getBoundingBox().expand(10.0),
            e -> e instanceof HostileEntity && e.isAlive());
        if (hostiles.isEmpty()) return false;                       // 没怪绝不开
        if (hostiles.size() >= b.domainMinHostiles) return true;    // 怪够多（至少 4 只）
        for (Entity e : hostiles) {                                 // 有强怪（血量高于 24）
            if (e instanceof LivingEntity le && le.getMaxHealth() > b.domainStrongHostileHealth) return true;
        }
        PlayerEntity owner = getOwner();
        if (owner != null && owner.getHealth() <= 8.0f) return true;   // 玩家状态不好
        return getHealth() <= getMaxHealth() * 0.4f;                    // 她自己状态不好
    }

    /** Fire an AI-generated 切入幻灭之形 line (form-2 persona is already active by deploy time). */
    private void announceDomainEnter() {
        xyz.apollodorus.mcgf.ai.MoodManager.tryEventProactive(this,
            GirlfriendConfig.pickOne(ConfigManager.get().prompts.domainEnter));
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

    private static final int CHEST_REMARK_GAP = 600; // ~30s minimum between chest remarks (was 10s: too chatty in ruins)
    private static final int CHEST_CLUSTER_RADIUS = 10; // 播报一个箱子后，这半径内成片的箱子一并噤声（遗迹箱子成堆时别逐个念）
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
        suppressChestCluster(sw, found);                   // 连同附近成片的箱子一起噤声，别在遗迹里逐个念
        lastChestRemarkTick = now;
        // ~50% her own 达妮娅 perceive clip; otherwise (and for any other persona) an AI line.
        if (!xyz.apollodorus.mcgf.ai.Voice.maybePerceive(this) && MCGirlfriendMod.BRAIN != null) {
            MCGirlfriendMod.BRAIN.proactive(this, GirlfriendConfig.pickOne(ConfigManager.get().prompts.foundChest));
        }
    }

    /**
     * 播报一个箱子后，把它周围 {@link #CHEST_CLUSTER_RADIUS} 内成片的其它容器一并记为「已播报」——遗迹/沉船/矿道里
     * 箱子常常成堆，否则她会每隔十来秒就念叨一个新箱子，很吵。整片箱子只提示一次（只影响语音，带路探查的
     * {@link PerceiveChestGoal} 仍会各自安静地去看）。只在真的播报了才扫，成本可忽略。
     */
    private void suppressChestCluster(ServerWorld sw, BlockPos center) {
        int r = CHEST_CLUSTER_RADIUS;
        int ry = Math.min(r, 5);   // 箱子基本同层，纵向收窄
        for (BlockPos p : BlockPos.iterate(
                center.getX() - r, center.getY() - ry, center.getZ() - r,
                center.getX() + r, center.getY() + ry, center.getZ() + r)) {
            if (IS_CONTAINER.test(sw.getBlockState(p))) remarkedChests.add(p.toImmutable());
        }
    }

    /** 玩家亲自打开了某个箱子 → 她不再感知它：既加入「已播报」集（不再提），也加入「已打开」集（不再带路过去）。 */
    public void ignoreChest(BlockPos pos) {
        if (pos == null) return;
        BlockPos p = pos.toImmutable();
        openedChests.add(p);
        remarkedChests.add(p);
    }

    /** True once the player has opened this chest — perception goals skip it (see {@link PerceiveChestGoal}). */
    public boolean isChestIgnored(BlockPos pos) {
        return openedChests.contains(pos);
    }

    private void enforceAttribute(RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> attr, double want) {        EntityAttributeInstance inst = getAttributeInstance(attr);
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
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        // 形态二·领域「制空权」：浮空时被箭/三叉戟等投掷物命中，蚀域消解掉大部分来袭伤害，
        // 让她不再是远程怪的活靶子（形态一另有投掷物墙，不走这条）。
        float dealt = amount;
        boolean voided = false;
        if (isFormTwo() && AbilityManager.hasDomain(this) && isProjectileDamage(source)) {
            double resist = ConfigManager.get().behavior.form2ProjectileResist;
            if (resist > 0) { dealt = (float) (amount * (1.0 - Math.min(1.0, resist))); voided = true; }
        }
        boolean applied = super.damage(world, source, dealt);
        if (voided) world.spawnParticles(net.minecraft.particle.ParticleTypes.REVERSE_PORTAL,
            getX(), getY() + getHeight() * 0.6, getZ(), 12, 0.4, 0.5, 0.4, 0.03);
        // 受到远程攻击（箭/三叉戟等投掷物）时，一形态会在攻击来源方向竖起一道虚质墙挡投掷物（二形态手短、不触发）。
        if (applied && !isFormTwo()) maybeRaiseProjectileWall(world, source);
        return applied;
    }

    /** True when the hit came from a projectile (arrow / trident / etc.) — used by the form-2 domain resist. */
    private static boolean isProjectileDamage(DamageSource source) {
        return source.isIn(net.minecraft.registry.tag.DamageTypeTags.IS_PROJECTILE)
            || source.getSource() instanceof net.minecraft.entity.projectile.ProjectileEntity;
    }

    /**
     * 远程防御：被投掷物击中时，在朝攻击来源方向约 3 格处竖起一道 3 宽×2 高的虚质墙挡后续投掷物。只在空气/可替换处
     * 放置（不毁地形/建筑），方块约 6 秒后自动消失。内置冷却 {@code projectileWallCooldownSeconds}（默认 120 秒）。
     */
    private void maybeRaiseProjectileWall(ServerWorld world, DamageSource source) {
        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        if (!b.projectileWallEnabled) return;
        if (!(source.getSource() instanceof net.minecraft.entity.projectile.ProjectileEntity proj)) return;
        long cd = (long) Math.max(1, b.projectileWallCooldownSeconds) * 20L;
        if (world.getTime() - lastProjectileWallTick < cd) return;

        // 来袭方向：优先用射手位置；没有（如发射器）就用投掷物反向速度推算来处。
        Entity attacker = source.getAttacker();
        Vec3d from;
        if (attacker != null && attacker != this) {
            from = attacker.getEntityPos();
        } else {
            Vec3d v = proj.getVelocity();
            if (v.lengthSquared() < 1.0e-4) return;
            from = getEntityPos().subtract(v.normalize().multiply(5.0));
        }
        double dx = from.x - getX();
        double dz = from.z - getZ();
        net.minecraft.util.math.Direction toward = Math.abs(dx) >= Math.abs(dz)
            ? (dx >= 0 ? net.minecraft.util.math.Direction.EAST : net.minecraft.util.math.Direction.WEST)
            : (dz >= 0 ? net.minecraft.util.math.Direction.SOUTH : net.minecraft.util.math.Direction.NORTH);
        net.minecraft.util.math.Direction perp = toward.rotateYClockwise();

        BlockPos base = getBlockPos().offset(toward, 3);   // 离她约 3 格、朝来源方向
        long expiry = world.getTime() + cd;                // 持续整个冷却（默认 120 秒）后自动消失
        int placed = 0;
        for (int side = -1; side <= 1; side++) {           // 3 宽（沿垂直于来袭方向）
            BlockPos col = base.offset(perp, side);
            for (int h = 0; h <= 1; h++) {                 // 2 高
                BlockPos pos = col.up(h);
                BlockState st = world.getBlockState(pos);
                if (st.isAir() || st.isReplaceable()) {    // 只在空处竖墙，不替换地形/玩家建筑
                    AbilityManager.blocks().place(world, pos, expiry, null);
                    placed++;
                }
            }
        }
        if (placed > 0) {
            lastProjectileWallTick = world.getTime();
            world.spawnParticles(net.minecraft.particle.ParticleTypes.PORTAL,
                base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5, 20, 0.6, 0.6, 0.6, 0.05);
        }
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

    /** True while the form-2 defensive cocoon is active — suppresses the hover float so she sits enclosed. */
    public boolean isInDefensiveCocoon() { return inDefensiveCocoon; }
    public void setInDefensiveCocoon(boolean v) { this.inDefensiveCocoon = v; }

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

    // --- 驻守（留守某区域，优先于跟随）---

    public BlockPos getGarrisonPos() { return garrisonPos; }
    public boolean isGarrisoned() { return garrisonPos != null; }
    /** Tell her to hold an area (she won't leave {@code garrisonRadius} of {@code pos}); overrides follow. */
    public void setGarrison(BlockPos pos) { this.garrisonPos = pos == null ? null : pos.toImmutable(); }
    /** Release the garrison (follow / come / capture / release all do this). */
    public void clearGarrison() { this.garrisonPos = null; }

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
     * 注意力机制核心：主人在一片区域（idleRoamRadius 内）逗留 ~1.5s 以上即视为「在附近逗留」。比
     * {@link #isOwnerStationary()}（死站不动）宽松——主人走走停停地挖矿/搭建时仍成立，所以她能安心做
     * 自己的事而不被几步移动打断。空闲活动（钓鱼/种田/顺手采集/闲逛/发呆）都以它为触发门槛；只有打盹
     * 仍用更严格的 {@link #isOwnerStationary()}。
     */
    public boolean isOwnerLoitering() {
        if (ownerAnchor == null) return false;
        return getEntityWorld().getTime() - ownerAnchorTick > 30L;   // 锚点稳定 ~1.5s
    }

    /** 自主活动每 tick 续一小段承诺窗口，使跟随在主人仍逗留近旁时暂时让位；窗口有界，活动停后数秒自动失效。 */
    public void markSelfBusy(int ticks) {
        long until = getEntityWorld().getTime() + ticks;
        if (until > selfBusyUntil) selfBusyUntil = until;
    }
    public boolean isSelfBusy() { return getEntityWorld().getTime() < selfBusyUntil; }
    public void clearSelfBusy() { selfBusyUntil = 0L; }

    /** AI 指令（go_fishing/tend_farm）开的「就近自由开工」窗口：让对应 Goal 绕过「主人逗留」门槛立刻开始。 */
    public void setFreeRoam(int ticks) { this.freeRoamUntil = getEntityWorld().getTime() + ticks; }
    public boolean isFreeRoam() { return getEntityWorld().getTime() < freeRoamUntil; }

    /**
     * Distance at which {@link FollowOwnerGoal} starts pulling her back. While the owner is loitering
     * nearby she's let off the leash out to {@code idleRoamRadius}, so her probabilistic idle wandering /
     * foraging / 自己的事 isn't yanked back the instant she strays past {@code followStartDistance} — that
     * constant cross-the-line/get-pulled-back was the old "pacing back and forth". The moment the
     * owner stops loitering (covers ground), isOwnerLoitering() flips false and normal follow resumes.
     */
    public double effectiveFollowStartDistance() {
        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        if (isFollowing() && isOwnerLoitering()) return Math.max(b.followStartDistance, b.idleRoamRadius);
        return b.followStartDistance;
    }

    // --- inventory / needs / task ---

    public SimpleInventory getInventory() { return inventory; }

    public Map<Item, Integer> getNeeds() { return needs; }

    public void addNeed(Item item, int count) {
        if (item == null || count <= 0) return;
        needs.merge(item, count, Integer::sum);
    }

    // --- 采集黑名单（玩家说「不要采集这个」）---

    public Set<Item> getGatherBlacklist() { return gatherBlacklist; }
    public boolean isGatherBlacklisted(Item item) { return item != null && gatherBlacklist.contains(item); }
    public void addGatherBlacklist(Item item) { if (item != null) gatherBlacklist.add(item); }
    /** Remove an item from the gather blacklist (e.g. the player now wants it). Returns true if it was on it. */
    public boolean removeGatherBlacklist(Item item) { return item != null && gatherBlacklist.remove(item); }

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
        if (garrisonPos != null) {
            view.putBoolean("HasGarrison", true);
            view.putInt("GarrisonX", garrisonPos.getX());
            view.putInt("GarrisonY", garrisonPos.getY());
            view.putInt("GarrisonZ", garrisonPos.getZ());
        }
        view.putString("Inv", serializeInventory());
        view.putString("Needs", serializeNeeds());
        view.putString("GatherBlacklist", serializeBlacklist());
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
        if (view.getBoolean("HasGarrison", false)) {
            garrisonPos = new BlockPos(view.getInt("GarrisonX", 0), view.getInt("GarrisonY", 0), view.getInt("GarrisonZ", 0));
        }
        view.getOptionalString("Inv").ifPresent(this::deserializeInventory);
        view.getOptionalString("Needs").ifPresent(this::deserializeNeeds);
        view.getOptionalString("GatherBlacklist").ifPresent(this::deserializeBlacklist);
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

    private String serializeBlacklist() {
        StringBuilder sb = new StringBuilder();
        for (Item it : gatherBlacklist) {
            if (sb.length() > 0) sb.append(';');
            sb.append(Registries.ITEM.getId(it));
        }
        return sb.toString();
    }

    private void deserializeBlacklist(String data) {
        gatherBlacklist.clear();
        if (data == null || data.isBlank()) return;
        for (String part : data.split(";")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            Item item = Registries.ITEM.get(Identifier.tryParse(s));
            if (item != Items.AIR) gatherBlacklist.add(item);
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
