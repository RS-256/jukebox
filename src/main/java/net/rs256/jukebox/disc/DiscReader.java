package net.rs256.jukebox.disc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import org.jspecify.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads {@code header.json} and streams {@code ticks.json} one tick at a time.
 * Unknown keys and unknown event types are skipped; a missing required key or a
 * {@code schema} newer than {@link Header#SCHEMA} is a {@link JsonParseException}.
 */
public final class DiscReader implements Closeable {
    private final JsonReader ticks;
    private boolean finished;

    private DiscReader(JsonReader ticks) {
        this.ticks = ticks;
    }

    public static Header readHeader(Reader in) {
        JsonObject json = JsonParser.parseReader(in).getAsJsonObject();
        return headerFromJson(json);
    }

    /** Reads up to the start of the {@code ticks} array; call {@link #next()} for each tick. */
    public static DiscReader openTicks(Reader in) throws IOException {
        JsonReader json = new JsonReader(in);
        json.beginObject();
        boolean schemaChecked = false;
        while (json.hasNext()) {
            String name = json.nextName();
            if (name.equals("schema")) {
                checkSchema(json.nextInt());
                schemaChecked = true;
            } else if (name.equals("ticks")) {
                if (!schemaChecked) {
                    throw new JsonParseException("\"schema\" must come before \"ticks\"");
                }
                json.beginArray();
                return new DiscReader(json);
            } else {
                json.skipValue();
            }
        }
        throw new JsonParseException("missing \"ticks\"");
    }

    /** Returns the next tick, or null after the last one. */
    public @Nullable TickRecord next() throws IOException {
        if (this.finished) {
            return null;
        }
        if (!this.ticks.hasNext()) {
            this.ticks.endArray();
            this.finished = true;
            return null;
        }

        String path = this.ticks.getPath();
        JsonElement element = JsonParser.parseReader(this.ticks);
        try {
            return tickFromJson(element.getAsJsonObject());
        } catch (JsonParseException | IllegalStateException e) {
            throw new JsonParseException(path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close() throws IOException {
        this.ticks.close();
    }

    private static void checkSchema(int schema) {
        if (schema > Header.SCHEMA) {
            throw new JsonParseException("schema " + schema + " is newer than supported " + Header.SCHEMA);
        }
    }

    /** Reads what {@code DiscWriter#metaToJson} writes, plus {@code durationTicks} and {@code stats}. */
    private static Header headerFromJson(JsonObject json) {
        int schema = requireNumber(json, "schema").intValue();
        checkSchema(schema);

        JsonObject timing = requireObject(json, "timing");
        JsonObject stats = requireObject(json, "stats");
        return new Header(
                schema,
                requireString(json, "source"),
                requireInstant(json, "created"),
                requireNumber(json, "durationTicks").intValue(),
                requireString(json, "mcVersion"),
                requireString(json, "modVersion"),
                requireString(json, "world"),
                new Header.Timing(
                        requireString(timing, "mode"),
                        requireString(timing, "accuracy"),
                        requireNumber(timing, "initialNspt").longValue()
                ),
                new Header.Stats(
                        requireNumber(stats, "ticksRecorded").intValue(),
                        requireNumber(stats, "sounds").intValue(),
                        requireNumber(stats, "uniqueSounds").intValue()
                )
        );
    }

    /** Reads what {@code DiscWriter#tickToJson} writes. */
    private static TickRecord tickFromJson(JsonObject json) {
        List<JukeboxEvent> events = new ArrayList<>();
        for (JsonElement element : requireArray(json, "events")) {
            JukeboxEvent event = eventFromJson(element.getAsJsonObject());
            if (event != null) {
                events.add(event);
            }
        }
        return new TickRecord(requireNumber(json, "tick").intValue(), events);
    }

    /** Returns null for an event type this version does not know. */
    private static @Nullable JukeboxEvent eventFromJson(JsonObject json) {
        return switch (requireString(json, "type")) {
            case JukeboxEvent.SoundEvent.TYPE -> soundFromJson(json);
            case JukeboxEvent.StopEvent.TYPE -> stopFromJson(json);
            case JukeboxEvent.ListenerEvent.TYPE -> listenerFromJson(json);
            case JukeboxEvent.TimingEvent.TYPE -> timingFromJson(json);
            default -> null;
        };
    }

    /** Reads what {@code DiscWriter#soundToJson} writes. */
    private static JukeboxEvent.SoundEvent soundFromJson(JsonObject json) {
        JsonArray pos = requirePos(json);
        return new JukeboxEvent.SoundEvent(
                requireString(json, "sound"),
                requireString(json, "category"),
                requireString(json, "dimension"),
                pos.get(0).getAsDouble(), pos.get(1).getAsDouble(), pos.get(2).getAsDouble(),
                requireNumber(json, "volume").floatValue(),
                requireNumber(json, "pitch").floatValue(),
                requireNumber(json, "seed").longValue(),
                optionalInt(json, "entity"),
                optionalTargets(json),
                optionalFloat(json, "minVolume"),
                requireNumber(json, "range").floatValue()
        );
    }

    /** Reads what {@code DiscWriter#stopToJson} writes. */
    private static JukeboxEvent.StopEvent stopFromJson(JsonObject json) {
        return new JukeboxEvent.StopEvent(
                optionalString(json, "sound"),
                optionalString(json, "category"),
                optionalTargets(json)
        );
    }

    /** Reads what {@code DiscWriter#listenerToJson} writes. */
    private static JukeboxEvent.ListenerEvent listenerFromJson(JsonObject json) {
        JsonArray pos = requirePos(json);
        JsonArray rot = requireArray(json, "rot");
        if (rot.size() != 2) {
            throw new JsonParseException("\"rot\" must have 2 elements");
        }
        return new JukeboxEvent.ListenerEvent(
                requireString(json, "listenerId"),
                requireString(json, "dimension"),
                pos.get(0).getAsDouble(), pos.get(1).getAsDouble(), pos.get(2).getAsDouble(),
                rot.get(0).getAsFloat(), rot.get(1).getAsFloat(),
                requireBoolean(json, "present")
        );
    }

    /** Reads what {@code DiscWriter#timingToJson} writes. */
    private static JukeboxEvent.TimingEvent timingFromJson(JsonObject json) {
        return new JukeboxEvent.TimingEvent(
                optionalLong(json, "nspt"),
                optionalBoolean(json, "frozen"),
                optionalInt(json, "steps"),
                optionalInt(json, "sprint")
        );
    }

    private static JsonArray requirePos(JsonObject json) {
        JsonArray pos = requireArray(json, "pos");
        if (pos.size() != 3) {
            throw new JsonParseException("\"pos\" must have 3 elements");
        }
        return pos;
    }

    private static @Nullable List<UUID> optionalTargets(JsonObject json) {
        if (json.get("targets") == null) {
            return null;
        }

        List<UUID> targets = new ArrayList<>();
        for (JsonElement element : requireArray(json, "targets")) {
            try {
                targets.add(UUID.fromString(element.getAsString()));
            } catch (IllegalArgumentException e) {
                throw new JsonParseException("\"targets\" has an invalid uuid: " + element, e);
            }
        }
        return targets;
    }

    private static Instant requireInstant(JsonObject json, String key) {
        String value = requireString(json, key);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new JsonParseException("\"" + key + "\" is not an instant: " + value, e);
        }
    }

    private static JsonElement require(JsonObject json, String key) {
        JsonElement element = json.get(key);

        if (element == null || element.isJsonNull()) {
            throw new JsonParseException("missing \"" + key + "\"");
        }
        return element;
    }

    private static JsonPrimitive requirePrimitive(JsonObject json, String key) {
        JsonElement element = require(json, key);
        if (!element.isJsonPrimitive()) {
            throw new JsonParseException("\"" + key + "\" must be a value");
        }
        return element.getAsJsonPrimitive();
    }

    private static String requireString(JsonObject json, String key) {
        JsonPrimitive primitive = requirePrimitive(json, key);
        if (!primitive.isString()) {
            throw new JsonParseException("\"" + key + "\" must be a string");
        }
        return primitive.getAsString();
    }

    private static Number requireNumber(JsonObject json, String key) {
        JsonPrimitive primitive = requirePrimitive(json, key);
        if (!primitive.isNumber()) {
            throw new JsonParseException("\"" + key + "\" must be a number");
        }
        return primitive.getAsNumber();
    }

    private static boolean requireBoolean(JsonObject json, String key) {
        JsonPrimitive primitive = requirePrimitive(json, key);
        if (!primitive.isBoolean()) {
            throw new JsonParseException("\"" + key + "\" must be a boolean");
        }
        return primitive.getAsBoolean();
    }

    private static JsonArray requireArray(JsonObject json, String key) {
        JsonElement element = require(json, key);
        if (!element.isJsonArray()) {
            throw new JsonParseException("\"" + key + "\" must be an array");
        }
        return element.getAsJsonArray();
    }

    private static JsonObject requireObject(JsonObject json, String key) {
        JsonElement element = require(json, key);
        if (!element.isJsonObject()) {
            throw new JsonParseException("\"" + key + "\" must be an object");
        }
        return element.getAsJsonObject();
    }

    private static @Nullable String optionalString(JsonObject json, String key) {
        if (json.get(key) == null) {
            return null;
        } else {
            return requireString(json, key);
        }
    }

    private static @Nullable Integer optionalInt(JsonObject json, String key) {
        if (json.get(key) == null) {
            return null;
        } else {
            return requireNumber(json, key).intValue();
        }
    }

    private static @Nullable Long optionalLong(JsonObject json, String key) {
        if (json.get(key) == null) {
            return null;
        } else {
            return requireNumber(json, key).longValue();
        }
    }

    private static @Nullable Float optionalFloat(JsonObject json, String key) {
        if (json.get(key) == null) {
            return null;
        } else {
            return requireNumber(json, key).floatValue();
        }
    }

    private static @Nullable Boolean optionalBoolean(JsonObject json, String key) {
        if (json.get(key) == null) {
            return null;
        } else {
            return requireBoolean(json, key);
        }
    }
}
