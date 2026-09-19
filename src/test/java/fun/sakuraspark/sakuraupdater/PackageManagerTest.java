package fun.sakuraspark.sakuraupdater;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 通过 dependencyManagerTest 运行，仅使用 JDK 和本地 HTTP 测试服务。 */
public final class PackageManagerTest {
    private static final byte[] CONTENT = "dependency fixture".getBytes(StandardCharsets.UTF_8);
    private static final ClassLoader EMPTY = new ClassLoader(null) {};

    public static void main(String[] args) throws Exception {
        var dependency = new PackageManager.Dependency("test", "fixture", "1",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CONTENT)));
        if (args.length == 1) {
            new PackageManager(Path.of(args[0]), EMPTY).acquire(dependency);
            return;
        }
        require(PackageManager.resolveRepositories(null).equals(PackageManager.resolveRepositories("  ")),
            "blank environment uses defaults");
        require(PackageManager.resolveRepositories(null).size() == 2, "two default repositories");
        require(PackageManager.resolveRepositories(" https://example.com/maven ")
            .equals(List.of(URI.create("https://example.com/maven/"))), "custom source replaces defaults and preserves path");
        for (String invalid : List.of("relative/path", "file:///tmp/repo", "https://", "https://example.com/?q=x",
                "https://example.com/#fragment", "https://user:password@example.com/")) {
            try {
                PackageManager.resolveRepositories(invalid);
                throw new AssertionError("invalid repository accepted");
            } catch (IllegalArgumentException expected) {
                require(expected.getMessage().contains(PackageManager.REPOSITORY_ENV), "error names environment variable");
            }
        }
        Path root = Files.createTempDirectory("sakuraupdater-package-test-");
        AtomicInteger good = new AtomicInteger();
        AtomicInteger bad = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/good/", exchange -> {
            good.incrementAndGet();
            exchange.sendResponseHeaders(200, CONTENT.length);
            try (var output = exchange.getResponseBody()) { output.write(CONTENT); }
        });
        server.createContext("/bad/", exchange -> {
            bad.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.createContext("/truncated/", exchange -> {
            // 模拟响应正常结束，但文件内容仅传输了一部分。
            exchange.sendResponseHeaders(200, 3);
            try (var output = exchange.getResponseBody()) { output.write(CONTENT, 0, 3); }
        });
        server.createContext("/corrupt/", exchange -> {
            exchange.sendResponseHeaders(200, 3);
            try (var output = exchange.getResponseBody()) { output.write(new byte[3]); }
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        try {
            var manager = new PackageManager(root.resolve("cache"), List.of(base.resolve("bad/"), base.resolve("good/")), EMPTY);
            Path file = manager.acquire(dependency);
            require(good.get() == 1 && bad.get() == 1, "source fallback");
            require(java.util.Arrays.equals(Files.readAllBytes(file), CONTENT), "download contents");
            manager.acquire(dependency);
            require(good.get() == 1 && bad.get() == 1, "cache must not contact either source");
            Files.writeString(file, "broken");
            manager.acquire(dependency);
            require(good.get() == 2, "repair corrupt cache");

            ClassLoader embedded = new ClassLoader(null) {
                @Override public InputStream getResourceAsStream(String name) {
                    return name.equals("standaloneLibs/" + dependency.filename()) ? new ByteArrayInputStream(CONTENT) : null;
                }
            };
            new PackageManager(root.resolve("embedded"), List.of(base.resolve("bad/")), embedded).acquire(dependency);
            require(bad.get() == 2, "embedded resource takes priority over HTTP");
            ClassLoader damagedEmbedded = new ClassLoader(null) {
                @Override public InputStream getResourceAsStream(String name) { return new ByteArrayInputStream(new byte[3]); }
            };
            new PackageManager(root.resolve("embedded-broken"), List.of(base.resolve("good/")), damagedEmbedded).acquire(dependency);

            for (String failing : List.of("bad/", "corrupt/", "truncated/")) {
                Path failedDir = root.resolve(failing.substring(0, failing.length() - 1));
                try {
                    new PackageManager(failedDir, List.of(base.resolve(failing), base.resolve("bad/")), EMPTY).acquire(dependency);
                    throw new AssertionError("must reject " + failing);
                } catch (IOException expected) {
                    require(expected.getMessage().contains(dependency.filename()), "failure names dependency");
                    require(expected.getSuppressed().length == 2, "failure lists both sources");
                }
                require(!Files.exists(failedDir.resolve(dependency.filename())), "failure must not publish a jar");
                try (var paths = Files.list(failedDir)) {
                    require(paths.noneMatch(p -> p.toString().endsWith(".part")), "failure cleans temporary files");
                }
            }
            int before = good.get();
            try (var executor = Executors.newFixedThreadPool(4)) {
                var futures = new java.util.ArrayList<java.util.concurrent.Future<Path>>();
                for (int i = 0; i < 8; i++) futures.add(executor.submit(() ->
                    new PackageManager(root.resolve("threads"), List.of(base.resolve("good/")), EMPTY).acquire(dependency)));
                for (var future : futures) future.get(10, TimeUnit.SECONDS);
            }
            require(good.get() == before + 1, "same-process concurrent acquisition downloads once");

            before = good.get();
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            ProcessBuilder child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                PackageManagerTest.class.getName(), root.resolve("processes").toString());
            // 子进程通过真实环境变量选择源，同时验证无末尾斜杠的仓库路径。
            child.environment().put(PackageManager.REPOSITORY_ENV, base.resolve("good").toString());
            Process first = child.inheritIO().start();
            Process second = child.inheritIO().start();
            try {
                require(first.waitFor(20, TimeUnit.SECONDS) && second.waitFor(20, TimeUnit.SECONDS), "process lock timeout");
                require(first.exitValue() == 0 && second.exitValue() == 0, "process acquisition succeeds");
                require(good.get() == before + 1, "cross-process acquisition downloads once");
            } finally {
                first.destroyForcibly();
                second.destroyForcibly();
            }
            server.stop(0);
            manager.acquire(dependency);
            Process offline = child.inheritIO().start();
            try {
                require(offline.waitFor(10, TimeUnit.SECONDS) && offline.exitValue() == 0,
                    "custom repository unavailable but cache remains usable");
            } finally {
                offline.destroyForcibly();
            }
            System.out.println("PackageManager tests passed: environment override, fallback, offline cache, corruption, extraction, truncation, concurrency.");
        } finally {
            server.stop(0);
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
