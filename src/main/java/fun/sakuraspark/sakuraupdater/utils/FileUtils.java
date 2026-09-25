package fun.sakuraspark.sakuraupdater.utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FileUtils {
    public static List<File> getAllFiles(File dir) {
            
        List<File> fileList = new ArrayList<>();
        if (dir == null || !dir.exists()) {
            //LOGGER.warn(null == dir ? "Directory is null" : "Directory does not exist: {}", dir);
            return fileList;
        }
        if (dir.isDirectory()) { // 如果传入目录就遍历，否则直接添加文件
            // 如果是目录，获取所有文件和子目录
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        fileList.addAll(getAllFiles(file));
                    } else {
                        fileList.add(file);
                    }
                }
            }
        } else if (dir.isFile()) {
            fileList.add(dir);
        }
        return fileList;
    }

    /**
     * 把字节数格式化成人类可读的体积文字（1024 进制，保留一位小数）。
     * 单位不翻译，中英文都用 B/KB/MB/GB。
     *
     * @param bytes 字节数，负数会被当成未知
     * @return 例如 "0 B"、"512 B"、"1.5 MB"、"2.3 GB"；未知返回 "?"
     */
    public static String formatSize(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = { "KB", "MB", "GB", "TB" };
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    /**
     * 把秒数格式化成 "M:SS" 或 "H:MM:SS"（语言无关，供"预计剩余 %s"这类文案使用）。
     */
    public static String formatDuration(long seconds) {
        long total = Math.max(0, seconds);
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long secs = total % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs);
        }
        return String.format(Locale.ROOT, "%d:%02d", minutes, secs);
    }
}
