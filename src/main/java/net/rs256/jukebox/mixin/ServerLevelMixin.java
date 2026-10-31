package net.rs256.jukebox.mixin;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.rs256.jukebox.server.PacketRecorder;
import net.rs256.jukebox.server.ServerRecorder;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records both {@code playSeededSound} overloads at HEAD, before the broadcast range check.
 * Their packets are ignored by the {@link PacketRecorder} until RETURN.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(
            method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD")
    )
    private void jukebox$recordPositionedSound(
            @Nullable Entity except, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source,
            float volume, float pitch, long seed, CallbackInfo ci
    ) {
        ServerRecorder.recordSound((ServerLevel) (Object) this, x, y, z, sound, source, volume, pitch, seed, null);
    }

    /** The position is where the entity is now; v1 does not follow it afterwards. */
    @Inject(
            method = "playSeededSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD")
    )
    private void jukebox$recordEntitySound(
            @Nullable Entity except, Entity entity, Holder<SoundEvent> sound, SoundSource source,
            float volume, float pitch, long seed, CallbackInfo ci
    ) {
        ServerRecorder.recordSound((ServerLevel) (Object) this, entity.getX(), entity.getY(), entity.getZ(), sound, source, volume, pitch, seed, entity.getId());
    }

    @Inject(
            method = {
                    "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
                    "playSeededSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V"
            },
            at = @At("RETURN")
    )
    private void jukebox$exitSound(CallbackInfo ci) {
        PacketRecorder recorder = ServerRecorder.packetRecorder();
        if (recorder != null) {
            recorder.exitLevelSound();
        }
    }
}
