package net.rs256.jukebox.server;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.storage.LevelResource;
import net.rs256.jukebox.JukeboxMod;
import net.rs256.jukebox.disc.DiscContainer;
import net.rs256.jukebox.disc.DiscWriter;
import net.rs256.jukebox.disc.Header;
import net.rs256.jukebox.disc.JukeboxEvent;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Starts and ends server recordings. A recording streams into {@code <discDir>/.<name>.recording/}
 * and becomes {@code <discDir>/<name>.disc} on {@link #stop()}. One recording at a time per server.
 */
public final class ServerRecorder {
    public static final String DISC_EXTENSION = ".disc";
    private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static @Nullable ServerRecorder instance;

    /** @param ticks jukebox ticks recorded so far */
    public record Status(String name, Instant started, int ticks) {
    }

    private record Recording(String name, Path recordingDir, Path disc, DiscWriter writer, TickClock clock, PacketRecorder packetRecorder, Instant started) {
    }

    private final MinecraftServer server;
    private final Path discDir;
    private @Nullable Recording recording;

    private ServerRecorder(MinecraftServer server) {
        this.server = server;
        this.discDir = server.getWorldPath(LevelResource.ROOT).resolve("jukebox").resolve("discs").normalize();
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> instance = new ServerRecorder(server));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (instance != null) {
                instance.onShutdown();
            }
            instance = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (instance != null) {
                instance.onEndTick();
            }
        });
    }

    /** The recorder of the running server. */
    public static ServerRecorder get() {
        if (instance == null) {
            throw new IllegalStateException("no server is running");
        }
        return instance;
    }

    public boolean isRecording() {
        return this.recording != null;
    }

    /** The packet recorder of the running recording, or null when idle or off the server thread. */
    public static @Nullable PacketRecorder packetRecorder() {
        if (instance == null || instance.recording == null || !instance.server.isSameThread()) {
            return null;
        }
        return instance.recording.packetRecorder();
    }

    /**
     * Called from {@code ServerLevel#playSeededSound} before anything is sent, so sounds nobody hears
     * are recorded too. Values are raw: no clamp, no packet quantization. Only the server thread records.
     * The packets it sends are ignored by the {@link PacketRecorder} until
     * {@link PacketRecorder#exitLevelSound()}.
     *
     * @param entity id of the entity the sound follows, or null for a positioned sound
     */
    public static void recordSound(
            ServerLevel level,
            double x, double y, double z,
            Holder<SoundEvent> sound,
            SoundSource source,
            float volume,
            float pitch,
            long seed,
            @Nullable Integer entity
    ) {
        if (instance == null || instance.recording == null || !instance.server.isSameThread()) {
            return;
        }
        instance.recording.writer().add(new JukeboxEvent.SoundEvent(
                sound.value().location().toString(),
                source.getName(),
                level.dimension().identifier().toString(),
                x, y, z,
                volume,
                pitch,
                seed,
                entity,
                null,
                null,
                sound.value().getRange(volume)
        ));
        instance.recording.packetRecorder().enterLevelSound();
    }

    public @Nullable Status status() {
        if (this.recording == null) {
            return null;
        } else {
            return new Status(this.recording.name(), this.recording.started(), this.recording.clock().tick());
        }
    }

    /** {@code <world>_<yyyyMMdd-HHmmss>}, uses save folder name */
    public String defaultName() {
        Path world = this.server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        return world.getFileName() + "_" + LocalDateTime.now().format(NAME_TIME);
    }

    /**
     * @throws IllegalStateException     if a recording is already running
     * @throws IllegalArgumentException  if {@code name} is not a plain file name
     * @throws FileAlreadyExistsException if {@code <name>.disc} or its {@code .recording/} already exists
     */
    public Status start(String name) throws IOException {
        if (this.recording != null) {
            throw new IllegalStateException("already recording " + this.recording.name());
        }
        Path disc = this.resolveInDiscDir(name + DISC_EXTENSION, name);
        Path recordingDir = this.resolveInDiscDir("." + name + ".recording", name);
        if (Files.exists(disc)) {
            throw new FileAlreadyExistsException(disc.toString());
        }
        if (Files.exists(recordingDir)) {
            throw new FileAlreadyExistsException(recordingDir.toString());
        }

        Instant started = Instant.now();
        DiscWriter.Meta meta = new DiscWriter.Meta(
                Header.SOURCE_SERVER,
                started,
                SharedConstants.getCurrentVersion().name(),
                JukeboxMod.VERSION,
                this.server.getWorldData().getLevelName(),
                new Header.Timing(Header.Timing.MODE_IDEAL, Header.Timing.ACCURACY_TICK, this.server.tickRateManager().nanosecondsPerTick())
        );
        DiscWriter writer = DiscWriter.open(recordingDir, meta);
        TickClock clock = new TickClock(this.server.tickRateManager());
        writer.beginTick(clock.tick());
        writer.add(clock.initialEvent());
        this.recording = new Recording(name, recordingDir, disc, writer, clock, new PacketRecorder(writer), started);
        JukeboxMod.LOGGER.info("Started recording {}", name);
        return new Status(name, started, clock.tick());
    }

    /**
     * Finishes the recording, packs it into {@code <name>.disc} and deletes its {@code .recording/}.
     * @return the written {@code .disc}
     * @throws IllegalStateException if nothing is being recorded
     */
    public Path stop() throws IOException {
        Recording recording = this.take();
        recording.packetRecorder().flush();
        recording.writer().endTick();
        Header header = recording.writer().finish(recording.clock().tick());
        DiscContainer.pack(recording.recordingDir(), header, recording.disc(), DiscContainer.Codec.DEFLATE);
        DiscContainer.deleteRecording(recording.recordingDir());
        JukeboxMod.LOGGER.info("Saved recording {} to {}", recording.name(), recording.disc());
        return recording.disc();
    }

    /**
     * Throws the recording away, {@code .recording/} included.
     * @return the name of the discarded recording
     * @throws IllegalStateException if nothing is being recorded
     */
    public String cancel() throws IOException {
        Recording recording = this.take();
        recording.writer().close();
        DiscContainer.deleteRecording(recording.recordingDir());
        JukeboxMod.LOGGER.info("Cancelled recording {}", recording.name());
        return recording.name();
    }

    /**
     * Writes the merged packet sounds and tick rate changes, then closes the jukebox tick if game elements
     * ran. While frozen the tick stays open, so everything that happens during a freeze lands on the same tick.
     */
    private void onEndTick() {
        if (this.recording == null) {
            return;
        }
        DiscWriter writer = this.recording.writer();
        TickClock clock = this.recording.clock();
        try {
            this.recording.packetRecorder().flush();
            JukeboxEvent.TimingEvent timing = clock.poll();
            if (timing != null) {
                writer.add(timing);
            }
            if (clock.advance()) {
                writer.endTick();
                writer.beginTick(clock.tick());
            }
        } catch (IOException | RuntimeException e) {
            JukeboxMod.LOGGER.error("Recording {} failed; its .recording/ is left as it is", this.recording.name(), e);
            this.recording = null;
            try {
                writer.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
        }
    }

    /** Saves a running recording when the server stops; on failure the {@code .recording/} is left as it is. */
    private void onShutdown() {
        if (this.recording == null) {
            return;
        }
        try {
            this.stop();
        } catch (IOException | RuntimeException e) {
            JukeboxMod.LOGGER.error("Could not save the recording on shutdown", e);
        }
    }

    private Recording take() {
        if (this.recording == null) {
            throw new IllegalStateException("not recording");
        }
        Recording recording = this.recording;
        this.recording = null;
        return recording;
    }

    /** Rejects names that would leave {@link #discDir} or are not valid file names on this system. */
    private Path resolveInDiscDir(String fileName, String name) {
        Path path;
        try {
            path = this.discDir.resolve(fileName).normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("invalid name: " + name, e);
        }
        if (name.isEmpty() || !this.discDir.equals(path.getParent())) {
            throw new IllegalArgumentException("invalid name: " + name);
        }
        return path;
    }
}
