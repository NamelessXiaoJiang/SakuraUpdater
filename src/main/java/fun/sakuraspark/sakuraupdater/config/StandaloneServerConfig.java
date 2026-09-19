package fun.sakuraspark.sakuraupdater.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;

/**
 * 独立模式的服务器配置 - 完全脱离 Forge/Minecraft 依赖
 * 使用 TOML 格式（与 Forge 模式的 sakuraupdater-common.toml 格式兼容）
 * 
 * <p>
 * 配置文件路径: sakuraupdater-common.toml
 * </p>
 */
public class StandaloneServerConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(StandaloneServerConfig.class);
    private static final String CONFIG_FILE = "sakuraupdater-common.toml";
    private static boolean initialized = false;

    // ---- 配置值 ----
    private static int port = 25564;
    private static int maxThreads = 32;
    private static List<String> syncDirs = new ArrayList<>();

    private StandaloneServerConfig() {
        // 工具类，禁止实例化
    }

    public static boolean isStandalone() {
        return initialized;
    }

    /**
     * 初始化配置 - 从 TOML 文件加载，若文件不存在则自动创建默认配置
     */
    public static void initialize() {
        if (initialized)
            return;

        loadConfig();

        initialized = true;
    }

    /**
     * 从 TOML 文件加载配置
     */
    private static void loadConfig() {
        Path configPath = Paths.get(CONFIG_FILE);
        try {
            if (!Files.exists(configPath)) {
                LOGGER.info("Config file not found, creating default: {}", CONFIG_FILE);
                createDefaultConfig(configPath);
            }

            try (CommentedFileConfig config = CommentedFileConfig.builder(configPath)
                    .sync()
                    .autosave()
                    .build()) {
                config.load();

                // port
                Object portObj = config.get("port");
                if (portObj instanceof Number) {
                    int p = ((Number) portObj).intValue();
                    if (p >= 1 && p <= 65535) {
                        port = p;
                    } else {
                        LOGGER.warn("Config 'port' out of range (1-65535): {}, using default {}", p, port);
                    }
                }

                // max_threads
                Object threadsObj = config.get("max_threads");
                if (threadsObj instanceof Number) {
                    int t = ((Number) threadsObj).intValue();
                    if (t >= 4 && t <= 256) {
                        maxThreads = t;
                    } else {
                        LOGGER.warn("Config 'max_threads' out of range (4-256): {}, using default {}", t, maxThreads);
                    }
                }

                // SYNC_DIR
                List<String> syncList = config.get("SYNC_DIR");
                if (syncList != null) {
                    List<String> parsed = new ArrayList<>();
                    for (String entry : syncList) {
                        if (validateSyncEntry(entry)) {
                            parsed.add(entry);
                        }
                    }
                    syncDirs = parsed;
                }
            }
            LOGGER.info("Standalone config loaded successfully. port={}, syncDirs={}", port, syncDirs);
        } catch (Exception e) {
            LOGGER.error("Failed to load config file '{}', using defaults", CONFIG_FILE, e);
        }
    }

    /**
     * 创建带有注释说明的默认配置文件
     */
    private static void createDefaultConfig(Path configPath) throws IOException {
        String defaultContent = """
                #----IMPORTANT!!! Needs to restart!!!----
                #The port of the file server, default is 25564.
                #Range: 1 ~ 65535
                port = 25564
                #Max concurrent handler threads of the built-in file server.
                #Every in-flight file download occupies one thread, so keep it >= (client download_connections * expected concurrent updating players).
                #Range: 4 ~ 256
                max_threads = 32
                #A list of sync directories, each entry should be in the format 'targetpath:mode[:sourcepath:sourcepath2:...]', e.g. 'mod:mirror' or 'config:push:clientconfig'.
                #The 'targetpath' is the client target path of sync, and 'sourcepath' is the server source path of sync.
                #The 'sourcepath' is optional, if not provided, it will be the same as 'targetpath'.
                #'mode' can be 'mirror', 'push' or 'ignore'. 'mirror' will delete files in the target directory that are not in the source directory, while 'push' will not.
                #'ignore' mode uses regex to exclude files. Since the path is included, '.*filename$' is recommended (e.g., 'mods:ignore:.*abc\\.jar$').
                #This is used to determine which directories to sync with the client.
                #SYNC_DIR = [
                #    'example:mirror', // it same as 'example:mirror:example'
                #    'mods:mirror:mods:clientmods',
                #    'config:push:clientconfig',
                #    'mods:ignore:.*abc\\.jar$'
                #]
                SYNC_DIR = []
                """;

        Files.writeString(configPath, defaultContent);
    }

    /**
     * 验证同步目录条目格式
     */
    private static boolean validateSyncEntry(String entry) {
        if (entry == null || entry.isBlank()) {
            return false;
        }
        String[] parts = entry.split(":");
        if (parts.length < 2) {
            LOGGER.warn("sync_dirs entry '{}' 格式错误, 应为 'targetpath:mode[:sourcepath]'", entry);
            return false;
        }
        if (parts[0] == null || parts[0].isBlank()) {
            LOGGER.warn("sync_dirs entry '{}' 格式错误, targetpath 为空", entry);
            return false;
        }
        String mode = parts[1];
        if (!"mirror".equals(mode) && !"push".equals(mode) && !"ignore".equals(mode)) {
            LOGGER.warn("sync_dirs entry '{}' 格式错误, mode 应为 'mirror', 'push' 或 'ignore'", entry);
            return false;
        }
        return true;
    }

    // ---- Getter ----

    public static int getPort() {
        return port;
    }

    public static int getMaxThreads() {
        return maxThreads;
    }

    public static List<String> getSyncDirs() {
        loadConfig(); // 每次获取时重新加载配置，确保获取最新值
        return List.copyOf(syncDirs);
    }

}
