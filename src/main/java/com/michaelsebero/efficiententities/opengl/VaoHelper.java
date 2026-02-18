package com.michaelsebero.efficiententities.opengl;

import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.ARBVertexArrayObject;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GLContext;

/**
 * Portable vertex array object (VAO) helper.
 * Priority: OpenGL 4.5 → ARB_direct_state_access → OpenGL 3.0 → ARB_vertex_array_object
 *
 * Abstract instance methods are prefixed with "do" to avoid name collisions
 * with the public static API methods of the same signature.
 */
public enum VaoHelper {

    GL45_DSA {
        @Override protected boolean doSupported(ContextCapabilities caps) { return caps.OpenGL45; }
        @Override protected int     doCreate()          { return GL45.glCreateVertexArrays(); }
        @Override protected void    doDelete(int vao)   { GL30.glDeleteVertexArrays(vao); }
        @Override protected void    doBind(int vao)     { GL30.glBindVertexArray(vao); }
    },

    ARB_DSA {
        @Override protected boolean doSupported(ContextCapabilities caps) { return caps.GL_ARB_direct_state_access; }
        @Override protected int     doCreate()          { return ARBDirectStateAccess.glCreateVertexArrays(); }
        @Override protected void    doDelete(int vao)   { GL30.glDeleteVertexArrays(vao); }
        @Override protected void    doBind(int vao)     { GL30.glBindVertexArray(vao); }
    },

    GL30_VAO {
        @Override protected boolean doSupported(ContextCapabilities caps) { return caps.OpenGL30; }
        @Override protected int     doCreate()          { return GL30.glGenVertexArrays(); }
        @Override protected void    doDelete(int vao)   { GL30.glDeleteVertexArrays(vao); }
        @Override protected void    doBind(int vao)     { GL30.glBindVertexArray(vao); }
    },

    ARB_VAO {
        @Override protected boolean doSupported(ContextCapabilities caps) { return caps.GL_ARB_vertex_array_object; }
        @Override protected int     doCreate()          { return ARBVertexArrayObject.glGenVertexArrays(); }
        @Override protected void    doDelete(int vao)   { ARBVertexArrayObject.glDeleteVertexArrays(vao); }
        @Override protected void    doBind(int vao)     { ARBVertexArrayObject.glBindVertexArray(vao); }
    },

    NONE {
        @Override protected boolean doSupported(ContextCapabilities caps) { return true; }
        private UnsupportedOperationException err() {
            return new UnsupportedOperationException("Vertex array objects are not supported");
        }
        @Override protected int  doCreate()        { throw err(); }
        @Override protected void doDelete(int vao) { throw err(); }
        @Override protected void doBind(int vao)   { throw err(); }
    };

    // ---------------------------------------------------------------------- Singleton

    private static volatile VaoHelper chosen;

    private static VaoHelper get() {
        VaoHelper result = chosen;
        if (result == null) {
            ContextCapabilities caps = GLContext.getCapabilities();
            for (VaoHelper candidate : values()) {
                if (candidate.doSupported(caps)) {
                    chosen = result = candidate;
                    break;
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------------- Public API

    public static boolean isAvailable()           { return get() != NONE; }
    public static int  createVao()                { return get().doCreate(); }
    public static void deleteVao(int vao)         { get().doDelete(vao); }
    public static void bindVao(int vao)           { get().doBind(vao); }

    // ---------------------------------------------------------------------- Internals

    protected abstract boolean doSupported(ContextCapabilities caps);
    protected abstract int     doCreate();
    protected abstract void    doDelete(int vao);
    protected abstract void    doBind(int vao);
}
