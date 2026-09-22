package fun.sakuraspark.sakuraupdater.utils;

import fun.sakuraspark.sakuraupdater.config.DataConfig;
import fun.sakuraspark.sakuraupdater.config.IGetSyncDirs;
import fun.sakuraspark.sakuraupdater.config.DataConfig.FileData;
import fun.sakuraspark.sakuraupdater.config.DataConfig.PathData;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 封装服务端命令的业务逻辑，与命令框架解耦。
 * context 参数获取和消息发送不在此处处理。
 */
public class ServerCommandsHelper {
    /**
     * 命令执行结果
     */
    public static class CommandResult {
        public final boolean success;
        public final String message;

        private CommandResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public static CommandResult success(String message) {
            return new CommandResult(true, message);
        }

        public static CommandResult failure(String message) {
            return new CommandResult(false, message);
        }
    }

    // ---- data edit ----
    public static CommandResult editData(String version, String description) {
        String fileContent = null; // 如果 description 是文件路径，则读取文件内容
        if (new File(description).exists()) {
            try {
                fileContent = Files.readString(Path.of(description));
            } catch (IOException e) {
                return CommandResult.failure(
                        "Description file is exist but failed to read description file: " + e.getMessage());
            }
        }
        if (!DataConfig.editData(version, fileContent != null ? fileContent : description.replace("\\n", "\n"))) {
            return CommandResult.failure("Failed to edit data: version not found.");
        }
        return CommandResult.success("SakuraUpdater updated the description of version " + version
                + " (file list and time are left untouched).");
    }

    // ---- data repair ----
    /**
     * 重建某个版本的文件清单：重新扫描同步目录，把清单写回该版本，描述与 time 保持不变（不会打乱更新日志顺序）。
     * 用法场景：被旧版 data edit 清空过清单的版本，或该版本当时没能正确 commit。
     * 注意：重建的是"当前"同步目录的快照，不是该版本当时的快照。
     */
    public static CommandResult repairData(String version) {
        DataConfig.Data data = DataConfig.getDataByVersion(version);
        if (data == null) {
            return CommandResult.failure("Data with version " + version + " not found.");
        }
        List<PathData> pathData;
        try {
            pathData = getPathDataList();
        } catch (Throwable t) {
            // 这里连 Error 一起接（例如独立/模组环境混用时缺类的 NoClassDefFoundError），
            // 与 FileServer 读取线程池配置时的处理保持一致：给一条明确失败而不是把命令打崩
            return CommandResult.failure("Failed to scan sync directories: " + t);
        }
        if (pathData.isEmpty()) {
            return CommandResult.failure("Nothing to repair: the sync directories are empty, please check SYNC_DIR.");
        }
        if (!DataConfig.updateFiles(version, pathData)) {
            return CommandResult.failure("Failed to write the rebuilt file list back to version " + version + ".");
        }
        int fileCount = pathData.stream().mapToInt(p -> p.files == null ? 0 : p.files.size()).sum();
        return CommandResult.success("SakuraUpdater repaired version " + version + ": rebuilt " + pathData.size()
                + " path(s) / " + fileCount + " file(s) from the current sync directories. "
                + "Description and time were left untouched.");
    }

    // ---- data list ----
    public static CommandResult buildDataListString() {
        StringBuilder dataList = new StringBuilder("SakuraUpdater server data:\n");
        DataConfig.getAllVersions().forEach(version -> {
            DataConfig.Data data = DataConfig.getDataByVersion(version);
            if (data == null) {
                dataList.append("Version: ").append(version).append(", <failed to read this record>\n");
                return;
            }
            boolean noFileList = data.paths == null || data.paths.isEmpty();
            dataList.append("Version: ").append(version)
                    .append(", Time: ").append(data.time)
                    .append(", Description: ").append(data.description)
                    .append(noFileList ? ", <empty file list, run 'data repair " + version + "'>" : "")
                    .append("\n");
        });
        return CommandResult.success(dataList.toString());
    }

    // ---- data show ----
    public static CommandResult showData(String version) {
        DataConfig.Data data = DataConfig.getDataByVersion(version);
        if (data == null) {
            return CommandResult.failure("Data with version " + version + " not found.");
        }
        if (data.paths == null || data.paths.isEmpty()) {
            return CommandResult.failure("Version " + version + " has an empty file list in the database "
                    + "(it was probably wiped by the old 'data edit' bug, or this version was never committed "
                    + "with a valid SYNC_DIR). Run 'data repair " + version + "' on the server to rebuild it.");
        }
        StringBuilder dataList = new StringBuilder("SakuraUpdater server data:\n");
        for (PathData path : data.paths) {
            dataList.append(path.targetPath)
                    .append(" - ")
                    .append(path.model)
                    .append(":[\n");
            for (FileData file : path.files) {
                dataList.append("    (")
                        .append(file.targetPath)
                        .append(", ")
                        .append(file.sourcePath)
                        .append(", ")
                        .append(file.md5)
                        .append(")\n");
            }
            dataList.append("]\n");
        }
        return CommandResult.success(dataList.toString());
    }

