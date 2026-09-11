package xyz.apollodorus.mcgf.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.ai.MemoryStore;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity.Task;
import xyz.apollodorus.mcgf.entity.goal.WorkGoal;
import xyz.apollodorus.mcgf.entity.work.BoatUtil;
import xyz.apollodorus.mcgf.entity.work.CraftUtil;
import xyz.apollodorus.mcgf.entity.work.ActionExecutor;
import xyz.apollodorus.mcgf.entity.work.ContainerInteractUtil;
import xyz.apollodorus.mcgf.entity.work.ItemAppraiser;
import xyz.apollodorus.mcgf.entity.work.WorkUtil;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The tool surface exposed to the LLM. Each tool mutates entity state on the
 * server thread and returns a small JSON result fed back into the conversation.
 * Mirrors the capability set of the original lib/chatTools.js (follow / stop /
 * obtain / chop / mine / harvest / give / set_home / toggles).
 */
public final class Tools {
    private Tools() {}

    /** OpenAI-style tool/function definitions. */
    public static JsonArray definitions() {
        JsonArray arr = new JsonArray();
        arr.add(fn("follow_player", "玩家让你过来、跟着走时使用。", emptyParams()));
        arr.add(fn("come_to_player", "你想主动靠近玩家一次时使用（会开始跟随）。", emptyParams()));
        arr.add(fn("stop_moving", "玩家让你停下、在原地等待时使用（会停止当前任务）。", emptyParams()));
        arr.add(fn("guard_area", "玩家让你留守/守住这一带/待在这儿别乱跑时使用：你会守在当前位置约15格内、不再跟着他走（之后让你跟上/过来会自动解除驻守）。", emptyParams()));
        arr.add(fn("mine_ore", "去附近挖矿石。可选 count 指定块数。", countParam("要挖的矿石数量；玩家没说具体数就别填，默认16")));
        arr.add(fn("chop_logs", "去附近砍树收集原木。可选 count 指定块数。", countParam("要砍的原木数量；玩家没说具体数就别填，默认16")));
        arr.add(fn("harvest_crops", "去附近收割成熟的庄稼并自动补种。", emptyParams()));
        arr.add(fn("obtain_item", "玩家想要某样东西时使用：背包里有就直接给，没有就去附近采集。item 可用中文俗称。",
            itemCountParams("物品名（中/英），如 铁、煤、钻石、木头、小麦、diorite", "需要的数量；玩家明确说了数量才填，没说就别填（采集类默认16）")));
        arr.add(fn("craft_item", "用背包里的材料合成某样东西（工具、鱼竿、木板、木棍、盔甲…都行）：缺木板/木棍这类中间材料会自动先做出来；缺矿石/线这类采不到又合不成的原始材料会告诉你缺什么，你再让她去采。做好的东西放进她背包（要给玩家需再调 give_items_to_player）。",
            itemCountParams("要合成的物品名（中/英），如 木镐、鱼竿、工作台、箱子、铁剑", "要做的数量；不填默认1")));
        arr.add(fn("remember_need", "记住玩家想要的东西，之后遇到就顺手收集。",
            itemCountParams("物品名（中/英）", "想要的数量")));
        arr.add(fn("give_items_to_player", "把你背包里的某样东西给玩家。",
            itemCountParams("物品名（中/英）", "给出的数量")));
        arr.add(fn("set_home", "把你当前所在位置记为家/基地。", emptyParams()));
        arr.add(fn("set_combat", "开启或关闭战斗护卫（看到怪是否主动持剑保护玩家）。", enabledParam()));
        arr.add(fn("set_gather", "开启或关闭闲逛时顺手采集资源。", enabledParam()));
        arr.add(fn("board_boat", "附近有船时，让你坐上最近的那条船（开船赶路前用）。", emptyParams()));
        arr.add(fn("leave_boat", "你正坐在船上时，从船上下来。", emptyParams()));
        arr.add(fn("go_fishing", "你想去钓会儿鱼、或玩家让你去钓鱼时使用（背包里有鱼竿、附近有水才钓得成）。", emptyParams()));
        arr.add(fn("tend_farm", "你想去打理自家菜地、或玩家让你去种地时使用（设了家、背包里有种子才行）。", emptyParams()));
        arr.add(fn("avoid_item", "玩家说「别采集某样东西了」（比如别采铜矿）时使用：把它加入采集黑名单，之后顺手采集不再碰它。",
            itemOnlyParams("不想再采的物品名（中/英），如 铜、铜矿、橡木")));
        arr.add(fn("allow_item", "玩家说「又可以采集某样东西了」时使用：把它移出采集黑名单。",
            itemOnlyParams("重新允许采集的物品名（中/英）")));
                // --- New Daniya Agent Gameplay & Interaction Tools ---
        JsonObject useItemProps = new JsonObject();
        useItemProps.add("item", prop("string", "?????????? ??, ??, ???, ???, ????"));
        arr.add(fn("use_item", "???????????????????????", object(useItemProps)));

        JsonObject pillarProps = new JsonObject();
        pillarProps.add("height", prop("integer", "??????????1-5?"));
        arr.add(fn("build_pillar", "?????????????????????????????", object(pillarProps)));

        JsonObject placeProps = new JsonObject();
        placeProps.add("item", prop("string", "?????????? ??, ??, ???, ??"));
        placeProps.add("x", prop("integer", "??X??????????????"));
        placeProps.add("y", prop("integer", "??Y??????"));
        placeProps.add("z", prop("integer", "??Z??????"));
        arr.add(fn("place_block", "?????????????????????????", object(placeProps)));

        JsonObject shelterProps = new JsonObject();
        shelterProps.add("danger", prop("string", "?????? ???, ????, ???"));
        arr.add(fn("emergency_shelter", "??????????????????????????", object(shelterProps)));

        JsonObject chestProps = new JsonObject();
        chestProps.add("action", prop("string", "?????deposit(?????????), withdraw(??????), loot_all(??????????)"));
        chestProps.add("item", prop("string", "????withdraw??????"));
        chestProps.add("count", prop("integer", "???????1"));
        arr.add(fn("interact_chest", "?????????????", object(chestProps)));

        JsonObject smeltProps = new JsonObject();
        smeltProps.add("item", prop("string", "????????????? ??, ???, ????"));
        smeltProps.add("count", prop("integer", "???????1"));
        arr.add(fn("interact_furnace", "???????????????????????", object(smeltProps)));

        arr.add(fn("evaluate_inventory", "????????????????????????????????????????", emptyParams()));

        return arr;
    }

