package com.michaelsebero.efficiententities.util;

import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * Lightweight array-backed 4×4 matrix stack for accumulating per-bone
 * transforms during entity rendering.
 *
 * <h3>Storage convention</h3>
 * Matrices are stored <em>row-major</em>: entry [row i, column j] is at
 * flat index {@code i*4+j} within each 16-float block.  Transform vector v
 * is multiplied as {@code result = M * v}, so the translation lives in
 * column 3 (indices 3, 7, 11).
 *
 * <h3>Post-multiplication</h3>
 * All transform helpers post-multiply ({@code M = M_current * T_new}),
 * matching the order that OpenGL's fixed-function pipeline uses.
 *
 * <h3>Row-3 invariant</h3>
 * Row 3 is always {@code (0, 0, 0, 1)} for affine matrices and is never
 * written by the transform helpers.
 */
public final class MatrixStack {

    private static final int INITIAL_DEPTH = 32;

    /** Flat storage: {@code stack[top * 16 .. top * 16 + 15]} is the active matrix. */
    private float[] stack = new float[INITIAL_DEPTH * 16];
    private int top = 0;

    public MatrixStack() {
        writeIdentity(0);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Reset to a single identity matrix at the base of the stack. */
    public void reset() {
        top = 0;
        writeIdentity(0);
    }

    /** Push a copy of the current top matrix.  Grows for deep (modded) bone hierarchies. */
    public void push() {
        int next = (top + 1) * 16;
        if (next + 16 > stack.length) stack = Arrays.copyOf(stack, stack.length * 2);
        System.arraycopy(stack, top * 16, stack, next, 16);
        top++;
    }

    /** Pop the top matrix, restoring the previous one. */
    public void pop() {
        top--;
    }

    // ------------------------------------------------------------------ element access

    /** Backing array of the top matrix; rows 0–2 start at {@link #offset()}. */
    public float[] array() {
        return stack;
    }

    /** Offset of the top matrix within {@link #array()}. */
    public int offset() {
        return top * 16;
    }

    /**
     * Writes the top matrix into {@code dst} in OpenGL's column-major order,
     * ready for {@code glMultMatrix}.  Leaves {@code dst} flipped.
     */
    public void store(FloatBuffer dst) {
        float[] m = stack;
        int b = top * 16;
        dst.clear();
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                dst.put(m[b + row * 4 + col]);
            }
        }
        dst.flip();
    }

    // ------------------------------------------------------------------ transforms

    /**
     * Post-multiply by translation T(tx, ty, tz).
     * Only column 3 changes: {@code m[i][3] += m[i][0]*tx + m[i][1]*ty + m[i][2]*tz}.
     */
    public void translate(float tx, float ty, float tz) {
        int b = top * 16;
        float[] m = stack;
        // Rows 0-2 only; row 3 is (0,0,0,1) and its col-3 entry stays 1.
        m[b + 3]  += m[b]      * tx + m[b + 1]  * ty + m[b + 2]  * tz;
        m[b + 7]  += m[b + 4]  * tx + m[b + 5]  * ty + m[b + 6]  * tz;
        m[b + 11] += m[b + 8]  * tx + m[b + 9]  * ty + m[b + 10] * tz;
    }

    /**
     * Post-multiply by rotation of {@code angle} radians around the X axis.
     * <pre>
     * Rx = | 1   0    0   0 |
     *      | 0  cos -sin  0 |
     *      | 0  sin  cos  0 |
     *      | 0   0    0   1 |
     * </pre>
     * Columns 1 and 2 of M are updated per row:
     * {@code col1' = c*col1 + s*col2},  {@code col2' = -s*col1 + c*col2}.
     */
    public void rotateX(float angle) {
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        int b = top * 16;
        float[] m = stack;
        float c1, c2;
        for (int row = 0; row < 3; row++) {
            int i = b + row * 4;
            c1 = m[i + 1];  c2 = m[i + 2];
            m[i + 1] =  c * c1 + s * c2;
            m[i + 2] = -s * c1 + c * c2;
        }
    }

    /**
     * Post-multiply by rotation of {@code angle} radians around the Y axis.
     * <pre>
     * Ry = | cos  0  sin  0 |
     *      |  0   1   0   0 |
     *      |-sin  0  cos  0 |
     *      |  0   0   0   1 |
     * </pre>
     * Columns 0 and 2 update: {@code col0' = c*col0 - s*col2},
     * {@code col2' = s*col0 + c*col2}.
     */
    public void rotateY(float angle) {
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        int b = top * 16;
        float[] m = stack;
        float c0, c2;
        for (int row = 0; row < 3; row++) {
            int i = b + row * 4;
            c0 = m[i];      c2 = m[i + 2];
            m[i]     = c * c0 - s * c2;
            m[i + 2] = s * c0 + c * c2;
        }
    }

    /**
     * Post-multiply by rotation of {@code angle} radians around the Z axis.
     * <pre>
     * Rz = | cos -sin  0  0 |
     *      | sin  cos  0  0 |
     *      |  0    0   1  0 |
     *      |  0    0   0  1 |
     * </pre>
     * Columns 0 and 1 update: {@code col0' = c*col0 + s*col1},
     * {@code col1' = -s*col0 + c*col1}.
     */
    public void rotateZ(float angle) {
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        int b = top * 16;
        float[] m = stack;
        float c0, c1;
        for (int row = 0; row < 3; row++) {
            int i = b + row * 4;
            c0 = m[i];      c1 = m[i + 1];
            m[i]     =  c * c0 + s * c1;
            m[i + 1] = -s * c0 + c * c1;
        }
    }

    // ------------------------------------------------------------------ private

    private void writeIdentity(int top) {
        int b = top * 16;
        float[] m = stack;
        m[b]    = 1f; m[b+1]  = 0f; m[b+2]  = 0f; m[b+3]  = 0f;
        m[b+4]  = 0f; m[b+5]  = 1f; m[b+6]  = 0f; m[b+7]  = 0f;
        m[b+8]  = 0f; m[b+9]  = 0f; m[b+10] = 1f; m[b+11] = 0f;
        m[b+12] = 0f; m[b+13] = 0f; m[b+14] = 0f; m[b+15] = 1f;
    }
}
