package fun.sakuraspark.sakuraupdater.config;

import fun.sakuraspark.sakuraupdater.SakuraUpdater;
import fun.sakuraspark.sakuraupdater.SakuraUpdaterClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Forge's config APIs
@EventBusSubscriber(value = Dist.CLIENT, modid = SakuraUpdater.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ClientConfig {
	private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

	
	private static final ModConfigSpec.ConfigValue<String> HOST = BUILDER
			.comment("The host of the file server, default is 'localhost'.")
			.define("host", "localhost");

	private static final ModConfigSpec.IntValue PORT = BUILDER
			.comment("The port of the file server, default is 25564.")
			.defineInRange("port", 25564, 1, 65535);

	private static final ModConfigSpec.ConfigValue<String> now_version = BUILDER
			.comment("The current version of the client, used for update check. Don't change this unless you know what you're doing.")
			.define("now_version", "");

	private static final ModConfigSpec.IntValue DOWNLOAD_CONNECTIONS = BUILDER
			.comment("How many parallel connections to use when downloading one file. 1 disables chunked download (old behaviour).\n"
					+ "On a high-latency link this helps a lot: measured 1 connection ~75 KB/s vs 8 connections ~475 KB/s on the same file.\n"
					+ "Every connection holds one thread on the server, and the server has 'max_threads' of them in total, "
					+ "so lower this if several players update at the same time.")
			.defineInRange("download_connections", 8, 1, 16);

	public static final ModConfigSpec SPEC = BUILDER.build();

	public static int port;
	public static String host;

	/** 单个文件的并发下载连接数，见 download_connections 配置项 */
	public static int downloadConnections = 8;

	@SubscribeEvent
	public static void onLoad(final ModConfigEvent event) {
		port = PORT.get();
		host = HOST.get();
		downloadConnections = DOWNLOAD_CONNECTIONS.get();
		while (SakuraUpdaterClient.getInstance() == null);
		SakuraUpdaterClient.getInstance().connectToServer();
		// 游戏还在加载（资源重载/加载遮罩）时就把更新检查在后台跑起来，界面弹出时直接拿结果
		SakuraUpdaterClient.getInstance().prefetchUpdateCheck();
	}

	public static int getDownloadConnections() {
		return downloadConnections;
	}

	public static String getNowVersion() {
		return now_version.get();
	}

	public static void setNowVersion(String version) {
		now_version.set(version);
		SPEC.save();
	}
}
