package fun.sakuraspark.sakuraupdater.gui;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import fun.sakuraspark.sakuraupdater.SakuraUpdaterClient;
import fun.sakuraspark.sakuraupdater.SakuraUpdaterClient.UpdateCheckResult;
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

public class FixScreen extends Screen {
    
    public static final CubeMap CUBE_MAP = new CubeMap(new ResourceLocation("textures/gui/title/background/panorama"));

    private final PanoramaRenderer panorama = new PanoramaRenderer(CUBE_MAP);
    private boolean fading = true;
    private long fadeInStart;
    
    private static final ResourceLocation BEACON_LOCATION = new ResourceLocation("minecraft", "textures/gui/container/beacon.png");
    private static final ResourceLocation ICONS_LOCATION = new ResourceLocation("minecraft", "textures/gui/icons.png");

    private static final Logger LOGGER = LogUtils.getLogger();

    UpdateCheckResult fixStatus = UpdateCheckResult.CHECKING;

    public FixScreen() {
        super(Component.translatable("gui.sakuraupdater.FixScreen"));
        LOGGER.info("start Verify Mods Integrity...");
        CompletableFuture.supplyAsync(() -> {
            // 这里运行在后台线程中
            try {
                // 强制进行完整性检查
                UpdateCheckResult result = SakuraUpdaterClient.getInstance().updateCheck();
                if (result == UpdateCheckResult.ERROR || result == UpdateCheckResult.SERVER_IS_IN_FACTORY) {
                    return result; // Return error or factory mode status
                }
                if (SakuraUpdaterClient.getInstance().integrityCheck()) {
                    return UpdateCheckResult.NEED_UPDATE; // Need update
                }
                return UpdateCheckResult.NO_UPDATE; // No issues found
            } catch (Exception e) {
                LOGGER.error("Error during update check", e);
                return UpdateCheckResult.ERROR;
            }
        }, Util.backgroundExecutor()) // 使用 Minecraft 的后台线程池
                .thenAcceptAsync(result -> {
                    // 回到主线程更新UI
                    Minecraft.getInstance().execute(() -> {
                        fixStatus = result; // 更新状态
                        // 当状态变为1时，重建界面添加按钮
                        this.rebuildWidgets();
                    });
                });
    }

    @Override
    public void init() {
        super.init();
        if (fixStatus == UpdateCheckResult.ERROR || fixStatus == UpdateCheckResult.SERVER_IS_IN_FACTORY) {
            this.addRenderableWidget(
                    Button.builder(Component.translatable("gui.sakuraupdater.FixScreen.retry"), button -> {
                        // 点击按钮后重新检查更新
                        Minecraft.getInstance().setScreen(new FixScreen());
                    }).bounds(this.width / 2 - 100, this.height - 50, 200, 20).build());
            this.addRenderableWidget(
                    Button.builder(Component.translatable("gui.sakuraupdater.FixScreen.cancel"), button -> {
                        // 点击按钮后关闭当前界面
                        Minecraft.getInstance().setScreen(new TitleScreen(true));
                    }).bounds(this.width / 2 - 100, this.height - 20, 200, 20).build());
        } else if (fixStatus == UpdateCheckResult.NEED_UPDATE) {
            this.addRenderableWidget(
                    Button.builder(Component.translatable("gui.sakuraupdater.FixScreen.fix"), button -> {
                        // 点击按钮后打开更新界面
                        Minecraft.getInstance().setScreen(new UpdateScreen("FixScreen"));
                    }).bounds(this.width / 2 - 100, this.height - 50, 200, 20).build());
            this.addRenderableWidget(
                    Button.builder(Component.translatable("gui.sakuraupdater.FixScreen.cancel"), button -> {
                        // 点击按钮后关闭当前界面
                        Minecraft.getInstance().setScreen(new TitleScreen(true));
                    }).bounds(this.width / 2 - 100, this.height - 20, 200, 20).build());
        } else {
            this.addRenderableWidget(
                    Button.builder(Component.translatable("gui.sakuraupdater.FixScreen.ok"), button -> {
                        // 点击按钮后关闭当前界面
                        Minecraft.getInstance().setScreen(new TitleScreen(true));
                    }).bounds(this.width / 2 - 100, this.height - 20, 200, 20).build());
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
        if (fixStatus == UpdateCheckResult.NEED_UPDATE || fixStatus == UpdateCheckResult.NO_UPDATE) { // 显示书
            ItemStack stack = new ItemStack(Items.BOOK);
            guiGraphics.renderItem(stack, 0, 0);
        } else if (fixStatus == UpdateCheckResult.SERVER_IS_IN_FACTORY) { // 服务器处于工厂模式时显示工作台和钻石镐
            ItemStack stack = new ItemStack(Items.CRAFTING_TABLE);
            guiGraphics.renderItem(stack, 0, 0);
            stack = new ItemStack(Items.DIAMOND_PICKAXE);
            guiGraphics.renderItem(stack, 0, 0);
        } else if (fixStatus == UpdateCheckResult.ERROR) {// 出错时显示土豆服务器加ping unknown
            ItemStack stack = new ItemStack(Items.POISONOUS_POTATO);
            guiGraphics.renderItem(stack, 0, 0);
        }
        guiGraphics.pose().popPose();

        if (fixStatus == UpdateCheckResult.NEED_UPDATE) {
            guiGraphics.blit(BEACON_LOCATION, this.width / 2 + 5, this.height / 4 + 5, 112, 220, 18, 18, 256, 256); // 红色叉号
        } else if (fixStatus == UpdateCheckResult.NO_UPDATE) {
            guiGraphics.blit(BEACON_LOCATION, this.width / 2 + 5, this.height / 4 + 5, 90, 220, 18, 18, 256, 256); // 绿色对勾
        } else if (fixStatus == UpdateCheckResult.ERROR) {
            guiGraphics.blit(ICONS_LOCATION, this.width / 2 + 10, this.height / 4 + 10, 0, 216, 10, 8, 256, 256); // ping unknown
        }

        if (fixStatus == UpdateCheckResult.CHECKING) {
            guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2, 16777215);
        } else if (fixStatus == UpdateCheckResult.NEED_UPDATE) {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("gui.sakuraupdater.FixScreen.IssuesFound"), this.width / 2,
                    this.height / 2, 16711680); // Red color for issues found
        } else if (fixStatus == UpdateCheckResult.NO_UPDATE) {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("gui.sakuraupdater.FixScreen.NoIssues"), this.width / 2,
                    this.height / 2, 65280); // Green color for no issues found
        } else if (fixStatus == UpdateCheckResult.ERROR) {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("gui.sakuraupdater.FixScreen.error"), this.width / 2,
                    this.height / 2, 16711680); // Red color for error
        } else if (fixStatus == UpdateCheckResult.SERVER_IS_IN_FACTORY) {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("gui.sakuraupdater.UpdateCheckScreen.ServerInFactoryMode"), this.width / 2,
                    this.height / 2, 16711680); // Red color for error
        }
    }
}
