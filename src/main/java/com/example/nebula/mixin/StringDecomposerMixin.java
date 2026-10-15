package com.example.nebula.mixin;

import com.example.nebula.NameProtectModule;
import com.example.nebula.NameProtectSink;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSink;
import net.minecraft.util.StringDecomposer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every string the game draws or measures is fed through StringDecomposer. Name Protect wraps the receiving
 * sink so the real username is swapped for the fake one on the way through.
 * The check for "does this text even contain the name" is very cheap, so normal text is not slowed down.
 */
@Mixin(StringDecomposer.class)
public abstract class StringDecomposerMixin {

    @Inject(method = "iterateFormatted(Ljava/lang/String;ILnet/minecraft/network/chat/Style;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void nebula$protectFormatted(String text, int startIndex, Style style, Style resetStyle,
                                                FormattedCharSink sink, CallbackInfoReturnable<Boolean> cir) {
        if (sink instanceof NameProtectSink || !NameProtectModule.mayContain(text)) return;
        NameProtectSink wrapped = new NameProtectSink(sink);
        boolean result = StringDecomposer.iterateFormatted(text, startIndex, style, resetStyle, wrapped);
        wrapped.finish();
        cir.setReturnValue(result && !wrapped.isStopped());
    }

    @Inject(method = "iterate(Ljava/lang/String;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void nebula$protectPlain(String text, Style style, FormattedCharSink sink,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (sink instanceof NameProtectSink || !NameProtectModule.mayContain(text)) return;
        NameProtectSink wrapped = new NameProtectSink(sink);
        boolean result = StringDecomposer.iterate(text, style, wrapped);
        wrapped.finish();
        cir.setReturnValue(result && !wrapped.isStopped());
    }
}
