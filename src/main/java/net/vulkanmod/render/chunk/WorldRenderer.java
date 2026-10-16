package net.vulkanmod.render.chunk;

import com.google.common.collect.Sets;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.build.RenderRegionBuilder;
import net.vulkanmod.render.chunk.build.task.ChunkTask;
import net.vulkanmod.render.chunk.build.task.TaskDispatcher;
import net.vulkanmod.render.chunk.graph.SectionGraph;
import net.vulkanmod.render.profiling.BuildTimeProfiler;
import net.vulkanmod.render.profiling.Profiler;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.memory.buffer.IndirectBuffer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;

import java.util.*;

public class WorldRenderer {
    private static WorldRenderer INSTANCE;

    /**
     * 1.21.1 has no chunk section fade-in option. A fade time of 0 makes DrawBuffers
     * use a constant fade factor of 1.
     */
    private static final float CHUNK_FADE_IN_SECONDS = 0.0f;

    private final Minecraft minecraft;
    private ClientLevel level;
    private int renderDistance;
    private final RenderBuffers renderBuffers;

    private final Vector3d cameraPos = new Vector3d();
    private int lastCameraSectionX;
    private int lastCameraSectionY;
    private int lastCameraSectionZ;
    private float lastCameraX;
    private float lastCameraY;
    private float lastCameraZ;
    private float lastCamRotX;
    private float lastCamRotY;

    private SectionGrid sectionGrid;

    private SectionGraph sectionGraph;
    private boolean graphNeedsUpdate;

    private final Set<BlockEntity> globalBlockEntities = Sets.newHashSet();

    private final TaskDispatcher taskDispatcher;

    private double xTransparentOld;
    private double yTransparentOld;
    private double zTransparentOld;

    IndirectBuffer[] indirectBuffers;

    private long terrainSampler;
    private int terrainSamplerMipLevels = -1;

    public RenderRegionBuilder renderRegionCache;

    private final List<Runnable> onAllChangedCallbacks = new ObjectArrayList<>();

    private WorldRenderer(RenderBuffers renderBuffers) {
        this.minecraft = Minecraft.getInstance();
        this.renderBuffers = renderBuffers;

        this.renderRegionCache = new RenderRegionBuilder();
        this.taskDispatcher = new TaskDispatcher();

        ChunkTask.setTaskDispatcher(this.taskDispatcher);
        allocateIndirectBuffers();
        TerrainRenderType.updateMapping();

        Renderer.getInstance().addOnResizeCallback(() -> {
            if (this.indirectBuffers.length != Renderer.getFramesNum())
                allocateIndirectBuffers();
        });
    }

    public static WorldRenderer init(RenderBuffers renderBuffers) {
        if (INSTANCE != null)
            return INSTANCE;
        else
            return INSTANCE = new WorldRenderer(renderBuffers);
    }

    private void allocateIndirectBuffers() {
        if (this.indirectBuffers != null)
            Arrays.stream(this.indirectBuffers).forEach(Buffer::scheduleFree);

        this.indirectBuffers = new IndirectBuffer[Renderer.getFramesNum()];

        for (int i = 0; i < this.indirectBuffers.length; ++i) {
            this.indirectBuffers[i] = new IndirectBuffer(1000000, MemoryTypes.HOST_MEM);
        }
    }

    private void benchCallback() {
        BuildTimeProfiler.runBench(this.graphNeedsUpdate || !this.taskDispatcher.isIdle());
    }

    public void setupRenderer(Camera camera, Frustum frustum, boolean isCapturedFrustum, boolean spectator) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.push("Setup_Renderer");

        ProfilerFiller mcProfiler = this.minecraft.getProfiler();

        benchCallback();

        Vec3 camPos = camera.getPosition();
        this.cameraPos.set(camPos.x, camPos.y, camPos.z);
        if (this.minecraft.options.getEffectiveRenderDistance() != this.renderDistance) {
            this.allChanged();
        }

        mcProfiler.push("camera");
        float cameraX = (float) cameraPos.x();
        float cameraY = (float) cameraPos.y();
        float cameraZ = (float) cameraPos.z();
        int sectionX = SectionPos.posToSectionCoord(cameraX);
        int sectionY = SectionPos.posToSectionCoord(cameraY);
        int sectionZ = SectionPos.posToSectionCoord(cameraZ);

        profiler.push("reposition");
        if (this.lastCameraSectionX != sectionX || this.lastCameraSectionY != sectionY || this.lastCameraSectionZ != sectionZ) {
            this.lastCameraSectionX = sectionX;
            this.lastCameraSectionY = sectionY;
            this.lastCameraSectionZ = sectionZ;
            this.sectionGrid.repositionCamera(cameraX, cameraZ);
        }
        profiler.pop();

