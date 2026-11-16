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
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class FixScreen extends Screen {
    
    public static final ResourceLocation CONFIRM_LOCATION = ResourceLocation.withDefaultNamespace("textures/gui/sprites/container/beacon/confirm.png");
    public static final ResourceLocation CANCEL_LOCATION = ResourceLocation.withDefaultNamespace("textures/gui/sprites/container/beacon/cancel.png");
    public static final ResourceLocation PING_UNKNOWN_LOCATION = ResourceLocation.withDefaultNamespace("textures/gui/sprites/icon/ping_unknown.png");

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
                    return result;
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
        // // 渲染背景
        // if (this.fadeInStart == 0L && this.fading) {
        //     this.fadeInStart = Util.getMillis();
        // }
        // float f = this.fading ? (float) (Util.getMillis() - this.fadeInStart) / 1000.0F : 1.0F;
        // this.panorama.render(partialTick, Mth.clamp(f, 0.0F, 1.0F));
        // guiGraphics.fill(0, 0, this.width, this.height, 0x20000000);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        float scale = 3.0f; // 缩放因子
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(this.width / 2 - (16 * scale) / 2, this.height / 4 - (16 * scale) / 2, 0);
        guiGraphics.pose().scale(scale, scale, 0f);
        if (fixStatus == UpdateCheckResult.NEED_UPDATE || fixStatus == UpdateCheckResult.NO_UPDATE) { // 显示附魔书
            ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK);
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false);
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
            guiGraphics.blit(CANCEL_LOCATION, this.width / 2+5, this.height / 4+5, 0, 0, 18, 18, 18, 18); // 绘制红色叉号
        } else if (fixStatus == UpdateCheckResult.NO_UPDATE) {
            guiGraphics.blit(CONFIRM_LOCATION, this.width / 2+5, this.height / 4+5, 0, 0, 18, 18, 18, 18); // 绘制绿色对勾
        } else if (fixStatus == UpdateCheckResult.ERROR) {
            guiGraphics.blit(PING_UNKNOWN_LOCATION, this.width / 2+10, this.height / 4+10, 0, 0, 10, 8, 10, 8); // 绘制ping unknown图标
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
