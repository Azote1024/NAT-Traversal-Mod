package com.azote.nat_traversal_mod.mixin;

import com.azote.nat_traversal_mod.net.ConnectFallbackPolicy;
import com.azote.nat_traversal_mod.NatTraversalMod;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigLoader;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigSnapshot;
import com.azote.nat_traversal_mod.net.QuicDirectConnectorFactory;
import com.azote.nat_traversal_mod.net.routing.QuicDirectRouteContext;
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
    @Unique
    private static final String SERVER_CONNECTOR_THREAD_PREFIX = "Server Connector";

    @Inject(method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;", at = @At("HEAD"), cancellable = true)
    private static void natTraversalMod$connectQuic(InetSocketAddress address, boolean useEpoll, Connection connection, CallbackInfoReturnable<ChannelFuture> cir) {
        if (!Thread.currentThread().getName().startsWith(SERVER_CONNECTOR_THREAD_PREFIX)) {
            return;
        }

        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] Connection.connect hook fired. target='{}:{}', useEpoll={}",
                address.getHostString(),
                address.getPort(),
                useEpoll
        );

        if (!QuicDirectConnectorFactory.isOperational()) {
            QuicDirectRouteContext.clear();
            NatTraversalMod.LOGGER.info("[nat-traversal-mod] QUIC direct connector is not operational. fallback to TCP connect path.");
            return;
        }

        Optional<QuicDirectRouteContext.PendingRoute> pendingRoute = QuicDirectRouteContext.takeIfMatches(address);
        if (pendingRoute.isEmpty()) {
            NatTraversalMod.LOGGER.info("[nat-traversal-mod] No pending QUIC direct route for this target. fallback to TCP connect path.");
            return;
        }

        RuntimeConfigSnapshot runtimeConfig = RuntimeConfigLoader.load();
        if (!runtimeConfig.quicEnabled()) {
            NatTraversalMod.LOGGER.info("[nat-traversal-mod] quic.enabled=false. skip QUIC direct path and keep TCP connect path.");
            return;
        }

        String attemptId = pendingRoute.get().attemptId();
        Optional<ChannelFuture> quicFuture = QuicDirectConnectorFactory.connect(address, useEpoll, connection, attemptId);
        if (quicFuture.isPresent()) {
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] Direct QUIC connect path selected. attempt_id='{}', target='{}:{}'",
                    attemptId,
                    address.getHostString(),
                    address.getPort()
            );
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] route_result='quic_direct_selected' attempt_id='{}' target='{}:{}'",
                    attemptId,
                    address.getHostString(),
                    address.getPort()
            );
            cir.setReturnValue(quicFuture.get());
            return;
        }

        NatTraversalMod.LOGGER.info("[nat-traversal-mod] Direct QUIC connect failed. try relay fallback first.");
        ConnectFallbackPolicy.Decision fallbackDecision = ConnectFallbackPolicy.decide(pendingRoute.get());
        if (fallbackDecision.route() == ConnectFallbackPolicy.Route.RELAY) {
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] QUIC fallback route selected: relay connector target='{}:{}'",
                    fallbackDecision.target().getHostString(),
                    fallbackDecision.target().getPort()
            );
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] route_result='relay_selected' attempt_id='{}' target='{}:{}'",
                    attemptId,
                    fallbackDecision.target().getHostString(),
                    fallbackDecision.target().getPort()
            );
            cir.setReturnValue(Connection.connect(fallbackDecision.target(), useEpoll, connection));
            return;
        }

        if (fallbackDecision.route() == ConnectFallbackPolicy.Route.ORIGINAL_TCP) {
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] QUIC direct failed. fallback to resolved TCP target='{}:{}'",
                    fallbackDecision.target().getHostString(),
                    fallbackDecision.target().getPort()
            );
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] route_result='original_tcp_selected' attempt_id='{}' target='{}:{}'",
                    attemptId,
                    fallbackDecision.target().getHostString(),
                    fallbackDecision.target().getPort()
            );
            cir.setReturnValue(Connection.connect(fallbackDecision.target(), useEpoll, connection));
            return;
        }

        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] route_result='fallback_unavailable' attempt_id='{}' target='{}:{}'",
                attemptId,
                address.getHostString(),
                address.getPort()
        );
        NatTraversalMod.LOGGER.info("[nat-traversal-mod] Relay/TCP fallback unavailable. fallback to TCP connect path.");
    }
}


