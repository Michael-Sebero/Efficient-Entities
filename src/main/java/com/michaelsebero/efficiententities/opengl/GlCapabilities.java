package com.michaelsebero.efficiententities.opengl;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * OpenGL version and extensions of the current context.
 *
 * <p>Read with {@code glGetString}, {@code glGetInteger} and {@code glGetStringi},
 * which LWJGL 2 and LWJGL 3 declare identically.  LWJGL 2's
 * {@code GLContext.getCapabilities()} exists on LWJGL 3 only through a
 * compatibility layer, and Cleanroom has shipped several different ones.
 */
public final class GlCapabilities {

    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)");

    public final int major, minor;
    private final Set<String> extensions;

    GlCapabilities(int major, int minor, Set<String> extensions) {
        this.major = major;
        this.minor = minor;
        this.extensions = Collections.unmodifiableSet(extensions);
    }

    /** Queries the context current on this thread. */
    public static GlCapabilities query() {
        int[] version = parseVersion(GL11.glGetString(GL11.GL_VERSION));
        Set<String> extensions = new HashSet<>();
        if (version[0] >= 3) {
            // GL_EXTENSIONS via glGetString is removed from core profiles; glGetStringi works everywhere 3.0+.
            int count = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
            for (int i = 0; i < count; i++) {
                String extension = GL30.glGetStringi(GL11.GL_EXTENSIONS, i);
                if (extension != null) extensions.add(extension);
            }
        } else {
            String all = GL11.glGetString(GL11.GL_EXTENSIONS);
            if (all != null) extensions.addAll(Arrays.asList(all.trim().split("\\s+")));
        }
        return new GlCapabilities(version[0], version[1], extensions);
    }

    /** Parses the leading {@code major.minor} of a {@code GL_VERSION} string; {@code 0.0} if absent. */
    static int[] parseVersion(String version) {
        if (version != null) {
            Matcher m = VERSION.matcher(version);
            if (m.find()) return new int[] { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) };
        }
        return new int[] { 0, 0 };
    }

    public boolean atLeast(int major, int minor) {
        return this.major > major || (this.major == major && this.minor >= minor);
    }

    public boolean has(String extension) {
        return extensions.contains(extension);
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
