package com.michaelsebero.efficiententities.renderer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import com.michaelsebero.efficiententities.math.MatrixStack;
import com.michaelsebero.efficiententities.util.ModelData;

import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;

/**
 * Batched renderer that accumulates geometry data and performs efficient draw calls.
 * Uses dynamic buffers for streaming vertex data.
 */
public class BatchedModelRenderer {
    
    private static final int VERTEX_SIZE_BYTES = 32; // 3 pos + 2 uv + 3 normal (all floats)
    private static final int INITIAL_CAPACITY = 262144; // 256KB initial capacity
    
    private static BatchedModelRenderer instance;
    
    private ByteBuffer buffer;
    private int vbo;
    private final MatrixStack matrixStack = new MatrixStack();
    
    private int vertexCount = 0;
    private int batchVertexCount = 0;
    private boolean batching = false;
    
    public static BatchedModelRenderer getInstance() {
        if (instance == null) {
            instance = new BatchedModelRenderer();
        }
        return instance;
    }
    
    private BatchedModelRenderer() {
        initializeBuffers();
    }
    
    private void initializeBuffers() {
        buffer = BufferUtils.createByteBuffer(INITIAL_CAPACITY);
        vbo = GL15.glGenBuffers();
    }
    
    public void beginFrame() {
        buffer.clear();
        vertexCount = 0;
    }
    
    public void endFrame() {
        // Frame complete
    }
    
    public void startBatch() {
        batching = true;
        batchVertexCount = 0;
        buffer.clear();
        vertexCount = 0;
    }
    
    public void endBatch() {
        if (!batching) {
            throw new IllegalStateException("Not currently batching");
        }
        
        if (batchVertexCount > 0) {
            flush();
        }
        
        batching = false;
    }
    
    private void flush() {
        // Flip buffer for reading
        buffer.flip();
        
        // Upload data to GPU
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STREAM_DRAW);
        
        // Setup vertex attributes
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
        
        GL11.glVertexPointer(3, GL11.GL_FLOAT, VERTEX_SIZE_BYTES, 0);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, VERTEX_SIZE_BYTES, 12);
        GL11.glNormalPointer(GL11.GL_FLOAT, VERTEX_SIZE_BYTES, 20);
        
        // Draw batched geometry
        GL11.glDrawArrays(GL11.GL_QUADS, 0, batchVertexCount);
        
        // Cleanup
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
        
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        
        // Reset buffer for next batch
        buffer.clear();
    }
    
    /**
     * Render a model using CPU-side matrix transformations and batched geometry
     */
    public void renderModel(ModelRenderer model, float scale) {
        if (model == null) return;
        
        boolean wasBatching = batching;
        if (!wasBatching) {
            startBatch();
        }
        
        // Use recursive approach instead of queue to avoid null issues
        renderModelRecursive(model, scale);
        
        if (!wasBatching) {
            endBatch();
        }
    }
    
    /**
     * Recursively render a model and its children
     */
    private void renderModelRecursive(ModelRenderer model, float scale) {
        if (model == null || model.isHidden || !model.showModel) {
            return;
        }
        
        // Apply transformations
        matrixStack.push();
        matrixStack.translate(
            model.rotationPointX * scale,
            model.rotationPointY * scale,
            model.rotationPointZ * scale
        );
        
        if (model.rotateAngleZ != 0.0F) {
            matrixStack.rotateZ(model.rotateAngleZ);
        }
        if (model.rotateAngleY != 0.0F) {
            matrixStack.rotateY(model.rotateAngleY);
        }
        if (model.rotateAngleX != 0.0F) {
            matrixStack.rotateX(model.rotateAngleX);
        }
        
        // Render cubes
        if (model.cubeList != null) {
            for (Object boxObj : model.cubeList) {
                if (boxObj instanceof ModelBox) {
                    ModelBox box = (ModelBox) boxObj;
                    ModelData data = ModelData.from(box);
                    renderCube(data, scale);
                }
            }
        }
        
        // Render children recursively
        if (model.childModels != null && !model.childModels.isEmpty()) {
            for (ModelRenderer child : model.childModels) {
                if (child != null) {
                    renderModelRecursive(child, scale);
                }
            }
        }
        
        matrixStack.pop();
    }
    
    private void renderCube(ModelData data, float scale) {
        // Ensure buffer has enough capacity
        int requiredCapacity = buffer.position() + (24 * VERTEX_SIZE_BYTES);
        if (requiredCapacity > buffer.capacity()) {
            // Grow buffer
            ByteBuffer newBuffer = BufferUtils.createByteBuffer(buffer.capacity() * 2);
            buffer.flip();
            newBuffer.put(buffer);
            buffer = newBuffer;
        }
        
        // Transform vertices using CPU matrix
        float[][] vertices = data.getTransformedVertices(matrixStack, scale);
        float[][] normals = data.getTransformedNormals(matrixStack);
        float[][] uvs = data.getUVCoordinates();
        
        // Write all 24 vertices (6 faces * 4 vertices)
        for (int face = 0; face < 6; face++) {
            for (int vert = 0; vert < 4; vert++) {
                int idx = face * 4 + vert;
                
                // Position
                buffer.putFloat(vertices[idx][0]);
                buffer.putFloat(vertices[idx][1]);
                buffer.putFloat(vertices[idx][2]);
                
                // UV
                buffer.putFloat(uvs[idx][0]);
                buffer.putFloat(uvs[idx][1]);
                
                // Normal
                buffer.putFloat(normals[face][0]);
                buffer.putFloat(normals[face][1]);
                buffer.putFloat(normals[face][2]);
                
                vertexCount++;
                batchVertexCount++;
            }
        }
    }
    
    public void cleanup() {
        if (vbo != 0) {
            GL15.glDeleteBuffers(vbo);
            vbo = 0;
        }
    }
}