    /** Execute a tool call. Returns a JSON string result. */
    public static String execute(MinecraftServer server, GirlfriendEntity gf, ServerPlayerEntity owner,
                                 String name, JsonObject args) {
        return onServer(server, () -> {
            if (owner != null && gf.getOwnerUuid() == null) gf.setOwnerUuid(owner.getUuid());
            switch (name) {
                case "follow_player":
                case "come_to_player":
                    if (owner != null) gf.setOwnerUuid(owner.getUuid());
                    gf.setFollowing(true);
                    gf.clearGarrison();   // 跟上/过来 → 自动解除驻守
                    return ok();
                case "stop_moving":
                    gf.setFollowing(false);
                    gf.clearTask();
                    gf.getNavigation().stop();
                    return ok();
                case "guard_area":
                    gf.setGarrison(gf.getBlockPos());
                    gf.setFollowing(false);   // 留守模式：不跟走，但仍可在区域内闲逛/守着
                    gf.getNavigation().stop();
                    return ok();
                case "mine_ore": {
                    Task t = Task.mine(count(args, ConfigManager.get().behavior.defaultGatherCount));
                    gf.enqueueTask(t);
                    return queued(gf, t);
                }
                case "chop_logs": {
                    Task t = Task.chop(count(args, ConfigManager.get().behavior.defaultGatherCount));
                    gf.enqueueTask(t);
                    return queued(gf, t);
                }
                case "harvest_crops": {
                    Task t = Task.harvest();
                    gf.enqueueTask(t);
                    return queued(gf, t);
                }
                case "obtain_item":
                    return obtain(gf, args);
                case "craft_item":
                    return craft(gf, args);
                case "remember_need":
                    return remember(gf, args);
                case "give_items_to_player":
                    return give(gf, args);
                case "set_home":
                    gf.setHomePos(gf.getBlockPos());
                    return ok();
                case "set_combat":
                    gf.setCombatEnabled(enabled(args, true));
                    return "{\"ok\":true,\"combat\":" + gf.isCombatEnabled() + "}";
                case "set_gather":
                    gf.setGatherEnabled(enabled(args, true));
                    return "{\"ok\":true,\"gather\":" + gf.isGatherEnabled() + "}";
                case "board_boat":
                    return "{\"ok\":true,\"boarded\":" + BoatUtil.boardNearest(gf, 6.0) + "}";
                case "leave_boat":
                    return "{\"ok\":true,\"left\":" + BoatUtil.disembark(gf) + "}";
                case "go_fishing": {
                    gf.setFreeRoam(1200);   // 60s「就近自由开工」窗口：让 FishingGoal 越过逗留门槛立刻开始
                    boolean hasRod = gf.countItem(Items.FISHING_ROD) > 0;
                    boolean craftedRod = false;
                    if (!hasRod && ConfigManager.get().behavior.autoCraft) {   // 没竿就现做一根（够料/能凑齐中间材料）
                        CraftUtil.Result r = CraftUtil.craft(gf, Items.FISHING_ROD, 1);
                        if (r.ok()) { hasRod = true; craftedRod = true; craftFx(gf);
                            MemoryStore.record(gf, "没鱼竿，自己现做了一根，去钓鱼。"); }
                    }
                    return "{\"ok\":true,\"hasRod\":" + hasRod + ",\"craftedRod\":" + craftedRod
                        + ",\"waterNearby\":" + hasNearbyWater(gf) + "}";
                }
                case "tend_farm":
                    gf.setFreeRoam(1200);
                    return "{\"ok\":true,\"hasHome\":" + (gf.getHomePos() != null)
                        + ",\"hasSeeds\":" + hasSeeds(gf) + "}";
                case "avoid_item": {
                    Item item = WorkUtil.resolveItem(str(args, "item"));
                    if (item == null) return err("不认识这个物品");
                    gf.addGatherBlacklist(item);
                    gf.getNeeds().remove(item);   // 别一边拉黑、一边还记着要它
                    return "{\"ok\":true,\"avoided\":\"" + WorkUtil.displayName(item) + "\"}";
                }
                case "allow_item": {
                    Item item = WorkUtil.resolveItem(str(args, "item"));
                    if (item == null) return err("不认识这个物品");
                    return "{\"ok\":true,\"wasAvoided\":" + gf.removeGatherBlacklist(item) + "}";
                }
                                case "use_item": {
                    Item item = WorkUtil.resolveItem(str(args, "item"));
                    if (item == null) return err("???????");
                    boolean success = ActionExecutor.useItem(gf, item);
                    return "{\"ok\":" + success + "}";
                }
                case "build_pillar": {
                    int height = args != null && args.has("height") ? args.get("height").getAsInt() : 1;
                    boolean success = ActionExecutor.scaffoldPillar(gf, height);
                    return "{\"ok\":" + success + "}";
                }
                case "place_block": {
                    String itemName = str(args, "item");
                    Item item = WorkUtil.resolveItem(itemName);
                    if (item == null) return err("???????");
                    net.minecraft.util.math.BlockPos p;
                    if (args != null && args.has("x") && args.has("y") && args.has("z")) {
                        p = new net.minecraft.util.math.BlockPos(
                            args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt());
                    } else {
                        p = gf.getBlockPos().offset(gf.getHorizontalFacing());
                    }
                    boolean success = ActionExecutor.placeBlockAt(gf, p, item);
                    return "{\"ok\":" + success + "}";
                }
                case "emergency_shelter": {
                    boolean success = ActionExecutor.buildSimpleShelter(gf);
                    return "{\"ok\":" + success + "}";
                }
                case "interact_chest": {
                    if (gf.getEntityWorld() instanceof ServerWorld sw) {
                        java.util.List<net.minecraft.util.math.BlockPos> containers = ContainerInteractUtil.findNearbyContainers(sw, gf.getBlockPos(), 6);
                        if (containers.isEmpty()) return err("???????????");
                        net.minecraft.util.math.BlockPos cPos = containers.get(0);
                        String action = str(args, "action");
                        if ("withdraw".equalsIgnoreCase(action)) {
                            String it = str(args, "item");
                            Item item = WorkUtil.resolveItem(it);
                            if (item == null) return err("???????");
                            int cnt = args != null && args.has("count") ? args.get("count").getAsInt() : 1;
                            int taken = ContainerInteractUtil.takeItemFromContainer(sw, cPos, gf, item, cnt);
                            return "{\"ok\":true,\"taken\":" + taken + "}";
                        } else {
                            String it = str(args, "item");
                            Item item = it != null ? WorkUtil.resolveItem(it) : null;
                            int cnt = args != null && args.has("count") ? args.get("count").getAsInt() : 64;
                            int dep = ContainerInteractUtil.depositItemToContainer(sw, cPos, gf, item, cnt);
                            return "{\"ok\":true,\"deposited\":" + dep + "}";
                        }
                    }
                    return err("??????");
                }
                case "interact_furnace": {
                    if (gf.getEntityWorld() instanceof ServerWorld sw) {
                        java.util.List<net.minecraft.util.math.BlockPos> furnaces = ContainerInteractUtil.findNearbyFurnaces(sw, gf.getBlockPos(), 6);
                        if (furnaces.isEmpty()) return err("????????");
                        String res = ContainerInteractUtil.interactWithFurnace(sw, furnaces.get(0), gf);
                        return "{\"ok\":true,\"result\":\"" + res.replace('"', ' ') + "\"}";
                    }
                    return err("??????");
                }
                case "evaluate_inventory": {
                    int upgraded = gf.evaluateAndEquipBest();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < gf.getInventory().size(); i++) {
                        ItemStack s = gf.getInventory().getStack(i);
                        if (!s.isEmpty()) {
                            ItemAppraiser.Evaluation ev = ItemAppraiser.evaluate(s, gf);
                            sb.append(s.getName().getString()).append('(').append(ev.daniyaComment()).append("); ");
                        }
                    }
                    return "{\"ok\":true,\"upgradedEquipments\":" + upgraded + ",\"evaluations\":\"" + sb.toString().replace('"', ' ') + "\"}";
                }
                default:
                    return "{\"ok\":false,\"error\":\"unknown tool\"}";
            }
        });
    }

    // --- tool bodies ---

    private static String obtain(GirlfriendEntity gf, JsonObject args) {
        Item item = WorkUtil.resolveItem(str(args, "item"));
        if (item == null) return err("不认识这个物品");
        gf.removeGatherBlacklist(item);   // 玩家现在又要它了 → 自动解除黑名单（用户要求：拉黑后又要就自动恢复并去弄）
        // 没指定数量时：可采集的方块/矿物默认一批(=defaultGatherCount, 16)，一次性物品(工具/食物)默认 1。
        // 修复「让她挖圆石却只挖一个」：圆石走 obtain（非矿石），以前默认写死 1。
        int fallback = WorkGoal.isGettable(Task.obtain(item, 1))
            ? ConfigManager.get().behavior.defaultGatherCount : 1;
        int want = count(args, fallback);
        int have = gf.countItem(item);
        int giveNow = Math.min(have, want);
        int given = giveNow > 0 ? gf.giveToOwner(item, giveNow) : 0;
        int remaining = want - given;
        if (remaining <= 0) return "{\"ok\":true,\"gave\":" + given + "}";
        Task t = Task.obtain(item, remaining);
        if (!WorkGoal.isGettable(t)) return "{\"ok\":true,\"gave\":" + given + ",\"canSource\":false}";
        boolean nearby = WorkGoal.hasNearbyTarget(gf, t);
        gf.enqueueTask(t); // queued; if nothing's nearby she keeps it as a waiting job and retries when idle
        return "{\"ok\":true,\"gave\":" + given + ",\"willGather\":" + remaining + ",\"nearby\":" + nearby + "}";
    }

    /** Standard result for a freshly-queued gather job, telling the LLM whether a target is nearby now. */
    private static String queued(GirlfriendEntity gf, Task t) {
        return "{\"ok\":true,\"nearby\":" + WorkGoal.hasNearbyTarget(gf, t) + "}";
    }

    /**
     * 便携合成：用背包材料把 {@code item} 做出来（递归补中间材料，缺原始材料如实上报），做好的留在她背包里。
     * 由 LLM 的 {@code craft_item} 调用；她自己钓鱼缺竿时也走 {@link CraftUtil}。
     */
    private static String craft(GirlfriendEntity gf, JsonObject args) {
        Item item = WorkUtil.resolveItem(str(args, "item"));
        if (item == null) return err("不认识这个物品");
        if (!ConfigManager.get().behavior.autoCraft) return err("合成没开");
        CraftUtil.Result r = CraftUtil.craft(gf, item, count(args, 1));
        if (r.ok()) { craftFx(gf); MemoryStore.record(gf, "给他做了" + WorkUtil.displayName(item) + "。"); }
        StringBuilder sb = new StringBuilder("{\"ok\":").append(r.ok())
            .append(",\"crafted\":").append(r.crafted())
            .append(",\"item\":\"").append(WorkUtil.displayName(item)).append('"');
        if (r.missing() != null) sb.append(",\"missing\":\"").append(r.missing().replace('"', ' ')).append('"');
        return sb.append('}').toString();
    }

    /** 合成动作的表演：挥手 + 一小簇虚质/末地烛粒子 + 清脆一响（纯表现，无机制）。 */
    private static void craftFx(GirlfriendEntity gf) {
        gf.swingHand(Hand.MAIN_HAND);
        if (gf.getEntityWorld() instanceof ServerWorld sw) {
            double x = gf.getX(), y = gf.getY() + 1.0, z = gf.getZ();
            sw.spawnParticles(ParticleTypes.PORTAL, x, y, z, 12, 0.4, 0.5, 0.4, 0.02);
            sw.spawnParticles(ParticleTypes.END_ROD, x, y, z, 6, 0.3, 0.4, 0.3, 0.01);
            sw.playSound(null, x, y, z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.NEUTRAL, 0.7f, 1.4f);
        }
    }

    private static String remember(GirlfriendEntity gf, JsonObject args) {
        Item item = WorkUtil.resolveItem(str(args, "item"));
        if (item == null) return err("不认识这个物品");
        gf.removeGatherBlacklist(item);   // 记下来要的东西就别在黑名单里了
        gf.addNeed(item, count(args, 1));
        return ok();
    }

    private static String give(GirlfriendEntity gf, JsonObject args) {
        Item item = WorkUtil.resolveItem(str(args, "item"));
        if (item == null) return err("不认识这个物品");
        int given = gf.giveToOwner(item, count(args, gf.countItem(item)));
        return "{\"ok\":true,\"gave\":" + given + "}";
    }

    private static final Predicate<BlockState> IS_WATER = st -> st.getFluidState().isIn(FluidTags.WATER);

    /** Is there open water within idle range — so the LLM can say whether she can actually fish here. */
    private static boolean hasNearbyWater(GirlfriendEntity gf) {
        int r = ConfigManager.get().behavior.idleGatherRadius;
        return WorkUtil.findNearestBlock(gf.getEntityWorld(), gf.getBlockPos(), r, IS_WATER, null) != null;
    }

    /** Does she carry any plantable seed — so the LLM can say whether she can actually tend a farm. */
    private static boolean hasSeeds(GirlfriendEntity gf) {
        return gf.countItem(Items.WHEAT_SEEDS) > 0 || gf.countItem(Items.CARROT) > 0
            || gf.countItem(Items.POTATO) > 0 || gf.countItem(Items.BEETROOT_SEEDS) > 0;
    }

    // --- argument helpers ---

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static int count(JsonObject o, int fallback) {
        try {
            if (o != null && o.has("count") && o.get("count").isJsonPrimitive()) {
                int c = o.get("count").getAsInt();
                if (c > 0) return Math.min(c, 256);
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private static boolean enabled(JsonObject o, boolean fallback) {
        try {
            if (o != null && o.has("enabled") && o.get("enabled").isJsonPrimitive()) {
                return o.get("enabled").getAsBoolean();
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private static String ok() { return "{\"ok\":true}"; }
    private static String err(String m) { return "{\"ok\":false,\"error\":\"" + m + "\"}"; }

    // --- schema builders ---

    private static JsonObject fn(String name, String desc, JsonObject params) {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", desc);
        function.add("parameters", params);
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        return tool;
    }

    private static JsonObject emptyParams() {
        JsonObject p = new JsonObject();
        p.addProperty("type", "object");
        p.add("properties", new JsonObject());
        return p;
    }

    private static JsonObject countParam(String desc) {
        JsonObject props = new JsonObject();
        props.add("count", prop("integer", desc));
        return object(props);
    }

    private static JsonObject enabledParam() {
        JsonObject props = new JsonObject();
        props.add("enabled", prop("boolean", "true 开启，false 关闭"));
        JsonObject p = object(props);
        JsonArray req = new JsonArray();
        req.add("enabled");
        p.add("required", req);
        return p;
    }

    private static JsonObject itemCountParams(String itemDesc, String countDesc) {
        JsonObject props = new JsonObject();
        props.add("item", prop("string", itemDesc));
        props.add("count", prop("integer", countDesc));
        JsonObject p = object(props);
        JsonArray req = new JsonArray();
        req.add("item");
        p.add("required", req);
        return p;
    }

    private static JsonObject itemOnlyParams(String itemDesc) {
        JsonObject props = new JsonObject();
        props.add("item", prop("string", itemDesc));
        JsonObject p = object(props);
        JsonArray req = new JsonArray();
        req.add("item");
        p.add("required", req);
        return p;
    }

    private static JsonObject object(JsonObject props) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "object");
        p.add("properties", props);
        return p;
    }

    private static JsonObject prop(String type, String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("description", desc);
        return o;
    }

    private static String onServer(MinecraftServer server, Supplier<String> fn) {
        if (server.isOnThread()) return fn.get();
        try {
            return server.submit(fn::get).get();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"server busy\"}";
        }
    }
}
