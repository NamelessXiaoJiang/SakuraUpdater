package fun.sakuraspark.sakuraupdater;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fun.sakuraspark.sakuraupdater.config.DataConfig;
import fun.sakuraspark.sakuraupdater.config.StandaloneServerConfig;
import fun.sakuraspark.sakuraupdater.network.FileServer;
import fun.sakuraspark.sakuraupdater.utils.ServerCommandsHelper;

/**
 * 独立模式入口 - 脱离 Forge/Minecraft 运行
 * 使用 YAML 配置替代 ForgeConfigSpec
 */
public class SakuraUpdaterServerStandalone {

    private static final class PromptManager {
        public final AtomicBoolean awaitingInput = new AtomicBoolean(false);
        public final String promptText;

        private PromptManager(String promptText) {
            this.promptText = promptText;
        }

        private void showPrompt(PrintStream out) {
            awaitingInput.set(true);
            out.print(promptText);
            out.flush();
        }

        private void clearPromptState() {
            awaitingInput.set(false);
        }

        private void reprintPromptIfNeeded(PrintStream out) {
            if (awaitingInput.get()) {
                out.print(promptText);
                out.flush();
            }
        }
    }

    private static final class PromptingOutputStream extends OutputStream {
        private final PrintStream delegate;
        private final PromptManager promptManager;

        private PromptingOutputStream(PrintStream delegate, PromptManager promptManager) {
            this.delegate = delegate;
            this.promptManager = promptManager;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            if (b == '\n') {
                promptManager.reprintPromptIfNeeded(delegate);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            for (int i = off; i < off + len; i++) {
                if (b[i] == '\n') {
                    promptManager.reprintPromptIfNeeded(delegate);
                    break;
                }
            }
        }
    }

    private static PrintStream wrapPrintStream(PrintStream original, PromptManager promptManager) {
        return new PrintStream(new PromptingOutputStream(original, promptManager), true, StandardCharsets.UTF_8);
    }

    public static class CommandDispatcher {
        // 这里实现一个简单的命令解析器，根据输入的命令调用 ServerCommandsHelper 中的方法
        private String command;
        private List<CommandDispatcher> arge_list = new ArrayList<>();
        private Consumer<String> command_handler;

        /**
         * 创建一个新的命令分发器
         * 
         * @param command 命令中需要消费的头部命令，例如 "data list" 中的 "data"
         */
        CommandDispatcher(String command) {
            this.command = command;
        }

        /**
         * 添加一个子命令分发器，例如 "data list" 中的 "list" 可以作为 "data" 的子命令
         * 
         * @param handler 子命令分发器
         * @return 当前命令分发器，方便链式调用
         */
        public CommandDispatcher then(CommandDispatcher handler) {
            this.arge_list.add(handler);
            return this;
        }

        /**
         * 设置命令处理器，当命令被正确解析时调用
         * 
         * @param handler 处理器，接受完整的命令字符串作为参数
         * @return 当前命令分发器，方便链式调用
         */
        public CommandDispatcher execute(Consumer<String> handler) {
            this.command_handler = handler;
            return this;
        }

        /**
         * 分发命令，根据输入的命令字符串解析并调用相应的处理器
         * 链式传递下去的是命令字符串中去掉当前命令头部的部分，例如 "sakuraupdater data list" 传递给 "data" 的是 "list"
         * 
         * @param command 输入的完整命令字符串
         * @return 如果命令被处理返回 true，否则返回 false
         */
        public boolean dispatch(String command) {
            // TODO: 诡异的处理方式🤣
            String[] parts = command.split(" ", 2);
            boolean handled = false;
            if (this.command == null || parts[0].equals(this.command)) {
                if (this.command_handler != null) {
                    this.command_handler.accept(this.command == null ? command : parts.length > 1 ? parts[1] : "");
                    handled = true;
                }
                if (parts.length > 1) {
                    for (CommandDispatcher dispatcher : arge_list) {
                        if (dispatcher.dispatch(this.command == null ? command : parts[1])) {
                            handled = true;
                        }
                    }
                }
            }
            return handled;
        }

    }

    private static final Logger LOGGER = LoggerFactory.getLogger(SakuraUpdaterServerStandalone.class);

