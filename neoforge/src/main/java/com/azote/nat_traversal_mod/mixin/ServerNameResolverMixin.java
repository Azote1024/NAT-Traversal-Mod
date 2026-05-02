package com.azote.nat_traversal_mod.mixin;

import com.azote.nat_traversal_mod.Config;
import com.azote.nat_traversal_mod.Nat_traversal_mod;
import com.azote.nat_traversal_mod.net.ConnectionTargetResolver;
import com.azote.nat_traversal_mod.net.QuicDirectRouteContext;
import com.azote.nat_traversal_mod.net.ResolvedTarget;
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

        Nat_traversal_mod.LOGGER.info(
                "[nat-traversal-mod] Intercept hit. host='{}', room_name='{}'",
                requestedHost,
                Config.roomName()
        );

        Optional<ResolvedTarget> maybeTarget = ConnectionTargetResolver.resolve();
        if (maybeTarget.isEmpty()) {
            Nat_traversal_mod.LOGGER.warn(
                    "[nat-traversal-mod] Room resolve failed. host='{}'. Continue with original target.",
                    requestedHost
            );
            natTraversalMod$notifyPlayerIfConnectAttempt("[NAT] Room resolve failed. Fallback to original target.");
            return;
        }

        ResolvedTarget target = maybeTarget.get();
        String connectHost = target.hostIp();
        if (natTraversalMod$isLoopbackHost(requestedHost)) {
            // Same-PC runClient mode should avoid public-IP hairpin and connect directly to local QUIC bind.
            connectHost = "127.0.0.1";
        }
        ResolvedServerAddress resolvedAddress = ResolvedServerAddress.from(new InetSocketAddress(connectHost, target.hostPort()));

        Nat_traversal_mod.LOGGER.info(
                "[nat-traversal-mod] Resolved room target. {}:{}",
                connectHost,
                target.hostPort()
        );

        natTraversalMod$notifyPlayerIfConnectAttempt("[NAT] Route resolved: " + connectHost + ":" + target.hostPort());
        cir.setReturnValue(Optional.of(resolvedAddress));
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
            if (maybePort.chars().allMatch(Character::isDigit)) {
                try {
                    int parsedPort = Integer.parseInt(maybePort);
                    if (parsedPort >= 1 && parsedPort <= 65535) {
                        ruleHost = interceptRule.substring(0, splitIndex).trim();
                        rulePort = parsedPort;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (!ruleHost.equals(requestedHost)) {
            return false;
        }

        return rulePort == -1 || rulePort == requestedPort;
    }

    @Unique
    private static void natTraversalMod$notifyPlayerIfConnectAttempt(String message) {
        if (!Thread.currentThread().getName().startsWith("Server Connector")) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal(message), false);
            }
        });
    }
}
