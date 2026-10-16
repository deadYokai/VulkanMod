package net.vulkanmod.render.vertex;

import net.minecraft.client.renderer.RenderType;
import net.vulkanmod.Initializer;
import net.vulkanmod.interfaces.ExtendedRenderType;
import net.vulkanmod.vulkan.VRenderSystem;

import java.util.EnumSet;
import java.util.function.Function;

/**
 * The chunk pipeline works on four terrain layers. 1.21.1 vanilla has five render types:
 * solid, cutout_mipped, cutout, translucent, tripwire. Both cutout variants map onto CUTOUT.
 */
public enum TerrainRenderType {
    SOLID(0.0f),
    CUTOUT(0.5f),
    TRANSLUCENT(0.01f),
    TRIPWIRE(0.1f);

    public static final TerrainRenderType[] VALUES = TerrainRenderType.values();

    public static final EnumSet<TerrainRenderType> COMPACT_RENDER_TYPES = EnumSet.of(CUTOUT, TRANSLUCENT);
    public static final EnumSet<TerrainRenderType> SEMI_COMPACT_RENDER_TYPES = EnumSet.of(SOLID, CUTOUT, TRANSLUCENT);

    private static Function<TerrainRenderType, TerrainRenderType> remapper;

    static {
        SEMI_COMPACT_RENDER_TYPES.add(CUTOUT);
        SEMI_COMPACT_RENDER_TYPES.add(TRANSLUCENT);

        COMPACT_RENDER_TYPES.add(TRANSLUCENT);
    }

    public final float alphaCutout;

    TerrainRenderType(float alphaCutout) {
        this.alphaCutout = alphaCutout;
    }

    public void setCutoutUniform() {
        VRenderSystem.alphaCutout = this.alphaCutout;
    }

    public static TerrainRenderType get(RenderType renderType) {
        return ((ExtendedRenderType) renderType).getTerrainRenderType();
    }

    public static TerrainRenderType get(String name) {
        return switch (name) {
            case "solid" -> TerrainRenderType.SOLID;
            case "cutout", "cutout_mipped" -> TerrainRenderType.CUTOUT;
            case "translucent" -> TerrainRenderType.TRANSLUCENT;
            case "tripwire" -> TerrainRenderType.TRIPWIRE;
            default -> null;
        };
    }

    /**
     * @return the vanilla render type whose render state (block atlas + lightmap textures) this layer needs
     */
    public static RenderType getRenderType(TerrainRenderType renderType) {
        return switch (renderType) {
            case SOLID -> RenderType.solid();
            case CUTOUT -> RenderType.cutoutMipped();
            case TRANSLUCENT -> RenderType.translucent();
            case TRIPWIRE -> RenderType.tripwire();
        };
    }

    public static void updateMapping() {
        if (Initializer.CONFIG.uniqueOpaqueLayer) {
            remapper = (renderType) -> switch (renderType) {
                case SOLID, CUTOUT -> TerrainRenderType.CUTOUT;
                case TRANSLUCENT, TRIPWIRE -> TerrainRenderType.TRANSLUCENT;
            };
        } else {
            remapper = (renderType) -> switch (renderType) {
                case SOLID -> TerrainRenderType.SOLID;
                case CUTOUT -> TerrainRenderType.CUTOUT;
                case TRANSLUCENT, TRIPWIRE -> TerrainRenderType.TRANSLUCENT;
            };
        }
    }

    public static TerrainRenderType getRemapped(TerrainRenderType renderType) {
        return remapper.apply(renderType);
    }
}
