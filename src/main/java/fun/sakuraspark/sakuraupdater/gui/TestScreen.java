package fun.sakuraspark.sakuraupdater.gui;

import fun.sakuraspark.sakuraupdater.SakuraUpdaterClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class TestScreen extends Screen {

    // public static final CubeMap CUBE_MAP = new CubeMap(new ResourceLocation("textures/gui/title/background/panorama"));

    // private final PanoramaRenderer panorama = new PanoramaRenderer(CUBE_MAP);
    // private boolean fading = true;
    // private long fadeInStart;
    private static Component title = Component.translatable("gui.sakuraspark.screen.TestScreen");

    public TestScreen() {
        super(title);

    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // 禁止ESC关闭
    }

    @Override
    public void init() {
        super.init();
        // this.addRenderableWidget(Button.builder(Component.translatable("setScreen"),
        // (button) -> {
        // Minecraft.getInstance().setScreen(new TestScreen());
        // }).bounds(this.width / 2 - 100, this.height / 2 + 20, 200, 20).build());
        // this.addRenderableWidget(Button.builder(Component.translatable("pushGuiLayer"),
        // (button) -> {
        // Minecraft.getInstance().pushGuiLayer(new TestScreen());
        // }).bounds(this.width / 2 - 100, this.height / 2 + 50, 200, 20).build());
        this.addRenderableWidget(
                Button.builder(Component.translatable("gui.sakuraspark.screen.testscreen.connet"), (button) -> {
                    SakuraUpdaterClient.getInstance().connectToServer();
                }).bounds(this.width / 2 - 100, this.height / 2 - 20, 200, 20).build());
        this.addRenderableWidget(
                Button.builder(Component.translatable("gui.sakuraspark.screen.testscreen.getupdatelist"), (button) -> {
                    SakuraUpdaterClient.getInstance().updateCheck();
                }).bounds(this.width / 2 - 100, this.height / 2 + 20, 200, 20).build());
        this.addRenderableWidget(
                Button.builder(Component.translatable("gui.sakuraspark.screen.testscreen.downloadupdate"), (button) -> {
                    SakuraUpdaterClient.getInstance().downloadUpdate();
                }).bounds(this.width / 2 - 100, this.height / 2 + 50, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // if (this.fadeInStart == 0L && this.fading) {
        //     this.fadeInStart = Util.getMillis();
        // }

        // float f = this.fading ? (float) (Util.getMillis() - this.fadeInStart) / 1000.0F : 1.0F;
        // this.panorama.render(partialTick, Mth.clamp(f, 0.0F, 1.0F));
        // guiGraphics.fill(0, 0, this.width, this.height, 0x20000000);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2, 16777215);
    }

    // @Override
    // public void onClose() {
    // Minecraft.getInstance().setScreen(new TitleScreen(true));
    // }
}
