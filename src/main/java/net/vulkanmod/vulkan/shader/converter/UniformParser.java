package net.vulkanmod.vulkan.shader.converter;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.lwjgl.vulkan.VK11;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class UniformParser {

    private final GlslConverter converterInstance;
    private final StageUniforms[] stageUniforms = new StageUniforms[GlslConverter.ShaderStage.values().length];
    private StageUniforms currentUniforms;
    List<Uniform> globalUniforms = new ArrayList<>();

    private String type;
    private String name;

    private List<ImageDescriptor> imageDescriptors;

    public UniformParser(GlslConverter converterInstance) {
        this.converterInstance = converterInstance;

        for (int i = 0; i < this.stageUniforms.length; ++i) {
            this.stageUniforms[i] = new StageUniforms();
        }
    }

    public boolean parseToken(String token) {
        if (token.matches("uniform")) return false;

        if (this.type == null) {
            this.type = token;

        }
        else if (this.name == null) {
            token = removeSemicolon(token);

            this.name = token;

            Uniform uniform = new Uniform(this.type, this.name);
            if ("sampler2D".equals(this.type)) {
                if (!this.currentUniforms.samplers.contains(uniform))
                    this.currentUniforms.samplers.add(uniform);
            }
            else {
                if (!this.globalUniforms.contains(uniform))
                    this.globalUniforms.add(uniform);
            }

            this.resetSate();
            return true;
        }

        return false;
    }

    public void setCurrentUniforms(GlslConverter.ShaderStage shaderStage) {
        this.currentUniforms = stageUniforms[shaderStage.ordinal()];
    }

    private void resetSate() {
        this.type = null;
        this.name = null;
    }

    /**
     * Binding of the global UBO is always 0 when present, so samplers start right after it.
     * Descriptor set layout creation requires contiguous bindings starting at 0.
     */
    private int firstSamplerBinding() {
        return this.globalUniforms.isEmpty() ? 0 : 1;
    }

    public String createUniformsCode() {
        // An empty uniform block is invalid GLSL
        if (this.globalUniforms.isEmpty())
            return "";

        StringBuilder builder = new StringBuilder();

        //hardcoded 0 binding as it should always be 0 in this case
        builder.append(String.format("layout(binding = %d) uniform UniformBufferObject {\n", 0));
        for (Uniform uniform : this.globalUniforms) {
            builder.append(String.format("%s %s;\n", uniform.type, uniform.name));
        }
        builder.append("};\n\n");

        return builder.toString();
    }

    public String createSamplersCode(GlslConverter.ShaderStage shaderStage) {
        StringBuilder builder = new StringBuilder();

        this.imageDescriptors = createSamplerList();

        for (ImageDescriptor imageDescriptor : this.imageDescriptors) {
            builder.append(String.format("layout(binding = %d) uniform %s %s;\n", imageDescriptor.getBinding(),
                                         imageDescriptor.qualifier, imageDescriptor.name));
        }
        builder.append("\n");

        return builder.toString();
    }

    /**
     * @return the UBO holding all global uniforms, or null if there are none
     */
    public UBO createUBO() {
        if (this.globalUniforms.isEmpty())
            return null;

        AlignedStruct.Builder builder = new AlignedStruct.Builder();

        for (UniformParser.Uniform uniform : this.globalUniforms) {
            String name = uniform.name();
            String type = uniform.type();

            net.vulkanmod.vulkan.shader.layout.Uniform.Info uniformInfo = net.vulkanmod.vulkan.shader.layout.Uniform.createUniformInfo(type, name);

            builder.addUniform(uniformInfo);
        }

        // Use binding 0 for global uniforms
        return builder.buildUBO(0, VK11.VK_SHADER_STAGE_ALL);
    }

    private List<ImageDescriptor> createSamplerList() {
        int firstBinding = firstSamplerBinding();

        // Collect unique samplers (a sampler declared in both stages must only get one binding)
        List<Uniform> samplers = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (StageUniforms stageUniforms : this.stageUniforms) {
            for (Uniform uniform : stageUniforms.samplers) {
                if (seen.add(uniform.name))
                    samplers.add(uniform);
            }
        }

        // Texture slot: vanilla semantics (SamplerN -> slot N) when the name is known,
        // otherwise the smallest free slot
        int[] slots = new int[samplers.size()];
        Set<Integer> usedSlots = new HashSet<>();

        for (int i = 0; i < samplers.size(); ++i) {
            slots[i] = VTextureSelector.getTextureIdx(samplers.get(i).name);

            if (slots[i] >= 0)
                usedSlots.add(slots[i]);
        }

        int nextFree = 0;
        for (int i = 0; i < samplers.size(); ++i) {
            if (slots[i] >= 0)
                continue;

            while (usedSlots.contains(nextFree))
                nextFree++;

            slots[i] = nextFree;
            usedSlots.add(nextFree);
        }

        List<ImageDescriptor> imageDescriptors = new ObjectArrayList<>();

        for (int i = 0; i < samplers.size(); ++i) {
            Uniform uniform = samplers.get(i);

            imageDescriptors.add(new ImageDescriptor(firstBinding + i, uniform.type, uniform.name, slots[i],
                                                     VK11.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER));
        }

        return imageDescriptors;
    }

    public static String removeSemicolon(String s) {
        int last = s.length() - 1;
        if ((s.charAt(last)) != ';')
            throw new IllegalArgumentException("last char is not ;");
        return s.substring(0, last);
    }

    public List<Uniform> getGlobalUniforms() {
        return globalUniforms;
    }

    public List<ImageDescriptor> getSamplers() {
        return this.imageDescriptors;
    }

    public record Uniform(String type, String name) {
    }

    private static class StageUniforms {
        List<Uniform> samplers = new ArrayList<>();
    }

    enum State {
        Uniform,
        Sampler,
        None
    }
}
