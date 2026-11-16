package fun.sakuraspark.sakuraupdater;

import com.mojang.logging.LogUtils;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.javafmlmod.FMLModContainer;
import net.neoforged.fml.loading.FMLEnvironment;

import org.slf4j.Logger;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(SakuraUpdater.MODID)
public class SakuraUpdater {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "sakuraupdater";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();

    SakuraUpdaterServer serverInstance;
    SakuraUpdaterClient clientInstance;

    public SakuraUpdater(IEventBus modEventBus, FMLModContainer modContainer) {
        // LOGGER.info("Is debug enabled? {}", LOGGER.isDebugEnabled());
        // Register the commonSetup method for modloading
        // modEventBus.addListener(this::commonSetup);

        // Register ourselves for server and other game events we are interested in
        //NeoForge.EVENT_BUS.register(this);

        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            LOGGER.debug("Try run SakuraUpdater Server");
            serverInstance = new SakuraUpdaterServer();
            NeoForge.EVENT_BUS.register(serverInstance);
        }
        if (FMLEnvironment.dist == Dist.CLIENT) {
            LOGGER.debug("Try run SakuraUpdater Client");
            clientInstance = new SakuraUpdaterClient();
            NeoForge.EVENT_BUS.register(clientInstance);
        }
    }

    // private void commonSetup(final FMLCommonSetupEvent event)
    // {
    // // Some common setup code
    // LOGGER.debug("HELLO FROM COMMON SETUP");

    // if (Config.logDirtBlock)
    // LOGGER.debug("DIRT BLOCK >> {}", ForgeRegistries.BLOCKS.getKey(Blocks.DIRT));

    // LOGGER.debug(Config.magicNumberIntroduction + Config.magicNumber);
    // Config.items.forEach((item) -> LOGGER.info("ITEM >> {}", item.toString()));
    // }

}
