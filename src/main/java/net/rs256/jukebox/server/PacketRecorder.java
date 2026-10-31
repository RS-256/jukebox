package net.rs256.jukebox.server;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Records packets sent to players that the world hooks do not see. Packets of one tick with the same
 * content become one event whose {@code targets} are the receivers; they are written when the tick
 * ends. Server thread only.
 * <p>
 * Sound packets: those sent directly, without {@code ServerLevel#playSeededSound} ({@code /playsound},
 * the raid horn, mods). Packets with the same sound, seed and position become one {@code sound} event.
 */
public final class PacketRecorder {
    private final DiscWriter writer;
    private boolean inLevelSound;
    private @Nullable PlaySound playSound;
    /** Keys are the events without {@code targets}. */
    private final Map<JukeboxEvent.SoundEvent, Set<UUID>> pending = new LinkedHashMap<>();

    /** The raw arguments of a running {@code /playsound}; its packets carry relocated values. */
    private record PlaySound(String dimension, Vec3 pos, float volume, float minVolume) {
    }

    PacketRecorder(DiscWriter writer) {
        this.writer = writer;
    }

    /** Called for every packet sent to a client, before it is sent. */
    public static void onSend(ServerCommonPacketListenerImpl listener, Packet<?> packet) {
        if (!(packet instanceof ClientboundSoundPacket) && !(packet instanceof ClientboundSoundEntityPacket)) {
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
        for (Map.Entry<JukeboxEvent.SoundEvent, Set<UUID>> entry : this.pending.entrySet()) {
            JukeboxEvent.SoundEvent key = entry.getKey();
            this.writer.add(new JukeboxEvent.SoundEvent(
                    key.sound(), key.category(), key.dimension(),
                    key.x(), key.y(), key.z(),
                    key.volume(), key.pitch(), key.seed(),
                    key.entity(),
                    new ArrayList<>(entry.getValue()),
                    key.minVolume(),
                    key.range()
            ));
        }
        this.pending.clear();
        this.inLevelSound = false;
        this.playSound = null;
    }

    private void add(ServerPlayer player, Packet<?> packet) {
        if (this.inLevelSound) {
            return;
        }
        JukeboxEvent.SoundEvent key;
        if (packet instanceof ClientboundSoundPacket sound) {
            key = this.positionedKey(player, sound);
        } else if (packet instanceof ClientboundSoundEntityPacket sound) {
            key = this.entityKey(player, sound);
        } else {
            return;
        }
        if (key != null) {
            this.pending.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(player.getUUID());
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

    private static String dimension(ServerPlayer player) {
        return player.level().dimension().identifier().toString();
    }
}
