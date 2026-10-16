package net.vulkanmod.vulkan.pass;

import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;

import java.util.List;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

public class DefaultMainPass implements MainPass {

    public static DefaultMainPass create() {
        return new DefaultMainPass();
    }

    private Framebuffer mainFramebuffer;

    private RenderPass mainRenderPass;
    private RenderPass auxRenderPass;

    // One GL texture per swapchain image (or a single one for the offscreen fallback framebuffer)
    private VkGlTexture[] colorAttachmentTextures;

    DefaultMainPass() {
        createResources();
    }

    private void createResources() {
        if (this.mainFramebuffer != null) {
            if (this.mainFramebuffer != Renderer.getInstance()
                                                .getSwapChain()) {
                this.mainFramebuffer.cleanUp(true);
            }

            this.mainRenderPass.cleanUp();
            this.auxRenderPass.cleanUp();
        }

        Framebuffer framebuffer;
        if (Renderer.getInstance().getSwapChain().hasImages()) {
            framebuffer = Renderer.getInstance().getSwapChain();
        }
        else {
            framebuffer = Framebuffer.builder(10, 10, 1, true)
                                     .build();
        }

        this.mainFramebuffer = framebuffer;

        createColorAttachmentTextures();
        createRenderPasses();
    }

    private void createColorAttachmentTextures() {
        List<VulkanImage> images;
        if (this.mainFramebuffer instanceof SwapChain swapChain) {
            images = swapChain.getImages();
        }
        else {
            images = List.of(this.mainFramebuffer.getColorAttachment());
        }

        this.colorAttachmentTextures = new VkGlTexture[images.size()];

        for (int i = 0; i < images.size(); ++i) {
            int id = VkGlTexture.genTextureId();
            VkGlTexture.bindIdToImage(id, images.get(i));

            this.colorAttachmentTextures[i] = VkGlTexture.getTexture(id);
        }
    }

    @Override
    public VkGlTexture getColorAttachment() {
        int index = this.mainFramebuffer instanceof SwapChain ? Renderer.getCurrentImage() : 0;
        index = Math.min(index, this.colorAttachmentTextures.length - 1);

        return this.colorAttachmentTextures[index];
    }

    private void createRenderPasses() {
        RenderPass.Builder builder = RenderPass.builder(this.mainFramebuffer);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);

        this.mainRenderPass = builder.build();

        // Create an auxiliary RenderPass needed in case of main target rebinding
        builder = RenderPass.builder(this.mainFramebuffer);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

        this.auxRenderPass = builder.build();
    }

    @Override
    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
        Framebuffer framebuffer = this.mainFramebuffer;

        VulkanImage colorAttachment = framebuffer.getColorAttachment();
        colorAttachment.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

        Renderer.getInstance().beginRenderPass(this.mainRenderPass, framebuffer);

        Renderer.setViewport(0, 0, framebuffer.getWidth(), framebuffer.getHeight(), stack);

        VkRect2D.Buffer pScissor = framebuffer.scissor(stack);
        vkCmdSetScissor(commandBuffer, 0, pScissor);
    }

    @Override
    public void end(VkCommandBuffer commandBuffer) {
        Renderer.getInstance().endRenderPass(commandBuffer);

        if (this.mainFramebuffer == Renderer.getInstance().getSwapChain()) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                this.mainFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            }
        }

        int result = vkEndCommandBuffer(commandBuffer);
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Failed to record command buffer:" + result);
        }
    }

    @Override
    public void cleanUp() {
        this.mainRenderPass.cleanUp();
        this.auxRenderPass.cleanUp();
    }

    @Override
    public void onResize() {
        createResources();
    }

    public void rebindMainTarget() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        // Do not rebind if the framebuffer is already bound
        RenderPass boundRenderPass = Renderer.getInstance().getBoundRenderPass();
        if (boundRenderPass == this.mainRenderPass || boundRenderPass == this.auxRenderPass)
            return;

        Renderer.getInstance().endRenderPass(commandBuffer);
        Renderer.getInstance().beginRenderPass(this.auxRenderPass, this.mainFramebuffer);
    }

    @Override
    public void bindAsTexture() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        // Check if render pass is using the framebuffer
        RenderPass boundRenderPass = Renderer.getInstance().getBoundRenderPass();
        if (boundRenderPass == this.mainRenderPass || boundRenderPass == this.auxRenderPass)
            Renderer.getInstance().endRenderPass(commandBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.mainFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        VTextureSelector.bindTexture(this.mainFramebuffer.getColorAttachment());
    }

    @Override
    public Framebuffer getMainFramebuffer() {
        return mainFramebuffer;
    }
}
