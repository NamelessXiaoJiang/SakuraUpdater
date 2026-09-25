package fun.sakuraspark.sakuraupdater.network;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import fun.sakuraspark.sakuraupdater.config.DataConfig.Data;
import fun.sakuraspark.sakuraupdater.utils.MD5;

public class FileClient {
    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(FileClient.class);

    /** 小于这个大小就不值得分块了 */
    private static final long CHUNK_MIN_SIZE = 1024 * 1024;
    private static final int BUFFER_SIZE = 8192;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;
    /** 探测文件大小时用更短的读超时，免得准备阶段被卡住 */
    private static final int PROBE_READ_TIMEOUT_MS = 5000;
    /** 每一块的额外重试次数 */
    private static final int CHUNK_RETRIES = 2;
    /** 下载中的临时文件后缀，校验通过后才会改名为目标文件 */
    private static final String PART_SUFFIX = ".part";

    /** 下载过程中的字节回调。delta 可能为负：重试或整个文件作废时，会把之前汇报过的字节退回来。 */
    public interface ByteProgressListener {
        void onBytes(long delta);
    }

    /**
     * 一个文件的字节记账。
     * <p>
     * 不变量：一次 {@link #downloadFile} 向 listener 汇报的增量之和，恒等于最终落盘文件的字节数。
     * 所以每次尝试（分块的一次请求、或一次单流）拿到的 {@link ByteCounter} 在失败时必须只退自己那一份，
     * 整个文件作废时则用 {@link #rollbackAll()} 把本文件已计入的字节全部退回。
     */
    private static final class FileByteAccount {
        @Nullable
        private final ByteProgressListener listener;
        /** 本文件当前已经向上游计入的字节 */
        private long fileTotal;

        FileByteAccount(@Nullable ByteProgressListener listener) {
            this.listener = listener;
        }

        synchronized ByteCounter attempt() {
            return new ByteCounter();
        }

        private synchronized void add(long delta) {
            if (delta == 0) {
                return;
            }
            fileTotal += delta;
            if (listener != null) {
                listener.onBytes(delta);
            }
        }

        /** 整个文件作废（分块整体失败后回退单流、md5 不匹配、覆盖旧文件失败）：把已计入的字节全部退回 */
        synchronized void rollbackAll() {
            long back = -fileTotal;
            fileTotal = 0;
            if (back != 0 && listener != null) {
                listener.onBytes(back);
            }
        }

        /** 一次尝试的计数。只被该尝试所在的那一个线程访问，失败时退回自己汇报过的那部分。 */
        final class ByteCounter {
            private long reported;

            void read(long bytes) {
                reported += bytes;
                add(bytes);
            }

            void rollback() {
                long back = -reported;
                reported = 0;
                add(back);
            }
        }
    }

    private final String host;
    private final int port;
    private String baseUrl;
    /** 服务端是否支持 Range 请求（由心跳响应告知，旧版服务端为 false） */
    private boolean serverSupportsRanges = false;

    public FileClient(String host, int port) {
        this.host = host;
        this.port = port;
        this.baseUrl = "http://" + host + ":" + port;
    }

