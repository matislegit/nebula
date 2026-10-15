package com.example.nebula.mixin;

import com.example.nebula.FreecamModule;
import com.example.nebula.NebulaClient;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Minecraft hides terrain it thinks you can't see ("cave culling"), starting its search from the camera's position.
 * When a freecam camera sits inside solid blocks that search breaks and most of the world disappears, leaving odd
 * fragments. Vanilla already switches the culling off for spectators inside blocks, so while freecam is on we say
 * "spectator" here too.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @ModifyVariable(method = "cullTerrain", at = @At("HEAD"), argsOnly = true)
    private boolean nebula$noCaveCulling(boolean isSpectator) {
        FreecamModule fc = NebulaClient.FREECAM;
        return isSpectator || (fc != null && fc.isActive());
    }
}
