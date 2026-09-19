package fun.sakuraspark.sakuraupdater;

/** 仅依赖 JDK 的 java -jar 启动入口。 */
public class SakuraUpdaterBootstrap {
    public static void main(String[] args) {
        try {
            System.exit(PackageManager.runStandalone(args));
        } catch (Exception | LinkageError e) {
            System.err.println("SakuraUpdater standalone startup failed!");
            e.printStackTrace();
            System.exit(1);
        }
    }
}
