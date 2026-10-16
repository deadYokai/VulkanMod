package net.vulkanmod.mixin.render.clouds;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.render.profiling.Profiler;
import net.vulkanmod.render.sky.CloudRenderer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererM {

    @Shadow private int ticks;
    @Shadow private @Nullable ClientLevel level;

    @Unique
    private CloudRenderer vmCloudRenderer;

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    public void renderClouds(PoseStack poseStack, Matrix4f modelView, Matrix4f projection, float partialTicks, double camX, double camY, double camZ, CallbackInfo ci) {
        ci.cancel();

        if (this.level == null) {
            return;
        }

        float cloudHeight = this.level.effects().getCloudHeight();
        if (Float.isNaN(cloudHeight)) {
            return;
        }

        if (this.vmCloudRenderer == null) {
            this.vmCloudRenderer = new CloudRenderer();
        }

        Vec3 color = this.level.getCloudColor(partialTicks);
        int cloudColor = 0xFF000000
                | (to8Bit(color.x) << 16)
                | (to8Bit(color.y) << 8)
                | to8Bit(color.z);

        FogRenderer.levelFogColor();

        // 1.21.1 hands the camera rotation and projection in as arguments, whereas the VulkanMod
        // cloud renderer reads them from RenderSystem's model-view stack and VRenderSystem.
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        modelViewStack.mul(modelView);
        VRenderSystem.applyProjectionMatrix(projection);

        Profiler profiler = Profiler.getMainProfiler();
        profiler.push("Clouds");

        try {
            this.vmCloudRenderer.renderClouds(cloudHeight, cloudColor, camX, camY, camZ, this.ticks, partialTicks);
        } finally {
            profiler.pop();

            modelViewStack.popMatrix();
            RenderSystem.applyModelViewMatrix();

            VRenderSystem.applyProjectionMatrix(RenderSystem.getProjectionMatrix());
            VRenderSystem.calculateMVP();
        }
    }

    @Unique
    private static int to8Bit(double v) {
        return Math.max(0, Math.min(255, (int) Math.round(v * 255.0)));
    }

    @Inject(method = "allChanged", at = @At("RETURN"))
    private void onAllChanged(CallbackInfo ci) {
        if (this.vmCloudRenderer != null) {
            this.vmCloudRenderer.resetBuffer();
        }
    }

    @Inject(method = "onResourceManagerReload", at = @At("RETURN"))
    private void onReload(ResourceManager resourceManager, CallbackInfo ci) {
        if (this.vmCloudRenderer != null) {
            this.vmCloudRenderer.loadTexture();
        }
    }

}
