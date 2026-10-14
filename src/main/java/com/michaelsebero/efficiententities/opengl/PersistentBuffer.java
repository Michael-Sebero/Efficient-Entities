package com.michaelsebero.efficiententities.opengl;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.ARBBufferStorage;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;

/**
 * Selects the best available persistent-buffer API at runtime.
 * Priority: OpenGL 4.5 → ARB_direct_state_access → OpenGL 4.4 → ARB_buffer_storage
 *
 * <p>The non-DSA variants bind {@code GL_ARRAY_BUFFER} around each call and
 * leave it unbound, so callers never need to know which variant is active.
 * Every call used here has the same signature in LWJGL 2 and LWJGL 3.
 *
 * Abstract instance methods are prefixed with "do" to avoid name collisions
 * with the public static API methods of the same signature.
 */
public enum PersistentBuffer {

    GL45_DSA {
        @Override protected boolean doSupported(GlCapabilities caps) { return caps.atLeast(4, 5); }
        @Override protected int     doCreate()                             { return GL45.glCreateBuffers(); }
        @Override protected void    doInitStorage(int buf, long size, int flags) {
            GL45.glNamedBufferStorage(buf, size, flags);
        }
        @Override protected ByteBuffer doMap(int buf, long len, int access) {
            return GL45.glMapNamedBufferRange(buf, 0L, len, access, null);
        }
        @Override protected void    doFlush(int buf, long offset, long len) {
            GL45.glFlushMappedNamedBufferRange(buf, offset, len);
        }
        @Override protected void    doUnmap(int buf) {
            GL45.glUnmapNamedBuffer(buf);
        }
    },

    ARB_DSA {
        @Override protected boolean doSupported(GlCapabilities caps) {
            return caps.has("GL_ARB_direct_state_access") && hasBufferStorage(caps);
        }
        @Override protected int     doCreate()                             { return ARBDirectStateAccess.glCreateBuffers(); }
        @Override protected void    doInitStorage(int buf, long size, int flags) {
            ARBDirectStateAccess.glNamedBufferStorage(buf, size, flags);
        }
        @Override protected ByteBuffer doMap(int buf, long len, int access) {
            return ARBDirectStateAccess.glMapNamedBufferRange(buf, 0L, len, access, null);
        }
        @Override protected void    doFlush(int buf, long offset, long len) {
            ARBDirectStateAccess.glFlushMappedNamedBufferRange(buf, offset, len);
        }
        @Override protected void    doUnmap(int buf) {
            ARBDirectStateAccess.glUnmapNamedBuffer(buf);
        }
    },

    GL44_BIND {
        @Override protected boolean doSupported(GlCapabilities caps) { return caps.atLeast(4, 4); }
        @Override protected void    doInitStorage(int buf, long size, int flags) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buf);
            GL44.glBufferStorage(GL15.GL_ARRAY_BUFFER, size, flags);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        }
    },

    ARB_BIND {
        @Override protected boolean doSupported(GlCapabilities caps) {
            return caps.has("GL_ARB_buffer_storage") && caps.atLeast(3, 0); // glMapBufferRange
        }
        @Override protected void    doInitStorage(int buf, long size, int flags) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buf);
            ARBBufferStorage.glBufferStorage(GL15.GL_ARRAY_BUFFER, size, flags);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        }
    },

    NONE {
        @Override protected boolean doSupported(GlCapabilities caps) { return true; }
        private UnsupportedOperationException err() {
            return new UnsupportedOperationException("No persistent buffer extension available");
        }
        @Override protected int        doCreate()                         { throw err(); }
        @Override protected void       doInitStorage(int b, long s, int f) { throw err(); }
        @Override protected ByteBuffer doMap(int b, long l, int a)         { throw err(); }
        @Override protected void       doFlush(int b, long o, long l)      { throw err(); }
        @Override protected void       doUnmap(int b)                      { throw err(); }
    };

    // ---------------------------------------------------------------------- Singleton

    private static volatile PersistentBuffer chosen = NONE;

    public static void select(GlCapabilities caps) {
        for (PersistentBuffer candidate : values()) {
            if (candidate.doSupported(caps)) {
                chosen = candidate;
                return;
            }
        }
    }

    private static boolean hasBufferStorage(GlCapabilities caps) {
        return caps.atLeast(4, 4) || caps.has("GL_ARB_buffer_storage");
    }

    // ---------------------------------------------------------------------- Public API

    public static boolean    isAvailable()                                { return chosen != NONE; }
    public static int        createBuffer()                               { return chosen.doCreate(); }
    public static void       deleteBuffer(int buf)                        { GL15.glDeleteBuffers(buf); }
    public static void       initStorage(int buf, long size, int flags)   { chosen.doInitStorage(buf, size, flags); }
    /** Maps {@code [0, len)}; returns {@code null} if the driver refused the mapping. */
    public static ByteBuffer map(int buf, long len, int access)           { return chosen.doMap(buf, len, access); }
    public static void       flush(int buf, long offset, long len)        { chosen.doFlush(buf, offset, len); }
    public static void       unmap(int buf)                               { chosen.doUnmap(buf); }

    // ---------------------------------------------------------------------- Internals
    // Defaults implement the bind-to-edit path shared by GL44_BIND and ARB_BIND.

    protected abstract boolean doSupported(GlCapabilities caps);

    protected abstract void doInitStorage(int buf, long size, int flags);

    protected int doCreate() {
        return GL15.glGenBuffers();
    }

    protected ByteBuffer doMap(int buf, long len, int access) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buf);
        ByteBuffer mapped = GL30.glMapBufferRange(GL15.GL_ARRAY_BUFFER, 0L, len, access, null);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        return mapped;
    }

    protected void doFlush(int buf, long offset, long len) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buf);
        GL30.glFlushMappedBufferRange(GL15.GL_ARRAY_BUFFER, offset, len);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    protected void doUnmap(int buf) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buf);
        GL15.glUnmapBuffer(GL15.GL_ARRAY_BUFFER);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }
}
