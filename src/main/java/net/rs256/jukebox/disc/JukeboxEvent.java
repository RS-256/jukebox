package net.rs256.jukebox.disc;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * One event of a {@link TickRecord}. Values are always held resolved; omitting defaults
 * ({@code pos = [0,0,0]}, {@code dimension = previous}, ...) is up to the writer and reader.
 */
public sealed interface JukeboxEvent {
    String type();

    /**
     * A sound played in the world.
     *
     * @param category  {@code SoundSource#getName()}
     * @param volume    raw value, not clamped
     * @param pitch     raw value, not clamped
     * @param entity    entity id when sent as {@code ClientboundSoundEntityPacket}; position stays fixed
     * @param targets   receivers when {@code /playsound} was limited to some players
     * @param minVolume {@code minVolume} of {@code /playsound}
     * @param range     broadcast range; equals {@link #derivedRange(float)} unless the sound event has a fixed range
     */
    record SoundEvent(
            String sound,
            String category,
            String dimension,
            double x, double y, double z,
            float volume,
            float pitch,
            long seed,
            @Nullable Integer entity,
            @Nullable List<UUID> targets,
            @Nullable Float minVolume,
            float range
    ) implements JukeboxEvent {
        public static final String TYPE = "sound";
        public static final String DEFAULT_CATEGORY = "master";

        /** {@code net.minecraft.sounds.SoundEvent#getRange(volume)} without a fixed range. */
        public static float derivedRange(float volume) {
            if (volume > 1.0f) {
                return 16.0f * volume;
            } else {
                return 16.0f;
            }
        }

        @Override
        public String type() {
            return TYPE;
        }
    }

    /** {@code ClientboundStopSoundPacket}; both {@code sound} and {@code category} null means stop everything. */
    record StopEvent(
            @Nullable String sound,
            @Nullable String category,
            @Nullable List<UUID> targets
    ) implements JukeboxEvent {
        public static final String TYPE = "stop";

        @Override
        public String type() {
            return TYPE;
        }
    }

    /**
     * A listener pose sample. {@code x, y, z} is the eye position, {@code yaw, pitch} in degrees.
     * {@code present = false} marks that the target disappeared; the pose is then meaningless.
     */
    record ListenerEvent(
            String listenerId,
            String dimension,
            double x, double y, double z,
            float yaw, float pitch,
            boolean present
    ) implements JukeboxEvent {
        public static final String TYPE = "listener";

        @Override
        public String type() {
            return TYPE;
        }
    }

    /** Tick rate state changes. Every field is null unless it changed on this tick. */
    record TimingEvent(
            @Nullable Long nspt,
            @Nullable Boolean frozen,
            @Nullable Integer steps,
            @Nullable Integer sprint
    ) implements JukeboxEvent {
        public static final String TYPE = "timing";

        @Override
        public String type() {
            return TYPE;
        }
    }
}
