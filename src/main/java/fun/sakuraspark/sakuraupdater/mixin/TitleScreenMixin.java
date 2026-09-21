package fun.sakuraspark.sakuraupdater.mixin;

import com.mojang.logging.LogUtils;

import fun.sakuraspark.sakuraupdater.SakuraUpdaterClient;
import fun.sakuraspark.sakuraupdater.gui.FixScreen;
import fun.sakuraspark.sakuraupdater.gui.UpdateCheckScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ImageWidget;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin extends net.minecraft.client.gui.screens.Screen {
    protected TitleScreenMixin(Component title) {
        super(title);
    }

    private static final Logger LOGGER = LogUtils.getLogger();

    private Button updateCheckButton;
    private Button fixButton;
    private ImageWidget logoWidget;
    private static boolean warnedMissingInit = false;

    // 在菜单添加一个按钮，点击后打开更新界面
    @Inject(method = "init", at = @At("TAIL"))
    private void sakuraUpdater$addUpdateButton(CallbackInfo ci) {
        ResourceLocation logoTexture = new ResourceLocation("sakuraupdater", "textures/sakuraupdater.png");
        this.logoWidget = new ImageWidget(20, 20, logoTexture);
        this.logoWidget.setY(5);
        this.logoWidget.setX(0);
        this.addRenderableWidget(this.logoWidget);
        this.updateCheckButton = Button.builder(Component.translatable("gui.sakuraupdater.TitleScreen.checkupdate"), button -> {
            // 玩家手动点进来的：重新查一次，不复用启动时预取的结果
            SakuraUpdaterClient.getInstance().restartUpdateCheck();
            this.minecraft.setScreen(new UpdateCheckScreen());
        }).bounds(-60, 5, 60, 20).build();
        this.addRenderableWidget(this.updateCheckButton);
        this.fixButton = Button.builder(Component.translatable("gui.sakuraupdater.TitleScreen.fix"), button -> {
            this.minecraft.setScreen(new FixScreen());
        }).bounds(-60, 25, 60, 20).build();
        this.addRenderableWidget(this.fixButton);
    }

    // 添加按钮隐藏逻辑
    @Inject(method = "tick", at = @At("TAIL"))
    private void sakuraUpdater$addUpdateButtonMoveLogic(CallbackInfo ci) {
        // 防御:某些情况下(如资源重载/其它mod或connector在init前切换/重建TitleScreen)tick会在init之前触发，
        // 此时控件字段仍为null,直接解引用会导致NPE崩溃。字段未初始化则跳过本次移动逻辑。
        if (this.logoWidget == null || this.updateCheckButton == null || this.fixButton == null) {
            if (!warnedMissingInit) {
                warnedMissingInit = true;
                LOGGER.warn("SakuraUpdater: TitleScreen ticked before init mixin ran; fields not ready, skipping move logic.");
            }
            return;
        }
        if (this.logoWidget.isHovered() || this.updateCheckButton.isHovered() || this.fixButton.isHovered()) {
            this.updateCheckButton.setX(0);
            this.fixButton.setX(0);
            this.logoWidget.setX(60);
        } else {
            this.updateCheckButton.setX(-60);
            this.fixButton.setX(-60);
            this.logoWidget.setX(0);
        }
    }
}
