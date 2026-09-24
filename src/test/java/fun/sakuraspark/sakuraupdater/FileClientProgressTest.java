package fun.sakuraspark.sakuraupdater;

import com.sun.net.httpserver.HttpServer;

import fun.sakuraspark.sakuraupdater.network.FileClient;
import fun.sakuraspark.sakuraupdater.utils.FileUtils;
import fun.sakuraspark.sakuraupdater.utils.MD5;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

/**
 * 通过 downloadProgressTest 运行，只用 JDK 和一个本地 HTTP 测试服务，无需外部测试框架。
 * <p>
 * 要守住的不变量：一次 downloadFile 向进度回调汇报的字节增量之和，恒等于真正落盘的字节数。
 * 分块重试、分块整体失败后回退单流、md5 校验失败，都不能让界面上的进度虚高或跳变。
 */
public final class FileClientProgressTest {

    private static final String NORMAL = "mods/normal.jar";
    private static final String EMPTY = "mods/empty.jar";
    /** 故意不是块大小的整数倍：分块边界算错时能暴露出来 */
    private static final int SIZE = 3 * 1024 * 1024 + 12345;

    public static void main(String[] args) throws Exception {
        checkFormatting();
        byte[] content = new byte[SIZE];
        new Random(20260921L).nextBytes(content);
        String md5 = MD5.calculateMD5(content);
        String emptyMd5 = MD5.calculateMD5(new byte[0]);

        Path root = Files.createTempDirectory("sakuraupdater-progress-test-");
        FakeServer server = new FakeServer();
        server.files.put(NORMAL, content);
        server.files.put(EMPTY, new byte[0]);
        server.start();
        FileClient client = new FileClient("127.0.0.1", server.port());
        try {
            require(client.heartbeat(), "heartbeat must succeed");
            require(client.serverSupportsRanges(), "test server advertises Range support");

            // 1. 体积探测：正常文件与空文件
            require(client.probeRemoteSize(NORMAL) == SIZE, "probe must report the file size");
            require(client.probeRemoteSize(EMPTY) == 0, "probe must report 0 for an empty file");

            // 2. 分块下载：进度要一路推进，而不是下完才跳一次
            Recorder chunked = new Recorder(SIZE);
            String chunkedPath = root.resolve("chunked.jar").toString();
            require(client.downloadFile(NORMAL, chunkedPath, md5, 4, chunked), "chunked download must succeed");
            require(chunked.total() == SIZE, "chunked download must report exactly the file size, got " + chunked.total());
            require(chunked.max() <= SIZE, "chunked download must never report more than the file size");
            require(chunked.intermediate() >= 10,
                    "progress must advance during the download, saw only " + chunked.intermediate() + " updates");
            require(Arrays.equals(Files.readAllBytes(Path.of(chunkedPath)), content), "chunked download contents");
            require(server.rangeRequests.get() > 0, "the chunked path must have used Range requests");

            // 3. 空文件：体积为 0，不能让记账变成负数或者卡住
            Recorder empty = new Recorder(0);
            String emptyPath = root.resolve("empty.jar").toString();
            require(client.downloadFile(EMPTY, emptyPath, emptyMd5, 4, empty), "empty file download must succeed");
            require(empty.total() == 0, "empty file must report 0 bytes, got " + empty.total());
            require(Files.size(Path.of(emptyPath)) == 0, "empty file contents");

            // 4. 某个分块先失败一次再重试：重试的那段不能把同一批字节算两遍
            long retryChunkStart = chunkStart(SIZE, 4, 1);
            AtomicBoolean failedOnce = new AtomicBoolean();
            server.failRange = (start, end) -> start == retryChunkStart && !failedOnce.getAndSet(true);
            int rangeBefore = server.rangeRequests.get();
            Recorder retried = new Recorder(SIZE);
            String retriedPath = root.resolve("retried.jar").toString();
            require(client.downloadFile(NORMAL, retriedPath, md5, 4, retried), "download with one retried chunk must succeed");
            require(failedOnce.get(), "the test must actually have failed one chunk once");
            // 探测 1 次 + 4 个分块各 1 次 + 被重试那块的 1 次
            require(server.rangeRequests.get() >= rangeBefore + 6,
                    "the chunked path must really have been used and retried, saw "
                            + (server.rangeRequests.get() - rangeBefore) + " Range requests");
            require(retried.total() == SIZE, "a retried chunk must not inflate the total, got " + retried.total());
            require(retried.max() <= SIZE, "a retried chunk must never push the total above the file size");
            require(Arrays.equals(Files.readAllBytes(Path.of(retriedPath)), content), "retried download contents");
            server.failRange = (start, end) -> false;

            // 5. 部分分块成功、整体仍失败 → 回退单流：已计入的字节必须先全部退回再重新计
            server.failRange = (start, end) -> !(start == 0 && end == 0) && start != 0;
            rangeBefore = server.rangeRequests.get();
            Recorder fallback = new Recorder(SIZE);
            String fallbackPath = root.resolve("fallback.jar").toString();
            require(client.downloadFile(NORMAL, fallbackPath, md5, 4, fallback), "fallback to a single stream must succeed");
            // 探测 1 次 + 至少 1 个分块请求（成功的那块已计入、失败的那几块重试过）
            require(server.rangeRequests.get() >= rangeBefore + 5,
                    "the chunked path must really have run (and failed) before the fallback, saw "
                            + (server.rangeRequests.get() - rangeBefore) + " Range requests");
            require(fallback.total() == SIZE,
                    "the single-stream fallback must not double count the chunked attempt, got " + fallback.total());
            require(fallback.max() <= SIZE, "the total must never exceed the file size during the fallback");
            require(Arrays.equals(Files.readAllBytes(Path.of(fallbackPath)), content), "fallback download contents");
            require(server.fullRequests.get() > 0, "the fallback must have used a full (non-Range) request");
            server.failRange = (start, end) -> false;

            // 6. md5 不匹配：文件没有落盘，这部分字节就不该留在进度里
            Recorder mismatch = new Recorder(SIZE);
            String mismatchPath = root.resolve("mismatch.jar").toString();
            require(!client.downloadFile(NORMAL, mismatchPath, "00000000000000000000000000000000", 4, mismatch),
                    "a wrong md5 must fail the download");
            require(mismatch.total() == 0, "a discarded download must roll all its bytes back, got " + mismatch.total());
            require(!Files.exists(Path.of(mismatchPath)), "a failed download must not publish the target file");
            require(!Files.exists(Path.of(mismatchPath + ".part")), "a failed download must clean the .part file");

            // 7. 完全下载失败（文件不在服务端）：也不能留下字节
            Recorder missing = new Recorder(SIZE);
            require(!client.downloadFile("mods/not-on-server.jar", root.resolve("missing.jar").toString(), md5, 4, missing),
                    "a missing file must fail the download");
            require(missing.total() == 0, "a missing file must report 0 bytes, got " + missing.total());
            require(client.serverSupportsRanges(),
                    "a single file returning 404 must not disable chunked downloads for everything else");

            System.out.println("FileClient progress tests passed: size probe, chunked live progress, empty file, "
                    + "chunk retry, chunked-to-single fallback, md5 mismatch, missing file.");
        } finally {
            server.stop();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    /** 第 index 个分块的起始偏移，与 FileClient 的分块算法保持一致 */
    private static long chunkStart(long total, int connections, int index) {
        int chunkCount = (int) Math.min(connections, (total + 1024 * 1024 - 1) / (1024 * 1024));
        long chunkSize = (total + chunkCount - 1) / chunkCount;
        return index * chunkSize;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** 记下回调汇报的每一个增量，用来验证"总量不会虚高"和"进度是连续推进的" */
    private static final class Recorder implements FileClient.ByteProgressListener {
        private final long expectedSize;
        private long current;
        private long max;
        private int intermediate;

        Recorder(long expectedSize) {
            this.expectedSize = expectedSize;
        }

        @Override
        public synchronized void onBytes(long delta) {
            current += delta;
            max = Math.max(max, current);
            if (current > 0 && current < expectedSize) {
                intermediate++;
            }
        }

        synchronized long total() {
            return current;
        }

        synchronized long max() {
            return max;
        }

        synchronized int intermediate() {
            return intermediate;
        }
    }

    /** 本地假服务端：/heartbeat 报告支持 Range，/file 按 Range 返回 206，可让指定区间失败 */
    private static final class FakeServer {
        final Map<String, byte[]> files = new HashMap<>();
        /** 返回 true 表示这次 Range 请求按 500 失败，参数是 (start, end) */
        volatile BiPredicate<Long, Long> failRange = (start, end) -> false;
        final AtomicInteger rangeRequests = new AtomicInteger();
        final AtomicInteger fullRequests = new AtomicInteger();

        private HttpServer server;
        private ExecutorService executor;

        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/heartbeat", exchange -> {
                byte[] body = "{\"status\":\"ok\",\"acceptRanges\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.createContext("/file", this::handleFile);
            executor = Executors.newFixedThreadPool(8);
            server.setExecutor(executor);
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
            if (executor != null) {
                executor.shutdownNow();
            }
        }

        private void handleFile(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            byte[] request = exchange.getRequestBody().readAllBytes();
            String name = new String(request, StandardCharsets.UTF_8)
                    .replaceAll("(?s).*\"file\"\\s*:\\s*\"([^\"]*)\".*", "$1");
            byte[] content = files.get(name);
            if (content == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            String range = exchange.getRequestHeaders().getFirst("Range");
            long[] bounds = parseRange(range, content.length);
            if (bounds == null) {
                fullRequests.incrementAndGet();
                exchange.sendResponseHeaders(200, content.length == 0 ? -1 : content.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(content);
                }
                return;
            }
            long start = bounds[0];
            long end = bounds[1];
            if (content.length == 0) {
                // 空文件无法满足 bytes=0-0
                exchange.getResponseHeaders().set("Content-Range", "bytes */0");
                exchange.sendResponseHeaders(416, -1);
                exchange.close();
                return;
            }
            rangeRequests.incrementAndGet();
            if (failRange.test(start, end)) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + content.length);
            exchange.sendResponseHeaders(206, end - start + 1);
            try (var output = exchange.getResponseBody()) {
                output.write(content, (int) start, (int) (end - start + 1));
            }
        }

        /** 解析 "bytes=start-end"；返回 null 表示请求里没有 Range */
        private static long[] parseRange(String header, int length) {
            if (header == null || !header.startsWith("bytes=")) {
                return null;
            }
            String[] parts = header.substring("bytes=".length()).split("-", 2);
            long start = Long.parseLong(parts[0].trim());
            long end = parts.length > 1 && !parts[1].isBlank() ? Long.parseLong(parts[1].trim()) : length - 1L;
            return new long[] { start, end };
        }
    }

    /** 体积格式化本身也是界面要用的，顺带在这里定住格式 */
    private static void checkFormatting() {
        require("0 B".equals(FileUtils.formatSize(0)), "0 bytes");
        require("512 B".equals(FileUtils.formatSize(512)), "bytes stay raw");
        require("1.0 KB".equals(FileUtils.formatSize(1024)), "1024 becomes 1.0 KB");
        require("1.5 MB".equals(FileUtils.formatSize(1024 * 1024 * 3 / 2)), "one decimal place");
        require("1:02".equals(FileUtils.formatDuration(62)), "duration under an hour");
        require("1:00:00".equals(FileUtils.formatDuration(3600)), "duration with hours");
    }
}
