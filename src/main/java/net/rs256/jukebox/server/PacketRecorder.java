package net.rs256.jukebox.server;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.rs256.jukebox.disc.DiscWriter;
import net.rs256.jukebox.disc.JukeboxEvent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Records packets sent to players that the world hooks do not see. Consecutive packets with the same
 * content become one event whose {@code targets} are the receivers, so a command sending to each
 * player in a loop records once; a different packet in between starts a new event, so the order of
 * sounds and stops is kept. Events are written when the tick ends. Server thread only.
 * <p>
 * Sound packets: those sent directly, without {@code ServerLevel#playSeededSound} ({@code /playsound},
 * the raid horn, mods). Packets with the same sound, seed and position become one {@code sound} event.
 * <p>
 * Stop sound packets ({@code /stopsound}): packets with the same sound and category become one
 * {@code stop} event.
 */
public final class PacketRecorder {
    private final DiscWriter writer;
    private boolean inLevelSound;
    private @Nullable PlaySound playSound;
    private final List<Pending> pending = new ArrayList<>();

    /** @param key the event without {@code targets} */
    private record Pending(JukeboxEvent key, Set<UUID> targets) {
    }

    /** The raw arguments of a running {@code /playsound}; its packets carry relocated values. */
    private record PlaySound(String dimension, Vec3 pos, float volume, float minVolume) {
    }

    PacketRecorder(DiscWriter writer) {
        this.writer = writer;
    }

    /** Called for every packet sent to a client, before it is sent. */
    public static void onSend(ServerCommonPacketListenerImpl listener, Packet<?> packet) {
        if (!(packet instanceof ClientboundSoundPacket)
                && !(packet instanceof ClientboundSoundEntityPacket)
                && !(packet instanceof ClientboundStopSoundPacket)) {
            return;
        }
        if (!(listener instanceof ServerGamePacketListenerImpl game)) {
            return;
        }

        PacketRecorder recorder = ServerRecorder.packetRecorder();
        if (recorder != null) {
            recorder.add(game.player, packet);
        }
    }

    /** {@code ServerLevel#playSeededSound} is recorded by itself, so its packets are ignored. */
    void enterLevelSound() {
        this.inLevelSound = true;
    }

    public void exitLevelSound() {
        this.inLevelSound = false;
    }

    /** Packets sent until {@link #exitPlaySound()} are recorded with these raw values and {@code minVolume}. */
    public void enterPlaySound(ServerLevel level, Vec3 pos, float volume, float minVolume) {
        this.playSound = new PlaySound(level.dimension().identifier().toString(), pos, volume, minVolume);
    }

    public void exitPlaySound() {
        this.playSound = null;
    }

    /** Writes the merged events of this tick into the open tick. */
    void flush() {
        for (Pending pending : this.pending) {
            this.writer.add(withTargets(pending.key(), new ArrayList<>(pending.targets())));
        }
        this.pending.clear();
        this.inLevelSound = false;
        this.playSound = null;
    }

    private void add(ServerPlayer player, Packet<?> packet) {
        if (this.inLevelSound) {
            return;
        }
        JukeboxEvent key;
        if (packet instanceof ClientboundSoundPacket sound) {
            key = this.positionedKey(player, sound);
        } else if (packet instanceof ClientboundSoundEntityPacket sound) {
            key = this.entityKey(player, sound);
        } else if (packet instanceof ClientboundStopSoundPacket stop) {
            key = stopKey(stop);
        } else {
            return;
        }
        if (key == null) {
            return;
        }

        if (!this.pending.isEmpty() && this.pending.getLast().key().equals(key)) {
            this.pending.getLast().targets().add(player.getUUID());
        } else {
            Set<UUID> targets = new LinkedHashSet<>();
            targets.add(player.getUUID());
            this.pending.add(new Pending(key, targets));
        }
    }

    /** The packet position is quantized to 1/8 block; {@code /playsound} uses its raw arguments instead. */
    private JukeboxEvent.SoundEvent positionedKey(ServerPlayer player, ClientboundSoundPacket sound) {
        PlaySound playSound = this.playSound;
        if (playSound == null) {
            return key(sound.getSound(), sound.getSource(), dimension(player), sound.getX(), sound.getY(), sound.getZ(),
                    sound.getVolume(), sound.getPitch(), sound.getSeed(), null, null);
        } else {
            return key(sound.getSound(), sound.getSource(), playSound.dimension(), playSound.pos().x(), playSound.pos().y(), playSound.pos().z(),
                    playSound.volume(), sound.getPitch(), sound.getSeed(), null, playSound.minVolume());
        }
    }

    /** Uses where the entity is now, like {@code ServerLevel#playSeededSound}; an unknown entity is dropped. */
    private JukeboxEvent.@Nullable SoundEvent entityKey(ServerPlayer player, ClientboundSoundEntityPacket sound) {
        Entity entity = player.level().getEntity(sound.getId());
        if (entity == null) {
            return null;
        }

        return key(sound.getSound(), sound.getSource(), dimension(player), entity.getX(), entity.getY(), entity.getZ(),
                sound.getVolume(), sound.getPitch(), sound.getSeed(), entity.getId(), null);
    }

    private static JukeboxEvent.SoundEvent key(
            Holder<SoundEvent> sound,
            SoundSource source,
            String dimension,
            double x, double y, double z,
            float volume,
            float pitch,
            long seed,
            @Nullable Integer entity,
            @Nullable Float minVolume
    ) {
        return new JukeboxEvent.SoundEvent(
                sound.value().location().toString(),
                source.getName(),
                dimension,
                x, y, z,
                volume,
                pitch,
                seed,
                entity,
                null,
                minVolume,
                sound.value().getRange(volume)
        );
    }

    /** Both the sound and the category may be absent = any */
    private static JukeboxEvent.StopEvent stopKey(ClientboundStopSoundPacket stop) {
        String sound = null;
        if (stop.getName() != null) {
            sound = stop.getName().toString();
        }
        String category = null;
        if (stop.getSource() != null) {
            category = stop.getSource().getName();
        }
        return new JukeboxEvent.StopEvent(sound, category, null);
    }

    private static JukeboxEvent withTargets(JukeboxEvent key, List<UUID> targets) {
        if (key instanceof JukeboxEvent.SoundEvent sound) {
            return new JukeboxEvent.SoundEvent(
                    sound.sound(), sound.category(), sound.dimension(),
                    sound.x(), sound.y(), sound.z(),
                    sound.volume(), sound.pitch(), sound.seed(),
                    sound.entity(),
                    targets,
                    sound.minVolume(),
                    sound.range()
            );
        } else if (key instanceof JukeboxEvent.StopEvent stop) {
            return new JukeboxEvent.StopEvent(stop.sound(), stop.category(), targets);
        } else {
            throw new IllegalArgumentException("not a packet event: " + key.type());
        }
    }

    private static String dimension(ServerPlayer player) {
        return player.level().dimension().identifier().toString();
    }
}
