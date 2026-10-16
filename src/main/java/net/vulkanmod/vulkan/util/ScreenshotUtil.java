package net.vulkanmod.vulkan.util;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.mixin.texture.image.NativeImageAccessor;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

public abstract class ScreenshotUtil {

    /**
     * 1.21.1: Screenshot.takeScreenshot(RenderTarget) is synchronous and returns a NativeImage,
     * so we read the Vulkan image back directly instead of going through a command encoder.
     */
    public static NativeImage takeScreenshot(RenderTarget target) {
        int width = target.width;
        int height = target.height;

        VkGlTexture glTexture = VkGlTexture.getTexture(target.getColorTextureId());
        if (glTexture == null || glTexture.getVulkanImage() == null) {
            throw new IllegalStateException("Tried to capture screenshot of an incomplete framebuffer");
        }

        VulkanImage image = glTexture.getVulkanImage();

        NativeImage nativeImage = new NativeImage(width, height, false);
        long pixels = ((NativeImageAccessor) (Object) nativeImage).getPixels();

        // Need to submit and wait cmds if screenshot was requested
        // before the end of the frame
        Renderer.getInstance().flushCmds();

        ImageUtil.downloadTexture(image, pixels);

        boolean isBgraFormat = image.format == VK10.VK_FORMAT_B8G8R8A8_UNORM;

        // Screenshots are opaque: swap channels if needed and force alpha to 0xFF
        for (long offset = 0; offset < (long) width * height * 4L; offset += 4) {
            int color = MemoryUtil.memGetInt(pixels + offset);

            if (isBgraFormat) {
                color = ColorUtil.BGRAtoRGBA(color);
            }

            MemoryUtil.memPutInt(pixels + offset, color | 0xFF000000);
        }

        return nativeImage;
    }
}
