package net.rs256.jukebox;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
//? if <=1.18.2 {
/*import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
*///?} else {
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
//?}

public class JukeboxMod implements ModInitializer {
    public static final String MOD_ID = "jukebox";
    public static final String VERSION = /*$ mod_version*/ "0.1.0";
    public static final String MINECRAFT = /*$ minecraft*/ "1.21.11";

    //? if <=1.18.2 {
    /*public static final Logger LOGGER = LogManager.getLogger();
     *///?} else {
    public static final Logger LOGGER = LogUtils.getLogger();
    //?}

    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.
        LOGGER.info("Jukebox {} for Minecraft {}", VERSION, MINECRAFT);

        //registerCommands();
    }

    /**
     * Adapts to the ResourceLocation -> Identifier changes introduced in 1.21.
     */
    public static Identifier id(String namespace, String path) {
        //? if <1.21 {
        /*return new ResourceLocation(namespace, path);
        *///?} else {
        return Identifier.fromNamespaceAndPath(namespace, path);
        //?}
    }

    // public void registerCommands() {
    //     CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> TemplateModCommand.register(dispatcher, registryAccess));
    // }
}
