package fun.sakuraspark.sakuraupdater;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/** 仅依赖 JDK 的运行时依赖管理器，可在日志库加载前获取并加载依赖。 */
public final class PackageManager {
    record Dependency(String group, String artifact, String version, String sha256) {
        String filename() { return artifact + "-" + version + ".jar"; }
        String mavenPath() { return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + filename(); }
    }

    // 固定校验值取自已有发布依赖，不使用镜像临时提供的校验值。
    static final List<Dependency> DEPENDENCIES = List.of(
        new Dependency("org.xerial", "sqlite-jdbc", "3.46.0.0", "e697df15be3f95219d80773c5f1002030e33e932adda186c1c86fd51df6691a9"),
        new Dependency("com.google.code.gson", "gson", "2.10", "0cdd163ce3598a20fc04eee71b140b24f6f2a3b35f0a499dbbdd9852e83fbfaf"),
        new Dependency("com.electronwill.night-config", "toml", "3.8.1", "3c782b62bc6a1cc49967696a475f10f709ad385784cd9bb4ec882350cc4efbeb"),
        new Dependency("com.electronwill.night-config", "core", "3.8.1", "831144d6c417671360ed240c4ebf51147b8b54f2df126f08f71835bdcda3a1f3"),
        new Dependency("org.slf4j", "slf4j-api", "2.0.9", "0818930dc8d7debb403204611691da58e49d42c50b6ffcfdce02dadb7c3c2b6c"),
        new Dependency("org.slf4j", "slf4j-simple", "2.0.9", "71f9c6de6dbaec2d10caa303faf08c5e749be53b242896c64c96b7c6bb6d62dc")
    );

    static final String REPOSITORY_ENV = "SAKURAUPDATER_MAVEN_REPO";
    private static final PackageManager INSTANCE = new PackageManager(Path.of("lib"), PackageManager.class.getClassLoader());

