package xyz.apollodorus.mcgf.client.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;
import xyz.apollodorus.mcgf.net.ConfigUpdatePayload;

/**
 * In-game editor for 达妮娅's LLM + TTS connection settings, opened from the companion panel's button
 * so players never have to hunt down config/mcgf.json. Pre-fills from the live config (integrated
 * server → the client shares the same {@link ConfigManager} static); 保存 sends the values to the
 * server, which applies them to the running config and writes the file. Only the connection
 * essentials are editable here; everything else stays file-only.
 *
 * <p>Opening this from the companion panel closes that container (vanilla screen-switch behaviour),
 * so both buttons return to the game rather than to a now-stale backpack screen.
 */
public class CompanionConfigScreen extends Screen {
    private static final int TITLE = 0xFFF2D9E6;
    private static final int LABEL = 0xFFB9A7C4;

    private TextFieldWidget llmUrl, llmKey, llmModel, ttsUrl, ttsRef, ttsPrompt;
    private boolean ttsEnabled;
    private boolean woodEnabled;
    private boolean pickupEnabled;
    private boolean cropsEnabled;
    private boolean storageEnabled;
    private boolean lightEnabled;
    private boolean fishEnabled;
    private boolean farmEnabled;
    private ButtonWidget ttsToggle;
    private ButtonWidget woodToggle;
    private ButtonWidget pickupToggle;
    private ButtonWidget cropsToggle;
    private ButtonWidget storageToggle;
    private ButtonWidget lightToggle;
    private ButtonWidget fishToggle;
    private ButtonWidget farmToggle;

    public CompanionConfigScreen() {
        super(Text.literal("达妮娅 · 接口配置"));
    }

    @Override
    protected void init() {
        GirlfriendConfig cfg = ConfigManager.get();
        ttsEnabled = cfg.tts.enabled;
        woodEnabled = cfg.behavior.autoGatherWood;
        pickupEnabled = cfg.behavior.autoPickup;
        cropsEnabled = cfg.behavior.autoGatherCrops;
        storageEnabled = cfg.behavior.autoStorage;
        lightEnabled = cfg.behavior.autoLight;
        fishEnabled = cfg.behavior.autoFish;
        farmEnabled = cfg.behavior.autoFarm;

        int w = Math.min(360, this.width - 40);
        int x = (this.width - w) / 2;
        int half = (w - 10) / 2;
        int rightX = x + w - half;
        int rowH = 28;
        int y = Math.max(30, (this.height - 230) / 2);

        // 文本框分两列（左 LLM、右 TTS），各三行——高度减半，default GUI 尺寸下也放得下。
        llmUrl    = field(x, y, half, cfg.llm.baseURL);
        ttsUrl    = field(rightX, y, half, cfg.tts.url);          y += rowH;
        llmKey    = field(x, y, half, cfg.llm.apiKey);
        ttsRef    = field(rightX, y, half, cfg.tts.refAudioPath); y += rowH;
        llmModel  = field(x, y, half, cfg.llm.model);
        ttsPrompt = field(rightX, y, half, cfg.tts.promptText);   y += rowH + 4;

        // 开关：每行两个，共四行。
        ttsToggle     = toggle(x,      y, half, this::ttsLabel,     () -> ttsEnabled = !ttsEnabled);
        woodToggle    = toggle(rightX, y, half, this::woodLabel,    () -> woodEnabled = !woodEnabled);    y += 24;
        pickupToggle  = toggle(x,      y, half, this::pickupLabel,  () -> pickupEnabled = !pickupEnabled);
        cropsToggle   = toggle(rightX, y, half, this::cropsLabel,   () -> cropsEnabled = !cropsEnabled);  y += 24;
        storageToggle = toggle(x,      y, half, this::storageLabel, () -> storageEnabled = !storageEnabled);
        lightToggle   = toggle(rightX, y, half, this::lightLabel,   () -> lightEnabled = !lightEnabled);  y += 24;
        fishToggle    = toggle(x,      y, half, this::fishLabel,    () -> fishEnabled = !fishEnabled);
        farmToggle    = toggle(rightX, y, half, this::farmLabel,    () -> farmEnabled = !farmEnabled);

        // 保存/取消固定贴在屏幕底部——无论 GUI 尺寸大小都看得见、点得到（之前会被挤出屏幕外）。
        int btnY = this.height - 28;
        addDrawableChild(ButtonWidget.builder(Text.literal("保存"), b -> save())
            .dimensions(x, btnY, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("取消"), b -> closeToGame())
            .dimensions(rightX, btnY, half, 20).build());
    }

    /** A toggle button that flips a boolean (via {@code onToggle}) and refreshes its own label. */
    private ButtonWidget toggle(int x, int y, int w, java.util.function.Supplier<Text> label, Runnable onToggle) {
        ButtonWidget[] ref = new ButtonWidget[1];
        ref[0] = ButtonWidget.builder(label.get(), b -> {
            onToggle.run();
            ref[0].setMessage(label.get());
        }).dimensions(x, y, w, 20).build();
        addDrawableChild(ref[0]);
        return ref[0];
    }

    private TextFieldWidget field(int x, int y, int w, String value) {
        TextFieldWidget f = new TextFieldWidget(this.textRenderer, x, y, w, 16, Text.empty());
        f.setMaxLength(512);
        f.setText(value == null ? "" : value);
        addDrawableChild(f);
        return f;
    }

    private Text ttsLabel() {
        return Text.literal("语音 (TTS)：" + (ttsEnabled ? "开" : "关"));
    }

    private Text woodLabel() {
        return Text.literal("自动撸树：" + (woodEnabled ? "开" : "关"));
    }

    private Text pickupLabel() {
        return Text.literal("自动拾取：" + (pickupEnabled ? "开" : "关"));
    }

    private Text cropsLabel() {
        return Text.literal("自动收菜：" + (cropsEnabled ? "开" : "关"));
    }

    private Text storageLabel() {
        return Text.literal("自动存储：" + (storageEnabled ? "开" : "关"));
    }

    private Text lightLabel() {
        return Text.literal("自动照明：" + (lightEnabled ? "开" : "关"));
    }

    private Text fishLabel() {
        return Text.literal("自动钓鱼：" + (fishEnabled ? "开" : "关"));
    }

    private Text farmLabel() {
        return Text.literal("打理菜地：" + (farmEnabled ? "开" : "关"));
    }

    private void save() {
        ClientPlayNetworking.send(new ConfigUpdatePayload(
            llmUrl.getText().trim(), llmKey.getText().trim(), llmModel.getText().trim(),
            ttsEnabled, ttsUrl.getText().trim(), ttsRef.getText(), ttsPrompt.getText(),
            woodEnabled, pickupEnabled, cropsEnabled, storageEnabled, lightEnabled, fishEnabled, farmEnabled));
        closeToGame();
    }

    private void closeToGame() {
        if (this.client != null) this.client.setScreen(null);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, TITLE);
        label(ctx, llmUrl, "LLM 地址 baseURL");
        label(ctx, llmKey, "LLM 密钥 apiKey");
        label(ctx, llmModel, "对话模型 model");
        label(ctx, ttsUrl, "TTS 地址 url");
        label(ctx, ttsRef, "参考音频 refAudio");
        label(ctx, ttsPrompt, "参考文本 promptText");
    }

    private void label(DrawContext ctx, TextFieldWidget f, String text) {
        ctx.drawText(this.textRenderer, Text.literal(text), f.getX(), f.getY() - 10, LABEL, false);
    }
}
