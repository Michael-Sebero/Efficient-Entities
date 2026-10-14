package com.michaelsebero.efficiententities.opengl;

import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/**
 * Portable CPU-wait-for-GPU primitive built on timestamp queries.
 * Priority: OpenGL 3.3 → ARB_timer_query
 *
 * <p>A timestamp is recorded once every preceding command has completed, and
 * reading {@code GL_QUERY_RESULT} blocks until it is, so waiting on the query
 * waits for the frame that issued it, exactly like a fence.  Fence sync objects
 * are avoided because LWJGL 2 and LWJGL 3 type them differently ({@code GLSync}
 * vs {@code long}): on LWJGL 3 they exist only through a compatibility layer, and
 * lwjglxx (Cleanroom 0.3–0.6) bridges {@code glFenceSync} but not
 * {@code glClientWaitSync}.  The query calls take plain {@code int}s in both.
 *
 * Abstract instance methods are prefixed with "do" to avoid collisions
 * with the public static API.
 */
public enum GpuFence {

    CORE_TIMER_QUERY {
        @Override protected boolean doSupported(GlCapabilities caps) { return caps.atLeast(3, 3); }
        @Override protected void    doIssue(int query)                { GL33.glQueryCounter(query, GL33.GL_TIMESTAMP); }
    },

    ARB_TIMER_QUERY {
        @Override protected boolean doSupported(GlCapabilities caps) { return caps.has("GL_ARB_timer_query"); }
        @Override protected void    doIssue(int query)                { ARBTimerQuery.glQueryCounter(query, ARBTimerQuery.GL_TIMESTAMP); }
    },

    NONE {
        @Override protected boolean doSupported(GlCapabilities caps) { return true; }
        @Override protected void    doIssue(int query) {
            throw new UnsupportedOperationException("No timer query support available");
        }
    };

    // ---------------------------------------------------------------------- Selection

    private static volatile GpuFence chosen = NONE;

    public static void select(GlCapabilities caps) {
        for (GpuFence candidate : values()) {
            if (candidate.doSupported(caps)) {
                chosen = candidate;
                return;
            }
        }
    }

    // ---------------------------------------------------------------------- Public API

    public static boolean isAvailable() { return chosen != NONE; }

    /** Marks the point after every GL command issued so far. */
    public static int create() {
        int query = GL15.glGenQueries();
        chosen.doIssue(query);
        return query;
    }

    /** Blocks until the GPU has passed {@code query}, then deletes it. */
    public static void waitAndDelete(int query) {
        GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT);
        GL15.glDeleteQueries(query);
    }

    // ---------------------------------------------------------------------- Internals

    protected abstract boolean doSupported(GlCapabilities caps);
    protected abstract void    doIssue(int query);
}
