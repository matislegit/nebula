package com.example.nebula.mixin;

import com.example.nebula.NebulaClient;
import com.example.nebula.SkinProtectModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Skin Protect: your own character is drawn with the chosen skin instead of your real one. */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
    private void nebula$skinProtect(CallbackInfoReturnable<PlayerSkin> cir) {
        SkinProtectModule sp = NebulaClient.SKIN_PROTECT;
        if (sp != null && sp.isEnabled() && (Object) this == Minecraft.getInstance().player) {
            PlayerSkin skin = sp.currentSkin();
            if (skin != null) cir.setReturnValue(skin);
        }
    }
}
