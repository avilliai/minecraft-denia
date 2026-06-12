package xyz.apollodorus.mcgf.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.item.Item;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity.Task;
import xyz.apollodorus.mcgf.entity.goal.WorkGoal;
import xyz.apollodorus.mcgf.entity.work.BoatUtil;
import xyz.apollodorus.mcgf.entity.work.WorkUtil;

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
        arr.add(fn("mine_ore", "去附近挖矿石。可选 count 指定块数。", countParam("要挖的矿石数量")));
        arr.add(fn("chop_logs", "去附近砍树收集原木。可选 count 指定块数。", countParam("要砍的原木数量")));
        arr.add(fn("harvest_crops", "去附近收割成熟的庄稼并自动补种。", emptyParams()));
        arr.add(fn("obtain_item", "玩家想要某样东西时使用：背包里有就直接给，没有就去附近采集。item 可用中文俗称。",
            itemCountParams("物品名（中/英），如 铁、煤、钻石、木头、小麦、diorite", "需要的数量")));
        arr.add(fn("remember_need", "记住玩家想要的东西，之后遇到就顺手收集。",
            itemCountParams("物品名（中/英）", "想要的数量")));
        arr.add(fn("give_items_to_player", "把你背包里的某样东西给玩家。",
            itemCountParams("物品名（中/英）", "给出的数量")));
        arr.add(fn("set_home", "把你当前所在位置记为家/基地。", emptyParams()));
        arr.add(fn("set_combat", "开启或关闭战斗护卫（看到怪是否主动持剑保护玩家）。", enabledParam()));
        arr.add(fn("set_gather", "开启或关闭闲逛时顺手采集资源。", enabledParam()));
        arr.add(fn("board_boat", "附近有船时，让你坐上最近的那条船（开船赶路前用）。", emptyParams()));
        arr.add(fn("leave_boat", "你正坐在船上时，从船上下来。", emptyParams()));
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
                    return ok();
                case "stop_moving":
                    gf.setFollowing(false);
                    gf.clearTask();
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
                default:
                    return "{\"ok\":false,\"error\":\"unknown tool\"}";
            }
        });
    }

    // --- tool bodies ---

    private static String obtain(GirlfriendEntity gf, JsonObject args) {
        Item item = WorkUtil.resolveItem(str(args, "item"));
        if (item == null) return err("不认识这个物品");
        int want = count(args, 1);
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

    private static String remember(GirlfriendEntity gf, JsonObject args) {
        Item item = WorkUtil.resolveItem(str(args, "item"));
        if (item == null) return err("不认识这个物品");
        gf.addNeed(item, count(args, 1));
        return ok();
    }

    private static String give(GirlfriendEntity gf, JsonObject args) {
        Item item = WorkUtil.resolveItem(str(args, "item"));
        if (item == null) return err("不认识这个物品");
        int given = gf.giveToOwner(item, count(args, gf.countItem(item)));
        return "{\"ok\":true,\"gave\":" + given + "}";
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
