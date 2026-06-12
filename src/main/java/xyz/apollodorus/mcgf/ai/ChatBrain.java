package xyz.apollodorus.mcgf.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.entity.work.WorkUtil;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The LLM brain. Holds per-player chat history, builds the world context, calls
 * the LLM (with a single tool round-trip), and routes the reply through
 * {@link SpeechBus}. Heavy work runs on a daemon executor so the server thread
 * is never blocked. Mirrors the askAI flow of the original plugins/Chat.js.
 */
public final class ChatBrain {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcgf/brain");
    private static final Gson GSON = new Gson();

    private final LlmClient llm = new LlmClient();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final Set<UUID> proactivePending = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor;

    public ChatBrain() {
        AtomicInteger n = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "mcgf-brain-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Called on the server thread when a nearby owner sends normal chat. */
    public void handleChat(ServerPlayerEntity player, GirlfriendEntity gf, String message) {
        UUID id = player.getUuid();
        if (!pending.add(id)) {
            SpeechBus.speak(gf, "我还在想上一句，稍等我一下下~");
            return;
        }
        MinecraftServer server = gf.getEntityWorld().getServer();
        if (server == null) {
            pending.remove(id);
            return;
        }
        String context = buildContext(gf, player); // read world on the server thread
        executor.submit(() -> {
            try {
                orchestrate(server, gf, player, id, message, context);
            } catch (Exception e) {
                LOGGER.warn("[mcgf] brain error: {}", e.toString());
                server.execute(() -> SpeechBus.speak(gf, "刚才走神了，你再说一次嘛~"));
            } finally {
                pending.remove(id);
            }
        });
    }

    private void orchestrate(MinecraftServer server, GirlfriendEntity gf, ServerPlayerEntity player,
                             UUID id, String message, String context) {
        GirlfriendConfig cfg = ConfigManager.get();
        boolean useTools = cfg.llm.toolsEnabled;
        Session session = session(id);

        String system = cfg.fillName(gf.isFormTwo() ? cfg.llm.systemPromptForm2 : cfg.llm.systemPrompt)
            + "\n\n[环境]\n" + context;
        if (!session.memory.isBlank()) system += "\n\n[关于你和他的记忆]\n" + session.memory;

        JsonArray messages = new JsonArray();
        messages.add(msg("system", system));
        for (JsonObject h : session.window) messages.add(h);
        JsonObject userMsg = msg("user", message);
        messages.add(userMsg);

        LOGGER.info("[mcgf] <- {}: {}", player.getName().getString(), message);

        JsonObject assistant = llm.complete(messages, useTools ? Tools.definitions() : null);
        if (assistant == null) {
            server.execute(() -> SpeechBus.speak(gf, "网络好像有点问题，过会儿再聊嘛~"));
            return;
        }

        String reply = getString(assistant, "content");
        JsonArray toolCalls = assistant.has("tool_calls") && assistant.get("tool_calls").isJsonArray()
            ? assistant.getAsJsonArray("tool_calls") : null;

        if (useTools && toolCalls != null && !toolCalls.isEmpty()) {
            messages.add(assistant);
            for (JsonElement el : toolCalls) {
                JsonObject call = el.getAsJsonObject();
                JsonObject function = call.getAsJsonObject("function");
                String name = getString(function, "name");
                JsonObject args = parseArgs(getString(function, "arguments"));
                LOGGER.info("[mcgf] tool {} {}", name, args);
                String result = Tools.execute(server, gf, player, name, args);
                JsonObject toolMsg = new JsonObject();
                toolMsg.addProperty("role", "tool");
                toolMsg.addProperty("tool_call_id", getString(call, "id"));
                toolMsg.addProperty("content", result);
                messages.add(toolMsg);
            }
            JsonObject second = llm.complete(messages, null);
            if (second != null) {
                String r2 = getString(second, "content");
                if (r2 != null && !r2.isBlank()) reply = r2;
            }
        }

        if (reply == null || reply.isBlank()) reply = "好呀~";
        final String finalReply = reply;

        recordRound(session, userMsg, finalReply, cfg);

        LOGGER.info("[mcgf] -> {}: {}", player.getName().getString(), finalReply);
        server.execute(() -> {
            gf.addAffection(1); // chatting with her warms her up
            SpeechBus.speak(gf, finalReply);
        });

        // Fold the conversation into a compact memory once it gets long — done after the reply is
        // already on its way, so the player never waits on the extra summarization round-trip.
        maybeCompact(session, cfg);
    }

    private String buildContext(GirlfriendEntity gf, ServerPlayerEntity player) {
        BlockPos pos = gf.getBlockPos();
        double dist = gf.distanceTo(player);
        long tod = gf.getEntityWorld().getTimeOfDay() % 24000L;
        String daypart = tod < 12000 ? "白天" : (tod < 13000 ? "黄昏" : "夜晚");
        String biome = gf.getEntityWorld().getBiome(pos)
            .getKey().map(k -> k.getValue().getPath()).orElse("unknown");
        int hostiles = countHostiles(gf);
        return String.join("\n",
            "你的生命：" + (int) gf.getHealth() + "/" + (int) gf.getMaxHealth(),
            "你的坐标：(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")，生物群系：" + biome + "，" + daypart,
            "对话玩家「" + player.getName().getString() + "」距你约 " + String.format("%.1f", dist) + " 格",
            "你的状态：" + (gf.isFollowing() ? "跟随玩家" : "原地待命")
                + "，正在「" + gf.getActivity() + "」"
                + "，护卫" + (gf.isCombatEnabled() ? "开" : "关")
                + "，顺手采集" + (gf.isGatherEnabled() ? "开" : "关")
                + (gf.getHomePos() != null ? "，已设家" : "，未设家"),
            "附近敌对生物：" + (hostiles > 0 ? hostiles + " 只（注意保护玩家）" : "无"),
            "你手上拿着：" + heldItemName(gf) + "（采集/挖矿时会自动换上你背包里最好的工具）",
            "你的背包：" + inventorySummary(gf),
            "玩家想要的东西（遇到就收）：" + needsSummary(gf),
            "你对玩家的好感度：" + gf.getAffection() + "/100（" + gf.affectionTier() + "）"
                + "——好感越高越黏人、越主动撒娇亲近；偏低则更慵懒、带点试探的小距离。请让此刻语气贴合这个亲密度。"
        );
    }

