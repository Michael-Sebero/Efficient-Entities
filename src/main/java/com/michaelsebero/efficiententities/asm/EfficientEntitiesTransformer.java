package com.michaelsebero.efficiententities.asm;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;

/**
 * Bytecode transformer that wraps each consecutive run of
 * {@link net.minecraft.client.model.ModelRenderer#render(float)} calls
 * inside an entity model's render method with
 * {@code EfficientModelRenderer.startBatch()} / {@code endBatch()}.
 *
 * <h3>Critical contract for IClassTransformer</h3>
 * {@code LaunchClassLoader.runTransformers} chains transformers by passing
 * each transformer's return value as the {@code basicClass} input to the next.
 * Returning {@code null} is therefore catastrophic: the next transformer
 * receives null, also returns null, and eventually {@code findClass} calls
 * {@code defineClass(null, ...)} which NullPointerExceptions -- surfacing as
 * a {@code ClassNotFoundException} for every class loaded afterward.
 * We must ALWAYS return valid bytes: either the transformed array or the
 * original {@code classBytes} unchanged. Never null.
 */
public class EfficientEntitiesTransformer implements IClassTransformer {

    private static final Logger LOG = LogManager.getLogger("efficiententities");

    private static final String RENDERER_OWNER =
        "com/michaelsebero/efficiententities/renderer/EfficientModelRenderer";
    private static final String RENDERER_DESC  =
        "()Lcom/michaelsebero/efficiententities/renderer/EfficientModelRenderer;";

    private static final String MODEL_BASE     = "net/minecraft/client/model/ModelBase";
    private static final String MODEL_RENDERER = "net/minecraft/client/model/ModelRenderer";

    private static final String RENDER_MCP  = "render";
    private static final String RENDER_SRG  = "func_78785_a";
    private static final String RENDER_DESC = "(F)V";

    private static final String ENTITY_RENDER_DESC = "(Lnet/minecraft/entity/Entity;FFFFFF)V";

    // -------------------------------------------------------------------------

    @Override
    public byte[] transform(String obfName, String deobfName, byte[] classBytes) {
        if (classBytes == null) return null;
        try {
            byte[] transformed = tryTransform(deobfName, classBytes);
            // CRITICAL: never return null to LaunchClassLoader.
            // Returning null corrupts the transformer chain and causes an NPE
            // in defineClass that kills every subsequent class load.
            return transformed != null ? transformed : classBytes;
        } catch (Throwable t) {
            LOG.error("[EfficientEntities] Error transforming '{}', using original bytes.", deobfName, t);
            return classBytes;
        }
    }

    /**
     * Returns transformed bytes if modified, or {@code null} if the class
     * should be left unchanged. The caller converts null to original bytes.
     */
    private byte[] tryTransform(String deobfName, byte[] classBytes) {
        String internalName = deobfName.replace('.', '/');

        // Skip packages that can never contain a ModelBase subclass.
        if (isDefinitelyNotModelBase(internalName)) return null;

        // Confirm ModelBase ancestry via superclass chain walk.
        if (!extendsModelBase(classBytes)) return null;

        ClassReader reader    = new ClassReader(classBytes);
        ClassNode   classNode = new ClassNode();
        reader.accept(classNode, 0);

        MethodNode renderMethod = findMethod(classNode, "render", "func_78088_a", ENTITY_RENDER_DESC);
        if (renderMethod == null) return null;

        boolean didTransform = false;
        int     extraFlags   = 0;

        for (AbstractInsnNode insn = renderMethod.instructions.getFirst();
             insn != null;
             insn = insn.getNext()) {

            if (!isModelRendererRender(insn)) continue;

            AbstractInsnNode last = insn;
            for (AbstractInsnNode next = last.getNext(); next != null; next = next.getNext()) {
                if (next instanceof LineNumberNode) continue;
                if (next instanceof LabelNode)      continue;
                if (next instanceof VarInsnNode)    continue;
                if (next instanceof FieldInsnNode)  continue;
                if (next instanceof LdcInsnNode)    continue;
                if (next instanceof InsnNode) {
                    int op = next.getOpcode();
                    if (op != Opcodes.RETURN && op != Opcodes.ATHROW) continue;
                }
                if (isModelRendererRender(next)) {
                    last = next;
                    continue;
                }
                break;
            }

            AbstractInsnNode insertBefore = insn;
            if (hasPrecedingLoadSequence(insn)) {
                insertBefore = insn.getPrevious().getPrevious().getPrevious();
            } else {
                extraFlags |= ClassWriter.COMPUTE_MAXS;
            }

            renderMethod.instructions.insertBefore(insertBefore, startBatchInsns());
            renderMethod.instructions.insert(last,               endBatchInsns());

            didTransform = true;
            insn = last;
        }

        if (!didTransform) return null;

        LOG.debug("[EfficientEntities] Injected batch calls into {}", deobfName);

        ClassWriter writer = new ClassWriter(extraFlags) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        classNode.accept(writer);
        return writer.toByteArray();
    }

