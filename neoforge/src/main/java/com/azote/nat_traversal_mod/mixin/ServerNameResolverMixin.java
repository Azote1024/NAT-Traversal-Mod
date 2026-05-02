package com.azote.nat_traversal_mod.mixin;

import com.azote.nat_traversal_mod.net.routing.QuicDirectRouteContext;
import com.azote.nat_traversal_mod.net.ServerNameResolverHook;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(ServerNameResolver.class)
public class ServerNameResolverMixin {
    @Inject(method = "resolveAddress", at = @At("HEAD"), cancellable = true)
    private void natTraversalMod$resolveAddress(ServerAddress address, CallbackInfoReturnable<Optional<ResolvedServerAddress>> cir) {
        QuicDirectRouteContext.clear();
        ServerNameResolverHook.resolveAddressOverride(address).ifPresent(resolved -> cir.setReturnValue(Optional.of(resolved)));
    }
}

