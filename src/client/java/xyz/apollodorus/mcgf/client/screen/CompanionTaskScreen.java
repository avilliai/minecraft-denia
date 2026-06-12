package xyz.apollodorus.mcgf.client.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import xyz.apollodorus.mcgf.net.TaskCancelPayload;
import xyz.apollodorus.mcgf.screen.CompanionScreenData;

import java.util.ArrayList;
import java.util.List;

/**
 * Her task list, opened from the companion panel's 任务列表 button: each queued job on its own row
 * with a 取消 button, plus 返回. The list is a snapshot taken when the panel opened (passed in via
 * {@link CompanionScreenData}); canceling sends a {@link TaskCancelPayload} to the server and rebuilds
 * the screen from the locally-updated list (reopen the panel to refresh against the live queue).
 */
public class CompanionTaskScreen extends Screen {
    private static final int TITLE = 0xFFF2D9E6;
    private static final int TEXT  = 0xFFF2D9E6;
    private static final int DIM   = 0xFFB9A7C4;
    private static final int ROW_H = 24;

    private final int entityId;
    private final List<String> rows;
    private int listX, listW, listTop;

    public CompanionTaskScreen(CompanionScreenData data) {
        this(data == null ? -1 : data.entityId(), parse(data == null ? null : data.tasksData()));
    }

    private CompanionTaskScreen(int entityId, List<String> rows) {
        super(Text.literal("达妮娅 · 任务列表"));
        this.entityId = entityId;
        this.rows = new ArrayList<>(rows);
    }

    private static List<String> parse(String tasksData) {
        List<String> out = new ArrayList<>();
        if (tasksData != null && !tasksData.isBlank()) {
            for (String s : tasksData.split("\n")) if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    @Override
    protected void init() {
        listW = Math.min(320, this.width - 40);
        listX = (this.width - listW) / 2;
        int blockH = Math.max(1, rows.size()) * ROW_H;
        listTop = Math.max(40, (this.height - blockH - 60) / 2);

        int cancelW = 52;
        for (int i = 0; i < rows.size(); i++) {
            final int idx = i;
            addDrawableChild(ButtonWidget.builder(Text.literal("取消"), b -> cancel(idx))
                .dimensions(listX + listW - cancelW, listTop + i * ROW_H, cancelW, 20).build());
        }

        int by = Math.min(this.height - 28, listTop + blockH + 12);
        addDrawableChild(ButtonWidget.builder(Text.literal("返回"), b -> closeToGame())
            .dimensions(listX, by, listW, 20).build());
    }

    private void cancel(int idx) {
        if (idx < 0 || idx >= rows.size()) return;
        if (entityId >= 0) ClientPlayNetworking.send(new TaskCancelPayload(entityId, idx));
        List<String> updated = new ArrayList<>(rows);
        updated.remove(idx);
        if (this.client != null) this.client.setScreen(new CompanionTaskScreen(entityId, updated));
    }

    private void closeToGame() {
        if (this.client != null) this.client.setScreen(null);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 14, TITLE);
        if (rows.isEmpty()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("暂时没有任务~"), this.width / 2, listTop + 4, DIM);
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            ctx.drawText(this.textRenderer, Text.literal((i + 1) + ". " + rows.get(i)),
                listX + 4, listTop + i * ROW_H + 6, TEXT, false);
        }
    }
}
