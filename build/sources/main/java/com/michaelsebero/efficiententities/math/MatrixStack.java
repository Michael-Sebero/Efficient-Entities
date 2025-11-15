package com.michaelsebero.efficiententities.math;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * CPU-side matrix stack for efficient transformations without GPU state changes.
 * Replaces glPushMatrix/glPopMatrix with pure CPU calculations.
 */
public class MatrixStack {
    
    private final Deque<Matrix4f> stack = new ArrayDeque<>();
    private Matrix4f current;
    
    public MatrixStack() {
        current = Matrix4f.identity();
    }
    
    public void push() {
        stack.push(current.copy());
        current = current.copy();
    }
    
    public void pop() {
        if (stack.isEmpty()) {
            throw new IllegalStateException("Matrix stack underflow");
        }
        current = stack.pop();
    }
    
    public void translate(float x, float y, float z) {
        current.translate(x, y, z);
    }
    
    public void rotateX(float radians) {
        current.rotateX(radians);
    }
    
    public void rotateY(float radians) {
        current.rotateY(radians);
    }
    
    public void rotateZ(float radians) {
        current.rotateZ(radians);
    }
    
    public void scale(float x, float y, float z) {
        current.scale(x, y, z);
    }
    
    public Matrix4f getMatrix() {
        return current;
    }
    
    /**
     * Transform a point by the current matrix
     */
    public float[] transform(float x, float y, float z) {
        return current.transform(x, y, z);
    }
    
    /**
     * Transform a normal vector by the current matrix (rotation only)
     */
    public float[] transformNormal(float x, float y, float z) {
        return current.transformNormal(x, y, z);
    }
}

/**
 * 4x4 transformation matrix for 3D graphics
 */
class Matrix4f {
    
    private final float[] m = new float[16];
    
    private Matrix4f() {}
    
    public static Matrix4f identity() {
        Matrix4f mat = new Matrix4f();
        mat.m[0] = 1; mat.m[5] = 1; mat.m[10] = 1; mat.m[15] = 1;
        return mat;
    }
    
    public Matrix4f copy() {
        Matrix4f mat = new Matrix4f();
        System.arraycopy(this.m, 0, mat.m, 0, 16);
        return mat;
    }
    
    public void translate(float x, float y, float z) {
        m[12] += m[0] * x + m[4] * y + m[8] * z;
        m[13] += m[1] * x + m[5] * y + m[9] * z;
        m[14] += m[2] * x + m[6] * y + m[10] * z;
        m[15] += m[3] * x + m[7] * y + m[11] * z;
    }
    
    public void rotateX(float rad) {
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        
        float m4 = m[4], m5 = m[5], m6 = m[6], m7 = m[7];
        float m8 = m[8], m9 = m[9], m10 = m[10], m11 = m[11];
        
        m[4] = m4 * cos + m8 * sin;
        m[5] = m5 * cos + m9 * sin;
        m[6] = m6 * cos + m10 * sin;
        m[7] = m7 * cos + m11 * sin;
        
        m[8] = m8 * cos - m4 * sin;
        m[9] = m9 * cos - m5 * sin;
        m[10] = m10 * cos - m6 * sin;
        m[11] = m11 * cos - m7 * sin;
    }
    
    public void rotateY(float rad) {
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        
        float m0 = m[0], m1 = m[1], m2 = m[2], m3 = m[3];
        float m8 = m[8], m9 = m[9], m10 = m[10], m11 = m[11];
        
        m[0] = m0 * cos - m8 * sin;
        m[1] = m1 * cos - m9 * sin;
        m[2] = m2 * cos - m10 * sin;
        m[3] = m3 * cos - m11 * sin;
        
        m[8] = m0 * sin + m8 * cos;
        m[9] = m1 * sin + m9 * cos;
        m[10] = m2 * sin + m10 * cos;
        m[11] = m3 * sin + m11 * cos;
    }
    
    public void rotateZ(float rad) {
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        
        float m0 = m[0], m1 = m[1], m2 = m[2], m3 = m[3];
        float m4 = m[4], m5 = m[5], m6 = m[6], m7 = m[7];
        
        m[0] = m0 * cos + m4 * sin;
        m[1] = m1 * cos + m5 * sin;
        m[2] = m2 * cos + m6 * sin;
        m[3] = m3 * cos + m7 * sin;
        
        m[4] = m4 * cos - m0 * sin;
        m[5] = m5 * cos - m1 * sin;
        m[6] = m6 * cos - m2 * sin;
        m[7] = m7 * cos - m3 * sin;
    }
    
    public void scale(float x, float y, float z) {
        m[0] *= x; m[1] *= x; m[2] *= x; m[3] *= x;
        m[4] *= y; m[5] *= y; m[6] *= y; m[7] *= y;
        m[8] *= z; m[9] *= z; m[10] *= z; m[11] *= z;
    }
    
    public float[] transform(float x, float y, float z) {
        return new float[] {
            m[0] * x + m[4] * y + m[8] * z + m[12],
            m[1] * x + m[5] * y + m[9] * z + m[13],
            m[2] * x + m[6] * y + m[10] * z + m[14]
        };
    }
    
    public float[] transformNormal(float x, float y, float z) {
        float nx = m[0] * x + m[4] * y + m[8] * z;
        float ny = m[1] * x + m[5] * y + m[9] * z;
        float nz = m[2] * x + m[6] * y + m[10] * z;
        
        // Normalize
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 0) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        
        return new float[] { nx, ny, nz };
    }
}
