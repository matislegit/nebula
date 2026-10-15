package com.example.nebula.mixin;

import com.example.nebula.FreecamModule;
import com.example.nebula.NebulaClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mouse movement calls Entity.turn on the player. In freecam it turns the camera instead. */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "turn(DD)V", at = @At("HEAD"), cancellable = true)
    private void nebula$turn(double yRot, double xRot, CallbackInfo ci) {
        FreecamModule fc = NebulaClient.FREECAM;
        if (fc != null && fc.isActive() && (Object) this == Minecraft.getInstance().player) {
            fc.onTurn(yRot, xRot);
            ci.cancel();
        }
    }
}