        double entityDistanceScaling = this.minecraft.options.entityDistanceScaling().get();
        Entity.setViewScale(Mth.clamp((double) this.renderDistance / 8.0D, 1.0D, 2.5D) * entityDistanceScaling);

        mcProfiler.popPush("cull");

        mcProfiler.popPush("update");

        boolean cameraMoved = false;
        float d_xRot = Math.abs(camera.getXRot() - this.lastCamRotX);
        float d_yRot = Math.abs(camera.getYRot() - this.lastCamRotY);
        cameraMoved |= d_xRot > 2.0f || d_yRot > 2.0f;

        cameraMoved |= cameraX != this.lastCameraX || cameraY != this.lastCameraY || cameraZ != this.lastCameraZ;
        this.graphNeedsUpdate |= cameraMoved;

        if (!isCapturedFrustum) {
            //Debug
//            this.graphNeedsUpdate = true;

            if (this.graphNeedsUpdate()) {
                this.graphNeedsUpdate = false;
                this.lastCameraX = cameraX;
                this.lastCameraY = cameraY;
                this.lastCameraZ = cameraZ;
                this.lastCamRotX = camera.getXRot();
                this.lastCamRotY = camera.getYRot();

                this.sectionGraph.update(camera, frustum, spectator);
            }
        }

        this.indirectBuffers[Renderer.getCurrentFrame()].reset();