    public static int main(String[] args) {
        System.out.println("""
                  ____        _                    _   _           _       _            
                 / ___|  __ _| | ___   _ _ __ __ _| | | |_ __   __| | __ _| |_ ___ _ __ 
                 \\___ \\ / _` | |/ / | | | '__/ _` | | | | '_ \\ / _` |/ _` | __/ _ \\ '__|
                  ___) | (_| |   <| |_| | | | (_| | |_| | |_) | (_| | (_| | ||  __/ |   
                 |____/ \\__,_|_|\\_\\\\__,_|_|  \\__,_|\\___/| .__/ \\__,_|\\__,_|\\__\\___|_|   
                                                        |_|                             
                """);
        LOGGER.info("SakuraUpdater Standalone Server is starting...");

        // 1. 加载 YAML 配置
        StandaloneServerConfig.initialize();

        // 2. 连接数据库
        if (!DataConfig.connectToDatabase("sakuraupdater-database.db")) {
            LOGGER.error("Failed to connect to SakuraUpdater database!");
            return 1;
        }

        // 3. 启动文件服务器
        int port = StandaloneServerConfig.getPort();
        LOGGER.info("Starting file server on port {}...", port);
        FileServer fileServer = new FileServer(port);
        fileServer.start();

        // 4. 设置命令分发器
        CommandDispatcher rootDispatcher = new CommandDispatcher(null)
                .then(new CommandDispatcher("data")
                        .then(new CommandDispatcher("list").execute(cmd -> {
                            ServerCommandsHelper.CommandResult result = ServerCommandsHelper.buildDataListString();
                            if (result.success) {
                                LOGGER.info(result.message);
                            } else {
                                LOGGER.warn(result.message);
                            }
                        }))
                        .then(new CommandDispatcher("edit").execute(cmd -> {
                            // 这里可以解析 cmd 获取版本和描述等参数，然后调用 ServerCommandsHelper.editData()
                            // 例如，命令格式可以是 "sakuraupdater data edit <version> <description>"
                            String[] parts = cmd.split(" ", 2);
                            if (parts.length < 2) {
                                LOGGER.warn(
                                        "Invalid command format. Usage: sakuraupdater data edit <version> <description>");
                                return;
                            }
                            String version = parts[0];
                            String description = parts[1];
                            ServerCommandsHelper.CommandResult result = ServerCommandsHelper.editData(version,
                                    description);
                            if (result.success) {
                                LOGGER.info(result.message);
                            } else {
                                LOGGER.warn(result.message);
                            }
                        }))
                        .then(new CommandDispatcher("repair").execute(cmd -> {
                            String[] parts = cmd.split(" ", 2);
                            if (parts.length > 1) {
                                LOGGER.warn(
                                        "Invalid command format. Usage: sakuraupdater data repair <version>");
                                return;
                            }
                            ServerCommandsHelper.CommandResult result = ServerCommandsHelper.repairData(parts[0]);
                            if (result.success) {
                                LOGGER.info(result.message);
                            } else {
                                LOGGER.warn(result.message);
                            }
                        }))
                        .then(new CommandDispatcher("show").execute(cmd -> {
                            String[] parts = cmd.split(" ", 2);
                            if (parts.length > 1) {
                                LOGGER.warn(
                                        "Invalid command format. Usage: sakuraupdater data show <version>");
                                return;
                            }
                            ServerCommandsHelper.CommandResult result = ServerCommandsHelper.showData(parts[0]);
                            if (result.success) {
                                LOGGER.info(result.message);
                            } else {
                                LOGGER.warn(result.message);
                            }
                        }))
                        .then(new CommandDispatcher("delete").execute(cmd -> {
                            // 这里可以解析 cmd 获取版本参数，然后调用 ServerCommandsHelper.deleteData()
                            String[] parts = cmd.split(" ", 2);
                            if (parts.length > 1) {
                                LOGGER.warn(
                                        "Invalid command format. Usage: sakuraupdater data delete <version>");
                                return;
                            }
                            ServerCommandsHelper.CommandResult result = ServerCommandsHelper.deleteData(parts[0]);
                            if (result.success) {
                                LOGGER.info(result.message);
                            } else {
                                LOGGER.warn(result.message);
                            }
                        }))
                        .then(new CommandDispatcher("clear").execute(cmd -> {
                            ServerCommandsHelper.CommandResult result = ServerCommandsHelper.clearData();
                            if (result.success) {
                                LOGGER.info(result.message);
                            } else {
                                LOGGER.warn(result.message);
                            }
                        })))
                .then(new CommandDispatcher("commit").execute(cmd -> {
                    // description 是支持空格的，所以只分割两部分，第一部分是版本号，第二部分是描述
                    String[] parts = cmd.split(" ", 2);
                    if (parts.length < 2) {
                        LOGGER.warn(
                                "Invalid command format. Usage: sakuraupdater commit <version> <description>");
                        return;
                    }
                    String version = parts[0];
                    String description = parts[1];
                    ServerCommandsHelper.CommandResult result = ServerCommandsHelper.commitData(version,
                            description);
                    if (result.success) {
                        LOGGER.info(result.message);
                    } else {
                        LOGGER.warn(result.message);
                    }
                }));

        // 5. 监听控制台输入，分发命令
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                //promptManager.showPrompt(System.out);
                if (!scanner.hasNextLine()) {
                    // 输入流被关闭，退出循环
                    break;
                }
                String input = scanner.nextLine();
                if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input) || "stop".equalsIgnoreCase(input)) {
                    LOGGER.info("Shutting down SakuraUpdater Standalone Server...");
                    scanner.close();
                    return 0;
                }
                // if ("restart".equalsIgnoreCase(input)) {
                //     LOGGER.info("Restarting SakuraUpdater Standalone Server...");
                //     fileServer.shutdown();
                //     scanner.close();
                //     return 1; // 返回 1 表示需要重启
                // }
                try {
                    if (!rootDispatcher.dispatch(input)) {
                        LOGGER.warn("Unknown command: {}", input);
                    }
                } catch (Exception e) {
                    LOGGER.error("Error while executing command: ", e);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error while reading console input: ", e);
            return 1;
        } finally {
            fileServer.shutdown();
            DataConfig.closeDatabase();
        }
        return 0;
    }
}
