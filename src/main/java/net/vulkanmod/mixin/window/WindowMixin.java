package net.vulkanmod.mixin.window;


import com.mojang.blaze3d.platform.*;
import com.mojang.blaze3d.systems.RenderSystem;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.Platform;
import net.vulkanmod.config.video.VideoModeManager;
import net.vulkanmod.config.option.Options;
import net.vulkanmod.config.video.VideoModeSet;
import net.vulkanmod.config.video.WindowMode;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.glfw.GLFW.*;

@Mixin(Window.class)
public abstract class WindowMixin {
    @Final @Shadow private long window;

    @Shadow private boolean vsync;
    @Shadow private boolean fullscreen;

    @Shadow @Final private static Logger LOGGER;

    @Shadow private int windowedX;
    @Shadow private int windowedY;
    @Shadow private int windowedWidth;
    @Shadow private int windowedHeight;
    @Shadow private int x;
    @Shadow private int y;
    @Shadow private int width;
    @Shadow private int height;

    @Shadow private int framebufferWidth;
    @Shadow private int framebufferHeight;

    @Shadow public abstract int getWidth();

    @Shadow public abstract int getHeight();

    @Shadow protected abstract void updateFullscreen(boolean bl);


    @Unique private boolean wasOnFullscreen = false;

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwWindowHint(II)V"))
    private void redirect(int hint, int value) { }

    // 1.21.1's Window constructor touches OpenGL: there is no GL context with Vulkan
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwMakeContextCurrent(J)V"))
    private void redirectMakeContextCurrent(long window) { }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL;createCapabilities()Lorg/lwjgl/opengl/GLCapabilities;"))
    private org.lwjgl.opengl.GLCapabilities redirectCreateCapabilities() {
        return null;
    }

    // Vulkan device not initialized yet
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;maxSupportedTextureSize()I"))
    private int redirectMaxSupportedTextureSize() {
        return 0;
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSetWindowSizeLimits(JIIII)V"))
    private void redirectWindowSizeLimits(long window, int minwidth, int minheight, int maxwidth, int maxheight) { }

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private void vulkanHint(WindowEventHandler windowEventHandler, ScreenManager screenManager, DisplayData displayData, String string, String string2, CallbackInfo ci) {
        GLFW.glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);

        //Fix Gnome Client-Side Decorators
        boolean b = (Platform.isGnome() | Platform.isWeston() | Platform.isGeneric()) && Platform.isWayLand();
        GLFW.glfwWindowHint(GLFW_DECORATED, (b ? GLFW_FALSE : GLFW_TRUE));
    }

    @Inject(method = "<init>", at = @At(value = "RETURN"))
    private void getHandle(WindowEventHandler windowEventHandler, ScreenManager screenManager, DisplayData displayData, String string, String string2, CallbackInfo ci) {
        VRenderSystem.setWindow(this.window);
    }

    /**
     * @author
     */
    @Overwrite
    public void updateVsync(boolean vsync) {
        this.vsync = vsync;
        Vulkan.setVsync(vsync);
    }

    /**
     * @author
     */
    @Overwrite
    public void toggleFullScreen() {
        this.fullscreen = !this.fullscreen;
        Options.fullscreenDirty = true;

        if (!this.fullscreen) {
            Config config = Initializer.CONFIG;
            config.windowMode = WindowMode.WINDOWED.mode;
        }
    }

    /**
     * @author
     */
    @Overwrite
    public void updateDisplay() {
        RenderSystem.flipFrame(this.window);

        if (Options.fullscreenDirty) {
            Options.fullscreenDirty = false;
            this.updateFullscreen(this.vsync);
        }
    }

    /**
     * @author
     */
    @Overwrite
    private void setMode() {
        Config config = Initializer.CONFIG;
        VideoModeManager.checkConfigVideoMode(config);

        if (this.fullscreen) {
            config.windowMode = WindowMode.EXCLUSIVE_FULLSCREEN.mode;
        }

        if (this.fullscreen) {
            {
                VideoModeManager.selectBestMonitor((Window) (Object) this);
                long monitor = VideoModeManager.selectedMonitor;
                VideoModeSet.VideoMode videoMode = config.videoMode;

                boolean supported;
                VideoModeSet set = VideoModeManager.getVideoModeSet(videoMode);

                if (set != null) {
                    supported = set.hasRefreshRate(videoMode.refreshRate);
                }
                else {
                    supported = false;
                }

                if (!supported) {
                    LOGGER.error("Resolution not supported, using first available as fallback");
                    videoMode = VideoModeManager.getFirstAvailable().getVideoMode();
                }

                if (!this.wasOnFullscreen) {
                    this.windowedX = this.x;
                    this.windowedY = this.y;
                    this.windowedWidth = this.width;
                    this.windowedHeight = this.height;
                }

                this.x = 0;
                this.y = 0;
                this.width = videoMode.width;
                this.height = videoMode.height;
                GLFW.glfwSetWindowMonitor(this.window, monitor, this.x, this.y, this.width, this.height, videoMode.refreshRate);

                this.wasOnFullscreen = true;
            }
        }
        else if (config.windowMode == WindowMode.WINDOWED_FULLSCREEN.mode) {
            VideoModeManager.selectBestMonitor((Window) (Object) this);
            VideoModeSet.VideoMode videoMode = VideoModeManager.getOsVideoMode();

            if (!this.wasOnFullscreen) {
                this.windowedX = this.x;
                this.windowedY = this.y;
                this.windowedWidth = this.width;
                this.windowedHeight = this.height;
            }

            int width = videoMode.width;
            int height = videoMode.height;

            GLFW.glfwSetWindowAttrib(this.window, GLFW_DECORATED, GLFW_FALSE);
            GLFW.glfwSetWindowMonitor(this.window, 0L, 0, 0, width, height, -1);

            this.width = width;
            this.height = height;
            this.wasOnFullscreen = true;
        } else {
            this.x = this.windowedX;
            this.y = this.windowedY;
            this.width = this.windowedWidth;
            this.height = this.windowedHeight;

            GLFW.glfwSetWindowMonitor(this.window, 0L, this.x, this.y, this.width, this.height, -1);
            GLFW.glfwSetWindowAttrib(this.window, GLFW_DECORATED, GLFW_TRUE);

            this.wasOnFullscreen = false;
        }
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    private void onFramebufferResize(long window, int width, int height) {
        if (window == this.window) {
            int prevWidth = this.getWidth();
            int prevHeight = this.getHeight();

            if(width > 0 && height > 0) {
                this.framebufferWidth = width;
                this.framebufferHeight = height;

                Renderer.scheduleSwapChainUpdate();
            }

        }
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    private void onResize(long window, int width, int height) {
        this.width = width;
        this.height = height;

        if(width > 0 && height > 0)
            Renderer.scheduleSwapChainUpdate();
    }

}
