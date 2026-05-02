package com.azote.nat_traversal_mod.mixin;

import com.azote.nat_traversal_mod.Config;
import com.azote.nat_traversal_mod.NatTraversalMod;
import com.azote.nat_traversal_mod.net.RelayEndpoint;
import com.azote.nat_traversal_mod.net.QuicDirectRouteContext;
import com.azote.nat_traversal_mod.net.RouteAttemptPlanner;
import com.azote.nat_traversal_mod.net.RelayClientConnectorManager;
import com.azote.nat_traversal_mod.net.ResolvedTarget;
import com.azote.nat_traversal_mod.net.SupabaseRoomsClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.InetSocketAddress;
import java.util.Optional;

@Mixin(ServerNameResolver.class)
public class ServerNameResolverMixin {
    private static final String SERVER_CONNECTOR_THREAD_PREFIX = "Server Connector";

    @Inject(method = "resolveAddress", at = @At("HEAD"), cancellable = true)
    private void natTraversalMod$resolveAddress(ServerAddress address, CallbackInfoReturnable<Optional<ResolvedServerAddress>> cir) {
        QuicDirectRouteContext.clear();

        String interceptRule = Config.interceptHost();
        if (interceptRule.isBlank()) {
            return;
        }

        String requestedHost = address.getHost();
        int requestedPort = address.getPort();
        if (!natTraversalMod$matchesIntercept(interceptRule, requestedHost, requestedPort)) {
            return;
        }

        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] Intercept hit. host='{}', room_name='{}'",
                requestedHost,
                Config.roomName()
        );

        if (Config.tcpQuicRelayMode() && natTraversalMod$handlePlannedStage(cir, requestedHost, requestedPort)) {
            return;
        }

        Optional<ResolvedTarget> maybeTarget = SupabaseRoomsClient.resolve();
        if (maybeTarget.isEmpty()) {
            NatTraversalMod.LOGGER.warn(
                    "[nat-traversal-mod] Room resolve failed. host='{}'. Continue with original target.",
                    requestedHost
            );
            natTraversalMod$notifyPlayerIfConnectAttempt("[NAT] Room resolve failed. Fallback to original target.");
            return;
        }

        natTraversalMod$applyResolvedTarget(cir, requestedHost, requestedPort, maybeTarget.get());
    }

    @Unique
    private static boolean natTraversalMod$handlePlannedStage(
            CallbackInfoReturnable<Optional<ResolvedServerAddress>> cir,
            String requestedHost,
            int requestedPort
    ) {
        Optional<RouteAttemptPlanner.PlanResult> stagedDecision = natTraversalMod$planConnectorDecision();
        if (stagedDecision.isEmpty()) {
            return false;
        }

        RouteAttemptPlanner.PlanResult plan = stagedDecision.get();
        RouteAttemptPlanner.Decision decision = plan.decision();
        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] tcp_quic_relay planner room_name='{}' decision='{}' tcp_used={}/{} quic_used={}/{}",
                Config.roomName(),
                decision,
                plan.tcpAttemptsUsed(),
                plan.tcpMaxAttempts(),
                plan.quicAttemptsUsed(),
                plan.quicMaxAttempts()
        );

        if (decision == RouteAttemptPlanner.Decision.TCP_DIRECT) {
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] tcp_quic_relay stage=tcp_direct. keep original target='{}:{}' (tcp_used={}/{})",
                    requestedHost,
                    requestedPort,
                    plan.tcpAttemptsUsed(),
                    plan.tcpMaxAttempts()
            );
            return true;
        }

        if (decision == RouteAttemptPlanner.Decision.RELAY) {
            natTraversalMod$applyRelayPlannerResult(cir, plan);
            return true;
        }

        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] tcp_quic_relay stage=quic_direct. (quic_used={}/{})",
                plan.quicAttemptsUsed(),
                plan.quicMaxAttempts()
        );
        return false;
    }

    @Unique
    private static void natTraversalMod$applyRelayPlannerResult(
            CallbackInfoReturnable<Optional<ResolvedServerAddress>> cir,
            RouteAttemptPlanner.PlanResult plan
    ) {
        if (!RelayClientConnectorManager.ensureStarted()) {
            NatTraversalMod.LOGGER.warn(
                    "[nat-traversal-mod] tcp_quic_relay stage=relay but relay connector unavailable. fallback to original target."
            );
            return;
        }

        int relayPort = Config.relayClientLocalPort();
        InetSocketAddress relayTarget = new InetSocketAddress("127.0.0.1", relayPort);
        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] tcp_quic_relay stage=relay. route='{}:{}' (tcp_used={}/{} quic_used={}/{})",
                relayTarget.getHostString(),
                relayTarget.getPort(),
                plan.tcpAttemptsUsed(),
                plan.tcpMaxAttempts(),
                plan.quicAttemptsUsed(),
                plan.quicMaxAttempts()
        );
        cir.setReturnValue(Optional.of(ResolvedServerAddress.from(relayTarget)));
    }

    @Unique
    private static Optional<RouteAttemptPlanner.PlanResult> natTraversalMod$planConnectorDecision() {
        if (!natTraversalMod$isServerConnectorThread()) {
            return Optional.empty();
        }
        RouteAttemptPlanner.PlanResult decision = RouteAttemptPlanner.planForConnectorDetailed(
                Config.roomName(),
                Config.tcpAttempts(),
                Config.quicAttempts(),
                Config.routeStageResetMs()
        );
        return Optional.of(decision);
    }

    @Unique
    private static boolean natTraversalMod$isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    @Unique
    private static boolean natTraversalMod$matchesIntercept(String interceptRule, String requestedHost, int requestedPort) {
        String ruleHost = interceptRule;
        int rulePort = -1;

        int splitIndex = interceptRule.lastIndexOf(':');
        if (splitIndex > 0 && splitIndex < interceptRule.length() - 1) {
            String maybePort = interceptRule.substring(splitIndex + 1).trim();
            int parsedPort = natTraversalMod$parsePortOrMinusOne(maybePort);
            if (parsedPort != -1) {
                ruleHost = interceptRule.substring(0, splitIndex).trim();
                rulePort = parsedPort;
            }
        }

        if (!ruleHost.equals(requestedHost)) {
            return false;
        }

        return rulePort == -1 || rulePort == requestedPort;
    }

    @Unique
    private static void natTraversalMod$notifyPlayerIfConnectAttempt(String message) {
        if (!natTraversalMod$isServerConnectorThread()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal(message), false);
            }
        });
    }

    @Unique
    private static void natTraversalMod$applyResolvedTarget(
            CallbackInfoReturnable<Optional<ResolvedServerAddress>> cir,
            String requestedHost,
            int requestedPort,
            ResolvedTarget target
    ) {
        String connectHost = target.hostIp();
        if (natTraversalMod$isLoopbackHost(requestedHost)) {
            // Same-PC runClient mode should avoid public-IP hairpin and connect directly to local QUIC bind.
            connectHost = "127.0.0.1";
        }

        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] Resolved room target. {}:{}",
                connectHost,
                target.hostPort()
        );

        String attemptId = QuicDirectRouteContext.currentAttemptId().orElse("");
        InetSocketAddress fallbackTarget = new InetSocketAddress(requestedHost, requestedPort);
        QuicDirectRouteContext.set(new RelayEndpoint(connectHost, target.hostPort()), attemptId, fallbackTarget);

        natTraversalMod$notifyPlayerIfConnectAttempt("[NAT] Route resolved: " + connectHost + ":" + target.hostPort());
        ResolvedServerAddress resolvedAddress = ResolvedServerAddress.from(new InetSocketAddress(connectHost, target.hostPort()));
        cir.setReturnValue(Optional.of(resolvedAddress));
    }

    @Unique
    private static int natTraversalMod$parsePortOrMinusOne(String text) {
        if (text.isBlank() || !text.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        try {
            int parsedPort = Integer.parseInt(text);
            return parsedPort >= 1 && parsedPort <= 65535 ? parsedPort : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    @Unique
    private static boolean natTraversalMod$isServerConnectorThread() {
        return Thread.currentThread().getName().startsWith(SERVER_CONNECTOR_THREAD_PREFIX);
    }
}

