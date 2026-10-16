package net.vulkanmod.vulkan.pass;

import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

public interface MainPass {

    void begin(VkCommandBuffer commandBuffer, MemoryStack stack);

    void end(VkCommandBuffer commandBuffer);

    void cleanUp();

    void onResize();

    default void mainTargetBindWrite() {}

    default void mainTargetUnbindWrite() {}

    default void rebindMainTarget() {}

    default void bindAsTexture() {}

    default Framebuffer getMainFramebuffer() {
        return null;
    }

    /**
     * @return the GL texture wrapping the main framebuffer's current color image
     * (1.21.1 code addresses the main render target through its GL texture id)
     */
    default VkGlTexture getColorAttachment() {
        throw new UnsupportedOperationException();
    }

}
