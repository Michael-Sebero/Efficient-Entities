package com.michaelsebero.efficiententities.opengl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import com.michaelsebero.efficiententities.renderer.EfficientModelRenderer;

/**
 * Resolves every LWJGL class, method and field the mod references against
 * LWJGL 2.9.4 (Forge) and against plain LWJGL 3 as shipped by each Cleanroom
 * release line, without any LWJGL 2 compatibility layer.
 *
 * <p>Cleanroom's compatibility layers (lwjglxx in 0.3–0.6, LWJGLY in 0.7) only
 * add members that LWJGL 3 lacks, and each covers a different subset, so a
 * reference that resolves in plain LWJGL 3 works on every Cleanroom version.
 * A reference that needs the layer is exactly what crashed 0.6.13:
 * {@code GL32.glClientWaitSync(GLSync, int, long)} exists in LWJGLY but not lwjglxx.
 *
 * <p>The environments' jars are supplied by Gradle (see {@code build.gradle}).
 */
class LwjglApiCompatibilityTest {

    private static final String ENV_PREFIX = "lwjgl.env.";

    @Test
    void everyLwjglReferenceResolvesInEveryEnvironment() throws Exception {
        Set<String> references = lwjglReferences();
        assertFalse(references.isEmpty());

        Map<String, String> environments = new HashMap<>();
        System.getProperties().forEach((k, v) -> {
            if (k.toString().startsWith(ENV_PREFIX)) environments.put(k.toString().substring(ENV_PREFIX.length()), v.toString());
        });
        assertTrue(environments.size() >= 4, "environments configured: " + environments.keySet());

        System.out.println("LWJGL references checked (" + references.size() + "):");
        references.forEach(r -> System.out.println("  " + r));

        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> env : new java.util.TreeMap<>(environments).entrySet()) {
            Map<String, ClassInfo> classes = index(env.getValue());
            for (String reference : references) {
                if (!resolves(classes, reference)) failures.add(env.getKey() + ": missing " + reference);
            }
        }
        if (!failures.isEmpty()) fail("Unresolvable LWJGL references:\n  " + String.join("\n  ", failures));
    }

    // ------------------------------------------------------------------ references made by the mod

    /** {@code C owner}, {@code M owner.name desc} or {@code F owner.name desc}. */
    private static Set<String> lwjglReferences() throws IOException, URISyntaxException {
        Set<String> refs = new TreeSet<>();
        Path root = new File(EfficientModelRenderer.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toPath();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (!file.toString().endsWith(".class")) continue;
                ClassNode node = new ClassNode();
                new ClassReader(Files.readAllBytes(file)).accept(node, 0);
                addTypes(refs, Type.getObjectType(node.superName));
                for (String itf : node.interfaces) addTypes(refs, Type.getObjectType(itf));
                for (FieldNode field : node.fields) addTypes(refs, Type.getType(field.desc));
                for (MethodNode method : node.methods) {
                    addTypes(refs, Type.getMethodType(method.desc));
                    for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (insn instanceof MethodInsnNode) {
                            MethodInsnNode call = (MethodInsnNode) insn;
                            if (isLwjgl(call.owner)) refs.add("M " + call.owner + "." + call.name + call.desc);
                            addTypes(refs, Type.getObjectType(call.owner));
                            addTypes(refs, Type.getMethodType(call.desc));
                        } else if (insn instanceof FieldInsnNode) {
                            FieldInsnNode access = (FieldInsnNode) insn;
                            if (isLwjgl(access.owner)) refs.add("F " + access.owner + "." + access.name + " " + access.desc);
                            addTypes(refs, Type.getObjectType(access.owner));
                            addTypes(refs, Type.getType(access.desc));
                        } else if (insn instanceof TypeInsnNode) {
                            addTypes(refs, Type.getObjectType(((TypeInsnNode) insn).desc));
                        } else if (insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof Type) {
                            addTypes(refs, (Type) ((LdcInsnNode) insn).cst);
                        }
                    }
                }
            }
        }
        return refs;
    }

    private static void addTypes(Set<String> refs, Type type) {
        switch (type.getSort()) {
            case Type.METHOD:
                for (Type arg : type.getArgumentTypes()) addTypes(refs, arg);
                addTypes(refs, type.getReturnType());
                break;
            case Type.ARRAY:
                addTypes(refs, type.getElementType());
                break;
            case Type.OBJECT:
                if (isLwjgl(type.getInternalName())) refs.add("C " + type.getInternalName());
                break;
            default:
                break;
        }
    }

    private static boolean isLwjgl(String internalName) {
        return internalName.startsWith("org/lwjgl/");
    }

    // ------------------------------------------------------------------ environment model

    private static boolean resolves(Map<String, ClassInfo> classes, String reference) {
        String kind = reference.substring(0, 1), body = reference.substring(2);
        if (kind.equals("C")) return classes.containsKey(body);
        int dot = body.lastIndexOf('.', kind.equals("M") ? body.indexOf('(') : body.indexOf(' '));
        String owner = body.substring(0, dot);
        String member = body.substring(dot + 1);
        // JVM resolution: the class itself, then superclasses, then superinterfaces.
        List<String> pending = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        pending.add(owner);
        while (!pending.isEmpty()) {
            ClassInfo info = classes.get(pending.remove(0));
            if (info == null || !seen.add(info.name)) continue;
            if ((kind.equals("M") ? info.methods : info.fields).contains(member)) return true;
            if (info.superName != null) pending.add(info.superName);
            pending.addAll(info.interfaces);
        }
        return false;
    }

    private static Map<String, ClassInfo> index(String classpath) throws IOException {
        Map<String, ClassInfo> classes = new HashMap<>();
        for (String path : classpath.split(File.pathSeparator)) {
            if (path.isEmpty() || !path.endsWith(".jar")) continue;
            try (JarFile jar = new JarFile(path)) {
                for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                    JarEntry entry = e.nextElement();
                    String name = entry.getName();
                    if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.endsWith("module-info.class")) continue;
                    try (InputStream in = jar.getInputStream(entry)) {
                        ClassInfo info = ClassInfo.parse(in);
                        classes.putIfAbsent(info.name, info);
                    }
                }
            }
        }
        return classes;
    }

    /**
     * Minimal class-file reader (names, supertypes and member signatures only).
     * Independent of the class-file version, unlike the ASM that ships with Forge.
     */
    private static final class ClassInfo {
        String name, superName;
        final List<String> interfaces = new ArrayList<>();
        final Set<String> methods = new HashSet<>();
        final Set<String> fields = new HashSet<>();

        static ClassInfo parse(InputStream stream) throws IOException {
            DataInputStream in = new DataInputStream(stream);
            in.readInt();                     // magic
            in.readUnsignedShort();           // minor
            in.readUnsignedShort();           // major
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            int[] classIndex = new int[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1:  utf8[i] = in.readUTF(); break;
                    case 7:  classIndex[i] = in.readUnsignedShort(); break;
                    case 8: case 16: case 19: case 20: in.readUnsignedShort(); break;
                    case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: in.readInt(); break;
                    case 5: case 6: in.readLong(); i++; break;
                    case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;
                    default: throw new IOException("constant pool tag " + tag);
                }
            }
            ClassInfo info = new ClassInfo();
            in.readUnsignedShort();           // access
            info.name = utf8[classIndex[in.readUnsignedShort()]];
            int superIndex = in.readUnsignedShort();
            info.superName = superIndex == 0 ? null : utf8[classIndex[superIndex]];
            for (int i = in.readUnsignedShort(); i > 0; i--) info.interfaces.add(utf8[classIndex[in.readUnsignedShort()]]);
            for (int pass = 0; pass < 2; pass++) {
                for (int i = in.readUnsignedShort(); i > 0; i--) {
                    int access = in.readUnsignedShort();
                    String memberName = utf8[in.readUnsignedShort()];
                    String desc = utf8[in.readUnsignedShort()];
                    boolean visible = (access & 0x0001) != 0; // only public members are callable from the mod
                    if (visible) {
                        if (pass == 0) info.fields.add(memberName + " " + desc);
                        else info.methods.add(memberName + desc);
                    }
                    for (int a = in.readUnsignedShort(); a > 0; a--) {
                        in.readUnsignedShort();
                        in.skipBytes(in.readInt());
                    }
                }
            }
            return info;
        }
    }
}