    // ---- data delete ----
    public static CommandResult deleteData(String version) {
        if (DataConfig.removeData(version)) {
            return CommandResult.success("SakuraUpdater server data deleted!");
        } else {
            return CommandResult.failure("Failed to delete data: Version not found.");
        }
    }

    // ---- data clear ----
    public static CommandResult clearData() {
        if (!DataConfig.clearData()) {
            return CommandResult.failure("Failed to clear data.");
        }
        return CommandResult.success("SakuraUpdater server data cleared!");
    }

    // ---- commit ----
    public static CommandResult commitData(String version, String description) {
        List<PathData> pathData;
        try {
            pathData = getPathDataList();
        } catch (Throwable t) {
            return CommandResult.failure("Failed to scan sync directories: " + t);
        }
        if (pathData.isEmpty()) {
            // 空清单的版本会让客户端"什么都没得下"就直接把版本号推进到最新
            return CommandResult.failure("Nothing to commit: the sync directories are empty. "
                    + "Please check SYNC_DIR before committing, otherwise clients would treat this version "
                    + "as 'nothing to sync' and skip the update.");
        }
        return commitData(version, description, pathData);
    }
    public static CommandResult commitData(String version, String description, List<PathData> pathData) {
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm:ss"));

        String fileContent = null;
        if (new File(description).exists()) {
            try {
                fileContent = Files.readString(Path.of(description));
            } catch (IOException e) {
                return CommandResult.failure(
                        "Description file is exist but failed to read description file: " + e.getMessage());
            }
        }

        if (!DataConfig.addData(version, timestamp,
                fileContent != null ? fileContent : description.replace("\\n", "\n"),
                pathData)) {
            return CommandResult.failure("Failed to add commit: Version already exists or invalid data.");
        }
        return CommandResult.success("SakuraUpdater server commit added!");
    }

    // ---- 扫描同步目录，生成 PathData 列表 ----
    public static List<PathData> getPathDataList() {
        List<PathData> pathDataList = new ArrayList<>();
        List<String[]> ignoreList = new ArrayList<>();
        for (String syncDir : IGetSyncDirs.getSyncDirs()) {
            List<String> parts = List.of(syncDir.split(":"));
            if (parts.size() < 2) {
                continue;
            }
            String targetPath = parts.get(0).trim();
            String model = parts.get(1).trim();
            if (model.equalsIgnoreCase("ignore")) {
                parts.stream().skip(2).forEach(ignorePath -> {// 将 ignorePath 添加到 ignoreList 中，格式为 [targetPath, ignorePath]
                    ignoreList.add(new String[]{targetPath, ignorePath});
                });
                continue; // 忽略该路径，跳过后续处理
            }
            if (pathDataList.stream().anyMatch(p -> p.targetPath.equals(targetPath))) {
                continue; // 已经存在相同 targetPath 的 PathData，跳过
            }
            List<String> sourcePath = parts.size() > 2 ? parts.subList(2, parts.size()) : List.of(targetPath);
            PathData data = new PathData();
            data.model = model;
            data.targetPath = targetPath;
            data.files = new ArrayList<>();
            for (String source : sourcePath) {
                // 递归获取 source 目录下的所有文件，并生成 FileData 列表
                FileUtils.getAllFiles(new File(source)).forEach(file -> {
                    FileData fileData = new FileData();
                    fileData.sourcePath = file.toString().replace(File.separator, "/");
                    fileData.targetPath = file.toString().replace(source, targetPath).replace(File.separator, "/");
                    fileData.md5 = MD5.calculateMD5(file);
                    data.files.add(fileData);
                });
            }
            pathDataList.add(data);
        }
        ignoreList.forEach(p -> {
            pathDataList.stream().filter(other-> other.targetPath.equals(p[0])).forEach(other -> { // 更具正则表达式 p[1] 从 other.files 中移除符合条件的 FileData
                other.files.removeIf(fileData -> fileData.targetPath.matches(p[1]));
            });
        });
        return pathDataList;
    }
}
