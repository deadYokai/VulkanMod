package net.vulkanmod.mixin.util;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;
import net.vulkanmod.vulkan.util.ScreenshotUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screenshot.class)
public class ScreenshotMixin {

    @Inject(method = "takeScreenshot(Lcom/mojang/blaze3d/pipeline/RenderTarget;)Lcom/mojang/blaze3d/platform/NativeImage;",
            at = @At("HEAD"), cancellable = true)
    private static void takeScreenshot(RenderTarget renderTarget, CallbackInfoReturnable<NativeImage> cir) {
        cir.setReturnValue(ScreenshotUtil.takeScreenshot(renderTarget));
    }

}
