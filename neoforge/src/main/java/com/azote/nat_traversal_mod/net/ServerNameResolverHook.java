package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.NatTraversalMod;
import com.azote.nat_traversal_mod.config.runtime.ConnectStrategy;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigLoader;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigSnapshot;
import com.azote.nat_traversal_mod.net.routing.QuicDirectRouteContext;
import com.azote.nat_traversal_mod.net.routing.RouteAttemptPlanner;
import com.azote.nat_traversal_mod.net.supabase.SupabaseRoomsClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.net.InetSocketAddress;
import java.util.Optional;

public final class ServerNameResolverHook {
    private static final String SERVER_CONNECTOR_THREAD_PREFIX = "Server Connector";

    private record PlannedStageResult(boolean stopRoomResolve, Optional<ResolvedServerAddress> resolvedAddress) {
    }

    private ServerNameResolverHook() {
    }

    public static Optional<ResolvedServerAddress> resolveAddressOverride(ServerAddress address) {
        // Keep pinger and other non-connect paths out of NAT route orchestration.
        if (!isServerConnectorThread()) {
            return Optional.empty();
        }

        RuntimeConfigSnapshot runtimeConfig = RuntimeConfigLoader.load();
        String interceptRule = runtimeConfig.interceptHost();
        if (interceptRule.isBlank()) {
            return Optional.empty();
        }

        String requestedHost = address.getHost();
        int requestedPort = address.getPort();
        if (!matchesIntercept(interceptRule, requestedHost, requestedPort)) {
            return Optional.empty();
        }

        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] Intercept hit. host='{}', room_name='{}'",
                requestedHost,
                runtimeConfig.roomName()
        );

        if (runtimeConfig.connectStrategy() == ConnectStrategy.TCP_QUIC_RELAY) {
            PlannedStageResult stageResult = handlePlannedStage(requestedHost, requestedPort, runtimeConfig);
            if (stageResult.stopRoomResolve()) {
                return stageResult.resolvedAddress();
            }
        }

        Optional<ResolvedTarget> maybeTarget = SupabaseRoomsClient.resolve();
        if (maybeTarget.isEmpty()) {
            NatTraversalMod.LOGGER.warn(
                    "[nat-traversal-mod] Room resolve failed. host='{}'. Continue with original target.",
                    requestedHost
            );
            notifyPlayerIfConnectAttempt("[NAT] Room resolve failed. Fallback to original target.");
            return Optional.empty();
        }

        return Optional.of(applyResolvedTarget(requestedHost, requestedPort, runtimeConfig, maybeTarget.get()));
    }

    private static PlannedStageResult handlePlannedStage(String requestedHost, int requestedPort, RuntimeConfigSnapshot runtimeConfig) {
        Optional<RouteAttemptPlanner.PlanResult> stagedDecision = planConnectorDecision(runtimeConfig);
        if (stagedDecision.isEmpty()) {
            return new PlannedStageResult(false, Optional.empty());
        }

        RouteAttemptPlanner.PlanResult plan = stagedDecision.get();
        RouteAttemptPlanner.Decision decision = plan.decision();
        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] tcp_quic_relay planner room_name='{}' decision='{}' tcp_used={}/{} quic_used={}/{}",
                runtimeConfig.roomName(),
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
            return new PlannedStageResult(true, Optional.empty());
        }

        if (decision == RouteAttemptPlanner.Decision.RELAY) {
            return new PlannedStageResult(true, applyRelayPlannerResult(plan, runtimeConfig));
        }

        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] tcp_quic_relay stage=quic_direct. (quic_used={}/{})",
                plan.quicAttemptsUsed(),
                plan.quicMaxAttempts()
        );
        return new PlannedStageResult(false, Optional.empty());
    }

    private static Optional<ResolvedServerAddress> applyRelayPlannerResult(RouteAttemptPlanner.PlanResult plan, RuntimeConfigSnapshot runtimeConfig) {
        if (!RelayClientConnectorManager.ensureStarted()) {
            NatTraversalMod.LOGGER.warn(
                    "[nat-traversal-mod] tcp_quic_relay stage=relay but relay connector unavailable. fallback to original target."
            );
            return Optional.empty();
        }

        InetSocketAddress relayTarget = new InetSocketAddress("127.0.0.1", runtimeConfig.relayClientLocalPort());
        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] tcp_quic_relay stage=relay. route='{}:{}' (tcp_used={}/{} quic_used={}/{})",
                relayTarget.getHostString(),
                relayTarget.getPort(),
                plan.tcpAttemptsUsed(),
                plan.tcpMaxAttempts(),
                plan.quicAttemptsUsed(),
                plan.quicMaxAttempts()
        );
        return Optional.of(ResolvedServerAddress.from(relayTarget));
    }

    private static Optional<RouteAttemptPlanner.PlanResult> planConnectorDecision(RuntimeConfigSnapshot runtimeConfig) {
        if (!isServerConnectorThread()) {
            return Optional.empty();
        }
        return Optional.of(RouteAttemptPlanner.planForConnectorDetailed(
                runtimeConfig.roomName(),
                runtimeConfig.tcpAttempts(),
                runtimeConfig.quicAttempts(),
                runtimeConfig.routeStageResetMs()
        ));
    }

    private static ResolvedServerAddress applyResolvedTarget(
            String requestedHost,
            int requestedPort,
            RuntimeConfigSnapshot runtimeConfig,
            ResolvedTarget target
    ) {
        String connectHost = target.hostIp();
        boolean forceLocalhost = runtimeConfig.debugForceLocalhost();
        if (forceLocalhost || isLoopbackHost(requestedHost)) {
            connectHost = "127.0.0.1";
        }

        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] Resolved room target. {}:{} (debug_force_localhost={})",
                connectHost,
                target.hostPort(),
                forceLocalhost
        );

        String attemptId = QuicDirectRouteContext.currentAttemptId().orElse("");
        InetSocketAddress fallbackTarget = new InetSocketAddress(requestedHost, requestedPort);
        QuicDirectRouteContext.set(new RelayEndpoint(connectHost, target.hostPort()), attemptId, fallbackTarget);

        notifyPlayerIfConnectAttempt("[NAT] Route resolved: " + connectHost + ":" + target.hostPort());
        return ResolvedServerAddress.from(new InetSocketAddress(connectHost, target.hostPort()));
    }

    private static boolean matchesIntercept(String interceptRule, String requestedHost, int requestedPort) {
        String ruleHost = interceptRule;
        int rulePort = -1;

        int splitIndex = interceptRule.lastIndexOf(':');
        if (splitIndex > 0 && splitIndex < interceptRule.length() - 1) {
            String maybePort = interceptRule.substring(splitIndex + 1).trim();
            int parsedPort = parsePortOrMinusOne(maybePort);
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

    private static int parsePortOrMinusOne(String text) {
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

    private static boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    private static void notifyPlayerIfConnectAttempt(String message) {
        if (!isServerConnectorThread()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal(message), false);
            }
        });
    }

    private static boolean isServerConnectorThread() {
        return Thread.currentThread().getName().startsWith(SERVER_CONNECTOR_THREAD_PREFIX);
    }
}


