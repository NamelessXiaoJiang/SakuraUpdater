package fun.sakuraspark.sakuraupdater.config;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

import javax.annotation.Nullable;

import fun.sakuraspark.sakuraupdater.PackageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

public class DataConfig {
    public static class Data {
        public String version;
        public String time;
        public String description;
        public List<PathData> paths; // 文件列表
    }
    public static class PathData {
        public String model;
        public String targetPath; // 目标路径
        public List<FileData> files; // 文件列表
    }
    public static class FileData {
        public String sourcePath; // 相对路径
        public String targetPath; // 目标路径
        public String md5; // 文件MD5
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(DataConfig.class);

    private static Connection connection = null;
    private static Driver sqliteDriver;

    /** 数据库层负责驱动实例化与连接创建，包管理器仅提供依赖的类加载器。 */
    private static synchronized Connection openSqliteConnection(String database) throws IOException, SQLException {
        if (sqliteDriver == null) {
            ClassLoader loader = PackageManager.loadDependency("org.xerial", "sqlite-jdbc");
            try {
                sqliteDriver = (Driver) Class.forName("org.sqlite.JDBC", true, loader).getConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError e) {
                throw new SQLException("Cannot load SQLite JDBC driver", e);
            }
        }
        // 直接调用驱动，避免 DriverManager 按调用方类加载器过滤动态加载的驱动。
        Connection result = sqliteDriver.connect("jdbc:sqlite:" + database, new Properties());
        if (result == null) throw new SQLException("SQLite driver rejected database " + database);
        return result;
    }

    /**
     * 连接到SQLite数据库
     */
    public static boolean connectToDatabase(String dburl){
        try {
            connection = openSqliteConnection(dburl);
            try (Statement stmt = connection.createStatement()) {
                // 当表不存在时创建表
                String sql = "CREATE TABLE IF NOT EXISTS updates (" +
                             "version TEXT PRIMARY KEY, " +
                             "time TEXT, " +
                             "description TEXT, " +
                             "data TEXT)"; // 使用 json 存储复杂对象
                stmt.execute(sql);
                stmt.close();
            }
        } catch (Exception | LinkageError e) {
            LOGGER.error("Failed to initialize SQLite database {}", dburl, e);
            closeDatabase();
            connection = null;
            return false;
        }
        return true;
    }

    /**
     * 关闭数据库连接
     */
    public static void closeDatabase() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (Exception e) {
        }
    }

