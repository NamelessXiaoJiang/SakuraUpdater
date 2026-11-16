package fun.sakuraspark.sakuraupdater.gui;

import java.util.concurrent.CompletableFuture;

import fun.sakuraspark.sakuraupdater.SakuraUpdaterClient;
import fun.sakuraspark.sakuraupdater.utils.FileUtils;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class UpdateScreen extends Screen {

    // public static final CubeMap CUBE_MAP = new CubeMap(new ResourceLocation("textures/gui/title/background/panorama"));

    // private final PanoramaRenderer panorama = new PanoramaRenderer(CUBE_MAP);
    // private boolean fading = true;
    // private long fadeInStart;
    
    public static final ResourceLocation CONFIRM_LOCATION = ResourceLocation.withDefaultNamespace("textures/gui/sprites/container/beacon/confirm.png");
    public static final ResourceLocation CANCEL_LOCATION = ResourceLocation.withDefaultNamespace("textures/gui/sprites/container/beacon/cancel.png");

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

    /**
     * 进度条口径：只有下载阶段才按字节推进，探测体积 / 删旧文件这两个阶段不产生字节，条停在 0。
     * 下载结束（DONE）后条保持在最终位置，和"更新完成"的文案一起看。
     */
    private static boolean isBytePhase(SakuraUpdaterClient.UpdatePhase phase) {
        return phase == SakuraUpdaterClient.UpdatePhase.DOWNLOADING
                || phase == SakuraUpdaterClient.UpdatePhase.DONE;
    }

    public void drawProgressBar(GuiGraphics guiGraphics, int X, int Y, int width, int height, long done, long total,
            float partialTick) {
        if (total <= 0)
            return;

        // 计算目标进度
        float targetProgress = (float) Math.min(1.0, (double) done / (double) total);

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

    /** 进度条下面那几行字：当前在做什么、已下载多少、还剩多少、速度多快 */
    private void drawProgressText(GuiGraphics guiGraphics, SakuraUpdaterClient.UpdateProgress progress) {
        int centerX = this.width / 2;
        int y = this.height / 2 + 44;
        switch (progress.phase) {
            case PREPARING -> guiGraphics.drawCenteredString(this.font,
                    Component.translatable("gui.sakuraupdater.UpdateScreen.preparing", progress.doneFiles,
                            progress.totalFiles),
                    centerX, y, 16777215);
            case DELETING -> guiGraphics.drawCenteredString(this.font,
                    Component.translatable("gui.sakuraupdater.UpdateScreen.deleting", progress.doneFiles,
                            progress.totalFiles),
                    centerX, y, 16777215);
            case DOWNLOADING -> {
                // 清单里有文件没带体积时总量只是下限，用"≥"标出来，免得看起来像算错了
                Component total = progress.totalKnown
                        ? Component.literal(FileUtils.formatSize(progress.totalBytes))
                        : Component.translatable("gui.sakuraupdater.UpdateScreen.atleast",
                                FileUtils.formatSize(progress.totalBytes));
                guiGraphics.drawCenteredString(this.font,
                        Component.translatable("gui.sakuraupdater.UpdateScreen.downloaded",
                                FileUtils.formatSize(progress.doneBytes), total),
                        centerX, y, 16777215);
                long remaining = Math.max(0, progress.totalBytes - progress.doneBytes);
                int percent = progress.totalBytes > 0
                        ? (int) Math.min(100, progress.doneBytes * 100 / progress.totalBytes)
                        : 0;
                guiGraphics.drawCenteredString(this.font,
                        Component.translatable("gui.sakuraupdater.UpdateScreen.remaining",
                                FileUtils.formatSize(remaining), percent),
                        centerX, y + 12, 16777215);
                if (progress.bytesPerSecond > 0) {
                    guiGraphics.drawCenteredString(this.font,
                            Component.translatable("gui.sakuraupdater.UpdateScreen.speed",
                                    FileUtils.formatSize(progress.bytesPerSecond),
                                    FileUtils.formatDuration(remaining / progress.bytesPerSecond)),
                            centerX, y + 24, 11184810);
                }
            }
            default -> {
            }
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        float scale = 3.0f; // 缩放因子
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(this.width / 2 - (16 * scale) / 2, this.height / 4 - (16 * scale) / 2, 0);
        guiGraphics.pose().scale(scale, scale, 0f);
        if (updateStatus == -1) { // 正在下载更新时显示附魔书加闪烁
            ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK);
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            guiGraphics.renderItem(stack, 0, 0);
        } else { // 出错和完成时显示普通附魔书
            ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK);
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false);
            guiGraphics.renderItem(stack, 0, 0);
        }
        guiGraphics.pose().popPose();

        if (updateStatus !=-1 && updateStatus ==0) {
            guiGraphics.blit(CONFIRM_LOCATION, this.width / 2+5, this.height / 4+5, 0, 0, 18, 18, 18, 18); // 绘制绿色对勾
        } else if (updateStatus != -1 && updateStatus > 0) {
            guiGraphics.blit(CANCEL_LOCATION, this.width / 2+5, this.height / 4+5, 0, 0, 18, 18, 18, 18); // 绘制ping unknown图标
        }

        SakuraUpdaterClient.UpdateProgress progress = SakuraUpdaterClient.getInstance().getUpdateProgress();
        if (progress.phase != SakuraUpdaterClient.UpdatePhase.IDLE) {
            boolean byBytes = isBytePhase(progress.phase);
            this.drawProgressBar(guiGraphics, this.width / 2 - 100, this.height / 2 + 20, 200, 20,
                    byBytes ? progress.doneBytes : 0L, byBytes ? progress.totalBytes : 0L, partialTick);
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
            // 更新进行中才画这几行；完成/失败态要留给下面的按钮位置（height/2 + 50 起）
            this.drawProgressText(guiGraphics, progress);
        }
    }
}
