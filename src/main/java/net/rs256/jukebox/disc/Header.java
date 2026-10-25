package net.rs256.jukebox.disc;

import java.time.Instant;

/** {@code header.json}, fixed when the recording stops. */
public record Header(
        int schema,
        String source,
        Instant created,
        int durationTicks,
        String mcVersion,
        String modVersion,
        String world,
        Timing timing,
        Stats stats
) {
    /** Bumped only on breaking changes. */
    public static final int SCHEMA = 0;

    public static final String SOURCE_SERVER = "server";

    /**
     * @param mode        {@code ideal} only for now
     * @param accuracy    {@code tick} for server recordings
     * @param initialNspt {@code nanosecondsPerTick()} when the recording started
     */
    public record Timing(String mode, String accuracy, long initialNspt) {
        public static final String MODE_IDEAL = "ideal";
        public static final String ACCURACY_TICK = "tick";
    }

    /**
     * @param ticksRecorded ticks with at least one event
     * @param sounds        number of {@link JukeboxEvent.SoundEvent}
     * @param uniqueSounds  number of distinct sound ids
     */
    public record Stats(int ticksRecorded, int sounds, int uniqueSounds) {
    }
}
