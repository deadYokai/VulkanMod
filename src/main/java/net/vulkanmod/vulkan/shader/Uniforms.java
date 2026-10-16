package net.vulkanmod.vulkan.shader;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.util.MappedBuffer;

import java.util.function.Supplier;

public class Uniforms {

    /**
     * Fog start/end used for the fog component that is inactive. Any distance is below this start,
     * so linear_fog_value() returns 0 for it.
     */
    private static final float NO_FOG = Float.MAX_VALUE;

    public static Object2ReferenceOpenHashMap<String, Supplier<Integer>> vec1i_uniformMap = new Object2ReferenceOpenHashMap<>();

    public static Object2ReferenceOpenHashMap<String, Supplier<Float>> vec1f_uniformMap = new Object2ReferenceOpenHashMap<>();
    public static Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> vec2f_uniformMap = new Object2ReferenceOpenHashMap<>();
    public static Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> vec3f_uniformMap = new Object2ReferenceOpenHashMap<>();
    public static Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> vec4f_uniformMap = new Object2ReferenceOpenHashMap<>();

    public static Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> mat4f_uniformMap = new Object2ReferenceOpenHashMap<>();

    private static boolean isSphericalFog() {
        return RenderSystem.getShaderFogShape() == FogShape.SPHERE;
    }

    public static void setupDefaultUniforms() {

        //Mat4
        mat4f_uniformMap.put("ModelViewMat", VRenderSystem::getModelViewMatrix);
        mat4f_uniformMap.put("ProjMat", VRenderSystem::getProjectionMatrix);
        mat4f_uniformMap.put("MVP", VRenderSystem::getMVP);
        mat4f_uniformMap.put("TextureMat", VRenderSystem::getTextureMatrix);

        //Vec1i
        vec1i_uniformMap.put("EndPortalLayers", () -> 15);
        vec1i_uniformMap.put("FogShape", () -> RenderSystem.getShaderFogShape().getIndex());
        vec1i_uniformMap.put("CurrentTime", VRenderSystem::getCurrentTime);
        // Texture filtering choice introduced after 1.21.1: always plain nearest sampling here
        vec1i_uniformMap.put("UseRgss", () -> 0);

        //Vec1
        // Legacy (1.21.1) fog uniforms
        vec1f_uniformMap.put("FogStart", RenderSystem::getShaderFogStart);
        vec1f_uniformMap.put("FogEnd", RenderSystem::getShaderFogEnd);
        vec1f_uniformMap.put("LineWidth", RenderSystem::getShaderLineWidth);
        vec1f_uniformMap.put("GameTime", RenderSystem::getShaderGameTime);
        vec1f_uniformMap.put("GlintAlpha", RenderSystem::getShaderGlintAlpha);
        vec1f_uniformMap.put("AlphaCutout", () -> VRenderSystem.alphaCutout);

        // Split fog model used by the VulkanMod terrain/cloud shaders, derived from the single 1.21.1 fog:
        // a spherical fog drives the "environmental" pair, a cylindrical fog the "render distance" pair.
        vec1f_uniformMap.put("FogEnvironmentalStart", () -> isSphericalFog() ? RenderSystem.getShaderFogStart() : NO_FOG);
        vec1f_uniformMap.put("FogEnvironmentalEnd", () -> isSphericalFog() ? RenderSystem.getShaderFogEnd() : NO_FOG);
        vec1f_uniformMap.put("FogRenderDistanceStart", () -> isSphericalFog() ? NO_FOG : RenderSystem.getShaderFogStart());
        vec1f_uniformMap.put("FogRenderDistanceEnd", () -> isSphericalFog() ? NO_FOG : RenderSystem.getShaderFogEnd());
        vec1f_uniformMap.put("FogSkyEnd", RenderSystem::getShaderFogEnd);
        vec1f_uniformMap.put("FogCloudsEnd", RenderSystem::getShaderFogEnd);

        //Vec2
        vec2f_uniformMap.put("ScreenSize", VRenderSystem::getScreenSize);
        vec2f_uniformMap.put("TextureSize", VRenderSystem::getTextureSize);
        vec2f_uniformMap.put("TexelSize", VRenderSystem::getTexelSize);

        //Vec3
        vec3f_uniformMap.put("Light0_Direction", () -> VRenderSystem.lightDirection0);
        vec3f_uniformMap.put("Light1_Direction", () -> VRenderSystem.lightDirection1);
        vec3f_uniformMap.put("ModelOffset", () -> VRenderSystem.modelOffset);
        vec3f_uniformMap.put("ChunkOffset", () -> VRenderSystem.modelOffset);

        //Vec4
        vec4f_uniformMap.put("ColorModulator", VRenderSystem::getShaderColor);
        vec4f_uniformMap.put("FogColor", VRenderSystem::getShaderFogColor);

    }

    public static Supplier<MappedBuffer> getUniformSupplier(String type, String name) {
        return switch (type) {
            case "mat4" -> Uniforms.mat4f_uniformMap.get(name);
            case "vec4" -> Uniforms.vec4f_uniformMap.get(name);
            case "vec3" -> Uniforms.vec3f_uniformMap.get(name);
            case "vec2", "ivec2" -> Uniforms.vec2f_uniformMap.get(name);

            default -> null;
        };
    }
}
