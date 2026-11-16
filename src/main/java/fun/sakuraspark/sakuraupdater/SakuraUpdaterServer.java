package fun.sakuraspark.sakuraupdater;

import org.slf4j.Logger;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import static com.mojang.brigadier.arguments.StringArgumentType.*;
import static fun.sakuraspark.sakuraupdater.utils.CommandUtils.*;

import com.mojang.logging.LogUtils;

import fun.sakuraspark.sakuraupdater.config.DataConfig;
import fun.sakuraspark.sakuraupdater.config.ServerConfig;
import fun.sakuraspark.sakuraupdater.config.DataConfig.PathData;
import fun.sakuraspark.sakuraupdater.config.IGetSyncDirs;
import fun.sakuraspark.sakuraupdater.network.FileServer;
import fun.sakuraspark.sakuraupdater.utils.FileWatchdog;
import fun.sakuraspark.sakuraupdater.utils.ServerCommandsHelper;
import fun.sakuraspark.sakuraupdater.utils.ServerCommandsHelper.CommandResult;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.vehicle.Minecart;

import static net.minecraft.commands.Commands.*;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;

//@Mod.EventBusSubscriber(value = Dist.DEDICATED_SERVER, modid = SakuraUpdater.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SakuraUpdaterServer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private FileServer file_server;

    public static SakuraUpdaterServer INSTANCE;

    private FileWatchdog watchdog;
    private long lastWatchdogNotifyTime = 0;
    private static final long WATCHDOG_NOTIFY_INTERVAL = 30_000; // 30秒

    SakuraUpdaterServer() {
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.COMMON, ServerConfig.SPEC);
        if (!DataConfig.connectToDatabase("config/sakuraupdater-database.db")) {
            throw new IllegalStateException(
                    "Failed to initialize SakuraUpdater database; see dependency/database errors above.");
        }
        INSTANCE = this;
        // fileServer = new FileServer(ConfigServer.SERVER_PORT.get(),
        // ConfigServer.SYNC_DIR.get());

        // set watchdog
        watchdog = new FileWatchdog();
        watchdog.setFileChangeHandler(() -> {
            file_server.setHeartbeatStatus("factory"); // set heartbeat status to factory when file change detected
            Util.backgroundExecutor().execute(() -> {
                while (watchdog.isFileChanged()) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastWatchdogNotifyTime < WATCHDOG_NOTIFY_INTERVAL) {
                        continue;
                    }
                    lastWatchdogNotifyTime = currentTime;
                    // 1. output log
                    LOGGER.warn("file change detected, don't forget to commit the changes!");

                    // 2. send message to all online ops
                    MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                    if (server != null) {
                        server.execute(() -> {
                            Component msg = Component.translatable("gui.sakuraupdater.Message.FileChangeDetected");
                            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                                if (server.getPlayerList().isOp(player.getGameProfile())) {
                                    player.sendSystemMessage(msg);
                                }
                            }
                        });
                    }

                }
            });
        });
    }

    public static SakuraUpdaterServer getInstance() {
        return INSTANCE;
    }

    public void runServer() {
        // add sync directories to watchdog
        for (String syncDir : IGetSyncDirs.getSyncDirs()) {
            List<String> parts = List.of(syncDir.split(":"));
            if (parts.size() < 2) {
                continue;
            }
            String targetPath = parts.get(0).trim();
            String model = parts.get(1).trim();
            if (!model.equals("mirror") && !model.equals("push")) {
                continue;
            }
            List<String> sourcePath = parts.size() > 2 ? parts.subList(2, parts.size()) : List.of(targetPath);
            for (String source : sourcePath) {
                watchdog.registerPath(new File(source).toPath());
            }
        }
        // first time check
        Thread firstTimeCheckThread = new Thread(() -> {
            List<String> lastMD5List = new ArrayList<>();
            List<String> currentMD5List = new ArrayList<>();
            for (PathData data : DataConfig.getLastData().paths) {
                if (!data.model.equals("mirror") && !data.model.equals("push")) {
                    continue;
                }
                data.files.forEach(file -> lastMD5List.add(file.md5));
            }
            for (PathData data : ServerCommandsHelper.getPathDataList()) {
                if (!data.model.equals("mirror") && !data.model.equals("push")) {
                    continue;
                }
                data.files.forEach(file -> currentMD5List.add(file.md5));
            }
            Collections.sort(lastMD5List);
            Collections.sort(currentMD5List);
            if (!lastMD5List.equals(currentMD5List)) {
                watchdog.fileChanged();
            }
        });
        firstTimeCheckThread.setDaemon(true);
        firstTimeCheckThread.start();

        if (file_server != null) {
            if (file_server.isRunning()) {
                LOGGER.warn("SakuraUpdater Server is already running!");
                return;
            }
            return;
        }
        file_server = new FileServer(ServerConfig.port, true);
        file_server.start();
        LOGGER.info("SakuraUpdater Server is running!");
    }

    // Register commands for the server
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(sakuraupdaterCommand());
    }

    // ----Create command----
    private LiteralArgumentBuilder<CommandSourceStack> sakuraupdaterCommand() {
        return literal("sakuraupdater")
                .then(createDataCommand())
                .then(createCommitCommand());
    }

    // 动态版本建议提供器
    private static final SuggestionProvider<CommandSourceStack> VERSION_SUGGESTIONS = (context, builder) -> {
        // 根据DataConfig.datalist动态生成版本建议
        for (String version : DataConfig.getAllVersions()) {
            builder.suggest(version);
        }
        return builder.buildFuture();
    };

    // ----Create the /ssync data command----
    private LiteralArgumentBuilder<CommandSourceStack> createDataCommand() {
        return literal("data")
                .requires(source -> source.hasPermission(2))
                .then(createDataEditCommand())
                .then(createDataRepairCommand())
                .then(createDataListCommand())
                .then(createDataShowCommand())
                .then(createDataDeleteCommand())
                .then(createDataClearCommand());
    }

    // ----Create the /ssync data subcommands----
    private LiteralArgumentBuilder<CommandSourceStack> createDataEditCommand() {
        return literal("edit")
                .then(argument("version", string())
                        .suggests(VERSION_SUGGESTIONS)
                        .then(argument("description", string())
                                .executes(context -> {
                                    String version = getString(context, "version");
                                    String description = getString(context, "description");
                                    CommandResult result = ServerCommandsHelper.editData(version, description);
                                    if (!result.success) {
                                        sendFailureMessage(context.getSource(), result.message);
                                        return 0;
                                    }
                                    sendSuccessMessage(context.getSource(), result.message);
                                    return 1;
                                })));
    }

    // ----Create the /ssync data repair command----
    private LiteralArgumentBuilder<CommandSourceStack> createDataRepairCommand() {
        return literal("repair")
                .then(argument("version", string())
                        .suggests(VERSION_SUGGESTIONS)
                        .executes(context -> {
                            String version = getString(context, "version");
                            CommandResult result = ServerCommandsHelper.repairData(version);
                            if (!result.success) {
                                sendFailureMessage(context.getSource(), result.message);
                                return 0;
                            }
                            sendSuccessMessage(context.getSource(), result.message);
                            return 1;
                        }));
    }

    // ----Create the /ssync data list command----
    private LiteralArgumentBuilder<CommandSourceStack> createDataListCommand() {
        return literal("list")
                .executes(context -> {
                    CommandResult dataList = ServerCommandsHelper.buildDataListString();
                    if (!dataList.success) {
                        sendFailureMessage(context.getSource(), dataList.message);
                        return 0;
                    }
                    sendSuccessMessage(context.getSource(), dataList.message);
                    return 1;
                });
    }

    // ----Create the /ssync data show command----
    private LiteralArgumentBuilder<CommandSourceStack> createDataShowCommand() {
        return literal("show")
                .then(argument("version", string())
                        .suggests(VERSION_SUGGESTIONS)
                        .executes(context -> {
                            String version = getString(context, "version");
                            CommandResult result = ServerCommandsHelper.showData(version);
                            if (!result.success) {
                                sendFailureMessage(context.getSource(), result.message);
                                return 0;
                            }
                            sendSuccessMessage(context.getSource(), result.message);
                            return 1;
                        }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> createDataDeleteCommand() {
        return literal("delete")
                .then(argument("version", string())
                        .suggests(VERSION_SUGGESTIONS)
                        .executes(context -> {
                            String version = getString(context, "version");
                            CommandResult result = ServerCommandsHelper.deleteData(version);
                            if (!result.success) {
                                sendFailureMessage(context.getSource(), result.message);
                            } else {
                                sendSuccessMessage(context.getSource(), result.message);
                            }
                            return 1;
                        }));
    }

    // ----Create the /ssync data clear command----
    private LiteralArgumentBuilder<CommandSourceStack> createDataClearCommand() {
        return literal("clear")
                .executes(context -> {
                    CommandResult result = ServerCommandsHelper.clearData();
                    if (!result.success) {
                        sendFailureMessage(context.getSource(), result.message);
                    } else {
                        sendSuccessMessage(context.getSource(), result.message);
                    }
                    return 1;
                });
    }

    // ----Create the /ssync commit command----
    private LiteralArgumentBuilder<CommandSourceStack> createCommitCommand() {
        return literal("commit")
                .requires(source -> source.hasPermission(2))
                .then(argument("version", string())
                        .suggests(VERSION_SUGGESTIONS)
                        .then(argument("description", greedyString())
                                .executes(context -> {
                                    // LOGGER.debug("why!");
                                    String version = getString(context, "version");
                                    String description = getString(context, "description");

                                    CommandResult result = ServerCommandsHelper.commitData(version, description);
                                    if (!result.success) {
                                        sendFailureMessage(context.getSource(), result.message);
                                        return 0;
                                    }
                                    sendSuccessMessage(context.getSource(), result.message);
                                    watchdog.handled(); // reset the file change flag after commit
                                    file_server.setHeartbeatStatus("ok"); // set heartbeat status to "ok" from "factory"
                                                                          // after commit
                                    return 1;
                                })));
    }

}
