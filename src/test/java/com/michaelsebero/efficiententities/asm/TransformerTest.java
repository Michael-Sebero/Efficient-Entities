package com.michaelsebero.efficiententities.asm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import net.minecraft.client.model.ModelBiped;

/**
 * Runs the transformer over every vanilla class that declares
 * {@code render(Entity, float x6)} and checks the output.
 */
class TransformerTest {

    private static final String HOOKS = "com/michaelsebero/efficiententities/renderer/EfficientModelRenderer";

    @Test
    void vanillaModelsAreBatchedAndVerify() throws Exception {
        Map<String, Integer> runsByClass = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : minecraftClasses().entrySet()) {
            String name = entry.getKey();
            byte[] original = entry.getValue();
            byte[] out = new EfficientEntitiesTransformer().transform(name, name, original);
            assertNotNull(out, name);
            if (out == original) continue;

            verify(name, out);
            int runs = checkRuns(name, out);
            runsByClass.put(name, runs);
        }

        System.out.println("Batched classes: " + runsByClass);
        // Direct and indirect ModelBase subclasses alike; the old superclass walk missed the latter in production.
        assertEquals(2, (int) runsByClass.get("net.minecraft.client.model.ModelBiped"), "child + adult paths");
        assertTrue(runsByClass.containsKey("net.minecraft.client.model.ModelPlayer"), "indirect ModelBase subclass");
        assertTrue(runsByClass.containsKey("net.minecraft.client.model.ModelQuadruped"));
        assertTrue(runsByClass.size() >= 20, "batched " + runsByClass.size() + " classes");
    }

    @Test
    void unrelatedClassesAreReturnedUntouched() throws IOException {
        byte[] bytes = classBytes("net/minecraft/client/renderer/GlStateManager.class");
        assertSame(bytes, new EfficientEntitiesTransformer().transform("x", "net.minecraft.client.renderer.GlStateManager", bytes));
        assertNull(new EfficientEntitiesTransformer().transform("x", "x", null));
    }

    /** ASM's verifier: stack/locals consistency, frames and types. */
    private static void verify(String name, byte[] bytes) {
        StringWriter errors = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(bytes), TransformerTest.class.getClassLoader(), false, new PrintWriter(errors));
        if (errors.getBuffer().length() > 0) fail(name + " failed verification:\n" + errors);
    }

    /**
     * Checks that every startBatch is followed by guarded part renders and a
     * matching endBatch, with nothing in between that could move the GL matrix
     * or branch.  Returns the number of runs.
     */
    private static int checkRuns(String name, byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int runs = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!isHook(insn, "startBatch")) continue;
                runs++;
                int parts = 0;
                AbstractInsnNode cursor = insn.getNext();
                while (!isHook(cursor, "endBatch")) {
                    assertNotNull(cursor, name + ": unterminated batch");
                    if (cursor.getOpcode() == Opcodes.DUP2) {
                        assertEquals(Opcodes.POP, cursor.getNext().getOpcode(), name);
                        assertTrue(isHook(cursor.getNext().getNext(), "beforeBatchedPart"), name);
                        AbstractInsnNode call = cursor.getNext().getNext().getNext();
                        MethodInsnNode render = (MethodInsnNode) call;
                        assertEquals("net/minecraft/client/model/ModelRenderer", render.owner, name);
                        assertEquals("(F)V", render.desc, name);
                        parts++;
                        cursor = call.getNext();
                        continue;
                    }
                    assertTrue(cursor.getType() != AbstractInsnNode.METHOD_INSN
                            && cursor.getType() != AbstractInsnNode.JUMP_INSN
                            && cursor.getType() != AbstractInsnNode.FRAME,
                        name + "." + method.name + ": " + cursor.getClass().getSimpleName() + " inside a batch");
                    cursor = cursor.getNext();
                }
                assertTrue(parts >= 2, name + ": batch of " + parts);
                insn = cursor;
            }
        }
        return runs;
    }

    private static boolean isHook(AbstractInsnNode insn, String name) {
        return insn instanceof MethodInsnNode
            && ((MethodInsnNode) insn).owner.equals(HOOKS)
            && ((MethodInsnNode) insn).name.equals(name);
    }

    // ------------------------------------------------------------------ class discovery

    private static byte[] classBytes(String resource) throws IOException {
        try (InputStream in = TransformerTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            return readAll(in);
        }
    }

    /** Every class in the Minecraft classes location (jar or directory) declaring a model render method. */
    private static Map<String, byte[]> minecraftClasses() throws IOException, URISyntaxException {
        Map<String, byte[]> classes = new TreeMap<>();
        File location = new File(ModelBiped.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (location.isDirectory()) {
            Path root = location.toPath();
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    String rel = root.relativize(file).toString().replace(File.separatorChar, '/');
                    if (rel.startsWith("net/minecraft/") && rel.endsWith(".class")) {
                        addIfModelRender(classes, rel, Files.readAllBytes(file));
                    }
                }
            }
        } else {
            try (JarFile jar = new JarFile(location)) {
                for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                    JarEntry entry = e.nextElement();
                    if (entry.getName().startsWith("net/minecraft/") && entry.getName().endsWith(".class")) {
                        try (InputStream in = jar.getInputStream(entry)) {
                            addIfModelRender(classes, entry.getName(), readAll(in));
                        }
                    }
                }
            }
        }
        assertTrue(classes.size() > 30, "found " + classes.size() + " model classes in " + Paths.get(location.toURI()));
        return classes;
    }

    private static void addIfModelRender(Map<String, byte[]> classes, String resource, byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
        for (MethodNode method : node.methods) {
            if (method.desc.equals("(Lnet/minecraft/entity/Entity;FFFFFF)V")) {
                classes.put(resource.substring(0, resource.length() - 6).replace('/', '.'), bytes);
                return;
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
