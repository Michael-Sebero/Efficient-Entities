package com.michaelsebero.efficiententities.opengl;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.ARBBufferStorage;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GLContext;

/**
 * Selects the best available persistent-buffer API at runtime.
 * Priority: OpenGL 4.5 → ARB_direct_state_access → OpenGL 4.4 → ARB_buffer_storage
 *
 * Abstract instance methods are prefixed with "do" to avoid name collisions
 * with the public static API methods of the same signature.
 */
public enum PersistentBuffer {

    GL45_DSA {
        @Override protected boolean doSupported(ContextCapabilities caps) { return caps.OpenGL45; }
        @Override protected int     doCreate()                            { return GL45.glCreateBuffers(); }
        @Override protected void    doDelete(int buf)                     { GL15.glDeleteBuffers(buf); }
        @Override protected void    doBind(int target, int buf, boolean force) {
            if (force) GL15.glBindBuffer(target, buf);
        }
        @Override protected void    doInitStorage(int target, int buf, long size, int flags) {
            GL45.glNamedBufferStorage(buf, size, flags);
        }
        @Override protected ByteBuffer doMap(int target, int buf, long offset, long len, int access) {
            return GL45.glMapNamedBufferRange(buf, offset, len, access, null);
        }
        @Override protected void    doFlush(int target, int buf, long offset, long len) {
            GL45.glFlushMappedNamedBufferRange(buf, offset, len);
        }
        @Override protected void    doUnmap(int target, int buf) {
            GL45.glUnmapNamedBuffer(buf);
        }
    },

    ARB_DSA {
        @Override protected boolean doSupported(ContextCapabilities caps) { return caps.GL_ARB_direct_state_access; }
        @Override protected int     doCreate()                            { return ARBDirectStateAccess.glCreateBuffers(); }
        @Override protected void    doDelete(int buf)                     { GL15.glDeleteBuffers(buf); }
        @Override protected void    doBind(int target, int buf, boolean force) {
            if (force) GL15.glBindBuffer(target, buf);
        }
        @Override protected void    doInitStorage(int target, int buf, long size, int flags) {
            ARBDirectStateAccess.glNamedBufferStorage(buf, size, flags);
        }
        @Override protected ByteBuffer doMap(int target, int buf, long offset, long len, int access) {
            return ARBDirectStateAccess.glMapNamedBufferRange(buf, offset, len, access, null);
        }
        @Override protected void    doFlush(int target, int buf, long offset, long len) {
            ARBDirectStateAccess.glFlushMappedNamedBufferRange(buf, offset, len);
        }
        @Override protected void    doUnmap(int target, int buf) {
            ARBDirectStateAccess.glUnmapNamedBuffer(buf);
        }
    },

    GL44 {
        @Override protected boolean doSupported(ContextCapabilities caps) { return caps.OpenGL44; }
        @Override protected int     doCreate()                            { return GL15.glGenBuffers(); }
        @Override protected void    doDelete(int buf)                     { GL15.glDeleteBuffers(buf); }
        @Override protected void    doBind(int target, int buf, boolean force) {
            GL15.glBindBuffer(target, buf);
        }
        @Override protected void    doInitStorage(int target, int buf, long size, int flags) {
            org.lwjgl.opengl.GL44.glBufferStorage(target, size, flags);
        }
        @Override protected ByteBuffer doMap(int target, int buf, long offset, long len, int access) {
            return GL30.glMapBufferRange(target, offset, len, access, null);
        }
        @Override protected void    doFlush(int target, int buf, long offset, long len) {
            GL30.glFlushMappedBufferRange(target, offset, len);
        }
        @Override protected void    doUnmap(int target, int buf) {
            GL15.glUnmapBuffer(target);
        }
    },

    ARB_BS {
        @Override protected boolean doSupported(ContextCapabilities caps) { return caps.GL_ARB_buffer_storage; }
        @Override protected int     doCreate()                            { return GL15.glGenBuffers(); }
        @Override protected void    doDelete(int buf)                     { GL15.glDeleteBuffers(buf); }
        @Override protected void    doBind(int target, int buf, boolean force) {
            GL15.glBindBuffer(target, buf);
        }
        @Override protected void    doInitStorage(int target, int buf, long size, int flags) {
            ARBBufferStorage.glBufferStorage(target, size, flags);
        }
        @Override protected ByteBuffer doMap(int target, int buf, long offset, long len, int access) {
            return GL30.glMapBufferRange(target, offset, len, access, null);
        }
        @Override protected void    doFlush(int target, int buf, long offset, long len) {
            GL30.glFlushMappedBufferRange(target, offset, len);
        }
        @Override protected void    doUnmap(int target, int buf) {
            GL15.glUnmapBuffer(target);
        }
    },

    NONE {
        @Override protected boolean doSupported(ContextCapabilities caps) { return true; }
        private UnsupportedOperationException err() {
            return new UnsupportedOperationException("No persistent buffer extension available");
        }
        @Override protected int        doCreate()                                          { throw err(); }
        @Override protected void       doDelete(int b)                                     { throw err(); }
        @Override protected void       doBind(int t, int b, boolean f)                    { throw err(); }
        @Override protected void       doInitStorage(int t, int b, long s, int f)         { throw err(); }
        @Override protected ByteBuffer doMap(int t, int b, long o, long l, int a)         { throw err(); }
        @Override protected void       doFlush(int t, int b, long o, long l)              { throw err(); }
        @Override protected void       doUnmap(int t, int b)                              { throw err(); }
    };

    // ---------------------------------------------------------------------- Singleton

    private static volatile PersistentBuffer chosen;

    public static PersistentBuffer get() {
        PersistentBuffer result = chosen;
        if (result == null) {
            ContextCapabilities caps = GLContext.getCapabilities();
            for (PersistentBuffer candidate : values()) {
                if (candidate.doSupported(caps)) {
                    chosen = result = candidate;
                    break;
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------------- Public API

    public static boolean isAvailable()                                           { return get() != NONE; }
    public static int  createBuffer()                                             { return get().doCreate(); }
    public static void deleteBuffer(int buf)                                      { get().doDelete(buf); }
    public static void bindBuffer(int target, int buf, boolean required)          { get().doBind(target, buf, required); }
    public static void initStorage(int target, int buf, long size, int flags)     { get().doInitStorage(target, buf, size, flags); }
    public static ByteBuffer mapBuffer(int target, int buf, long off, long len, int access) {
        return get().doMap(target, buf, off, len, access);
    }
    public static void flushBuffer(int target, int buf, long off, long len)       { get().doFlush(target, buf, off, len); }
    public static void unmapBuffer(int target, int buf)                           { get().doUnmap(target, buf); }

    // ---------------------------------------------------------------------- Internals

    protected abstract boolean    doSupported(ContextCapabilities caps);
    protected abstract int        doCreate();
    protected abstract void       doDelete(int buf);
    protected abstract void       doBind(int target, int buf, boolean force);
    protected abstract void       doInitStorage(int target, int buf, long size, int flags);
    protected abstract ByteBuffer doMap(int target, int buf, long offset, long length, int access);
    protected abstract void       doFlush(int target, int buf, long offset, long length);
    protected abstract void       doUnmap(int target, int buf);
}
