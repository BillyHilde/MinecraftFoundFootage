package com.sp.render.grass;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.systems.RenderSystem;
import com.sp.SPBRevamped;
import com.sp.SPBRevampedClient;
import com.sp.compat.modmenu.ConfigStuff;
import com.sp.mixininterfaces.RenderIndirectExtension;
import com.sp.world.levels.custom.Level324Backroomslevel;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.VeilRenderer;
import foundry.veil.api.client.render.framebuffer.AdvancedFbo;
import foundry.veil.api.client.render.framebuffer.VeilFramebuffers;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormatElement;
import net.minecraft.util.Identifier;
import org.joml.Vector4fc;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;

import static net.minecraft.client.render.VertexFormats.NORMAL_ELEMENT;
import static net.minecraft.client.render.VertexFormats.POSITION_ELEMENT;
import static net.minecraft.util.math.MathHelper.floor;
import static net.minecraft.util.math.MathHelper.sqrt;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL15C.glGenBuffers;
import static org.lwjgl.opengl.GL42C.*;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.glDispatchCompute;

public class GrassRenderer {
    VertexBuffer vertexBuffer;
    private static final Identifier shaderPath = new Identifier(SPBRevamped.MOD_ID, "grass/grass");
    private static final Identifier windTexture = new Identifier(SPBRevamped.MOD_ID, "textures/shaders/puddle_noise.png");

    private static final Identifier computeShaderPath = new Identifier(SPBRevamped.MOD_ID, "grass/compute/positions");
    private final int positionsVbo;
    private final int indirectVbo;

    private int lastGrassCount;
    private int lastMeshResolution;
    private ByteBuffer cmd;

    private float getGrassHeight() {
        if (SPBRevampedClient.getCurrentBackroomsLevel() instanceof Level324Backroomslevel) {
            return 1.5f;
        }

        return 1;
    }

    public static final VertexFormat POSITION_NORMAL = new VertexFormat(
            ImmutableMap.<String, VertexFormatElement>builder()
                    .put("Position", POSITION_ELEMENT)
                    .put("Color", NORMAL_ELEMENT)
                    .build()
    );

    public GrassRenderer() {
        this.vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder bufferBuilder = tessellator.getBuffer();

        bufferBuilder.begin(VertexFormat.DrawMode.QUADS, POSITION_NORMAL);


        this.createGrassModel(bufferBuilder);

        BufferBuilder.BuiltBuffer builtBuffer = bufferBuilder.end();

        this.vertexBuffer.bind();
        this.vertexBuffer.upload(builtBuffer);
        VertexBuffer.unbind();


        //*Initialize Grass Positions buffer and Indirect buffer struct
        this.positionsVbo = glGenBuffers();
        this.indirectVbo = glGenBuffers();
        this.updateBuffers(true);
    }

    public void render() {
//        RenderSystem.disableDepthTest();
        AdvancedFbo fbo = VeilRenderSystem.renderer().getFramebufferManager().getFramebuffer(VeilFramebuffers.OPAQUE);
        if(fbo == null) return;
        fbo.bind(false);

        //*If there is a change in the grass count or resolution, update the buffers
        if(ConfigStuff.grassQuality.getCount() != this.lastGrassCount || ConfigStuff.grassQuality.getResolution() != this.lastMeshResolution) {
            if (this.vertexBuffer != null) {
                this.vertexBuffer.close();
            }

            this.vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder bufferBuilder = tessellator.getBuffer();

            bufferBuilder.begin(VertexFormat.DrawMode.QUADS, POSITION_NORMAL);

            this.createGrassModel(bufferBuilder);

            this.vertexBuffer.bind();
            this.vertexBuffer.upload(bufferBuilder.end());
            VertexBuffer.unbind();


        }

        //*Update the Buffers
        this.updateBuffers(false);

        //*Use a compute shader to get all visible grass positions (Frustum Culling)
        this.computeGrassPositions();



        ShaderProgram shader = VeilRenderSystem.setShader(shaderPath);
        if(shader == null) return;

        shader.setFloat("GameTime", RenderSystem.getShaderGameTime());
        shader.setInt("NumOfInstances", floor(sqrt(ConfigStuff.grassQuality.getCount())));
        shader.setFloat("grassHeight", getGrassHeight());
        shader.setFloat("density", ConfigStuff.grassQuality.getDensity());

//        int prevTexture = RenderSystem.getShaderTexture(0);
        RenderSystem.setShaderTexture(0, windTexture);
        shader.addSampler("WindNoise", RenderSystem.getShaderTexture(0));
        shader.applyShaderSamplers(0);

        this.vertexBuffer.bind();
        //*glDrawElementsIndirect needs the indirect fbo
        //*REMEMBER the int struct goes HERE and not directly into the method like I thought before
        glBindBuffer(GL43.GL_DRAW_INDIRECT_BUFFER, this.indirectVbo);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, this.positionsVbo);
        shader.bind();

        ((RenderIndirectExtension)this.vertexBuffer).spb_revamped_1_20_1$drawIndirect();

        ShaderProgram.unbind();
        shader.clearSamplers();

        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, 0);
        glBindBuffer(GL43.GL_DRAW_INDIRECT_BUFFER, 0);
        VertexBuffer.unbind();
