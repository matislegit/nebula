package com.example.nebula.mixin;

import com.example.nebula.NebulaClient;
import com.example.nebula.NametagsModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Nametags module: hides the vanilla name tag of other players, because the module draws its own.
 * require = 0 means that if this method ever can't be found, the game simply keeps the vanilla tags
 * instead of failing to start.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void nebula$hideVanillaTag(LivingEntity entity, double distanceSq, CallbackInfoReturnable<Boolean> cir) {
        NametagsModule nt = NebulaClient.NAMETAGS;
        if (nt != null && nt.isEnabled() && entity instanceof AbstractClientPlayer
                && entity != Minecraft.getInstance().player) {
            cir.setReturnValue(false);
        }
    }
}
