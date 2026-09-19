package fun.sakuraspark.sakuraupdater;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;

import fun.sakuraspark.sakuraupdater.config.ClientConfig;
import fun.sakuraspark.sakuraupdater.config.DataConfig.Data;
import fun.sakuraspark.sakuraupdater.config.DataConfig.FileData;
import fun.sakuraspark.sakuraupdater.config.DataConfig.PathData;
import fun.sakuraspark.sakuraupdater.gui.FixScreen;
import fun.sakuraspark.sakuraupdater.gui.TestScreen;
import fun.sakuraspark.sakuraupdater.gui.UpdateCheckScreen;
import fun.sakuraspark.sakuraupdater.gui.UpdateScreen;
import fun.sakuraspark.sakuraupdater.network.FileClient;
import fun.sakuraspark.sakuraupdater.utils.FileUtils;
import fun.sakuraspark.sakuraupdater.utils.MD5;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import static fun.sakuraspark.sakuraupdater.utils.CommandUtils.sendSuccessMessage;
import static net.minecraft.commands.Commands.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SakuraUpdaterClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static SakuraUpdaterClient INSTANCE;

    private FileClient file_client;
    private Data last_update_data = null; // 上次更新的数据
    private Data current_update_data = null; // 当前更新的数据，只有存在push时才会有
    private List<Data> changelog = null; // 更新日志（从当前版本到最新版本）

    private Pair<Integer, Integer> update_progress = new Pair<>(-1, -1); // 更新进度
    private int download_failures = -1; // 更新失败次数
    Pair<List<File>, List<FileData>> integrityCheckResult;

    // 自动弹出更新界面的状态机，只在渲染线程访问
    private enum ShowState {
        WAITING, // 还没成功展示，见到标题界面就尝试
        SHOWING, // 界面已经设置，等待确认玩家是否真的用上了
        DONE // 本次启动已经处理完，不再自动弹出
    }

    private static final int SHOW_RETRY_DELAY_TICKS = 20; // 被其它 mod 顶掉后重试的间隔
    private static final int MAX_SHOW_ATTEMPTS = 5; // 最多尝试次数，防止和别的 mod 无限互抢

    private ShowState show_state = ShowState.WAITING;
    private Screen show_screen = null; // 当前这次尝试使用的界面实例
    private boolean show_displayed = false; // 我们的界面是否真的显示过（被别人在同一帧内抢走时为 false）
    private int show_retry_delay = 0;
    private int show_attempts = 0;

    private boolean debug = false; // 是否开启调试模式 

    SakuraUpdaterClient() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        INSTANCE = this;
        LOGGER.info("SakuraUpdater Client is running!");
    }

    // 获取示例
    public static SakuraUpdaterClient getInstance() {
        return INSTANCE;
    }

    public Data getLastUpdateData() {
        if (last_update_data == null) {
            last_update_data = file_client.getUpdateList();
            if (last_update_data == null) {
                LOGGER.error("Failed to fetch update list from server.");
                return null;
            }
        }
        return last_update_data;
    }

    /**
     * 获取从当前版本到最新版本的所有更新日志（按时间升序）
     */
    public List<Data> getChangeLog() {
        if (changelog == null) {
            changelog = file_client.getChangeLog(ClientConfig.getNowVersion());
            if (changelog == null) {
                LOGGER.error("Failed to fetch changelog from server.");
                return null;
            }
        }
        return changelog;
    }

    /**
     * 获取聚合后的更新日志文本（Markdown格式），每个版本用 ## 标题开头
     */
    public String getChangeLogText() {
        List<Data> logs = getChangeLog();
        if (logs == null || logs.isEmpty()) {
            Data last = getLastUpdateData();
            return last != null ? last.description : "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < logs.size(); i++) {
            Data data = logs.get(i);
            if (i > 0) {
                sb.append("\n\n");
            }
            sb.append("## ").append(data.version).append("\n");
            sb.append(data.description);
        }
        return sb.toString();
    }

    public int updateCheck() {
        last_update_data = null; // 重置上次更新数据，强制重新获取
        changelog = null; // 重置更新日志，强制重新获取
        if (getLastUpdateData() == null) {
            return -1;
        }
        if (last_update_data.version.equals(ClientConfig.getNowVersion())) {
            LOGGER.info("Client is up to date.");
            return 0;
        }
        LOGGER.warn("Client is outdated. Latest version: {}", last_update_data.version);
        return 1;
    }

    public boolean integrityCheck() {
        //TODO: this shit needs to be rebuild！
        //TODO: 先删除在下载有点危险
        update_progress = new Pair<>(-1, -1); // 重置进度
        integrityCheckResult = new Pair<List<File>, List<FileData>>(new ArrayList<>(), new ArrayList<>());
        if (getLastUpdateData() == null) {
            return false;
        }
        if (last_update_data.paths == null || last_update_data.paths.isEmpty()) {
            // 服务端这个版本的文件清单是空的（被旧版 data edit 清空过、commit 时 SYNC_DIR 是空的、或该版本没 commit 好）。
            // 这里必须"响亮失败"：异常会被 UpdateCheckScreen 的 try/catch 接住并显示"检查失败"；
            // 不能返回 false，否则界面会当成"只有服务端更新"从而静默放行玩家进服（而且什么都没同步）。
            throw new IllegalStateException("Server version " + last_update_data.version
                    + " has an empty file list, run 'data repair " + last_update_data.version
                    + "' on the server to rebuild it.");
        }
        Gson gson = new Gson();
        for (PathData pathData : last_update_data.paths) {
            // 格式错误
            if (!pathData.model.equals("mirror") && !pathData.model.equals("push")) {
                LOGGER.warn("Unknown model: {}, skipping integrity check for this path.", pathData.model);
                continue;
            }
            // mirror需要删除
            if (pathData.model.equals("mirror")) {
                FileUtils.getAllFiles(new File(pathData.targetPath)).forEach(file -> {
                    FileData fileData = null;
                    for (FileData fd : pathData.files) {
                        if (fd.targetPath.equals(file.toString().replace(File.separator, "/"))) { // 判断是否存
                            fileData = fd;
                            break;
                        }
                    }
                    if (fileData == null) {
                        LOGGER.warn("File {} not in list\nwill be deleted in mirror mode.", file);
                        integrityCheckResult.getFirst().add(file);
                    }
                    if (fileData != null && !fileData.md5.equals(MD5.calculateMD5(file))) {
                        LOGGER.warn("File {} md5 not match\nwill be removed in mirror mode.", file);
                        integrityCheckResult.getFirst().add(file); // 文件损坏也删除
                    }

                });
            }
            // push需要删除
            if (pathData.model.equals("push")) {
                if (current_update_data == null) {
                    current_update_data = file_client.getUpdateList(ClientConfig.getNowVersion());
                    if (current_update_data == null) {
                        LOGGER.error("Failed to fetch current update list from server.");
                        return false;
                    }
                    LOGGER.info("Current update list fetched successfully: {}", gson.toJson(current_update_data));
                }
                if (current_update_data.version != null
                        && !current_update_data.version.equals(last_update_data.version)) { // 版本不一致时执行，避免""会自动返回最新版本问题
                    current_update_data.paths.forEach(CurrentPathData -> {
                        if (CurrentPathData.targetPath.equals(pathData.targetPath)) {
                            CurrentPathData.files.forEach(file -> {
                                if (!pathData.files.stream()
                                        .anyMatch(fileData -> file.targetPath.equals(fileData.targetPath)
                                                && new File(file.targetPath).exists() && fileData.md5.equals(MD5.calculateMD5(new File(file.targetPath))))) { // 判断是否存在和对比md5
                                    LOGGER.warn("File {} will be deleted in push mode.", file.targetPath);
                                    integrityCheckResult.getFirst().add(new File(file.targetPath));
                                }
                            });
                        }
                    });
                }
            }
            // 需要下载
            pathData.files.forEach(fileData -> {
                if (!FileUtils.getAllFiles(new File(pathData.targetPath)).stream()
                        .anyMatch(file -> file.toString().replace(File.separator, "/").equals(fileData.targetPath)
                                && fileData.md5.equals(MD5.calculateMD5(file)))) { // 判断是否存在和对比md5
                    LOGGER.warn("File {} will be downloaded to {}", fileData.sourcePath + ":" + fileData.md5,
                            fileData.targetPath);
                    integrityCheckResult.getSecond().add(fileData);
                }
            });
        }

        download_failures = -1; // 重置失败次数
        if (integrityCheckResult.getFirst().isEmpty() && integrityCheckResult.getSecond().isEmpty()) {
            LOGGER.info("No files to remove or download.");
            update_progress = new Pair<>(0, 0);
            ClientConfig.setNowVersion(last_update_data.version); // 不需要更新文件但是还是需要更新本地版本号
            return false;
        }

        update_progress = new Pair<>(0,
                integrityCheckResult.getFirst().size() + integrityCheckResult.getSecond().size()); // 更新进度
        return true;
    }

    public void downloadUpdate() {
        if (download_failures == -1) {
            download_failures = 0;
        }
        // 删除不需要的文件
        integrityCheckResult.getFirst().forEach(file -> {
            if (file.delete()) {
                LOGGER.info("Deleted file: {}", file);
                update_progress = new Pair<>(update_progress.getFirst() + 1, update_progress.getSecond());
            } else {
                LOGGER.error("Failed to delete file: {}", file);
                update_progress = new Pair<>(update_progress.getFirst() + 1, update_progress.getSecond());
            }
        });

        // 下载需要的文件
        integrityCheckResult.getSecond().forEach(fileData -> {
            if (file_client.downloadFile(fileData.sourcePath, fileData.targetPath, fileData.md5,
                    ClientConfig.getDownloadConnections())) {
                LOGGER.info("Downloaded file: {}", fileData.sourcePath);
            } else {
                download_failures++;
                LOGGER.error("Failed to download file: {}", fileData.sourcePath);
            }
            update_progress = new Pair<>(update_progress.getFirst() + 1, update_progress.getSecond());
        });
        if (download_failures == 0) {
            LOGGER.info("All files downloaded successfully.");
            ClientConfig.setNowVersion(last_update_data.version);
        }
    }

    public Pair<Integer, Integer> getUpdateProgress() {
        return update_progress;
    }

    public int getDownloadFailures() {
        return download_failures;
    }

    public void connectToServer() {
        if (file_client != null) {
            if (file_client.heartbeat()) {
                LOGGER.warn("Already connected to the server.");
                return;
            }
            return;
        }
        file_client = new FileClient(ClientConfig.host, ClientConfig.port);

        if (file_client.heartbeat()) {
            LOGGER.info("Connected to SakuraUpdater Server at {}:{}", ClientConfig.host, ClientConfig.port);
            return;
        }
    }

    private Screen createUpdateScreen() {
        return debug ? new TestScreen() : new UpdateCheckScreen();
    }

    /** 我们自己的界面：自动弹窗本身和它的下级界面，玩家进到这些界面就说明更新提示已经送达 */
    private boolean isOurScreen(Screen screen) {
        return screen instanceof UpdateCheckScreen || screen instanceof UpdateScreen
                || screen instanceof TestScreen || screen instanceof FixScreen;
    }

    private void startShow() {
        show_screen = createUpdateScreen();
        show_displayed = false;
        show_attempts++;
        show_state = ShowState.SHOWING;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onScreenOpenning(ScreenEvent.Opening event) {
        // 主菜单打开时抢先把更新界面塞进去；用 LOWEST 保证在同样改屏幕的监听器里最后写入
        if (show_state != ShowState.WAITING || show_retry_delay > 0 || show_attempts >= MAX_SHOW_ATTEMPTS)
            return;
        if (!(event.getScreen() instanceof TitleScreen))
            return;
        startShow();
        event.setNewScreen(show_screen);
    }

    // 兜底重试：其它 mod（例如 Distant Horizons 的更新界面）可能在之后用 setScreen 直接顶掉我们的界面，
    // 光靠 ScreenEvent.Opening 只有一次机会，被顶掉就再也弹不出来了
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (show_retry_delay > 0) {
            show_retry_delay--;
        }
        if (show_state == ShowState.DONE) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();

        if (show_state == ShowState.WAITING) {
            if (isOurScreen(minecraft.screen)) { // 玩家自己从标题界面的按钮打开了我们的界面
                show_state = ShowState.DONE;
                return;
            }
            if (show_retry_delay > 0 || !(minecraft.screen instanceof TitleScreen)) {
                return;
            }
            if (show_attempts >= MAX_SHOW_ATTEMPTS) {
                show_state = ShowState.DONE;
                LOGGER.warn("SakuraUpdater: update screen was replaced {} times, giving up this launch. "
                        + "The title screen button can still open it manually.", show_attempts);
                return;
            }
            startShow();
            minecraft.setScreen(show_screen);
            return;
        }

        // SHOWING
        if (minecraft.screen == show_screen) {
            show_displayed = true; // 界面确实显示过
            return;
        }
        // 界面被换掉了：只有回到标题界面或进入我们自己的下级界面，才算玩家用完了这次提示。
        // 不能按"显示了多久"来判断：其它 mod（Distant Horizons）可能在我们界面显示几秒之后才把它顶掉。
        if (show_displayed && (minecraft.screen instanceof TitleScreen || isOurScreen(minecraft.screen))) {
            show_state = ShowState.DONE;
            return;
        }
        // 被别的 mod 顶掉了，记下是谁干的并稍后重试
        LOGGER.warn("SakuraUpdater: update screen was replaced by {} before the player used it, retrying in {} ticks.",
                minecraft.screen == null ? "null" : minecraft.screen.getClass().getName(), SHOW_RETRY_DELAY_TICKS);
        show_state = ShowState.WAITING;
        show_retry_delay = SHOW_RETRY_DELAY_TICKS;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                literal("sakuraupdater")
                        .then(createReloadCommand()));
        // .then(LiteralArgumentBuilder.<CommandSourceStack>literal("reload")
        // .then(LiteralArgumentBuilder.<CommandSourceStack>literal("client")
        // .requires(source -> source.hasPermission(0))
        // .executes(ctx -> {
        // ctx.getSource().sendSuccess(
        // () -> net.minecraft.network.chat.Component
        // .literal("SakuraUpdater 客户端配置已重载！"),
        // true);
        // ClientConfig.onLoad(null); // 重新加载配置
        // return 1;
        // }))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> createReloadCommand() {
        return literal("reload")
                .then(literal("client")
                        .requires(source -> source.hasPermission(0))
                        .executes(ctx -> {
                            sendSuccessMessage(ctx.getSource(), "SakuraUpdater 客户端配置已重载！");
                            ClientConfig.onLoad(null); // 重新加载配置
                            return 1;
                        }));
    }
}
