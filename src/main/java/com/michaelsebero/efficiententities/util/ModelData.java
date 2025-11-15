package com.michaelsebero.efficiententities.util;

import java.lang.reflect.Field;

import com.michaelsebero.efficiententities.math.MatrixStack;

import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.PositionTextureVertex;
import net.minecraft.client.model.TexturedQuad;

/**
 * Extracts and caches geometry data from Minecraft's ModelBox for efficient rendering
 */
public class ModelData {
    
    private final float[][] vertices;     // 24 vertices (6 faces * 4 vertices each)
    private final float[][] uvCoords;     // UV coordinates for each vertex
    private final float[][] normals;      // 6 face normals
    
    private ModelData(float[][] vertices, float[][] uvCoords, float[][] normals) {
        this.vertices = vertices;
        this.uvCoords = uvCoords;
        this.normals = normals;
    }
    
    /**
     * Extract geometry data from a ModelBox
     */
    public static ModelData from(ModelBox box) {
        try {
            // Get the quads array
            Field quadField = getFieldByNames(ModelBox.class, "quadList", "field_78254_i");
            if (quadField == null) {
                quadField = getFieldByType(ModelBox.class, TexturedQuad[].class);
            }
            if (quadField == null) {
                throw new RuntimeException("Could not find quadList field in ModelBox");
            }
            
            quadField.setAccessible(true);
            TexturedQuad[] quads = (TexturedQuad[]) quadField.get(box);
            
            if (quads == null || quads.length != 6) {
                throw new RuntimeException("Invalid ModelBox: expected 6 quads, got " + (quads == null ? "null" : quads.length));
            }
            
            // Extract vertices and UVs directly from the quads
            // Minecraft stores quads in order: +X, -X, +Y, -Y, +Z, -Z
            float[][] vertices = new float[24][3];
            float[][] uvCoords = new float[24][2];
            
            int vertexIndex = 0;
            for (int faceIndex = 0; faceIndex < 6; faceIndex++) {
                PositionTextureVertex[] quadVerts = getQuadVertices(quads[faceIndex]);
                
                for (int i = 0; i < 4; i++) {
                    PositionTextureVertex vert = quadVerts[i];
                    
                    // Store position
                    vertices[vertexIndex][0] = (float) vert.vector3D.x;
                    vertices[vertexIndex][1] = (float) vert.vector3D.y;
                    vertices[vertexIndex][2] = (float) vert.vector3D.z;
                    
                    // Store UV
                    uvCoords[vertexIndex][0] = vert.texturePositionX;
                    uvCoords[vertexIndex][1] = vert.texturePositionY;
                    
                    vertexIndex++;
                }
            }
            
            // Define face normals in the same order as the quads
            float[][] normals = {
                { 1.0f,  0.0f,  0.0f}, // +X (east)
                {-1.0f,  0.0f,  0.0f}, // -X (west)
                { 0.0f,  1.0f,  0.0f}, // +Y (up)
                { 0.0f, -1.0f,  0.0f}, // -Y (down)
                { 0.0f,  0.0f,  1.0f}, // +Z (south)
                { 0.0f,  0.0f, -1.0f}  // -Z (north)
            };
            
            return new ModelData(vertices, uvCoords, normals);
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract model data", e);
        }
    }
    
    /**
     * Try to get a field by multiple names
     */
    private static Field getFieldByNames(Class<?> clazz, String... names) {
        for (String name : names) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                // Try next name
            }
        }
        return null;
    }
    
    /**
     * Try to get a field by its type
     */
    private static Field getFieldByType(Class<?> clazz, Class<?> fieldType) {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.getType() == fieldType) {
                return field;
            }
        }
        return null;
    }
    
    /**
     * Extract vertices from a TexturedQuad
     */
    private static PositionTextureVertex[] getQuadVertices(TexturedQuad quad) {
        try {
            Field vertField = getFieldByNames(TexturedQuad.class, "vertexPositions", "field_178209_a");
            if (vertField == null) {
                vertField = getFieldByType(TexturedQuad.class, PositionTextureVertex[].class);
            }
            if (vertField == null) {
                throw new RuntimeException("Could not find vertexPositions field in TexturedQuad");
            }
            
            vertField.setAccessible(true);
            PositionTextureVertex[] verts = (PositionTextureVertex[]) vertField.get(quad);
            
            if (verts == null || verts.length != 4) {
                throw new RuntimeException("Invalid TexturedQuad: expected 4 vertices, got " + (verts == null ? "null" : verts.length));
            }
            
            return verts;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get quad vertices", e);
        }
    }
    
    /**
     * Get transformed vertices for rendering
     */
    public float[][] getTransformedVertices(MatrixStack stack, float scale) {
        float[][] transformed = new float[24][3];
        
        for (int i = 0; i < 24; i++) {
            float[] pos = stack.transform(
                vertices[i][0] * scale,
                vertices[i][1] * scale,
                vertices[i][2] * scale
            );
            transformed[i] = pos;
        }
        
        return transformed;
    }
    
    /**
     * Get transformed normals for all 6 faces
     */
    public float[][] getTransformedNormals(MatrixStack stack) {
        float[][] transformed = new float[6][3];
        
        for (int i = 0; i < 6; i++) {
            transformed[i] = stack.transformNormal(
                normals[i][0],
                normals[i][1],
                normals[i][2]
            );
        }
        
        return transformed;
    }
    
    /**
     * Get UV coordinates
     */
    public float[][] getUVCoordinates() {
        return uvCoords;
    }
}
