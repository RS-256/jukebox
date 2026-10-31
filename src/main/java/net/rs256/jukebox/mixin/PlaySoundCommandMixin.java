package net.rs256.jukebox.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.commands.PlaySoundCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.rs256.jukebox.server.PacketRecorder;
import net.rs256.jukebox.server.ServerRecorder;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Collection;

/**
 * Gives the {@link PacketRecorder} the raw position, volume and {@code minVolume} of
 * {@code /playsound}, whose packets are relocated next to players out of range.
 */
@Mixin(PlaySoundCommand.class)
public abstract class PlaySoundCommandMixin {
    @WrapMethod(method = "playSound")
    private static int jukebox$playSound(
            CommandSourceStack source, Collection<ServerPlayer> targets, Identifier sound, SoundSource category,
            Vec3 pos, float volume, float pitch, float minVolume, Operation<Integer> original
    ) {
        PacketRecorder recorder = ServerRecorder.packetRecorder();
        if (recorder == null) {
            return original.call(source, targets, sound, category, pos, volume, pitch, minVolume);
        }

        recorder.enterPlaySound(source.getLevel(), pos, volume, minVolume);
        try {
            return original.call(source, targets, sound, category, pos, volume, pitch, minVolume);
        } finally {
            recorder.exitPlaySound();
        }
    }
}
