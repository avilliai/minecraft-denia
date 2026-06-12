package xyz.apollodorus.mcgf.client.screen;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.screen.CompanionScreenData;
import xyz.apollodorus.mcgf.screen.CompanionScreenHandler;
import xyz.apollodorus.mcgf.screen.ModScreens;

/**
 * The companion panel: her name, an affection bar + intimacy tier + current
 * activity, then her gear and backpack above the player's inventory. Drawn
 * procedurally (no bespoke GUI texture) so it ships without art assets.
 *
 * <p>Everything is painted in {@link #drawBackground} at absolute coordinates so
 * it doesn't depend on how 1.21.11 translates the foreground matrix; the default
 * title/label rendering is suppressed by overriding {@link #drawForeground}.
 */
public class CompanionScreen extends HandledScreen<CompanionScreenHandler> {
    private static final int PANEL    = 0xF0140C1C; // translucent dark plum
    private static final int SLOT_BG  = 0xFF2A2030;
    private static final int SLOT_HL  = 0xFF4A3A55;
    private static final int TEXT     = 0xFFF2D9E6;
    private static final int TEXT_DIM = 0xFFB9A7C4;
    private static final int HEART    = 0xFFE86A9A;
    private static final int BAR_BG   = 0xFF3A2E44;
    private static final int HP_GOOD  = 0xFF7FE07F; // full
    private static final int HP_WARN  = 0xFFE8C24A; // hurt
    private static final int HP_LOW   = 0xFFE05A5A; // critical

    public CompanionScreen(CompanionScreenHandler handler, PlayerInventory inv, Text title) {
        super(handler, inv, title);
        this.backgroundWidth = 176;
        this.backgroundHeight = 230; // extra bottom row beyond the 208 of slots, for the config button
    }

    @Override
    protected void init() {
        super.init();
        // Two buttons in the extra bottom row: the in-game config editor (so players never have to find
        // config/mcgf.json by hand) and the task list (view / cancel her queued jobs).
        int bw = 80, bh = 18, gap = 6;
        int bx = this.x + (this.backgroundWidth - (bw * 2 + gap)) / 2;
        int by = this.y + 206;
        addDrawableChild(ButtonWidget.builder(Text.literal("接口配置"),
                b -> { if (this.client != null) this.client.setScreen(new CompanionConfigScreen()); })
            .dimensions(bx, by, bw, bh).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("任务列表"),
                b -> { if (this.client != null) this.client.setScreen(new CompanionTaskScreen(this.handler.data())); })
            .dimensions(bx + bw + gap, by, bw, bh).build());
    }

    @Override
    protected void drawBackground(DrawContext ctx, float delta, int mouseX, int mouseY) {
        int px = this.x, py = this.y;
        ctx.fill(px, py, px + this.backgroundWidth, py + this.backgroundHeight, PANEL);

        // Slot frames for every handler slot.
        for (Slot slot : this.handler.slots) {
            int sx = px + slot.x, sy = py + slot.y;
            ctx.fill(sx - 1, sy - 1, sx + 17, sy + 17, SLOT_HL);
            ctx.fill(sx, sy, sx + 16, sy + 16, SLOT_BG);
        }

        // Header: name, affection bar, tier + activity.
        CompanionScreenData d = this.handler.data();
        int aff = d == null ? 0 : d.affection();
        ctx.drawText(this.textRenderer, this.title, px + 8, py + 6, TEXT, false);

        // Live HP, read straight off the tracked entity so the panel always matches her real
        // health — the opening payload is a one-shot snapshot, but her HP keeps changing while
        // the panel is open (regen, combat, eating), so a snapshot would drift out of sync.
        GirlfriendEntity gf = companionEntity();
        if (gf != null) {
            float max = gf.getMaxHealth();
            int cur = Math.max(0, (int) Math.ceil(gf.getHealth()));
            float frac = max > 0 ? gf.getHealth() / max : 0f;
            int hpColor = frac <= 0.3f ? HP_LOW : (frac < 1.0f ? HP_WARN : HP_GOOD);
            String hp = "生命 " + cur + "/" + (int) Math.ceil(max);
            int w = this.textRenderer.getWidth(hp);
            ctx.drawText(this.textRenderer, Text.literal(hp), px + this.backgroundWidth - 8 - w, py + 6, hpColor, false);
        }

        int barX = px + 8, barY = py + 17, barW = 160, barH = 6;
        ctx.fill(barX, barY, barX + barW, barY + barH, BAR_BG);
        int filled = MathHelper.clamp(aff * barW / 100, 0, barW);
        ctx.fill(barX, barY, barX + filled, barY + barH, HEART);

        String info = "♥ " + aff + "/100  " + GirlfriendEntity.tierFor(aff);
        if (d != null && d.activity() != null && !d.activity().isBlank()) info += "  · " + d.activity();
        ctx.drawText(this.textRenderer, Text.literal(info), barX, barY + 8, TEXT, false);

        ctx.drawText(this.textRenderer, this.playerInventoryTitle, px + 8, py + 115, TEXT_DIM, false);
    }

    /** Suppress the default title/inventory labels — we paint our own in drawBackground. */
    @Override
    protected void drawForeground(DrawContext ctx, int mouseX, int mouseY) {
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        this.drawMouseoverTooltip(ctx, mouseX, mouseY);
    }

    /** The live client-side entity behind this panel (or null) — used for her real-time HP. */
    private GirlfriendEntity companionEntity() {
        CompanionScreenData d = this.handler.data();
        if (d == null || this.client == null || this.client.world == null) return null;
        return this.client.world.getEntityById(d.entityId()) instanceof GirlfriendEntity gf ? gf : null;
    }

    public static void register() {
        HandledScreens.register(ModScreens.COMPANION, CompanionScreen::new);
    }
}
