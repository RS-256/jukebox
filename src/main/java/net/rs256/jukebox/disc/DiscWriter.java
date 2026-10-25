package net.rs256.jukebox.disc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Streams a recording into a {@code .recording/} directory: {@code meta.json} once at open,
 * then {@code ticks.json} one line per tick, flushed after every line.
 * Every required value is always written; nullable ones only when they are set.
 */
public final class DiscWriter implements Closeable {
    public static final String META_FILE = "meta.json";
    public static final String TICKS_FILE = "ticks.json";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Gson PRETTY_GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    /** What {@code header.json} already knows when the recording starts. */
    public record Meta(
            String source,
            Instant created,
            String mcVersion,
            String modVersion,
            String world,
            Header.Timing timing
    ) {
    }

    private final Meta meta;
    private final Writer ticks;
    private final List<JukeboxEvent> pending = new ArrayList<>();
    private final Set<String> uniqueSounds = new HashSet<>();

    private int currentTick = -1;
    private int lastWrittenTick = -1;
    private boolean inTick;
    private boolean closed;
    private int ticksRecorded;
    private int sounds;

    private DiscWriter(Meta meta, Writer ticks) {
        this.meta = meta;
        this.ticks = ticks;
    }

    /** Creates {@code recordingDir}, writes {@code meta.json} and opens {@code ticks.json}. Fails if they already exist. */
    public static DiscWriter open(Path recordingDir, Meta meta) throws IOException {
        Files.createDirectories(recordingDir);

        try (Writer out = Files.newBufferedWriter(recordingDir.resolve(META_FILE), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            out.write(PRETTY_GSON.toJson(metaToJson(meta)));
            out.write('\n');
        }

        Writer ticks = Files.newBufferedWriter(recordingDir.resolve(TICKS_FILE), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        ticks.write("{\"schema\":" + Header.SCHEMA + ",\"ticks\":[");
        ticks.flush();
        return new DiscWriter(meta, ticks);
    }

    public void beginTick(int tick) {
        this.ensureOpen();
        if (this.inTick) {
            throw new IllegalStateException("tick " + this.currentTick + " is not ended");
        }
        if (tick <= this.lastWrittenTick) {
            throw new IllegalArgumentException("tick " + tick + " is not after " + this.lastWrittenTick);
        }
        this.currentTick = tick;
        this.inTick = true;
    }

    public void add(JukeboxEvent event) {
        this.ensureOpen();
        if (!this.inTick) {
            throw new IllegalStateException("add outside of a tick");
        }
        this.pending.add(event);
    }

    /** Writes the tick as one line and flushes. A tick without events writes nothing. */
    public void endTick() throws IOException {
        this.ensureOpen();
        if (!this.inTick) {
            throw new IllegalStateException("no tick to end");
        }
        this.inTick = false;
        if (this.pending.isEmpty()) {
            return;
        }

        this.writeLine(tickToJson(this.currentTick, this.pending));
        for (JukeboxEvent event : this.pending) {
            if (event instanceof JukeboxEvent.SoundEvent sound) {
                this.sounds++;
                this.uniqueSounds.add(sound.sound());
            }
        }
        this.pending.clear();
        this.lastWrittenTick = this.currentTick;
        this.ticksRecorded++;
    }

    /** Closes {@code ticks.json} and returns the header of the finished recording. */
    public Header finish(int durationTicks) throws IOException {
        this.ensureOpen();
        if (this.inTick) {
            throw new IllegalStateException("tick " + this.currentTick + " is not ended");
        }
        this.ticks.write("\n]}\n");
        this.close();

        return new Header(
                Header.SCHEMA,
                this.meta.source(),
                this.meta.created(),
                durationTicks,
                this.meta.mcVersion(),
                this.meta.modVersion(),
                this.meta.world(),
                this.meta.timing(),
                new Header.Stats(this.ticksRecorded, this.sounds, this.uniqueSounds.size())
        );
    }

    /** Closes the files without finishing; {@code ticks.json} is left without its closing brackets. */
    @Override
    public void close() throws IOException {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.ticks.close();
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("writer is closed");
        }
    }

    /** Appends one line to {@code ticks.json}; the comma goes in front so a crash never leaves a trailing one. */
    private void writeLine(JsonObject line) throws IOException {
        if (this.ticksRecorded > 0) {
            this.ticks.write(',');
        }
        this.ticks.write('\n');
        this.ticks.write(GSON.toJson(line));
        this.ticks.flush();
    }

    /**
     * <pre>{@code
     * {
     *   "schema": 0,
     *   "source": "server",
     *   "created": "2026-10-22T12:34:56Z",
     *   "mcVersion": "1.21.11",
     *   "modVersion": "0.1.0",
     *   "world": "New World",
     *   "timing": {
     *     "mode": "ideal",
     *     "accuracy": "tick",
     *     "initialNspt": 50000000
     *   }
     * }
     * }</pre>
     */
    private static JsonObject metaToJson(Meta meta) {
        JsonObject timing = new JsonObject();
        timing.addProperty("mode", meta.timing().mode());
        timing.addProperty("accuracy", meta.timing().accuracy());
        timing.addProperty("initialNspt", meta.timing().initialNspt());

        JsonObject json = new JsonObject();
        json.addProperty("schema", Header.SCHEMA);
        json.addProperty("source", meta.source());
        json.addProperty("created", meta.created().toString());
        json.addProperty("mcVersion", meta.mcVersion());
        json.addProperty("modVersion", meta.modVersion());
        json.addProperty("world", meta.world());
        json.add("timing", timing);
        return json;
    }

    /**
     * <pre>{@code
     * {"tick":7,"events":[<event>, ...]}
     * }</pre>
     */
    private static JsonObject tickToJson(int tick, List<JukeboxEvent> events) {
        JsonArray array = new JsonArray();
        for (JukeboxEvent event : events) {
            array.add(eventToJson(event));
        }

        JsonObject json = new JsonObject();
        json.addProperty("tick", tick);
        json.add("events", array);
        return json;
    }

    /** One of {@link #soundToJson}, {@link #stopToJson}, {@link #listenerToJson} or {@link #timingToJson}. */
    private static JsonObject eventToJson(JukeboxEvent event) {
        return switch (event) {
            case JukeboxEvent.SoundEvent sound -> soundToJson(sound);
            case JukeboxEvent.StopEvent stop -> stopToJson(stop);
            case JukeboxEvent.ListenerEvent listener -> listenerToJson(listener);
            case JukeboxEvent.TimingEvent timing -> timingToJson(timing);
        };
    }

    /**
     * {@code entity}, {@code targets} and {@code minVolume} only when set.
     * <pre>{@code
     * {"type":"sound","sound":"minecraft:block.note_block.harp","category":"record",
     *  "dimension":"minecraft:overworld","pos":[1.5,64.0,-3.5],"volume":3.0,"pitch":1.5,
     *  "seed":-4532119923781,"range":48.0,
     *  "entity":42,"targets":["<uuid>"],"minVolume":0.1}
     * }</pre>
     */
    private static JsonObject soundToJson(JukeboxEvent.SoundEvent sound) {
        JsonObject json = new JsonObject();
        json.addProperty("type", sound.type());
        json.addProperty("sound", sound.sound());
        json.addProperty("category", sound.category());
        json.addProperty("dimension", sound.dimension());
        json.add("pos", posToJson(sound.x(), sound.y(), sound.z()));
        json.addProperty("volume", sound.volume());
        json.addProperty("pitch", sound.pitch());
        json.addProperty("seed", sound.seed());
        json.addProperty("range", sound.range());
        if (sound.entity() != null) {
            json.addProperty("entity", sound.entity());
        }
        if (sound.targets() != null) {
            json.add("targets", targetsToJson(sound.targets()));
        }
        if (sound.minVolume() != null) {
            json.addProperty("minVolume", sound.minVolume());
        }
        return json;
    }

    /**
     * Every key but {@code type} only when set.
     * <pre>{@code
     * {"type":"stop","sound":"minecraft:music.game","category":"music","targets":["<uuid>"]}
     * }</pre>
     */
    private static JsonObject stopToJson(JukeboxEvent.StopEvent stop) {
        JsonObject json = new JsonObject();
        json.addProperty("type", stop.type());
        if (stop.sound() != null) {
            json.addProperty("sound", stop.sound());
        }
        if (stop.category() != null) {
            json.addProperty("category", stop.category());
        }
        if (stop.targets() != null) {
            json.add("targets", targetsToJson(stop.targets()));
        }
        return json;
    }

    /**
     * <pre>{@code
     * {"type":"listener","listenerId":"rs256","dimension":"minecraft:overworld",
     *  "pos":[1.5,65.62,-3.5],"rot":[-90.0,12.5],"present":true}
     * }</pre>
     */
    private static JsonObject listenerToJson(JukeboxEvent.ListenerEvent listener) {
        JsonObject json = new JsonObject();
        json.addProperty("type", listener.type());
        json.addProperty("listenerId", listener.listenerId());
        json.addProperty("dimension", listener.dimension());
        json.add("pos", posToJson(listener.x(), listener.y(), listener.z()));
        JsonArray rot = new JsonArray();
        rot.add(listener.yaw());
        rot.add(listener.pitch());
        json.add("rot", rot);
        json.addProperty("present", listener.present());
        return json;
    }

    /**
     * Every key but {@code type} only when it changed on this tick.
     * <pre>{@code
     * {"type":"timing","nspt":50000000,"frozen":true,"steps":20,"sprint":1000}
     * }</pre>
     */
    private static JsonObject timingToJson(JukeboxEvent.TimingEvent timing) {
        JsonObject json = new JsonObject();
        json.addProperty("type", timing.type());
        if (timing.nspt() != null) {
            json.addProperty("nspt", timing.nspt());
        }
        if (timing.frozen() != null) {
            json.addProperty("frozen", timing.frozen());
        }
        if (timing.steps() != null) {
            json.addProperty("steps", timing.steps());
        }
        if (timing.sprint() != null) {
            json.addProperty("sprint", timing.sprint());
        }
        return json;
    }

    /**
     * <pre>{@code
     * [1.5,64.0,-3.5]
     * }</pre>
     */
    private static JsonArray posToJson(double x, double y, double z) {
        JsonArray json = new JsonArray();
        json.add(x);
        json.add(y);
        json.add(z);
        return json;
    }

    /**
     * <pre>{@code
     * ["00000000-0000-0000-0000-000000000001", ...]
     * }</pre>
     */
    private static JsonArray targetsToJson(List<UUID> targets) {
        JsonArray json = new JsonArray();
        for (UUID target : targets) {
            json.add(target.toString());
        }
        return json;
    }
}
