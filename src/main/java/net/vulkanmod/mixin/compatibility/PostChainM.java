package net.vulkanmod.mixin.compatibility;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(PostChain.class)
public abstract class PostChainM {

    @Shadow @Final private List<PostPass> passes;

    @Shadow private float lastStamp;
    @Shadow private float time;

    @Shadow protected abstract void setFilterMode(int i);

    /**
     * @author
     * @reason
     */
    @Overwrite
    public void process(float f) {
        if (f < this.lastStamp) {
            this.time += 1.0F - this.lastStamp;
            this.time += f;
        } else {
            this.time += f - this.lastStamp;
        }

        this.lastStamp = f;

        while(this.time > 20.0F) {
            this.time -= 20.0F;
        }

        int filterMode = 9728;

        for(PostPass postPass : this.passes) {
            int passFilterMode = postPass.getFilterMode();
            if (filterMode != passFilterMode) {
                this.setFilterMode(passFilterMode);
                filterMode = passFilterMode;
            }

            postPass.process(this.time / 20.0F);
        }

        this.setFilterMode(9728);

        Renderer.resetViewport();
    }

}