    /**
     * 添加数据记录
     * @param version 版本号
     * @param time 时间
     * @param description 描述
     * @param files 文件列表
     * @return 是否添加成功
     */
    public static boolean addData(String version, String time, String description, List<PathData> files) {
        // 使用 PreparedStatement 防止注入和特殊字符错误，try-with-resources 会自动关闭它
        String sql = "INSERT INTO updates (version, time, description, data) VALUES (?, ?, ?, ?)";
        Gson gson = new Gson();
        String dataJson = gson.toJson(files);
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            
            pstmt.setString(1, version);
            pstmt.setString(2, time);
            pstmt.setString(3, description);
            pstmt.setString(4, dataJson);
            pstmt.executeUpdate();
        } catch (Exception e) {
            return false;
        }
        return true; // 如果转换成功，返回true/*  */
    }

    /**
     * 编辑版本描述。只改 description，不动 data（文件清单）也不动 time。
     * <p>
     * 历史实现会把调用方传进来的 files（实际调用时是 null）序列化成字面量字符串 "null" 写进 data 列，
     * 从而清空该版本的文件清单；同时它还会刷新 time，导致编辑一个旧版本的描述后，
     * 该旧版本在 {@code ORDER BY time DESC} 里变成"最新版本"。这里只允许改描述，避免这两类副作用。
     *
     * @param version     版本号
     * @param description 新描述
     * @return 是否编辑成功（版本不存在返回 false）
     */
    public static boolean editData(String version, String description) {
        String sql = "UPDATE updates SET description = ? WHERE version = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, description);
            pstmt.setString(2, version);
            int affectedRows = pstmt.executeUpdate();
            if (affectedRows == 0) {
                return false; // 如果没有找到对应的版本，返回false
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to edit the description of version {}: {}", version, e.toString());
            return false;
        }
        return true;
    }

    /**
     * 只更新某个版本的文件清单（供 data repair 使用），描述与 time 保持不变。
     *
     * @return 是否更新成功（版本不存在或 files 为 null 返回 false）
     */
    public static boolean updateFiles(String version, List<PathData> files) {
        if (files == null) {
            return false;
        }
        String dataJson = new Gson().toJson(files);
        String sql = "UPDATE updates SET data = ? WHERE version = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, dataJson);
            pstmt.setString(2, version);
            if (pstmt.executeUpdate() == 0) {
                return false;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to update the file list of version {}: {}", version, e.toString());
            return false;
        }
        return true;
    }

    /**
     * 解析 data 列里的文件清单。
     * <p>
     * 被旧版 data edit 破坏过的行，data 列是 4 个字符的字面量 "null"。这种行在这里打一条明确的 WARN，
     * 但仍然返回 null —— 保持"响亮失败"：客户端会报检查失败，而不是被当成"没有文件"而静默放行玩家。
     */
    @Nullable
    private static List<PathData> parsePaths(@Nullable String dataJson, String version) {
        if (dataJson == null || dataJson.isBlank() || "null".equals(dataJson.trim())) {
            LOGGER.warn("Version {} has no file list in the database (data column = {}). "
                            + "It was probably wiped by the old 'data edit' bug, or this version was never committed. "
                            + "Run 'data repair {}' on the server to rebuild it.",
                    version, dataJson == null ? "NULL" : "'" + dataJson.trim() + "'", version);
            return null;
        }
        try {
            return new Gson().fromJson(dataJson, new com.google.gson.reflect.TypeToken<List<PathData>>() {
            }.getType());
        } catch (Exception e) {
            LOGGER.warn("Failed to parse the file list of version {}: {}", version, e.toString());
            return null;
        }
    }

    /**
     * 清空所有数据记录
     * @return 是否清空成功
     */
    public static boolean clearData() {
        try (Statement stmt = connection.createStatement()) {
            String sql = "DELETE FROM updates";
            stmt.executeUpdate(sql);
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    /**
     * 删除指定版本的数据记录
     * @param version 版本号
     */
    public static boolean removeData(String version) {
        String sql = "DELETE FROM updates WHERE version = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, version);
            int affectedRows = pstmt.executeUpdate();
            if (affectedRows == 0) {
                return false;
            } else {
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 删除最新的一条数据记录
     */
    public static boolean removeLastData() {
        String sql = "DELETE FROM updates WHERE version = (SELECT version FROM updates ORDER BY time DESC LIMIT 1)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            int affectedRows = pstmt.executeUpdate();
            if (affectedRows == 0) {
                return false;
            } else {
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 根据版本号获取数据记录
     * @param version 版本号
     * @return 数据记录对象，找不到则返回null
     */
    @Nullable
    public static Data getDataByVersion(String version) {
        String sql = "SELECT version, time, description, data FROM updates WHERE version = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, version);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    Data data = new Data();
                    data.version = rs.getString("version");
                    data.time = rs.getString("time");
                    data.description = rs.getString("description");
                    data.paths = parsePaths(rs.getString("data"), data.version);
                    return data;
                } else {
                    return null;
                }
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取最新的一条数据记录
     * @return 最新的数据记录对象，找不到则返回null
     */
    @Nullable
    public static Data getLastData() {
        String sql = "SELECT version, time, description, data FROM updates ORDER BY time DESC LIMIT 1";
        try(PreparedStatement pstmt = connection.prepareStatement(sql);
            ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) {
                Data data = new Data();
                data.version = rs.getString("version");
                data.time = rs.getString("time");
                data.description = rs.getString("description");
                String dataJson = rs.getString("data");
                Gson gson = new Gson();
                data.paths = gson.fromJson(dataJson, new com.google.gson.reflect.TypeToken<List<PathData>>(){}.getType());
                return data;
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取晚于指定版本的所有数据记录（按时间升序），不含指定版本本身。
     * 如果 version 为 null 或空，则返回全部记录。
     * @param version 起始版本号（不含），可为 null 表示从最早开始
     * @return 数据记录列表，出错则返回 null
     */
    @Nullable
    public static List<Data> getDataAfter(String version) {
        List<Data> datas = new java.util.ArrayList<>();
        String sql;
        if (version == null || version.isEmpty()) {
            sql = "SELECT version, time, description, data FROM updates ORDER BY time ASC";
        } else {
            sql = "SELECT version, time, description, data FROM updates WHERE time > (SELECT time FROM updates WHERE version = ?) ORDER BY time ASC";
        }
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            if (version != null && !version.isEmpty()) {
                pstmt.setString(1, version);
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Data data = new Data();
                    data.version = rs.getString("version");
                    data.time = rs.getString("time");
                    data.description = rs.getString("description");
                    data.paths = parsePaths(rs.getString("data"), data.version);
                    datas.add(data);
                }
            }
        } catch (Exception e) {
            return null;
        }
        return datas;
    }

    /**
     * 获取最新版本号
     * @return 最新的版本号字符串，找不到则返回null
     */
    @Nullable
    public static String getLastVersion() {
        String sql = "SELECT version FROM updates ORDER BY time DESC LIMIT 1";
        try(PreparedStatement pstmt = connection.prepareStatement(sql);
            ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) {
                return rs.getString("version");
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取最新时间
     * @return 最新的时间字符串，找不到则返回null
     */
    @Nullable
    public static String getLastTime() {
        String sql = "SELECT time FROM updates ORDER BY time DESC LIMIT 1";
        try(PreparedStatement pstmt = connection.prepareStatement(sql);
            ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) {
                return rs.getString("time");
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取最新描述
     * @return 最新的描述字符串，找不到则返回null
     */
    @Nullable
    public static String getLastDescription() {
        String sql = "SELECT description FROM updates ORDER BY time DESC LIMIT 1";
        try(PreparedStatement pstmt = connection.prepareStatement(sql);
            ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) {
                return rs.getString("description");
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取所有版本号列表
     * @return 版本号列表
     */
    @Nullable
    public static List<String> getAllVersions() {
        String sql = "SELECT version FROM updates ORDER BY time DESC";
        List<String> versions = new java.util.ArrayList<>();
        try(PreparedStatement pstmt = connection.prepareStatement(sql);
            ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                versions.add(rs.getString("version"));
            }
        } catch (Exception e) {
            return null;
        }
        return versions;
    }
}
