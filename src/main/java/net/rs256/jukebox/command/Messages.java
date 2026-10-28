package net.rs256.jukebox.command;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.rs256.jukebox.JukeboxMod;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Translatable messages whose fallback is our own {@code en_us.json}, so players without the mod
 * on a dedicated server read English instead of raw keys.
 */
public final class Messages {
    private static final String EN_US = "/assets/" + JukeboxMod.MOD_ID + "/lang/en_us.json";
    private static final Map<String, String> FALLBACKS = loadFallbacks();

    private Messages() {
    }

    public static MutableComponent translatable(String key, Object... args) {
        return Component.translatableWithFallback(key, FALLBACKS.get(key), args);
    }

    private static Map<String, String> loadFallbacks() {
        Map<String, String> fallbacks = new HashMap<>();
        try (InputStream in = Messages.class.getResourceAsStream(EN_US)) {
            if (in == null) {
                JukeboxMod.LOGGER.error("Missing {}", EN_US);
                return fallbacks;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                for (String key : json.keySet()) {
                    fallbacks.put(key, json.get(key).getAsString());
                }
            }
        } catch (IOException | RuntimeException e) {
            JukeboxMod.LOGGER.error("Could not read {}", EN_US, e);
        }
        return fallbacks;
    }
}
