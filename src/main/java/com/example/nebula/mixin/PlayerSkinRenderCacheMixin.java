package com.example.nebula.mixin;

import com.example.nebula.SkinProtectModule;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Grabs the game's skin cache when it is created, so Skin Protect can use it. */
@Mixin(PlayerSkinRenderCache.class)
public abstract class PlayerSkinRenderCacheMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void nebula$capture(CallbackInfo ci) {
        SkinProtectModule.cache = (PlayerSkinRenderCache) (Object) this;
    }
}