    private static int countHostiles(GirlfriendEntity gf) {
        List<Entity> list = gf.getEntityWorld().getOtherEntities(gf,
            gf.getBoundingBox().expand(16.0), e -> e instanceof HostileEntity && e.isAlive());
        return list.size();
    }

    private static String heldItemName(GirlfriendEntity gf) {
        ItemStack main = gf.getEquippedStack(EquipmentSlot.MAINHAND);
        return main.isEmpty() ? "空手" : WorkUtil.displayName(main.getItem());
    }

    private static String inventorySummary(GirlfriendEntity gf) {
        Map<Item, Integer> agg = new LinkedHashMap<>();
        var inv = gf.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty()) agg.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        if (agg.isEmpty()) return "空空的";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<Item, Integer> e : agg.entrySet()) {
            if (n++ >= 6) { sb.append("…"); break; }
            if (sb.length() > 0) sb.append("、");
            sb.append(WorkUtil.displayName(e.getKey())).append('x').append(e.getValue());
        }
        return sb.toString();
    }

    private static String needsSummary(GirlfriendEntity gf) {
        if (gf.getNeeds().isEmpty()) return "暂时没有";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Item, Integer> e : gf.getNeeds().entrySet()) {
            if (sb.length() > 0) sb.append("、");
            sb.append(WorkUtil.displayName(e.getKey())).append('x').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * Fire a one-off proactive line driven by a situation note (low health, night,
     * hostiles, finished a task, etc.). Runs off-thread; silently no-ops if she's
     * already busy with a reply. Caller must invoke on the server thread.
     */
    public void proactive(GirlfriendEntity gf, String situation) {
        PlayerEntity owner = gf.getOwner();
        if (!(owner instanceof ServerPlayerEntity sp)) return;
        UUID key = gf.getUuid();
        if (pending.contains(sp.getUuid()) || !proactivePending.add(key)) return;
        MinecraftServer server = gf.getEntityWorld().getServer();
        if (server == null) { proactivePending.remove(key); return; }

        String context = buildContext(gf, sp);
        final String memory = session(sp.getUuid()).memory;
        GirlfriendConfig cfg = ConfigManager.get();
        final boolean formTwo = gf.isFormTwo();
        executor.submit(() -> {
            try {
                String system = cfg.fillName(formTwo ? cfg.llm.ephemeralSystemForm2 : cfg.llm.ephemeralSystem)
                    + "\n\n[环境]\n" + context;
                if (!memory.isBlank()) system += "\n\n[关于你和他的记忆]\n" + memory;
                system += "\n\n[此刻]\n" + situation;
                JsonArray messages = new JsonArray();
                messages.add(msg("system", system));
                messages.add(msg("user", "（请你结合此刻的情境，主动、自然地说一句话）"));
                JsonObject a = llm.complete(messages, null);
                String reply = a == null ? null : getString(a, "content");
                if (reply != null && !reply.isBlank()) {
                    final String line = reply;
                    server.execute(() -> SpeechBus.speak(gf, line));
                }
            } catch (Exception e) {
                LOGGER.debug("[mcgf] proactive error: {}", e.toString());
            } finally {
                proactivePending.remove(key);
            }
        });
    }

    public void clearHistory(UUID id) {
        sessions.remove(id);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    // --- helpers ---

    private Session session(UUID id) {
        return sessions.computeIfAbsent(id, k -> new Session());
    }

    /** Append one finished exchange to both the request window and the memory backlog. */
    private static void recordRound(Session s, JsonObject userMsg, String reply, GirlfriendConfig cfg) {
        JsonObject assistantMsg = msg("assistant", reply);
        s.window.addLast(userMsg);
        s.window.addLast(assistantMsg);
        trim(s.window, Math.max(2, cfg.llm.maxHistory));
        s.sinceCompaction.addLast(userMsg);
        s.sinceCompaction.addLast(assistantMsg);
        // Safety cap so a session that never compacts (e.g. summarizer offline) can't grow forever.
        trim(s.sinceCompaction, Math.max(8, cfg.llm.compressAfterRounds * 2 + 8));
        s.rounds++;
    }

    /**
     * Once a session passes {@code compressAfterRounds}, summarize everything since the last
     * compaction (plus the prior memory) into a compact memory string and reset the counter.
     * On a summarizer failure we keep the counter high so it retries on the next round.
     */
    private void maybeCompact(Session s, GirlfriendConfig cfg) {
        int threshold = Math.max(4, cfg.llm.compressAfterRounds);
        if (s.rounds < threshold || s.sinceCompaction.isEmpty()) return;
        String summary = summarize(s, cfg);
        if (summary == null || summary.isBlank()) {
            LOGGER.warn("[mcgf] memory compaction failed; will retry next round");
            return;
        }
        int cap = Math.max(80, cfg.llm.memoryMaxChars);
        s.memory = summary.length() > cap ? summary.substring(0, cap) : summary;
        s.sinceCompaction.clear();
        s.rounds = 0;
        LOGGER.info("[mcgf] compacted conversation memory -> {} chars", s.memory.length());
    }

    /** Ask the LLM to merge prior memory + recent dialogue into one short first-person memory. */
    private String summarize(Session s, GirlfriendConfig cfg) {
        StringBuilder convo = new StringBuilder();
        if (!s.memory.isBlank()) convo.append("【已有记忆】\n").append(s.memory).append("\n\n【新的对话】\n");
        for (JsonObject m : s.sinceCompaction) {
            String role = getString(m, "role");
            String content = getString(m, "content");
            if (content == null || content.isBlank()) continue;
            convo.append("user".equals(role) ? "玩家：" : "你：").append(content).append('\n');
        }
        String sys = "你在帮《鸣潮》达妮娅整理她对这位玩家的长期记忆。把【已有记忆】和【新的对话】合并、去重、提炼成一段"
            + "不超过" + Math.max(80, cfg.llm.memoryMaxChars) + "字的第一人称记忆：记住玩家的名字/称呼、喜好与在意的东西、"
            + "你们一起做过或约定过的事、当前的关系氛围。只输出这段记忆本身，不要解释、不要列表、不要引号。";
        JsonArray messages = new JsonArray();
        messages.add(msg("system", sys));
        messages.add(msg("user", convo.toString()));
        JsonObject a = llm.complete(messages, null);
        return a == null ? null : getString(a, "content");
    }

    private static void trim(Deque<JsonObject> hist, int max) {
        while (hist.size() > max) hist.removeFirst();
    }

    /** Per-player chat memory: a small request window, a backlog awaiting compaction, and a memory. */
    private static final class Session {
        final Deque<JsonObject> window = new ArrayDeque<>();          // sent verbatim each request
        final Deque<JsonObject> sinceCompaction = new ArrayDeque<>(); // folded into memory on compaction
        volatile String memory = "";
        int rounds;
    }

    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private static String getString(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return null;
        JsonElement e = o.get(key);
        return e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static JsonObject parseArgs(String raw) {
        if (raw == null || raw.isBlank()) return new JsonObject();
        try {
            JsonObject o = GSON.fromJson(raw, JsonObject.class);
            return o != null ? o : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }
}
