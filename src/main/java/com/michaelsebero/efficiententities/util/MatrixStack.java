package com.michaelsebero.efficiententities.util;

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

    private static final int MAX_DEPTH = 32;

    /** Flat storage: {@code stack[top * 16 .. top * 16 + 15]} is the active matrix. */
    private final float[] stack = new float[MAX_DEPTH * 16];
    private int top = 0;

    public MatrixStack() {
        writeIdentity(0);
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Reset to a single identity matrix at the base of the stack.
     * Called once per entity batch from {@link
     * com.michaelsebero.efficiententities.renderer.EfficientModelRenderer#startBatch()}.
     */
    public void reset() {
        top = 0;
        writeIdentity(0);
    }

    /** Push a copy of the current top matrix. */
    public void push() {
        System.arraycopy(stack, top * 16, stack, (top + 1) * 16, 16);
        top++;
    }

    /** Pop the top matrix, restoring the previous one. */
    public void pop() {
        top--;
    }

    // ------------------------------------------------------------------ matrix element accessors

    /** Row 0 – world X coefficients (m00, m01, m02) and translation m03. */
    public float m00() { return stack[top * 16]; }
    public float m01() { return stack[top * 16 + 1]; }
    public float m02() { return stack[top * 16 + 2]; }
    public float m03() { return stack[top * 16 + 3]; }

    /** Row 1 – world Y coefficients and translation m13. */
    public float m10() { return stack[top * 16 + 4]; }
    public float m11() { return stack[top * 16 + 5]; }
    public float m12() { return stack[top * 16 + 6]; }
    public float m13() { return stack[top * 16 + 7]; }

    /** Row 2 – world Z coefficients and translation m23. */
    public float m20() { return stack[top * 16 + 8]; }
    public float m21() { return stack[top * 16 + 9]; }
    public float m22() { return stack[top * 16 + 10]; }
    public float m23() { return stack[top * 16 + 11]; }

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