    // -------------------------------------------------------------------------

    private static boolean isDefinitelyNotModelBase(String internalName) {
        return internalName.startsWith("java/")
            || internalName.startsWith("javax/")
            || internalName.startsWith("sun/")
            || internalName.startsWith("com/sun/")
            || internalName.startsWith("jdk/")
            || internalName.startsWith("org/lwjgl/")
            || internalName.startsWith("org/apache/")
            || internalName.startsWith("com/google/")
            || internalName.startsWith("io/netty/")
            || internalName.startsWith("scala/")
            || internalName.startsWith("kotlin/")
            || internalName.startsWith("org/objectweb/")
            || internalName.startsWith("org/spongepowered/")
            || internalName.startsWith("net/minecraftforge/")
            || internalName.startsWith("com/michaelsebero/");
    }

    private static boolean extendsModelBase(byte[] ownBytes) {
        String superName = new ClassReader(ownBytes).getSuperName();
        for (int depth = 0; depth < 20 && superName != null; depth++) {
            if (superName.equals(MODEL_BASE))         return true;
            if (superName.equals("java/lang/Object")) return false;
            if (!couldBeInModelHierarchy(superName))  return false;
            try {
                byte[] parentBytes = Launch.classLoader.getClassBytes(superName.replace('/', '.'));
                if (parentBytes == null) return false;
                superName = new ClassReader(parentBytes).getSuperName();
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    private static boolean couldBeInModelHierarchy(String internalName) {
        return !internalName.startsWith("java/")
            && !internalName.startsWith("javax/")
            && !internalName.startsWith("sun/")
            && !internalName.startsWith("com/sun/")
            && !internalName.startsWith("org/lwjgl/")
            && !internalName.startsWith("org/apache/")
            && !internalName.startsWith("com/google/")
            && !internalName.startsWith("io/netty/")
            && !internalName.startsWith("scala/");
    }

    private static MethodNode findMethod(ClassNode cn, String name1, String name2, String desc) {
        for (MethodNode mn : cn.methods) {
            if ((mn.name.equals(name1) || mn.name.equals(name2)) && mn.desc.equals(desc)) {
                return mn;
            }
        }
        return null;
    }

    private static boolean isModelRendererRender(AbstractInsnNode insn) {
        if (!(insn instanceof MethodInsnNode)) return false;
        MethodInsnNode mi = (MethodInsnNode) insn;
        return mi.owner.equals(MODEL_RENDERER)
            && (mi.name.equals(RENDER_MCP) || mi.name.equals(RENDER_SRG))
            && mi.desc.equals(RENDER_DESC);
    }

    private static boolean hasPrecedingLoadSequence(AbstractInsnNode insn) {
        AbstractInsnNode a = insn.getPrevious();
        if (a == null || a.getOpcode() != Opcodes.FLOAD)    return false;
        AbstractInsnNode b = a.getPrevious();
        if (b == null || b.getOpcode() != Opcodes.GETFIELD) return false;
        AbstractInsnNode c = b.getPrevious();
        return c != null && c.getOpcode() == Opcodes.ALOAD;
    }

    private static InsnList startBatchInsns() {
        InsnList list = new InsnList();
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,  RENDERER_OWNER, "instance",   RENDERER_DESC, false));
        list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RENDERER_OWNER, "startBatch", "()V",         false));
        return list;
    }

    private static InsnList endBatchInsns() {
        InsnList list = new InsnList();
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,  RENDERER_OWNER, "instance", RENDERER_DESC, false));
        list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RENDERER_OWNER, "endBatch", "()V",         false));
        return list;
    }
}
