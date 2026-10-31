package net.rs256.jukebox.mixin;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.rs256.jukebox.server.PacketRecorder;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Taps only the 2-argument {@code send}; the 1-argument one delegates to it. */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {
    @Inject(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
            at = @At("HEAD")
    )
    private void jukebox$recordPacket(Packet<?> packet, @Nullable ChannelFutureListener listener, CallbackInfo ci) {
        PacketRecorder.onSend((ServerCommonPacketListenerImpl) (Object) this, packet);
    }
}
