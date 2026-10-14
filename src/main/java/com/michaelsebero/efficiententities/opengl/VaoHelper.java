package com.michaelsebero.efficiententities.opengl;

import org.lwjgl.opengl.ARBVertexArrayObject;
import org.lwjgl.opengl.GL30;

/**
 * Portable vertex array object (VAO) helper.
 * Priority: OpenGL 3.0 → ARB_vertex_array_object
 *
 * Abstract instance methods are prefixed with "do" to avoid name collisions
 * with the public static API methods of the same signature.
 */
public enum VaoHelper {

    GL30_VAO {
        @Override protected boolean doSupported(GlCapabilities caps) { return caps.atLeast(3, 0); }
        @Override protected int     doCreate()          { return GL30.glGenVertexArrays(); }
        @Override protected void    doDelete(int vao)   { GL30.glDeleteVertexArrays(vao); }
        @Override protected void    doBind(int vao)     { GL30.glBindVertexArray(vao); }
    },

    ARB_VAO {
        @Override protected boolean doSupported(GlCapabilities caps) { return caps.has("GL_ARB_vertex_array_object"); }
        @Override protected int     doCreate()          { return ARBVertexArrayObject.glGenVertexArrays(); }
        @Override protected void    doDelete(int vao)   { ARBVertexArrayObject.glDeleteVertexArrays(vao); }
        @Override protected void    doBind(int vao)     { ARBVertexArrayObject.glBindVertexArray(vao); }
    },

    NONE {
        @Override protected boolean doSupported(GlCapabilities caps) { return true; }
        private UnsupportedOperationException err() {
            return new UnsupportedOperationException("Vertex array objects are not supported");
        }
        @Override protected int  doCreate()        { throw err(); }
        @Override protected void doDelete(int vao) { throw err(); }
        @Override protected void doBind(int vao)   { throw err(); }
    };

    // ---------------------------------------------------------------------- Selection

    private static volatile VaoHelper chosen = NONE;

    public static void select(GlCapabilities caps) {
        for (VaoHelper candidate : values()) {
            if (candidate.doSupported(caps)) {
                chosen = candidate;
                return;
            }
        }
    }

    // ---------------------------------------------------------------------- Public API

    public static boolean isAvailable()           { return chosen != NONE; }
    public static int  createVao()                { return chosen.doCreate(); }
    public static void deleteVao(int vao)         { chosen.doDelete(vao); }
    public static void bindVao(int vao)           { chosen.doBind(vao); }

    // ---------------------------------------------------------------------- Internals

    protected abstract boolean doSupported(GlCapabilities caps);
    protected abstract int     doCreate();
    protected abstract void    doDelete(int vao);
    protected abstract void    doBind(int vao);
}
