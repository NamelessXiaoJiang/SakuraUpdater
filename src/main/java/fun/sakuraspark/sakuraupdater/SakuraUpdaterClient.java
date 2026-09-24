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
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;

import static fun.sakuraspark.sakuraupdater.utils.CommandUtils.sendSuccessMessage;
import static net.minecraft.commands.Commands.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public class SakuraUpdaterClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static SakuraUpdaterClient INSTANCE;

    private FileClient file_client;
    private Data last_update_data = null; // 上次更新的数据
    private Data current_update_data = null; // 当前更新的数据，只有存在push时才会有
    private List<Data> changelog = null; // 更新日志（从当前版本到最新版本）
    // 无文件变化时本地版本会提前更新，日志请求和重试仍需使用本次检查开始时的版本。
    private String changelogFromVersion;

    // ---- 更新进度：后台线程记账，渲染线程每帧读一份不可变快照 ----
    /** 更新进行到哪一步：界面用它决定进度条的口径和提示文案 */
    public enum UpdatePhase {
        IDLE, // 没有可显示的进度
        PREPARING, // 清单里没有体积，正在向服务端探测各文件大小
        DELETING, // 正在清理旧文件（不产生体积，进度条停在 0）
        DOWNLOADING, // 按字节推进
        DONE // 下载阶段已经结束
    }

    /** 给界面看的不可变进度快照 */
    public static final class UpdateProgress {
        public final UpdatePhase phase;
        public final long doneBytes; // 已下载的字节数
        public final long totalBytes; // 需要下载的字节数；有文件体积未知时只是下限
        public final boolean totalKnown; // 分母是否完整（缺体积的文件都靠探测或实际落盘大小补上了）
        public final int doneFiles; // 当前阶段已完成 / 已探测的文件数
        public final int totalFiles; // 当前阶段的文件总数
        public final long bytesPerSecond; // 平滑后的下载速度，0 表示还没测出

        UpdateProgress(UpdatePhase phase, long doneBytes, long totalBytes, boolean totalKnown, int doneFiles,
                int totalFiles, long bytesPerSecond) {
            this.phase = phase;
            this.doneBytes = doneBytes;
            this.totalBytes = totalBytes;
            this.totalKnown = totalKnown;
            this.doneFiles = doneFiles;
            this.totalFiles = totalFiles;
            this.bytesPerSecond = bytesPerSecond;
        }
    }

    /** 进度记账，只在检查/探测/下载这些后台线程上改；改完由 publishProgress 换成快照给界面 */
    private static final class ProgressState {
        UpdatePhase phase = UpdatePhase.IDLE;
        long totalBytes;
        int unknownFiles; // 体积还未知的待下文件数，> 0 时总量只是下限
        int doneFiles;
        int totalFiles;
        long bytesPerSecond;
        long speedWindowStartMs; // 速度采样窗口，0 表示需要重新开始采样
        long speedWindowBytes;
    }

    private static final long PUBLISH_INTERVAL_MS = 50; // 快照刷新间隔，免得每读一个 8KB 就建一个对象
    private static final long SPEED_WINDOW_MS = 500; // 速度采样窗口

    private final ProgressState progress_state = new ProgressState();
    /** 已计入的下载字节。重试/回退/校验失败时回调会给负增量，所以这里用累加而不是递增 */
    private final AtomicLong received_bytes = new AtomicLong();
    private volatile UpdateProgress update_progress = new UpdateProgress(UpdatePhase.IDLE, 0, 0, true, 0, 0, 0);
    private long last_publish_ms;

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

    // ---- 启动预取：配置加载完就在后台把检查跑完，界面弹出时直接拿结果，不占玩家的等待时间 ----
    private CompletableFuture<Integer> update_check = null; // 当前这次检查（1/2/3/-1，含义同 UpdateCheckScreen.updateStatus）
    private boolean prefetch_started = false; // 预取整个进程只发起一次：配置 reload 不会再来一轮
    private volatile boolean update_check_stale = false; // 玩家进过世界/刚更新完，旧结果不能再拿去显示

    SakuraUpdaterClient() {
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
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
            changelog = file_client.getChangeLog(changelogFromVersion != null
                    ? changelogFromVersion : ClientConfig.getNowVersion());
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

    /** 启动预取：游戏还在加载时就把更新检查跑起来（配置加载完成后调用，整个进程只发起一次）。 */
    public synchronized void prefetchUpdateCheck() {
        if (prefetch_started) {
            return;
        }
        prefetch_started = true;
        LOGGER.info("SakuraUpdater: prefetching update check in the background, the game is still loading.");
        startUpdateCheck();
    }

    /** 取检查结果：有可用的预取就复用，没有（或已经过期）就立刻起一次新的。 */
    public synchronized CompletableFuture<Integer> getUpdateCheck() {
        if (update_check == null || update_check_stale) {
            startUpdateCheck();
        }
        return update_check;
    }

    /** 强制重新检查：玩家手动点“检查更新”或“重试”时用，不让旧结果顶替。 */
    public synchronized CompletableFuture<Integer> restartUpdateCheck() {
        startUpdateCheck();
        return update_check;
    }

    /** 把当前结果标记为过期：玩家已经进过世界，或本地刚更新完一轮。 */
    public void invalidateUpdateCheck() {
        update_check_stale = true;
    }

    /**
     * 发起一次检查。同一时刻只允许一份在跑：旧的还没结束时排在它后面，
     * 否则两份检查会同时写最新版本号和待删/待下清单这批字段，界面可能显示这一份的版本却用另一份的清单。
     */
    private synchronized void startUpdateCheck() {
        CompletableFuture<Integer> previous = update_check;
        if (previous != null && !previous.isDone()) {
            update_check = previous.handle((result, error) -> null).thenCompose(ignored -> runUpdateCheck());
        } else {
            update_check = runUpdateCheck();
        }
        update_check_stale = false;
    }

    /** 后台线程里的检查体：查版本 → 算本地文件校验 → 更新日志也一并取回来。 */
    private CompletableFuture<Integer> runUpdateCheck() {
        return CompletableFuture.supplyAsync(() -> {
            long started = System.currentTimeMillis();
            int result;
            try {
                int check = updateCheck();
                if (check == -1) {
                    result = -1;
                } else if (check == 0) {
                    result = 2;
                } else {
                    result = integrityCheck() ? 1 : 3;
                    // 更新日志本来会在界面 init() 里于渲染线程同步请求一次，这里先在后台取好；
                    // 取日志失败不影响这次检查的结论（界面会照旧自己重试一次）
                    try {
                        getChangeLogText();
                    } catch (Exception e) {
                        LOGGER.warn("SakuraUpdater: failed to prefetch the changelog.", e);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Error during update check", e);
                result = -1;
            }
            LOGGER.info("SakuraUpdater: update check finished in {} ms, result {}.",
                    System.currentTimeMillis() - started, result);
            return result;
        }, Util.backgroundExecutor());
    }

    public int updateCheck() {
        changelogFromVersion = ClientConfig.getNowVersion();
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
        resetProgress(); // 重置进度
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
            resetProgress();
            ClientConfig.setNowVersion(last_update_data.version); // 不需要更新文件但是还是需要更新本地版本号
            return false;
        }

        // 体积：清单里带 size 就能直接算出来，不用再多问服务端一句。
        // 本改动之前 commit/repair 出来的清单（以及旧服务端）没有 size，留给下载前那轮探测兜底。
        long totalBytes = 0;
        int unknownFiles = 0;
        for (FileData fileData : integrityCheckResult.getSecond()) {
            if (fileData.size > 0) {
                totalBytes += fileData.size;
            } else {
                unknownFiles++;
            }
        }
        synchronized (this) {
            progress_state.phase = UpdatePhase.IDLE;
            progress_state.totalBytes = totalBytes;
            progress_state.unknownFiles = unknownFiles;
            progress_state.doneFiles = 0;
            progress_state.totalFiles = integrityCheckResult.getSecond().size();
            progress_state.bytesPerSecond = 0;
            progress_state.speedWindowStartMs = 0;
            publishProgress(true);
        }
        LOGGER.info("SakuraUpdater: {} file(s) to download, {} to delete, total {}, {} file(s) without a known size.",
                integrityCheckResult.getSecond().size(), integrityCheckResult.getFirst().size(),
                FileUtils.formatSize(totalBytes), unknownFiles);
        return true;
    }

    public void downloadUpdate() {
        if (download_failures == -1) {
            download_failures = 0;
        }
        if (integrityCheckResult == null) {
            LOGGER.error("downloadUpdate() was called before integrityCheck(), nothing to do.");
            return;
        }
        List<File> toDelete = integrityCheckResult.getFirst();
        List<FileData> toDownload = integrityCheckResult.getSecond();

        // 清单里没有体积时先探一遍，否则界面给不出"总共需要多少"这个数。
        // 服务端换成带 size 的清单之后这一步是空转（零请求）。
        boolean needsSizeProbe;
        synchronized (this) {
            needsSizeProbe = progress_state.unknownFiles > 0;
        }
        if (needsSizeProbe) {
            prepareSizes(toDownload);
        }

        // 删除不需要的文件。删文件不产生体积，所以进度条停在 0，只报"第几个"。
        synchronized (this) {
            progress_state.phase = UpdatePhase.DELETING;
            progress_state.doneFiles = 0;
            progress_state.totalFiles = toDelete.size();
            publishProgress(true);
        }
        for (File file : toDelete) {
            if (file.delete()) {
                LOGGER.info("Deleted file: {}", file);
            } else {
                LOGGER.error("Failed to delete file: {}", file);
            }
            synchronized (this) {
                progress_state.doneFiles++;
                publishProgress(false);
            }
        }

        // 下载需要的文件
        synchronized (this) {
            progress_state.phase = UpdatePhase.DOWNLOADING;
            progress_state.doneFiles = 0;
            progress_state.totalFiles = toDownload.size();
            progress_state.bytesPerSecond = 0;
            progress_state.speedWindowStartMs = 0; // 删除阶段不产生字节，速度窗口从下载开始重新采
            publishProgress(true);
        }
        for (FileData fileData : toDownload) {
            if (file_client.downloadFile(fileData.sourcePath, fileData.targetPath, fileData.md5,
                    ClientConfig.getDownloadConnections(), byteListener)) {
                LOGGER.info("Downloaded file: {}", fileData.sourcePath);
                long actualSize = new File(fileData.targetPath).length();
                synchronized (this) {
                    if (fileData.size <= 0) {
                        progress_state.unknownFiles--;
                    }
                    // 用实际落盘大小修正分母：服务端记错体积、或清单里没有体积时，进度条会随着下载自我修正
                    progress_state.totalBytes += actualSize - Math.max(0, fileData.size);
                }
            } else {
                download_failures++;
                LOGGER.error("Failed to download file: {}", fileData.sourcePath);
            }
            synchronized (this) {
                progress_state.doneFiles++;
                publishProgress(true);
            }
        }
        synchronized (this) {
            progress_state.phase = UpdatePhase.DONE;
            publishProgress(true);
        }
        if (download_failures == 0) {
            LOGGER.info("All files downloaded successfully.");
            ClientConfig.setNowVersion(last_update_data.version);
            invalidateUpdateCheck(); // 本地已经更新完一轮，旧结果里的待删/待下清单不能再用于之后的显示
        }
    }

    /**
     * 并发探测清单里没有体积的文件（每个文件一次 Range: bytes=0-0），把体积补进分母。
     * 探不到的保持未知：总量会显示成下限，等文件真的下完再用实际大小补上。
     */
    private void prepareSizes(List<FileData> toDownload) {
        List<FileData> unknown = new ArrayList<>();
        for (FileData fileData : toDownload) {
            if (fileData.size <= 0) {
                unknown.add(fileData);
            }
        }
        if (unknown.isEmpty()) {
            return;
        }
        LOGGER.info("SakuraUpdater: probing the size of {} file(s) before downloading.", unknown.size());
        synchronized (this) {
            progress_state.phase = UpdatePhase.PREPARING;
            progress_state.doneFiles = 0;
            progress_state.totalFiles = unknown.size();
            publishProgress(true);
        }
        int threads = Math.min(Math.max(1, ClientConfig.getDownloadConnections()), unknown.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread thread = new Thread(r, "sakuraupdater-size");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (FileData fileData : unknown) {
                futures.add(pool.submit(() -> onSizeProbed(fileData, file_client.probeRemoteSize(fileData.sourcePath))));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    LOGGER.warn("Size probe task failed: {}", e.toString());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** 一个文件的体积探完了：>= 0 表示探到了（0 是空文件），-1 表示只能留给下载阶段补 */
    private synchronized void onSizeProbed(FileData fileData, long size) {
        if (size >= 0) {
            fileData.size = size;
            progress_state.totalBytes += size;
            progress_state.unknownFiles--;
        }
        progress_state.doneFiles++;
        publishProgress(true);
    }

    /** 下载线程的字节回调：只有真正落盘的文件会留下已计入的字节，重试/回退/校验失败会给负增量 */
    private final FileClient.ByteProgressListener byteListener = delta -> {
        received_bytes.addAndGet(delta);
        publishProgress(false); // 8 个分块线程都会进来，publishProgress 内部按 PUBLISH_INTERVAL_MS 节流
    };

    private void resetProgress() {
        synchronized (this) {
            progress_state.phase = UpdatePhase.IDLE;
            progress_state.totalBytes = 0;
            progress_state.unknownFiles = 0;
            progress_state.doneFiles = 0;
            progress_state.totalFiles = 0;
            progress_state.bytesPerSecond = 0;
            progress_state.speedWindowStartMs = 0;
            received_bytes.set(0);
            publishProgress(true);
        }
    }

    /** 把当前记账换成一份不可变快照给渲染线程；速度在这里按 500ms 窗口做指数滑动平均 */
    private synchronized void publishProgress(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - last_publish_ms < PUBLISH_INTERVAL_MS) {
            return;
        }
        last_publish_ms = now;
        if (progress_state.speedWindowStartMs == 0) {
            progress_state.speedWindowStartMs = now;
            progress_state.speedWindowBytes = received_bytes.get();
        }
        long elapsed = now - progress_state.speedWindowStartMs;
        if (elapsed >= SPEED_WINDOW_MS) {
            long bytes = received_bytes.get();
            long instant = Math.max(0, bytes - progress_state.speedWindowBytes) * 1000 / elapsed;
            progress_state.bytesPerSecond = progress_state.bytesPerSecond == 0 ? instant
                    : (instant * 3 + progress_state.bytesPerSecond * 7) / 10;
            progress_state.speedWindowStartMs = now;
            progress_state.speedWindowBytes = bytes;
        }
        update_progress = new UpdateProgress(progress_state.phase, received_bytes.get(),
                progress_state.totalBytes, progress_state.unknownFiles == 0, progress_state.doneFiles,
                progress_state.totalFiles, progress_state.bytesPerSecond);
    }

    public UpdateProgress getUpdateProgress() {
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
    public void onScreenOpening(ScreenEvent.Opening event) {
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
    public void onClientTick(ClientTickEvent.Post event) {
        if (show_retry_delay > 0) {
            show_retry_delay--;
        }
        if (show_state == ShowState.DONE) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            // 玩家已经进了世界（例如快速进入/自动进服，我们的界面根本没弹过）：这份预取结果
            // 之后不能再拿来显示，可能已经过了很久，甚至本地已经更新过一轮
            invalidateUpdateCheck();
        }

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
