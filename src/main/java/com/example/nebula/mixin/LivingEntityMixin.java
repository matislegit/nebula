package com.example.nebula.mixin;

import com.example.nebula.NebulaClient;
import com.example.nebula.SwingSpeedModule;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Swing Speed: replaces the per-tick swing animation update for the local player with one that uses our swing length. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Shadow public boolean swinging;
    @Shadow public int swingTime;
    @Shadow public float attackAnim;

    @Inject(method = "updateSwingTime", at = @At("HEAD"), cancellable = true)
    private void nebula$swingSpeed(CallbackInfo ci) {
        SwingSpeedModule m = NebulaClient.SWING_SPEED;
        if (m == null || !m.isEnabled() || (Object) this != Minecraft.getInstance().player) return;

        int duration = m.duration();
        if (this.swinging) {
            this.swingTime++;
            if (this.swingTime >= duration) {
                this.swingTime = 0;
                this.swinging = false;
            }
        } else {
            this.swingTime = 0;
        }
        this.attackAnim = (float) this.swingTime / (float) duration;
        ci.cancel();
    }
}