    private final Path directory;
    private final List<URI> repositories;
    private final ClassLoader resources;
    private static URLClassLoader standaloneLoader;
    private static final Map<Dependency, URLClassLoader> dependencyLoaders = new HashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (PackageManager.class) {
                dependencyLoaders.values().forEach(PackageManager::closeLoader);
                closeLoader(standaloneLoader);
            }
        }, "sakuraupdater-dependency-cleanup"));
    }

    // 从进程环境读取仓库配置；同样适用于独立模式与模组模式。
    PackageManager(Path directory, ClassLoader resources) {
        this(directory, resolveRepositories(System.getenv(REPOSITORY_ENV)), resources);
    }

    /** 非空环境变量完全替换默认源；补齐末尾斜杠以保留 Maven 仓库路径。 */
    static List<URI> resolveRepositories(String override) {
        if (override == null || override.isBlank()) {
            return List.of(URI.create("https://maven.aliyun.com/repository/central/"),
                URI.create("https://repo.maven.apache.org/maven2/"));
        }
        String value = override.trim();
        try {
            URI repository = URI.create(value.endsWith("/") ? value : value + "/");
            if (!("https".equalsIgnoreCase(repository.getScheme()) || "http".equalsIgnoreCase(repository.getScheme()))
                    || repository.getHost() == null || repository.getRawQuery() != null
                    || repository.getRawFragment() != null || repository.getRawUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            return List.of(repository);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(REPOSITORY_ENV
                + " 必须为完整的 HTTP(S) Maven 仓库根地址，不能包含用户名、密码、查询参数或片段");
        }
    }

    // 包内可见的注入入口，让测试无需访问公共仓库。
    PackageManager(Path directory, List<URI> repositories, ClassLoader resources) {
        this.directory = directory.toAbsolutePath().normalize();
        this.repositories = List.copyOf(repositories);
        this.resources = resources;
    }

    /** 使用进程内同步锁和跨进程文件锁，保护校验与替换操作。 */
    Path acquire(Dependency dependency) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve(dependency.filename());
        // 字符串驻留还可协调同一 JVM 中由不同类加载器加载的管理器实例。
        synchronized (target.toString().intern()) {
            try (FileChannel channel = FileChannel.open(directory.resolve(dependency.filename() + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                if (valid(target, dependency)) return target;
                IOException failure = new IOException("Cannot obtain dependency " + dependency.filename());
                String resource = "standaloneLibs/" + dependency.filename();
                try (InputStream input = resources.getResourceAsStream(resource)) {
                    if (input != null) return install(input, target, dependency);
                } catch (IOException e) {
                    recordFailure(failure, "embedded /" + resource, e);
                }
                for (URI repository : repositories) {
                    URI source = repository.resolve(dependency.mavenPath());
                    System.err.println("[SakuraUpdater] Downloading " + dependency.filename() + " from " + source);
                    HttpURLConnection connection = null;
                    try {
                        connection = (HttpURLConnection) source.toURL().openConnection();
                        connection.setConnectTimeout(10_000);
                        connection.setReadTimeout(60_000);
                        connection.setRequestProperty("User-Agent", "SakuraUpdater-PackageManager");
                        int status = connection.getResponseCode();
                        if (status != 200) throw new IOException("HTTP " + status);
                        try (InputStream input = connection.getInputStream()) {
                            return install(input, target, dependency);
                        }
                    } catch (IOException e) {
                        recordFailure(failure, source.toString(), e);
                    } finally {
                        if (connection != null) connection.disconnect();
                    }
                }
                throw failure;
            }
        }
    }

    private static void recordFailure(IOException failure, String source, IOException cause) {
        IOException detail = new IOException(source + ": " + cause.getMessage(), cause);
        failure.addSuppressed(detail);
        System.err.println("[SakuraUpdater] " + detail.getMessage());
    }

    private Path install(InputStream input, Path target, Dependency dependency) throws IOException {
        Path temporary = Files.createTempFile(directory, dependency.filename() + ".", ".part");
        try {
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            if (!valid(temporary, dependency)) throw new IOException("SHA-256 mismatch for " + dependency.filename());
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean valid(Path file, Dependency dependency) throws IOException {
        if (!Files.isRegularFile(file)) return false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest()).equals(dependency.sha256());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("JDK must support SHA-256", e);
        }
    }

    /** 在独立模式业务类及其日志组件初始化前准备全部依赖。 */
    public static int runStandalone(String[] args) throws Exception {
        synchronized (PackageManager.class) {
            if (standaloneLoader == null) {
                List<URL> urls = new ArrayList<>();
                urls.add(PackageManager.class.getProtectionDomain().getCodeSource().getLocation());
                for (Dependency dependency : DEPENDENCIES) urls.add(INSTANCE.acquire(dependency).toUri().toURL());
                standaloneLoader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
            }
        }
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(standaloneLoader);
        try {
            Class<?> entry = standaloneLoader.loadClass("fun.sakuraspark.sakuraupdater.SakuraUpdaterServerStandalone");
            return (Integer) entry.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            if (e.getCause() instanceof Error cause) throw cause;
            throw e;
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** 获取已登记依赖的类加载器；复用至进程退出，不涉及依赖库的业务调用。 */
    public static synchronized ClassLoader loadDependency(String group, String artifact) throws IOException {
        Dependency dependency = DEPENDENCIES.stream()
            .filter(item -> item.group().equals(group) && item.artifact().equals(artifact))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown dependency: " + group + ":" + artifact));
        URLClassLoader loader = dependencyLoaders.get(dependency);
        if (loader == null) {
            URL jar = INSTANCE.acquire(dependency).toUri().toURL();
            loader = new URLClassLoader(new URL[] {jar}, PackageManager.class.getClassLoader()) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) {
                            // 优先使用已校验 JAR 中的类，其余类交给应用环境提供。
                            try {
                                loaded = findClass(name);
                            } catch (ClassNotFoundException e) {
                                loaded = super.loadClass(name, false);
                            }
                        }
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                }

                @Override
                public URL getResource(String name) {
                    // 资源与类来自同一依赖版本，避免混用其他模组提供的原生库。
                    URL resource = findResource(name);
                    return resource != null ? resource : super.getResource(name);
                }
            };
            dependencyLoaders.put(dependency, loader);
        }
        return loader;
    }

    private static void closeLoader(URLClassLoader loader) {
        if (loader == null) return;
        try {
            loader.close();
        } catch (IOException e) {
            System.err.println("[SakuraUpdater] Cannot close dependency loader: " + e);
        }
    }
}