        mcProfiler.pop();
        profiler.pop();
    }

    public void uploadSections() {
        ProfilerFiller mcProfiler = this.minecraft.getProfiler();
        mcProfiler.push("upload");

        Profiler profiler = Profiler.getMainProfiler();
        profiler.push("Uploads");

        try {
            if (this.taskDispatcher.updateSections())
                this.graphNeedsUpdate = true;
        } catch (Exception e) {
            Initializer.LOGGER.error(e.getMessage());
            allChanged();
        }

        profiler.pop();

        mcProfiler.pop();
    }

    public boolean isSectionCompiled(BlockPos blockPos) {
        RenderSection renderSection = this.sectionGrid.getSectionAtBlockPos(blockPos);
        return renderSection != null && renderSection.isCompiled();
    }

    public void allChanged() {
        if (this.level != null) {
            this.level.clearTintCaches();

            this.renderRegionCache.clear();
            this.taskDispatcher.createThreads(Initializer.CONFIG.builderThreads);

            this.graphNeedsUpdate = true;

            this.renderDistance = this.minecraft.options.getEffectiveRenderDistance();
            if (this.sectionGrid != null) {
                this.sectionGrid.freeAllBuffers();
            }

            this.taskDispatcher.clearBatchQueue();
            synchronized (this.globalBlockEntities) {
                this.globalBlockEntities.clear();
            }

            this.sectionGrid = new SectionGrid(this.level, this.renderDistance);
            this.sectionGraph = new SectionGraph(this.level, this.sectionGrid, this.taskDispatcher);

            this.onAllChangedCallbacks.forEach(Runnable::run);

            Entity entity = this.minecraft.getCameraEntity();
            if (entity != null) {
                this.sectionGrid.repositionCamera(entity.getX(), entity.getZ());
            }

        }
    }

    public void setLevel(@Nullable ClientLevel level) {
        this.lastCameraX = Float.MIN_VALUE;
        this.lastCameraY = Float.MIN_VALUE;
        this.lastCameraZ = Float.MIN_VALUE;
        this.lastCameraSectionX = Integer.MIN_VALUE;
        this.lastCameraSectionY = Integer.MIN_VALUE;
        this.lastCameraSectionZ = Integer.MIN_VALUE;

        this.level = level;
        ChunkStatusMap.createInstance(renderDistance);
        if (level != null) {
            this.allChanged();
        } else {
            if (this.sectionGrid != null) {
                this.sectionGrid.freeAllBuffers();
                this.sectionGrid = null;
            }

            this.taskDispatcher.stopThreads();

            this.graphNeedsUpdate = true;
        }

    }

    public void addOnAllChangedCallback(Runnable runnable) {
        this.onAllChangedCallbacks.add(runnable);
    }

    public void clearOnAllChangedCallbacks() {
        this.onAllChangedCallbacks.clear();
    }

    public void renderSectionLayer(TerrainRenderType renderType, double camX, double camY, double camZ, Matrix4f modelView, Matrix4f projection) {
        Renderer.getInstance().getMainPass().rebindMainTarget();

        this.sortTranslucentSections(camX, camY, camZ);

        ProfilerFiller mcProfiler = this.minecraft.getProfiler();
        mcProfiler.push("render_" + renderType);

        // The vanilla render state binds the block atlas (texture 0) and the lightmap (texture 2)
        // as shader textures; they are picked up below by VTextureSelector.bindShaderTextures()
        RenderType vanillaRenderType = TerrainRenderType.getRenderType(renderType);
        vanillaRenderType.setupRenderState();

        final boolean isTranslucent = renderType == TerrainRenderType.TRANSLUCENT;
        final boolean indirectDraw = Initializer.CONFIG.indirectDraw;

        if (!isTranslucent) {
            GlStateManager._disableBlend();
        } else {
            GlStateManager._enableBlend();
            VRenderSystem.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        }

        VRenderSystem.enableCull();
        VRenderSystem.glDepthFun(GL11.GL_LEQUAL);

        GlStateManager._enableDepthTest();
        GlStateManager._depthMask(true);

        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._disablePolygonOffset();
        VRenderSystem.setPolygonModeGL(GL11.GL_FILL);

        VRenderSystem.applyMVP(modelView, projection);
        VRenderSystem.setPrimitiveTopologyGL(GL11.GL_TRIANGLES);

        Renderer.pushDebugSection("Terrain_" + renderType);

        Renderer renderer = Renderer.getInstance();
        GraphicsPipeline pipeline = PipelineManager.getTerrainShader(renderType);
        renderer.bindGraphicsPipeline(pipeline);

        // The terrain shader samples the atlas with its own linear sampler (it snaps UVs itself).
        // Install it for the duration of this draw only: other renderers share the block atlas
        // and rely on the sampler derived from its GL texture parameters.
        TextureManager textureManager = this.minecraft.getTextureManager();
        AbstractTexture atlasTexture = textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS);
        VkGlTexture atlasGlTexture = VkGlTexture.getTexture(atlasTexture.getId());
        VulkanImage atlasImage = atlasGlTexture != null ? atlasGlTexture.getVulkanImage() : null;

        long previousAtlasSampler = 0L;
        if (atlasImage != null) {
            int maxLod = atlasImage.mipLevels - 1;
            if (this.terrainSampler == 0L || this.terrainSamplerMipLevels != maxLod) {
                this.terrainSampler = SamplerManager.getSampler(true, true, maxLod);
                this.terrainSamplerMipLevels = maxLod;
            }

            previousAtlasSampler = atlasImage.getSampler();
            atlasImage.setSampler(this.terrainSampler);

            VRenderSystem.setTextureSize(atlasImage.width, atlasImage.height);
        }

        VTextureSelector.bindShaderTextures(pipeline);

        VRenderSystem.setCurrentTime((int) System.currentTimeMillis());

        long currentTimeMs = System.currentTimeMillis();
        float fadeTime = CHUNK_FADE_IN_SECONDS;
        int fadeTimeMs = (int) (fadeTime * 1000);
        float fadeTimeInv = fadeTime > 0 ? 1 / (fadeTime * 1000) : 1;

        IndexBuffer indexBuffer = Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer();
        Renderer.getDrawer().bindIndexBuffer(Renderer.getCommandBuffer(), indexBuffer, indexBuffer.indexType.value);

        UBO sectionData = pipeline.getUBO(2);
        sectionData.setUseGlobalBuffer(false);

        int currentFrame = Renderer.getCurrentFrame();
        Set<TerrainRenderType> allowedRenderTypes = Initializer.CONFIG.uniqueOpaqueLayer ? TerrainRenderType.COMPACT_RENDER_TYPES : TerrainRenderType.SEMI_COMPACT_RENDER_TYPES;
        if (allowedRenderTypes.contains(renderType)) {
            renderType.setCutoutUniform();

            for (Iterator<ChunkArea> iterator = this.sectionGraph.getChunkAreaQueue().iterator(isTranslucent); iterator.hasNext(); ) {
                ChunkArea chunkArea = iterator.next();
                var queue = chunkArea.sectionQueue;
                DrawBuffers drawBuffers = chunkArea.drawBuffers;

                if (drawBuffers.getAreaBuffer(renderType) != null && queue.size() > 0) {

                    drawBuffers.bindBuffers(Renderer.getCommandBuffer(), pipeline, renderType,
                                            sectionData,
                                            camX, camY, camZ,
                                            currentTimeMs, fadeTimeMs, fadeTimeInv);

                    renderer.uploadAndBindUBOs(pipeline);

                    if (indirectDraw) {
                        drawBuffers.buildDrawBatchesIndirect(cameraPos, indirectBuffers[currentFrame], queue, renderType);
                    }
                    else {
                        drawBuffers.buildDrawBatchesDirect(cameraPos, queue, renderType);
                    }
                }
            }
        }

        if (renderType == TerrainRenderType.CUTOUT || renderType == TerrainRenderType.TRIPWIRE) {
            indirectBuffers[currentFrame].submitUploads();
//            uniformBuffers.submitUploads();
        }

        // Need to reset push constants in case the pipeline will still be used for rendering
        if (!indirectDraw) {
            VRenderSystem.setModelOffset(0, 0, 0);
            renderer.pushConstants(pipeline);
        }

        if (atlasImage != null) {
            atlasImage.setSampler(previousAtlasSampler);
        }

        Renderer.popDebugSection();

        vanillaRenderType.clearRenderState();

        mcProfiler.pop();
    }

    private void sortTranslucentSections(double camX, double camY, double camZ) {
        ProfilerFiller mcProfiler = this.minecraft.getProfiler();
        mcProfiler.push("translucent_sort");
        double d0 = camX - this.xTransparentOld;
        double d1 = camY - this.yTransparentOld;
        double d2 = camZ - this.zTransparentOld;
        if (d0 * d0 + d1 * d1 + d2 * d2 > 2.0D) {
            this.xTransparentOld = camX;
            this.yTransparentOld = camY;
            this.zTransparentOld = camZ;
            int j = 0;

            Iterator<RenderSection> iterator = this.sectionGraph.getSectionQueue().iterator(false);

            while (iterator.hasNext() && j < 200) {
                RenderSection section = iterator.next();
                section.resortTransparency(this.taskDispatcher, this.cameraPos);

                if (!section.isCompletelyEmpty()) {
                    ++j;
                }
            }
        }

        mcProfiler.pop();
    }

    public void renderBlockEntities(PoseStack poseStack, double camX, double camY, double camZ,
                                    Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress, float gameTime) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.pop();
        profiler.push("Block-entities");

        MultiBufferSource bufferSource = this.renderBuffers.bufferSource();

        for (RenderSection renderSection : this.sectionGraph.getBlockEntitiesSections()) {
            List<BlockEntity> list = renderSection.getCompiledSection().getBlockEntities();
            if (!list.isEmpty()) {
                for (BlockEntity blockEntity : list) {
                    BlockPos blockPos = blockEntity.getBlockPos();
                    MultiBufferSource bufferSource1 = bufferSource;
                    poseStack.pushPose();
                    poseStack.translate((double) blockPos.getX() - camX, (double) blockPos.getY() - camY, (double) blockPos.getZ() - camZ);
                    SortedSet<BlockDestructionProgress> sortedset = destructionProgress.get(blockPos.asLong());
                    if (sortedset != null && !sortedset.isEmpty()) {
                        int j1 = sortedset.last().getProgress();
                        if (j1 >= 0) {
                            PoseStack.Pose pose = poseStack.last();
                            VertexConsumer vertexconsumer = new SheetedDecalTextureGenerator(this.renderBuffers.crumblingBufferSource().getBuffer(ModelBakery.DESTROY_TYPES.get(j1)), pose, 1.0f);
                            bufferSource1 = (renderType) -> {
                                VertexConsumer vertexConsumer2 = bufferSource.getBuffer(renderType);
                                return renderType.affectsCrumbling() ? VertexMultiConsumer.create(vertexconsumer, vertexConsumer2) : vertexConsumer2;
                            };
                        }
                    }

                    this.minecraft.getBlockEntityRenderDispatcher().render(blockEntity, gameTime, poseStack, bufferSource1);
                    poseStack.popPose();
                }
            }
        }
    }

    /**
     * Forces the terrain sampler to be recreated (e.g. after a texture filtering setting change).
     */
    public void resetSampler() {
        this.terrainSampler = 0L;
    }

    public void scheduleGraphUpdate() {
        this.graphNeedsUpdate = true;
    }

    public boolean graphNeedsUpdate() {
        return this.graphNeedsUpdate;
    }

    public int getVisibleSectionsCount() {
        return this.sectionGraph.getSectionQueue().size();
    }

    public void setSectionDirty(int x, int y, int z, boolean flag) {
        this.sectionGrid.setDirty(x, y, z, flag);

        this.renderRegionCache.remove(x, z);
    }

    public SectionGrid getSectionGrid() {
        return this.sectionGrid;
    }

    public ChunkAreaManager getChunkAreaManager() {
        if (this.sectionGrid == null)
            return null;
        return this.sectionGrid.chunkAreaManager;
    }

    public TaskDispatcher getTaskDispatcher() {
        return taskDispatcher;
    }

    public short getLastFrame() {
        return this.sectionGraph.getLastFrame();
    }

    public int getRenderDistance() {
        return this.renderDistance;
    }

    public String getChunkStatistics() {
        if (this.sectionGraph == null) {
            return null;
        }

        return this.sectionGraph.getStatistics();
    }

    public void cleanUp() {
        if (indirectBuffers != null)
            Arrays.stream(indirectBuffers).forEach(Buffer::scheduleFree);
    }

    public static WorldRenderer getInstance() {
        return INSTANCE;
    }

    public static ClientLevel getLevel() {
        return INSTANCE.level;
    }

    public static Vector3d getCameraPos() {
        return INSTANCE.cameraPos;
    }

}
