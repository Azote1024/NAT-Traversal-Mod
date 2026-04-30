package com.azote.nat_traversal_mod.mixin;

import com.azote.nat_traversal_mod.Config;
import com.azote.nat_traversal_mod.Nat_traversal_mod;
import com.azote.nat_traversal_mod.net.ConnectionTargetResolver;
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
        String interceptHost = Config.interceptHost();
        if (interceptHost.isBlank()) {
            return;
        }

        String requestedHost = address.getHost();
        if (!interceptHost.equals(requestedHost)) {
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
        ResolvedServerAddress resolvedAddress = ResolvedServerAddress.from(new InetSocketAddress(target.hostIp(), target.hostPort()));

        Nat_traversal_mod.LOGGER.info(
                "[nat-traversal-mod] Resolved room target. {}:{}",
                target.hostIp(),
                target.hostPort()
        );

        natTraversalMod$notifyPlayerIfConnectAttempt("[NAT] Route resolved: " + target.hostIp() + ":" + target.hostPort());
        cir.setReturnValue(Optional.of(resolvedAddress));
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
