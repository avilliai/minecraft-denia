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
import java.util.ArrayList;
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
    /**
     * Per-player coalescing inbox. While a reply is being generated, any further messages from the same
     * player are appended here (and the in-flight result is discarded) instead of being rejected — so two
     * quick questions get MERGED into one fresh request rather than answered separately. See {@link #runInbox}.
     */
    private final Map<UUID, Inbox> inboxes = new ConcurrentHashMap<>();
    /** Cap on un-answered messages merged together, so spamming can't grow the request unbounded. */
    private static final int MERGE_CAP = 6;
    private final Set<UUID> proactivePending = ConcurrentHashMap.newKeySet();
    /**
     * Per-girlfriend ring buffer of her most recent spoken lines (both chat replies and proactive
     * remarks), keyed by the gf entity UUID. Injected into later prompts as a "don't repeat these"
     * block — the main cure for the repetitive 黏人套话, since {@link #proactive} is otherwise stateless.
     */
    private final Map<UUID, Deque<String>> recentLines = new ConcurrentHashMap<>();
    private static final int RECENT_MAX = 8;
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
        MoodManager.notePlayerChatted(gf);   // 重置沉默感知：玩家说话了 → 回到「活跃」热度
        MinecraftServer server = gf.getEntityWorld().getServer();
        if (server == null) return;

        // 把消息投进该玩家的合并收件箱。若已有 worker 在处理上一句：不再回「还在想上一句」，而是把这条并进去
        // ——worker 会丢弃上一次还没说出口的结果，把两次提问合并成最新的一次重新回答（见 runInbox）。
        Inbox inbox = inboxes.computeIfAbsent(id, k -> new Inbox());
        boolean startWorker;
        synchronized (inbox) {
            inbox.messages.add(message);
            while (inbox.messages.size() > MERGE_CAP) inbox.messages.remove(0);
            inbox.epoch++;
            startWorker = !inbox.running;
            if (startWorker) inbox.running = true;
        }
        LOGGER.info("[mcgf] <- {}: {}", player.getName().getString(), message);
        if (startWorker) {
            executor.submit(() -> runInbox(server, gf, player, id, inbox));
        }
    }

    /**
     * Drains a player's inbox until empty. Each pass merges all un-answered messages into one request and
     * runs the LLM. If new messages arrived DURING that round (epoch advanced), the result is discarded and
     * the loop re-merges everything (old + new) into a single fresh question — so rapid-fire messages collapse
     * into one coherent answer instead of stacking up or getting a "稍等" brush-off. Runs on a brain thread;
     * exactly one worker is live per player (guarded by {@link Inbox#running}), so the player's Session is
     * only ever touched sequentially.
     */
    private void runInbox(MinecraftServer server, GirlfriendEntity gf, ServerPlayerEntity player, UUID id, Inbox inbox) {
        try {
            while (true) {
                int startEpoch;
                String merged;
                synchronized (inbox) {
                    if (inbox.messages.isEmpty()) { inbox.running = false; return; }
                    startEpoch = inbox.epoch;
                    merged = String.join("\n", inbox.messages);
                }

                // Context must be read on the server thread (entity/world state isn't thread-safe).
                String context;
                try {
                    context = server.submit(() -> buildContext(gf, player)).get();
                } catch (Exception e) {
                    LOGGER.warn("[mcgf] brain ctx error: {}", e.toString());
                    synchronized (inbox) { inbox.messages.clear(); inbox.running = false; }
                    return;
                }

                Reply reply;
                try {
                    reply = orchestrate(server, gf, player, merged, context);
                } catch (Exception e) {
                    LOGGER.warn("[mcgf] brain error: {}", e.toString());
                    synchronized (inbox) { inbox.messages.clear(); inbox.running = false; }
                    server.execute(() -> SpeechBus.speak(gf, "刚才走神了，你再说一次嘛~"));
                    return;
                }

                boolean shouldDeliver;
                synchronized (inbox) {
                    if (inbox.epoch == startEpoch) {
                        inbox.messages.clear();   // 这批正好答完，期间没有新消息
                        shouldDeliver = true;
                    } else {
                        shouldDeliver = false;    // 期间来了新消息 → 丢弃这次结果，下一轮合并重答
                    }
                }
                if (shouldDeliver) {
                    deliver(server, gf, player, reply);
                } else {
                    LOGGER.info("[mcgf] 新消息打断了上一次生成 → 丢弃旧结果，合并提问重答");
                }
                // 继续循环：缓冲空了就在顶部把 running 置 false 退出；否则把刚到的新消息也答掉。
            }
        } catch (Exception e) {
            LOGGER.warn("[mcgf] brain loop error: {}", e.toString());
            synchronized (inbox) { inbox.running = false; }
        }
    }

    /**
     * Run one LLM round (with a single tool round-trip) for {@code message} and return the reply WITHOUT
     * recording or speaking it — so {@link #runInbox} can discard a result that's been superseded. A null
     * {@link Reply#finalReply} means the LLM/network failed.
     */
    private Reply orchestrate(MinecraftServer server, GirlfriendEntity gf, ServerPlayerEntity player,
                              String message, String context) {
        GirlfriendConfig cfg = ConfigManager.get();
        boolean useTools = cfg.llm.toolsEnabled;
        Session session = session(player.getUuid());

        String system = cfg.fillName(gf.isFormTwo() ? cfg.llm.systemPromptForm2 : cfg.llm.systemPrompt)
            + "\n\n[环境]\n" + context;
        if (!session.memory.isBlank()) system += "\n\n[关于你和他的记忆]\n" + session.memory;
        system += episodicBlock(player.getUuid());
        system += recentBlock(gf.getUuid());

        JsonArray messages = new JsonArray();
        messages.add(msg("system", system));
        for (JsonObject h : session.window) messages.add(h);
        JsonObject userMsg = msg("user", message);
        messages.add(userMsg);

        JsonObject assistant = llm.complete(messages, useTools ? Tools.definitions() : null);
        if (assistant == null) {
            return new Reply(userMsg, null);   // 网络/LLM 失败：交给 deliver 说一句网络问题，不入历史
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
        return new Reply(userMsg, reply);
    }

    /** Record the finished exchange into history/memory and speak it. Called only for a non-superseded result. */
    private void deliver(MinecraftServer server, GirlfriendEntity gf, ServerPlayerEntity player, Reply reply) {
        if (reply.finalReply() == null) {
            server.execute(() -> SpeechBus.speak(gf, "网络好像有点问题，过会儿再聊嘛~"));
            return;
        }
        GirlfriendConfig cfg = ConfigManager.get();
        Session session = session(player.getUuid());

        recordRound(session, reply.userMsg(), reply.finalReply(), cfg);
        recordSpoken(gf.getUuid(), reply.finalReply());

        LOGGER.info("[mcgf] -> {}: {}", player.getName().getString(), reply.finalReply());
        server.execute(() -> {
            gf.addAffection(1); // chatting with her warms her up
            SpeechBus.speak(gf, reply.finalReply());
        });

        // Fold the conversation into a compact memory once it gets long — done after the reply is
        // already on its way, so the player never waits on the extra summarization round-trip.
        maybeCompact(player.getUuid(), session, cfg);
    }

    /** A computed-but-not-yet-delivered chat result. {@code finalReply == null} = LLM/network failure. */
    private record Reply(JsonObject userMsg, String finalReply) {}

    /** Per-player coalescing buffer: messages awaiting an answer, an epoch (bumped per message), and a worker flag. */
    private static final class Inbox {
        final java.util.List<String> messages = new ArrayList<>();
        int epoch;
        boolean running;
    }

    /** True while a chat request for this player is mid-flight, so proactive lines hold off. */
    private boolean isHandlingChat(UUID playerId) {
        Inbox inbox = inboxes.get(playerId);
        if (inbox == null) return false;
        synchronized (inbox) { return inbox.running; }
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
            "漂泊者现状：生命 " + (int) player.getHealth() + "/" + (int) player.getMaxHealth()
                + "，饥饿 " + player.getHungerManager().getFoodLevel() + "/20，手持 " + heldItemName(player)
                + "，所在 " + dimensionName(player) + "（这些是他的状态，不是你的；关心他但别像播报员念数据）",
            "你的状态：" + (gf.isFollowing() ? "跟随玩家" : "原地待命")
                + "，正在「" + gf.getActivity() + "」"
                + "，护卫" + (gf.isCombatEnabled() ? "开" : "关")
                + "，顺手采集" + (gf.isGatherEnabled() ? "开" : "关")
                + (gf.getHomePos() != null ? "，已设家" : "，未设家"),
            "附近敌对生物：" + (hostiles > 0 ? hostiles + " 只（注意保护玩家）" : "无"),
            "你手上拿着：" + heldItemName(gf) + "（采集/挖矿时会自动换上你背包里最好的工具）",
            "你的背包：" + inventorySummary(gf),
            xyz.apollodorus.mcgf.entity.work.ItemAppraiser.summarizeInventory(gf),
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

    private static String heldItemName(ServerPlayerEntity player) {
        ItemStack main = player.getMainHandStack();
        return main.isEmpty() ? "空手" : WorkUtil.displayName(main.getItem());
    }

    /** 玩家当前所在维度的中文短名，供上下文里她自然贴合此刻(下界/末地/主世界)。 */
    private static String dimensionName(ServerPlayerEntity player) {
        var key = player.getEntityWorld().getRegistryKey();
        return key == net.minecraft.world.World.NETHER ? "下界"
            : key == net.minecraft.world.World.END ? "末地" : "主世界";
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
    /**
     * Queue a one-off proactive line. Returns false if she's busy or can't speak right now
     * (caller should not consume an event cooldown in that case).
     */
    public boolean proactive(GirlfriendEntity gf, String situation) {
        PlayerEntity owner = gf.getOwner();
        if (!(owner instanceof ServerPlayerEntity sp)) return false;
        UUID key = gf.getUuid();
        if (isHandlingChat(sp.getUuid()) || !proactivePending.add(key)) return false;
        MinecraftServer server = gf.getEntityWorld().getServer();
        if (server == null) { proactivePending.remove(key); return false; }

        String context = buildContext(gf, sp);
        final String memory = session(sp.getUuid()).memory;
        final UUID gfId = gf.getUuid();
        final UUID ownerId = sp.getUuid();
        GirlfriendConfig cfg = ConfigManager.get();
        final boolean formTwo = gf.isFormTwo();
        executor.submit(() -> {
            try {
                String system = cfg.fillName(formTwo ? cfg.llm.ephemeralSystemForm2 : cfg.llm.ephemeralSystem)
                    + "\n\n[环境]\n" + context;
                if (!memory.isBlank()) system += "\n\n[关于你和他的记忆]\n" + memory;
                system += episodicBlock(ownerId);
                system += recentBlock(gfId);
                system += "\n\n[此刻]\n" + situation;
                JsonArray messages = new JsonArray();
                messages.add(msg("system", system));
                messages.add(msg("user", cfg.llm.proactiveUserPrompt));
                JsonObject a = llm.complete(messages, null);
                String reply = normalizeProactiveLine(a == null ? null : getString(a, "content"));
                if (reply != null && !reply.isBlank()) {
                    final String line = reply;
                    recordSpoken(gfId, line);
                    server.execute(() -> SpeechBus.speak(gf, line));
                }
            } catch (Exception e) {
                LOGGER.debug("[mcgf] proactive error: {}", e.toString());
            } finally {
                proactivePending.remove(key);
            }
        });
        return true;
    }

    /** Proactive lines are always one short spoken utterance — never a chat burst. */
    private static String normalizeProactiveLine(String reply) {
        if (reply == null) return null;
        reply = reply.trim();
        int bar = reply.indexOf("||");
        if (bar >= 0) reply = reply.substring(0, bar).trim();
        if (reply.length() >= 2 && reply.startsWith("\"") && reply.endsWith("\"")) {
            reply = reply.substring(1, reply.length() - 1).trim();
        }
        if (reply.length() >= 2 && reply.startsWith("「") && reply.endsWith("」")) {
            reply = reply.substring(1, reply.length() - 1).trim();
        }
        return reply;
    }

    public void clearHistory(UUID id) {
        sessions.remove(id);
    }

    /** Remember a line she just said (de-duplicated, capped to the last {@link #RECENT_MAX}). */
    private void recordSpoken(UUID gfId, String line) {
        if (line == null || line.isBlank()) return;
        Deque<String> q = recentLines.computeIfAbsent(gfId, k -> new ArrayDeque<>());
        synchronized (q) {
            q.remove(line);            // move a repeat to the back rather than keeping two copies
            q.addLast(line);
            while (q.size() > RECENT_MAX) q.removeFirst();
        }
    }

    /** A "don't repeat these" block built from her recent lines, or "" if she hasn't spoken yet. */
    private String recentBlock(UUID gfId) {
        Deque<String> q = recentLines.get(gfId);
        if (q == null) return "";
        StringBuilder sb = new StringBuilder();
        synchronized (q) {
            if (q.isEmpty()) return "";
            for (String l : q) sb.append("- ").append(l).append('\n');
        }
        return "\n\n[你最近已经说过下面这些话——这次务必换个话题、换种说法，绝不重复这些句式或用词]\n" + sb;
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    // --- helpers ---

    private Session session(UUID id) {
        return sessions.computeIfAbsent(id, k -> {
            Session s = new Session();
            s.memory = MemoryStore.getMemory(k);   // 载入该玩家的持久化长期记忆（游戏重启不忘）
            return s;
        });
    }

    /**
     * A "你们一起经历过的事" block built from the persistent episodic memory — the main lever for 活人感/记忆:
     * she can naturally bring up shared experiences (一起钓鱼、挖到钻石、去过下界、你死过一次…). Empty if none yet.
     */
    private static String episodicBlock(UUID ownerId) {
        List<String> eps = MemoryStore.episodes(ownerId);
        if (eps.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
            "\n\n[你们一起经历过的事——都是真发生过的，可以像老朋友那样偶尔自然提一句，但别一次性倒完、也别每句都提]\n");
        int start = Math.max(0, eps.size() - 10);   // 只取最近 10 条，控制 prompt 体积
        for (int i = start; i < eps.size(); i++) sb.append("- ").append(eps.get(i)).append('\n');
        return sb.toString();
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
    private void maybeCompact(UUID ownerId, Session s, GirlfriendConfig cfg) {
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
        MemoryStore.setMemory(ownerId, s.memory);   // 落盘：长期记忆持久化，重启不忘
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
