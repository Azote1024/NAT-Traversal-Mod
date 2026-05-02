package com.azote.nat_traversal_mod.mixin;

import com.azote.nat_traversal_mod.Config;
import com.azote.nat_traversal_mod.Nat_traversal_mod;
import com.azote.nat_traversal_mod.net.QuicDirectConnectorFactory;
import com.azote.nat_traversal_mod.net.QuicDirectRouteContext;
import com.azote.nat_traversal_mod.net.RelayClientConnectorManager;
import io.netty.channel.ChannelFuture;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.InetSocketAddress;
import java.util.Optional;

@Mixin(Connection.class)
public class ConnectionQuicConnectMixin {
    @Inject(method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;", at = @At("HEAD"), cancellable = true)
    private static void natTraversalMod$connectQuic(InetSocketAddress address, boolean useEpoll, Connection connection, CallbackInfoReturnable<ChannelFuture> cir) {
        Nat_traversal_mod.LOGGER.info(
                "[nat-traversal-mod] Connection.connect hook fired. target='{}:{}', useEpoll={}",
                address.getHostString(),
                address.getPort(),
                useEpoll
        );

        if (!QuicDirectConnectorFactory.isOperational()) {
            QuicDirectRouteContext.clear();
            Nat_traversal_mod.LOGGER.info("[nat-traversal-mod] QUIC direct connector is not operational. fallback to TCP connect path.");
            return;
        }

        if (QuicDirectRouteContext.takeIfMatches(address).isEmpty()) {
            Nat_traversal_mod.LOGGER.info("[nat-traversal-mod] No pending QUIC direct route for this target. fallback to TCP connect path.");
            return;
        }

        Optional<ChannelFuture> quicFuture = QuicDirectConnectorFactory.connect(address, useEpoll, connection);
        if (quicFuture.isPresent()) {
            Nat_traversal_mod.LOGGER.info("[nat-traversal-mod] Direct QUIC connect path selected. target='{}:{}'", address.getHostString(), address.getPort());
            cir.setReturnValue(quicFuture.get());
            return;
        }

        Nat_traversal_mod.LOGGER.info("[nat-traversal-mod] Direct QUIC connect failed. try relay fallback first.");
        if (natTraversalMod$tryRelayFallback(useEpoll, connection, cir)) {
            return;
        }

        Nat_traversal_mod.LOGGER.info("[nat-traversal-mod] Relay fallback unavailable. fallback to TCP connect path.");
    }

    @Unique
    private static boolean natTraversalMod$tryRelayFallback(boolean useEpoll, Connection connection, CallbackInfoReturnable<ChannelFuture> cir) {
        if (!RelayClientConnectorManager.ensureStarted()) {
            Nat_traversal_mod.LOGGER.info("[nat-traversal-mod] Relay fallback is unavailable: local relay client connector is not ready.");
            return false;
        }

        int relayPort = Config.relayClientLocalPort();
        InetSocketAddress relayTarget = new InetSocketAddress("127.0.0.1", relayPort);
        Nat_traversal_mod.LOGGER.info(
                "[nat-traversal-mod] QUIC fallback route selected: relay connector target='{}:{}'",
                relayTarget.getHostString(),
                relayTarget.getPort()
        );

        cir.setReturnValue(Connection.connect(relayTarget, useEpoll, connection));
        return true;
    }
}

