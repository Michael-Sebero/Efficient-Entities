package com.michaelsebero.efficiententities.opengl;

import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.ARBSync;
import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GLSync;

/**
 * Portable CPU-wait-for-GPU primitive.
 * Priority: GL4.5 DSA query → ARB DSA query → GL3.3 query →
 *           ARB_timer_query → GL3.2 fence → ARB_sync
 *
 * Abstract instance methods are prefixed with "do" to avoid collisions
 * with the public static API.
 */
public enum GpuSync {

    GL45_QUERY {
        @Override protected boolean doSupported(ContextCapabilities caps) { return caps.OpenGL45; }
        @Override protected Object  doCreate() { return GL45.glCreateQueries(GL33.GL_TIMESTAMP); }
        @Override protected void    doDelete(Object h) { GL15.glDeleteQueries((int) h); }
        @Override protected void    doWait(Object h)   { GL33.glGetQueryObjectui64((int) h, GL15.GL_QUERY_RESULT); }
    },

    ARB_DSA_QUERY {
        @Override protected boolean doSupported(ContextCapabilities caps) { return caps.GL_ARB_direct_state_access; }
        @Override protected Object  doCreate() { return ARBDirectStateAccess.glCreateQueries(GL33.GL_TIMESTAMP); }
        @Override protected void    doDelete(Object h) { GL15.glDeleteQueries((int) h); }
        @Override protected void    doWait(Object h)   { GL33.glGetQueryObjectui64((int) h, GL15.GL_QUERY_RESULT); }
    },

    GL33_QUERY {
        @Override protected boolean doSupported(ContextCapabilities caps) { return caps.OpenGL33; }
        @Override protected Object  doCreate() {
            int id = GL15.glGenQueries();
            GL33.glQueryCounter(id, GL33.GL_TIMESTAMP);
            return id;
        }
        @Override protected void doDelete(Object h) { GL15.glDeleteQueries((int) h); }
        @Override protected void doWait(Object h)   { GL33.glGetQueryObjectui64((int) h, GL15.GL_QUERY_RESULT); }
    },

    ARB_TIMER_QUERY {
        @Override protected boolean doSupported(ContextCapabilities caps) { return caps.GL_ARB_timer_query; }
        @Override protected Object  doCreate() {
            int id = GL15.glGenQueries();
            ARBTimerQuery.glQueryCounter(id, ARBTimerQuery.GL_TIMESTAMP);
            return id;
        }
        @Override protected void doDelete(Object h) { GL15.glDeleteQueries((int) h); }
        @Override protected void doWait(Object h)   { ARBTimerQuery.glGetQueryObjectui64((int) h, GL15.GL_QUERY_RESULT); }
    },

    GL32_FENCE {
        @Override protected boolean doSupported(ContextCapabilities caps) { return caps.OpenGL32; }
        @Override protected Object  doCreate() { return GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0); }
        @Override protected void    doDelete(Object h) { GL32.glDeleteSync((GLSync) h); }
        @Override protected void    doWait(Object h)   { GL32.glClientWaitSync((GLSync) h, 0, Long.MAX_VALUE); }
    },

    ARB_FENCE {
        @Override protected boolean doSupported(ContextCapabilities caps) { return caps.GL_ARB_sync; }
        @Override protected Object  doCreate() { return ARBSync.glFenceSync(ARBSync.GL_SYNC_GPU_COMMANDS_COMPLETE, 0); }
        @Override protected void    doDelete(Object h) { ARBSync.glDeleteSync((GLSync) h); }
        @Override protected void    doWait(Object h)   { ARBSync.glClientWaitSync((GLSync) h, 0, Long.MAX_VALUE); }
    },

    NONE {
        @Override protected boolean doSupported(ContextCapabilities caps) { return true; }
        private UnsupportedOperationException err() {
            return new UnsupportedOperationException("No GPU sync extension available");
        }
        @Override protected Object doCreate()         { throw err(); }
        @Override protected void   doDelete(Object h) { throw err(); }
        @Override protected void   doWait(Object h)   { throw err(); }
    };

    // ---------------------------------------------------------------------- Singleton

    private static volatile GpuSync chosen;

    private static GpuSync get() {
        GpuSync result = chosen;
        if (result == null) {
            ContextCapabilities caps = GLContext.getCapabilities();
            for (GpuSync candidate : values()) {
                if (candidate.doSupported(caps)) {
                    chosen = result = candidate;
                    break;
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------------- Public API

    public static boolean isAvailable()        { return get() != NONE; }
    public static Object  createSync()         { return get().doCreate(); }
    public static void    deleteSync(Object h) { get().doDelete(h); }
    public static void    waitSync(Object h)   { get().doWait(h); }

    // ---------------------------------------------------------------------- Internals

    protected abstract boolean doSupported(ContextCapabilities caps);
    protected abstract Object  doCreate();
    protected abstract void    doDelete(Object handle);
    protected abstract void    doWait(Object handle);
}
