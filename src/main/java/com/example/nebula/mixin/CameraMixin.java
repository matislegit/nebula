package com.example.nebula.mixin;

import com.example.nebula.FreecamModule;
import com.example.nebula.NebulaClient;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** After vanilla positions the camera on the player, freecam moves it somewhere else. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "setup", at = @At("TAIL"))
    private void nebula$freecam(CallbackInfo ci) {
        FreecamModule fc = NebulaClient.FREECAM;
        if (fc != null && fc.isActive()) {
            fc.step(Minecraft.getInstance());
            this.setRotation(fc.yaw(), fc.pitch());
            this.setPosition(fc.camX(), fc.camY(), fc.camZ());
        }
    }
}
