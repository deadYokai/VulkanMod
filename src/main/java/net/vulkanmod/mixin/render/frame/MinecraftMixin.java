package net.vulkanmod.mixin.render.frame;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Shadow public boolean noRender;

    /**
     * Renderer.beginFrame() already runs preInitFrame() and handles nested runTick calls.
     * The vanilla RenderSystem.clear(...) that follows the tick logic is left alone: it reaches
     * Renderer.clearAttachments() through the GlStateManager mixin.
     */
    @Inject(method = "runTick", at = @At(value = "HEAD"))
    private void preFrameOps(boolean bl, CallbackInfo ci) {
        Renderer.getInstance().beginFrame();
    }

    // Main target (framebuffer) ops
    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;bindWrite(Z)V"))
    private void redirectMainTarget1(RenderTarget instance, boolean bl) {
        Renderer.getInstance().getMainPass().mainTargetBindWrite();
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;unbindWrite()V"))
    private void redirectMainTarget2(RenderTarget instance) {
        Renderer.getInstance().getMainPass().mainTargetUnbindWrite();
    }

    // Presentation is done by the swapchain, there is no blit to the screen
    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"))
    private void removeBlit(RenderTarget instance, int i, int j) {
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;yield()V"))
    private void removeThreadYield() {
    }

    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
    private void limitWhenMinimized(CallbackInfoReturnable<Integer> cir) {
        if (this.noRender)
            cir.setReturnValue(10);
    }
}