    /**
     * 心跳检测（同时读取服务端能力）
     */
    public boolean heartbeat() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/heartbeat");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    updateServerCapabilities(readInputStream(is));
                } catch (Exception e) {
                    LOGGER.debug("Cannot read heartbeat body: {}", e.toString());
                }
                return true;
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 从心跳响应里读取服务端能力。旧版服务端返回纯文本 "OK"，此时保持"不支持 Range"。
     */
    private void updateServerCapabilities(String heartbeatBody) {
        try {
            JsonObject json = JsonParser.parseString(heartbeatBody).getAsJsonObject();
            if (json.has("acceptRanges")) {
                serverSupportsRanges = json.get("acceptRanges").getAsBoolean();
                LOGGER.info("Server supports Range requests: {}", serverSupportsRanges);
            }
        } catch (Exception e) {
            serverSupportsRanges = false;
        }
    }

    /** 服务端是否支持 Range 请求 */
    public boolean serverSupportsRanges() {
        return serverSupportsRanges;
    }

    
    /**
     * 获取可用文件列表
     */
    public Data getUpdateList() {
        return getUpdateList(null);
    }
    
    /**
     * 获取指定版本的更新列表
     */
    @Nullable
    public Data getUpdateList(String version) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/updateList");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json;charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            
            // 构建请求JSON
            JsonObject requestJson = new JsonObject();
            if (version != null && !version.isEmpty()) {
                requestJson.addProperty("version", version);
            }
            
            String requestBody = new Gson().toJson(requestJson);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }
            
            if (conn.getResponseCode() == 200) {
                String response = readInputStream(conn.getInputStream());
                Gson gson = new Gson();
                try {
                    Data data = gson.fromJson(response, Data.class);
                    LOGGER.debug("get update list for version: {}", version);
                    return data;
                } catch (JsonSyntaxException e) {
                    LOGGER.error("Cannot parse update list JSON", e);
                    return null;
                }
            } else {
                LOGGER.error("Failed to get update list: HTTP {}", conn.getResponseCode());
                return null;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get update list", e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
    
    /**
     * 获取更新日志：晚于指定版本的所有版本记录（按时间升序）。
     * version 为 null 或空时返回全部记录。
     */
    @Nullable
    public List<Data> getChangeLog(String version) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/changelog");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json;charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);

            // 构建请求JSON
            JsonObject requestJson = new JsonObject();
            if (version != null && !version.isEmpty()) {
                requestJson.addProperty("version", version);
            }

            String requestBody = new Gson().toJson(requestJson);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() == 200) {
                String response = readInputStream(conn.getInputStream());
                Gson gson = new Gson();
                try {
                    List<Data> dataList = gson.fromJson(response, new TypeToken<List<Data>>(){}.getType());
                    LOGGER.debug("Got changelog for version: {}", version);
                    return dataList;
                } catch (JsonSyntaxException e) {
                    LOGGER.error("Cannot parse changelog JSON", e);
                    return null;
                }
            } else {
                LOGGER.error("Failed to get changelog: HTTP {}", conn.getResponseCode());
                return null;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get changelog", e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 下载文件（单连接，不校验 md5）
     */
    public boolean downloadFile(String fileName, String saveDirectory) {
        return downloadFile(fileName, saveDirectory, null, 1);
    }

    public boolean downloadFile(String fileName, String savePath, @Nullable String expectedMd5, int connections) {
        return downloadFile(fileName, savePath, expectedMd5, connections, null);
    }

    /**
     * 下载文件：支持多连接分块，全程写入 .part 临时文件，md5 校验通过后才替换目标文件，
     * 因此下载中断（断网、退出游戏）只会留下 .part，不会破坏已有文件。
     *
     * @param fileName     服务端上的文件路径（同时作为在服务端白名单里的 key）
     * @param savePath     本地目标路径
     * @param expectedMd5  期望的 md5，为 null/空时跳过校验
     * @param connections  分块并发连接数，1 表示单连接
     * @param listener     下载进度回调（按字节，可能为负），为 null 时不汇报。
     *                     只有真正落盘的文件才会留下已计入的字节：中途失败、md5 不匹配都会全部退回。
     */
    public boolean downloadFile(String fileName, String savePath, @Nullable String expectedMd5, int connections,
            @Nullable ByteProgressListener listener) {
        File target = new File(savePath);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            LOGGER.error("Failed to create directory: {}", parent);
            return false;
        }
        File partFile = new File(savePath + PART_SUFFIX);
        FileByteAccount account = new FileByteAccount(listener);

        int maxConnections = Math.max(1, connections);
        boolean ok;
        if (maxConnections > 1 && serverSupportsRanges) {
            long total = probeContentLength(fileName);
            if (total > CHUNK_MIN_SIZE) {
                // 分块失败时前面成功的块也已经计过数，这里整体退回后再交给单流重新计一遍
                ok = downloadChunked(fileName, partFile, total, maxConnections, account);
                if (!ok) {
                    LOGGER.warn("Chunked download failed for {}, falling back to a single connection", fileName);
                    account.rollbackAll();
                    ok = downloadSingleStream(fileName, partFile, account);
                }
            } else {
                ok = downloadSingleStream(fileName, partFile, account);
            }
        } else {
            ok = downloadSingleStream(fileName, partFile, account);
        }

        if (!ok) {
            account.rollbackAll();
            deleteQuietly(partFile);
            return false;
        }

        String actualMd5 = MD5.calculateMD5(partFile);
        if (expectedMd5 != null && !expectedMd5.isEmpty() && !expectedMd5.equalsIgnoreCase(actualMd5)) {
            LOGGER.error("MD5 mismatch for {}: expected {}, got {}", fileName, expectedMd5, actualMd5);
            account.rollbackAll();
            deleteQuietly(partFile);
            return false;
        }

        // 校验通过后才动目标文件
        if (target.exists() && !target.delete()) {
            LOGGER.error("Failed to delete old file: {}", target);
            account.rollbackAll();
            deleteQuietly(partFile);
            return false;
        }
        try {
            Files.move(partFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.error("Failed to move {} to {}", partFile, target, e);
            account.rollbackAll();
            deleteQuietly(partFile);
            return false;
        }

        LOGGER.debug("Download success: {} ({} bytes, md5={})", fileName, target.length(), actualMd5);
        return true;
    }

    /**
     * 探测服务端上某个文件的字节数，供界面在下载前算出总体积。
     *
     * @return 文件字节数；-1 表示无法得知（服务端不支持 Range、文件不存在或探测失败）
     */
    public long probeRemoteSize(String fileName) {
        return probeContentLength(fileName);
    }

    /**
     * 用 Range: bytes=0-0 探测服务端上的文件大小。
     *
     * @return 文件总字节数；-1 表示服务端不支持 Range 或探测失败（调用方应退回单连接）
     */
    private long probeContentLength(String fileName) {
        HttpURLConnection conn = null;
        try {
            conn = openFileRequest(fileName, rangeHeader(0, 0), PROBE_READ_TIMEOUT_MS);
            int code = conn.getResponseCode();
            if (code == 206) {
                long total = parseContentRangeTotal(conn.getHeaderField("Content-Range"));
                try (InputStream is = conn.getInputStream()) {
                    is.read(); // 把这一个字节读掉，避免服务端写失败
                } catch (Exception ignored) {
                }
                if (total < 0) {
                    LOGGER.warn("Cannot parse Content-Range for {}, falling back to a single connection", fileName);
                }
                return total;
            }
            if (code == 416) {
                return 0; // 空文件
            }
            if (code == 200) {
                // 200 说明服务端忽略了 Range（旧版本服务端），此时才谈得上"服务端不支持分块"。
                // 其它状态码（403/404/5xx）只是这个文件有问题，不能因此把分块整个关掉。
                serverSupportsRanges = false;
                LOGGER.info("Server ignored the Range request, falling back to a single connection");
            } else {
                LOGGER.warn("Cannot probe the size of {}: HTTP {}", fileName, code);
            }
            return -1;
        } catch (Exception e) {
            LOGGER.warn("Failed to probe the size of {}: {}", fileName, e.toString());
            return -1;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 解析 Content-Range: bytes 0-0/1234 里的总大小 */
    private static long parseContentRangeTotal(@Nullable String contentRange) {
        if (contentRange == null) {
            return -1;
        }
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0) {
            return -1;
        }
        try {
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 多连接分块下载：每个线程负责一个字节区间，定位写入同一个 .part 文件
     */
    private boolean downloadChunked(String fileName, File partFile, long total, int maxConnections,
            FileByteAccount account) {
        int chunkCount = (int) Math.min(maxConnections, (total + CHUNK_MIN_SIZE - 1) / CHUNK_MIN_SIZE);
        if (chunkCount < 2) {
            return downloadSingleStream(fileName, partFile, account);
        }
        long chunkSize = (total + chunkCount - 1) / chunkCount;
        LOGGER.info("Chunked download started: {} ({} bytes, {} connections)", fileName, total, chunkCount);

        ExecutorService pool = Executors.newFixedThreadPool(chunkCount, r -> {
            Thread thread = new Thread(r, "sakuraupdater-chunk");
            thread.setDaemon(true);
            return thread;
        });
        try {
            // 先按总长度预分配，保证各线程定位写不会互相干扰
            try (RandomAccessFile raf = new RandomAccessFile(partFile, "rw")) {
                raf.setLength(total);
            } catch (IOException e) {
                LOGGER.error("Failed to allocate {}: {}", partFile, e.toString());
                return false;
            }

            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < chunkCount; i++) {
                long start = i * chunkSize;
                long end = Math.min(total, start + chunkSize) - 1;
                if (start > end) {
                    break;
                }
                int index = i + 1;
                futures.add(pool.submit(() -> fetchChunk(fileName, partFile, start, end, index, chunkCount, account)));
            }

            boolean allOk = true;
            for (Future<Boolean> future : futures) {
                try {
                    if (!future.get()) {
                        allOk = false;
                    }
                } catch (Exception e) {
                    LOGGER.error("Chunk task failed for {}", fileName, e);
                    allOk = false;
                }
            }
            return allOk;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 下载单个区间，失败按 CHUNK_RETRIES 重试 */
    private boolean fetchChunk(String fileName, File partFile, long start, long end, int index, int total,
            FileByteAccount account) {
        for (int attempt = 0; attempt <= CHUNK_RETRIES; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(1000L << (attempt - 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                LOGGER.warn("Retrying chunk {}/{} ({}-{}) of {} (attempt {} of {})",
                        index, total, start, end, fileName, attempt, CHUNK_RETRIES);
            }
            try {
                // 每次尝试用独立的计数：上一次尝试已经汇报过的字节在失败时就被退回了，
                // 否则重试会把同一段区间算两遍，界面上的进度就会虚高。
                if (fetchChunkOnce(fileName, partFile, start, end, account.attempt())) {
                    return true;
                }
            } catch (Exception e) {
                LOGGER.warn("Chunk {}/{} ({}-{}) of {} failed: {}", index, total, start, end, fileName, e.toString());
            }
        }
        return false;
    }

    private boolean fetchChunkOnce(String fileName, File partFile, long start, long end,
            FileByteAccount.ByteCounter counter) throws IOException {
        HttpURLConnection conn = openFileRequest(fileName, rangeHeader(start, end));
        try {
            int code = conn.getResponseCode();
            if (code != 206) {
                LOGGER.warn("Range request for {} ({}-{}) returned HTTP {}, expected 206", fileName, start, end, code);
                counter.rollback();
                return false;
            }
            long remaining = end - start + 1;
            try (InputStream is = conn.getInputStream();
                 RandomAccessFile raf = new RandomAccessFile(partFile, "rw")) {
                raf.seek(start);
                byte[] buffer = new byte[BUFFER_SIZE];
                while (remaining > 0) {
                    int bytesRead = is.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (bytesRead == -1) {
                        break;
                    }
                    raf.write(buffer, 0, bytesRead);
                    remaining -= bytesRead;
                    counter.read(bytesRead);
                }
            }
            if (remaining != 0) {
                LOGGER.warn("Chunk ({}-{}) of {} is incomplete, {} bytes missing", start, end, fileName, remaining);
                counter.rollback();
                return false;
            }
            return true;
        } catch (IOException e) {
            counter.rollback();
            throw e;
        } finally {
            conn.disconnect();
        }
    }

    /** 单连接下载整个文件到 .part */
    private boolean downloadSingleStream(String fileName, File partFile, FileByteAccount account) {
        FileByteAccount.ByteCounter counter = account.attempt();
        HttpURLConnection conn = null;
        try {
            conn = openFileRequest(fileName, null);
            int code = conn.getResponseCode();
            if (code != 200) {
                LOGGER.error("Download failed: HTTP {} {}", code, conn.getResponseMessage());
                counter.rollback();
                return false;
            }
            try (InputStream is = conn.getInputStream();
                 OutputStream os = new BufferedOutputStream(new FileOutputStream(partFile), 64 * 1024)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                    counter.read(bytesRead);
                }
            }
            return true;
        } catch (Exception e) {
            LOGGER.error("Download {} failed: {}", fileName, e);
            counter.rollback();
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 构造 POST /file 请求（body 里带文件名）；Range 与超时都必须在连接建立前设置好 */
    private HttpURLConnection openFileRequest(String fileName, @Nullable String rangeHeader) throws IOException {
        return openFileRequest(fileName, rangeHeader, READ_TIMEOUT_MS);
    }

    private HttpURLConnection openFileRequest(String fileName, @Nullable String rangeHeader, int readTimeoutMs)
            throws IOException {
        URL url = new URL(baseUrl + "/file");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json;charset=utf-8");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(readTimeoutMs);
        if (rangeHeader != null) {
            conn.setRequestProperty("Range", rangeHeader);
        }
        conn.setDoOutput(true);

        // 注意：写出请求体时就会真正建立连接，之后再 setRequestProperty 会抛 Already connected
        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("file", fileName);
        String requestBody = new Gson().toJson(requestJson);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.getBytes(StandardCharsets.UTF_8));
        }
        return conn;
    }

    private static String rangeHeader(long start, long end) {
        return "bytes=" + start + "-" + end;
    }

    private static void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) {
            LOGGER.warn("Failed to delete {}", file);
        }
    }
    
    /**
     * 上传文件
     */
    public boolean uploadFile(String fileName, String fileSourcePath) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/upload");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json;charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(120000);
            
            // 读取文件内容并进行Base64编码
            File sourceFile = new File(fileSourcePath);
            byte[] fileContent = new byte[(int) sourceFile.length()];
            try (FileInputStream fis = new FileInputStream(sourceFile)) {
                fis.read(fileContent);
            }
            String encodedContent = java.util.Base64.getEncoder().encodeToString(fileContent);
            
            // 构建请求JSON
            JsonObject requestJson = new JsonObject();
            requestJson.addProperty("file", fileName);
            requestJson.addProperty("content", encodedContent);
            
            String requestBody = new Gson().toJson(requestJson);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }
            
            if (conn.getResponseCode() == 200) {
                String response = readInputStream(conn.getInputStream());
                LOGGER.debug("File upload success: {}", fileName);
                return true;
            } else {
                LOGGER.error("File upload failed: HTTP {}", conn.getResponseCode());
                return false;
            }
        } catch (Exception e) {
            LOGGER.error("File upload failed: {}", fileName, e);
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
    
    /**
     * 读取输入流
     */
    private String readInputStream(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
            bos.write(buffer, 0, bytesRead);
        }
        return bos.toString(StandardCharsets.UTF_8.name());
    }
}