//        RenderSystem.setShaderTexture(0, prevTexture);

        AdvancedFbo.unbind();
//        RenderSystem.enableDepthTest();
    }

    private void updateBuffers(boolean init){
        int currentGrassCount = ConfigStuff.grassQuality.getCount();
        int currentMeshResolution = ConfigStuff.grassQuality.getResolution();
        boolean countChange = currentGrassCount != this.lastGrassCount;
        boolean resolutionChange = currentMeshResolution != this.lastMeshResolution;

        if(countChange) {
            //*Update positions buffer size
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, this.positionsVbo);
            glBufferData(GL_SHADER_STORAGE_BUFFER, (long) 4 * ((long) currentGrassCount) * Float.BYTES, GL_DYNAMIC_DRAW);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        }

        //*Update Indirect buffer instance count
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, this.indirectVbo);
        glBufferData(GL_DRAW_INDIRECT_BUFFER, (long) 20, GL_STATIC_DRAW);


        this.cmd = glMapBufferRange(
                GL_DRAW_INDIRECT_BUFFER, 0, 20,
                GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_BUFFER_BIT | GL_MAP_UNSYNCHRONIZED_BIT
        );


        //*Actual struct part
        /*
            *uint count;
            *uint primCount;
            *uint firstIndex;
            *uint baseVertex;
            *uint baseInstance;
        */
        if(cmd != null) {
            this.cmd.clear();
            this.cmd.putInt(VeilRenderSystem.getIndexCount(this.vertexBuffer));
            this.cmd.putInt(0);
            this.cmd.putInt(0);
            this.cmd.putInt(0);
            this.cmd.putInt(0);

            this.cmd.flip();

        }
        glUnmapBuffer(GL_DRAW_INDIRECT_BUFFER);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);

        if(countChange)this.lastGrassCount = currentGrassCount;
        if(resolutionChange)this.lastMeshResolution = currentMeshResolution;

    }

    private void computeGrassPositions() {
        ShaderProgram shader = VeilRenderSystem.setShader(computeShaderPath);
        if(shader == null) return;

        if(shader.isCompute()){
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, this.positionsVbo);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, this.indirectVbo);

            int numOfInst = floor(sqrt(ConfigStuff.grassQuality.getCount()));
            shader.setInt("NumOfInstances", numOfInst);
            shader.setFloat("density", ConfigStuff.grassQuality.getDensity());

            float maxDist = numOfInst / (ConfigStuff.grassQuality.getDensity() * 1.85f);
            shader.setFloat("maxDist", maxDist);

            Vector4fc[] planes = VeilRenderer.getCullingFrustum().getPlanes();
            float[] values = new float[4 * planes.length];
            for (int i = 0; i < planes.length; i++) {
                Vector4fc plane = planes[i];
                values[i * 4] = plane.x();
                values[i * 4 + 1] = plane.y();
                values[i * 4 + 2] = plane.z();
                values[i * 4 + 3] = plane.w();
            }
            shader.setFloats("FrustumPlanes", values);

            shader.bind();

            //*Eight local groups
            int grass = floor(sqrt((float) ConfigStuff.grassQuality.getCount()) / 8);
            int x = Math.min(grass, VeilRenderSystem.maxComputeWorkGroupCountX());
            int y = Math.min(grass, VeilRenderSystem.maxComputeWorkGroupCountY());

            glDispatchCompute(x, y, 1);
            glMemoryBarrier(GL_ALL_BARRIER_BITS);


            ShaderProgram.unbind();
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, 0);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, 0);
        }

        ShaderProgram.unbind();


    }

    private void createGrassModel(BufferBuilder bufferBuilder) {
        //*Segmented Grass blades (1 is just a single triangle)
        int segments = ConfigStuff.grassQuality.getResolution();
        float xStep = 0.1f/segments;

        for(int i = 0; i < segments; i++){
            bufferBuilder.vertex(0.6-xStep*(i+1),getGrassHeight()/segments*(i+1),0).normal(0,0,1).next();
            bufferBuilder.vertex(0.4+xStep*(i+1),getGrassHeight()/segments*(i+1),0).normal(0,0,1).next();
            bufferBuilder.vertex(0.4+xStep*(i),getGrassHeight()/segments*i,0)      .normal(0,0,1).next();
            bufferBuilder.vertex(0.6-xStep*(i),getGrassHeight()/segments*i,0)      .normal(0,0,1).next();

            bufferBuilder.vertex(0.6-xStep*(i),getGrassHeight()/segments*i,0)      .normal(0,0,-1).next();
            bufferBuilder.vertex(0.4+xStep*(i),getGrassHeight()/segments*i,0)      .normal(0,0,-1).next();
            bufferBuilder.vertex(0.4+xStep*(i+1),getGrassHeight()/segments*(i+1),0).normal(0,0,-1).next();
            bufferBuilder.vertex(0.6-xStep*(i+1),getGrassHeight()/segments*(i+1),0).normal(0,0,-1).next();
        }
    }

    public void close(){
        glDeleteBuffers(this.positionsVbo);
        glDeleteBuffers(this.indirectVbo);
        glUnmapBuffer(GL_DRAW_INDIRECT_BUFFER);
        this.cmd.clear();
        this.cmd = null;
    }

}