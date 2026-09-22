package fun.sakuraspark.sakuraupdater.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import fun.sakuraspark.sakuraupdater.SakuraUpdaterClient;
import fun.sakuraspark.sakuraupdater.config.DataConfig.Data;
import fun.sakuraspark.sakuraupdater.gui.components.MarkdownBox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class UpdateCheckScreen extends Screen {

    // public static final CubeMap CUBE_MAP = new CubeMap(new
    // ResourceLocation("textures/gui/title/background/panorama"));

    // private final PanoramaRenderer panorama = new PanoramaRenderer(CUBE_MAP);
    // private boolean fading = true;
    // private long fadeInStart;

    private int updateStatus = 0; // -1: error, 0: checking, 1: need update, 2: no update 3: only server update

    public static final ResourceLocation BOOK_LOCATION = ResourceLocation.withDefaultNamespace("textures/gui/book.png");
    public static final ResourceLocation CONFIRM_LOCATION = ResourceLocation.withDefaultNamespace("textures/gui/sprites/container/beacon/confirm.png");
    public static final ResourceLocation PING_UNKNOWN_LOCATION = ResourceLocation.withDefaultNamespace("textures/gui/sprites/icon/ping_unknown.png");
    private PageButton forwardButton;
    private PageButton backButton;
    private int currentPage = 0;
    private List<Data> changelogPages = List.of();

    private static final Logger LOGGER = LogUtils.getLogger();

    public UpdateCheckScreen() {
        super(Component.translatable("gui.sakuraupdater.UpdateCheckScreen"));
        CompletableFuture<Integer> check = SakuraUpdaterClient.getInstance().getUpdateCheck();
        if (check.isDone()) {
            // 游戏加载期间已经查完了：首帧就是最终状态，不闪也不用重建控件
            updateStatus = check.getNow(-1); // 只有已完成才会走到这里，检查体自己把异常兜成了 -1
            LOGGER.info("SakuraUpdater: update check already finished during loading, showing result {} at once.",
                    updateStatus);
            return;
        }
        LOGGER.info("start version checking...");
        check.thenAccept(result -> Minecraft.getInstance().execute(() -> {
            updateStatus = result; // 更新状态
            if (Minecraft.getInstance().screen == this) {
                // 当状态变为1时，重建界面添加按钮；界面已经被玩家关掉就不用重建了
                this.rebuildWidgets();
            }
        }));
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // 禁止ESC关闭
    }

    @Override
    public void init() {
        super.init();
        createUpdateButtons();
    }

    protected void createUpdateButtons() {
        if (updateStatus == 1 || updateStatus == 3) {
            var client = SakuraUpdaterClient.getInstance();
            var logs = client.getChangeLog();
            // 正文与翻页按钮共用本次重建的列表快照，避免页数和内容不一致。
            changelogPages = logs == null ? List.of() : new ArrayList<>(logs);
            if (changelogPages.isEmpty()) {
                // 服务端未返回历史日志时，退回最新版本说明，不直接读取第 0 项。
                var latest = client.getLastUpdateData();
                if (latest != null) changelogPages = List.of(latest);
            }
            // 重试或数据变化后，原页码可能已超出新的页数范围。
            currentPage = Math.max(0, Math.min(currentPage, changelogPages.size() - 1));
        }
        if (updateStatus == -1) {
            this.addRenderableWidget(
                    Button.builder(Component.translatable("gui.sakuraupdater.UpdateCheckScreen.retry"), button -> {
                        // 点击按钮后重新检查更新（手动重试不能复用启动时的预取结果）
                        SakuraUpdaterClient.getInstance().restartUpdateCheck();
                        Minecraft.getInstance().setScreen(new UpdateCheckScreen());
                    }).bounds(this.width / 2 - 80, 185, 160, 20).build());
            this.addRenderableWidget(
                    Button.builder(Component.translatable("gui.sakuraupdater.UpdateCheckScreen.cancel"), button -> {
                        // 点击按钮后关闭当前界面
                        Minecraft.getInstance().setScreen(new TitleScreen(true));
                    }).bounds(this.width / 2 - 80, 185 + 22, 160, 20).build());
        } else if (updateStatus == 1) {
            this.addRenderableWidget(
                    Button.builder(Component.translatable("gui.sakuraupdater.UpdateCheckScreen.update"), button -> {
                        // 点击按钮后打开更新界面
                        Minecraft.getInstance().setScreen(new UpdateScreen());
                    }).bounds(this.width / 2 - 80, 185, 160, 20).build());
            this.addRenderableWidget(
                    Button.builder(Component.translatable("gui.sakuraupdater.UpdateCheckScreen.cancel"), button -> {
                        // 点击按钮后关闭当前界面
                        Minecraft.getInstance().setScreen(new TitleScreen(true));
                    }).bounds(this.width / 2 - 80, 185 + 22, 160, 20).build());

        } else {
            this.addRenderableWidget(
                    Button.builder(Component.translatable("gui.sakuraupdater.UpdateCheckScreen.ok"), button -> {
                        // 点击按钮后关闭当前界面
                        Minecraft.getInstance().setScreen(new TitleScreen(true));
                    }).bounds(this.width / 2 - 80, 185, 160, 20).build());
        }
        if ((updateStatus == 1 || updateStatus == 3) && !changelogPages.isEmpty()) {
            createBookButtons();
            var page = changelogPages.get(currentPage);
            var markdownbox = new MarkdownBox(this.width / 2 - 62, 25, 120, 140,
                    "# " + (page.version == null ? "" : page.version) + "\n\n"
                            + (page.description == null ? "" : page.description));
            markdownbox.setBackgroundVisible(false);
            markdownbox.setColor(0x000000); // 设置为黑色
            markdownbox.setScrollbarVisible(false);
            this.addRenderableWidget(markdownbox);
        }
    }

    protected void createBookButtons() {
        int i = (this.width - 192) / 2;
        if (currentPage >0) {
            this.backButton = this.addRenderableWidget(new PageButton(i + 46, 159, false, button -> {
                onPageChange(false);
            }, false)); // 向后翻页
        }
        if (currentPage < changelogPages.size() - 1) {
            this.forwardButton = this.addRenderableWidget(new PageButton(i + 119, 159, true, button -> {
                onPageChange(true);
            }, true)); // 向前翻页
        }
    }

    protected void onPageChange(boolean forward) {
        int maxPage = changelogPages.size();
        if (forward && currentPage < maxPage - 1) {
            currentPage++;
        } else if (!forward && currentPage > 0) {
            currentPage--;
        }
        this.rebuildWidgets(); // 重建控件以更新显示的内容
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        float scale = 3.0f; // 缩放因子
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(this.width / 2 - (16 * scale) / 2, this.height / 4 - (16 * scale) / 2, 0);
        guiGraphics.pose().scale(scale, scale, 0f);
        if (updateStatus == 2) {// 不需要更新时显示附魔书加绿色对勾
            ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK);
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false);
            guiGraphics.renderItem(stack, 0, 0);
        } else if (updateStatus == -1) {// 出错时显示土豆服务器加ping unknown
            ItemStack stack = new ItemStack(Items.POISONOUS_POTATO);
            guiGraphics.renderItem(stack, 0, 0);
        }
        guiGraphics.pose().popPose();

        if (updateStatus == 2) {
            guiGraphics.blit(CONFIRM_LOCATION, this.width / 2+5, this.height / 4+5, 0, 0, 18, 18, 18, 18); // 绘制绿色对勾
        } else if (updateStatus == -1) {
            guiGraphics.blit(PING_UNKNOWN_LOCATION, this.width / 2+10, this.height / 4+10, 0, 0, 10, 8, 10, 8); // 绘制ping unknown图标
        }

        if (updateStatus == 0) {
            guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2, 16777215);
        } else if (updateStatus == 1 || updateStatus == 3) {
            guiGraphics.drawCenteredString(this.font,
                    Component.literal(SakuraUpdaterClient.getInstance().getLastUpdateData().version), this.width / 2,
                    16, 16711680); // Red color for need update
        } else if (updateStatus == 2) {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("gui.sakuraupdater.UpdateCheckScreen.NoUpdate"), this.width / 2,
                    this.height / 2, 65280); // Green color for no update
        } else if (updateStatus == -1) {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("gui.sakuraupdater.UpdateCheckScreen.Error"), this.width / 2,
                    this.height / 2, 16711680); // Red color for error

        }
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        if (updateStatus == 1 || updateStatus == 3) { // 需要更新时显示背景书籍图片
            guiGraphics.blit(BOOK_LOCATION, (this.width - 186) / 2, 2, 0, 0, 192, 192);
        }
    }
}
