package fun.sakuraspark.sakuraupdater.gui;

import java.util.concurrent.CompletableFuture;

import com.mojang.datafixers.util.Pair;

import fun.sakuraspark.sakuraupdater.SakuraUpdaterClient;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.CubeMap;
import net.minecraft.client.renderer.PanoramaRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class UpdateScreen extends Screen {

    public static final CubeMap CUBE_MAP = new CubeMap(new ResourceLocation("textures/gui/title/background/panorama"));

    private final PanoramaRenderer panorama = new PanoramaRenderer(CUBE_MAP);
    private boolean fading = true;
    private long fadeInStart;
    
    private static final ResourceLocation BEACON_LOCATION = new ResourceLocation("minecraft", "textures/gui/container/beacon.png");

    // 缓动控制
    private float currentProgress = 0.0f;
    private float EASING_SPEED = 0.1f; // 缓动速度
    
    private int updateStatus = -1;

    private String whereFrom="UpdateCheckScreen";
    
    public UpdateScreen() {
        this("UpdateCheckScreen");
    }
    public UpdateScreen(String whereFrom) {
        super(Component.translatable("gui.sakuraupdater.UpdateScreen"));
        this.whereFrom = whereFrom;
        CompletableFuture.supplyAsync(() -> {
            // 这里运行在后台线程中
            SakuraUpdaterClient.getInstance().downloadUpdate();
            return 0;
        }, Util.backgroundExecutor()) // 使用 Minecraft 的后台线程池
                .thenAcceptAsync(result -> {
                    // 回到主线程更新UI
                    Minecraft.getInstance().execute(() -> {
                        updateStatus = SakuraUpdaterClient.getInstance().getDownloadFailures();
                        this.rebuildWidgets();
                    });
                });
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // 禁止ESC关闭
    }

    @Override
    public void init() {
        if (updateStatus != -1) {
            // 如果有更新进度，重建界面添加按钮
            if (updateStatus != 0) {
                this.addRenderableWidget(Button.builder(Component.translatable("gui.sakuraupdater.UpdateScreen.retry",
                        updateStatus), button -> {
                            if (whereFrom.equals("UpdateCheckScreen")) {
                                // 重试要重新检查，不能复用启动时预取的结果（本地可能已经更新过一轮）
                                SakuraUpdaterClient.getInstance().restartUpdateCheck();
                                Minecraft.getInstance().setScreen(new UpdateCheckScreen());
                            } else if (whereFrom.equals("FixScreen")) {
                                Minecraft.getInstance().setScreen(new FixScreen());
                            }
                        }).bounds(this.width / 2 - 100, this.height / 2 + 50, 200, 20).build());
                this.addRenderableWidget(Button.builder(Component.translatable("gui.sakuraupdater.UpdateScreen.cancel",
                        updateStatus), button -> {
                            Minecraft.getInstance().setScreen(new TitleScreen(true));
                        }).bounds(this.width / 2 - 100, this.height / 2 + 80, 200, 20).build());
            } else {

                this.addRenderableWidget(
                        Button.builder(Component.translatable("gui.sakuraupdater.UpdateScreen.restartnow"), button -> {
                            Minecraft.getInstance().stop();
                        }).bounds(this.width / 2 - 100, this.height / 2 + 50, 200, 20).build());
                this.addRenderableWidget(Button
                        .builder(Component.translatable("gui.sakuraupdater.UpdateScreen.restartlater"), button -> {
                            Minecraft.getInstance().setScreen(new TitleScreen(true));
                        }).bounds(this.width / 2 - 100, this.height / 2 + 80, 200, 20).build());
            }
        }
    }

    public void drawProgressBar(GuiGraphics guiGraphics, int X, int Y, int width, int height, int min, int max,
            float partialTick) {
        if (max <= 0)
            return;

        // 计算目标进度
        float targetProgress = (float) min / max;

        // 使用线性插值进行缓动
        float progressDiff = targetProgress - currentProgress;
        if (Math.abs(progressDiff) > 0.001f) {
            currentProgress += progressDiff * EASING_SPEED;
        } else {
            currentProgress = targetProgress; // 接近目标时直接设置
        }

        // 计算实际的进度条宽度
        int progressWidth = (int) (currentProgress * (width - 4));
        // 绘制四条边框线
        guiGraphics.fill(X, Y, X + width, Y + 1, 0xFFFFFFFF); // 上边
        guiGraphics.fill(X, Y + height - 1, X + width, Y + height, 0xFFFFFFFF); // 下边
        guiGraphics.fill(X, Y, X + 1, Y + height, 0xFFFFFFFF); // 左边
        guiGraphics.fill(X + width - 1, Y, X + width, Y + height, 0xFFFFFFFF); // 右边

        if (progressWidth > 0) {
            guiGraphics.fill(X + 2, Y + 2, X + 2 + progressWidth, Y + height - 2, 0xFFFFFFFF);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 渲染背景
        if (this.fadeInStart == 0L && this.fading) {
            this.fadeInStart = Util.getMillis();
        }
        float f = this.fading ? (float) (Util.getMillis() - this.fadeInStart) / 1000.0F : 1.0F;
        this.panorama.render(partialTick, Mth.clamp(f, 0.0F, 1.0F));
        guiGraphics.fill(0, 0, this.width, this.height, 0x20000000);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        float scale = 3.0f; // 缩放因子
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(this.width / 2 - (16 * scale) / 2, this.height / 4 - (16 * scale) / 2, 0);
        guiGraphics.pose().scale(scale, scale, 0f);
        if (updateStatus == -1) { // 正在下载更新时显示附魔书加闪烁
            ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK);
            guiGraphics.renderItem(stack, 0, 0);
        } else { // 出错和完成时显示普通附魔书
            ItemStack stack = new ItemStack(Items.BOOK);
            guiGraphics.renderItem(stack, 0, 0);
        }
        guiGraphics.pose().popPose();

        if (updateStatus != -1 && updateStatus == 0) {
            guiGraphics.blit(BEACON_LOCATION, this.width / 2 + 5, this.height / 4 + 5, 90, 220, 18, 18, 256, 256); // 绿色对勾
        } else if (updateStatus != -1 && updateStatus > 0) {
            guiGraphics.blit(BEACON_LOCATION, this.width / 2 + 5, this.height / 4 + 5, 112, 220, 18, 18, 256, 256); // 红色叉号
        }

        Pair<Integer, Integer> progress = SakuraUpdaterClient.getInstance().getUpdateProgress();
        if (progress.getSecond() >= 0) {
            // 绘制进度条
            this.drawProgressBar(guiGraphics, this.width / 2 - 100, this.height / 2 + 20, 200, 20, progress.getFirst(),
                    progress.getSecond(), partialTick);
        }

        if (updateStatus != -1) {
            guiGraphics.drawCenteredString(this.font, Component.translatable("gui.sakuraupdater.UpdateScreen.complete"),
                    this.width / 2, this.height / 2 - 20, 16777215);
            if (updateStatus != 0) {
                guiGraphics.drawCenteredString(this.font,
                        Component.translatable("gui.sakuraupdater.UpdateScreen.failed",
                                updateStatus),
                        this.width / 2, this.height / 2, 16711680); // Red color for failed
            }

        } else {
            guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2, 16777215);
        }
    }
}
