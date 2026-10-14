package com.michaelsebero.efficiententities.asm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.minecraft.launchwrapper.IClassTransformer;

/**
 * Wraps each run of two or more consecutive
 * {@link net.minecraft.client.model.ModelRenderer#render(float)} calls inside
 * a model's {@code render(Entity, float x6)} method with
 * {@code EfficientModelRenderer.startBatch()} / {@code endBatch()}, so the whole
 * run reaches the GPU as one draw call.
 *
 * <h3>What may sit inside a run</h3>
 * Only instructions that cannot touch GL state or change control flow: loads,
 * stores, field access, constants, arithmetic and casts.  Any other method call
 * (e.g. a {@code GlStateManager} transform between parts) ends the run, and so
 * does any label that is a jump, switch or exception-handler target, which
 * guarantees that whenever {@code endBatch} executes, its {@code startBatch}
 * did too.
 *
 * <h3>Parts with their own render code</h3>
 * A field typed {@code ModelRenderer} may hold a subclass that overrides
 * {@code render} and moves the GL matrix before drawing.  Each batched call is
 * therefore preceded by {@code EfficientModelRenderer.beforeBatchedPart(part)},
 * which closes the batch for such parts.  The original {@code INVOKEVIRTUAL}
 * is left in place so other mods' redirects still find it.
 *
 * <h3>Which classes</h3>
 * Every class that declares {@code render(Entity, float x6)} and calls
 * {@code ModelRenderer.render(float)}.  No superclass walk is attempted: the
 * superclass bytes of vanilla models are notch-named in production, so a
 * walk by deobfuscated name silently fails, and batching is equally safe in
 * any class because a run never spans a GL state change.
 *
 * <p>Must run after FML's deobfuscation transformer; see
 * {@link EfficientEntitiesCoremod}.  Never returns {@code null} for non-null
 * input: {@code LaunchClassLoader} feeds each transformer's output to the next.
 */
public class EfficientEntitiesTransformer implements IClassTransformer {

    private static final Logger LOG = LogManager.getLogger("efficiententities");

    private static final String HOOKS = "com/michaelsebero/efficiententities/renderer/EfficientModelRenderer";

    private static final String MODEL_RENDERER     = "net/minecraft/client/model/ModelRenderer";
    private static final String PART_RENDER_MCP    = "render";
    private static final String PART_RENDER_SRG    = "func_78785_a";
    private static final String PART_RENDER_DESC   = "(F)V";

    private static final String MODEL_RENDER_MCP   = "render";
    private static final String MODEL_RENDER_SRG   = "func_78088_a";
    private static final String MODEL_RENDER_DESC  = "(Lnet/minecraft/entity/Entity;FFFFFF)V";

    // Cheap pre-filter: both strings must appear in the constant pool.
    private static final byte[] MODEL_RENDER_DESC_BYTES = MODEL_RENDER_DESC.getBytes(StandardCharsets.UTF_8);
    private static final byte[] MODEL_RENDERER_BYTES    = MODEL_RENDERER.getBytes(StandardCharsets.UTF_8);

    @Override
    public byte[] transform(String name, String transformedName, byte[] classBytes) {
        if (classBytes == null) return null;
        if (!contains(classBytes, MODEL_RENDER_DESC_BYTES) || !contains(classBytes, MODEL_RENDERER_BYTES)) {
            return classBytes;
        }
        try {
            byte[] transformed = transformClass(transformedName, classBytes);
            return transformed != null ? transformed : classBytes;
        } catch (Throwable t) {
            LOG.error("[EfficientEntities] Error transforming '{}', using original bytes.", transformedName, t);
            return classBytes;
        }
    }

    /** Returns the transformed bytes, or {@code null} if nothing was batched. */
    static byte[] transformClass(String className, byte[] classBytes) {
        ClassNode classNode = new ClassNode();
        new ClassReader(classBytes).accept(classNode, 0);

        int runs = 0;
        for (MethodNode method : classNode.methods) {
            if (MODEL_RENDER_DESC.equals(method.desc)
                    && (MODEL_RENDER_MCP.equals(method.name) || MODEL_RENDER_SRG.equals(method.name))) {
                runs += wrapRuns(method);
            }
        }
        if (runs == 0) return null;

        LOG.debug("[EfficientEntities] Batched {} part run(s) in {}", runs, className);

        // The inserted code never branches and leaves the stack as it found it, so
        // existing stack map frames stay valid; only max stack grows (DUP2), and
        // COMPUTE_MAXS, unlike COMPUTE_FRAMES, needs no class hierarchy lookups.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    private static int wrapRuns(MethodNode method) {
        Set<LabelNode> branchTargets = branchTargets(method);
        int runs = 0;

        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!isPartRender(insn)) continue;

            List<AbstractInsnNode> parts = new ArrayList<>();
            parts.add(insn);
            for (AbstractInsnNode next = insn.getNext(); next != null; next = next.getNext()) {
                if (isPartRender(next)) {
                    parts.add(next);
                } else if (!isInert(next, branchTargets)) {
                    break;
                }
            }
            AbstractInsnNode last = parts.get(parts.size() - 1);

            // A single part gains nothing over the renderer's own per-call batch.
            if (parts.size() > 1) {
                method.instructions.insertBefore(insn, hook("startBatch", "()V"));
                for (AbstractInsnNode part : parts) {
                    // Stack holds [part, scale]; hand a copy of the part to the guard.
                    InsnList guard = new InsnList();
                    guard.add(new InsnNode(Opcodes.DUP2));
                    guard.add(new InsnNode(Opcodes.POP));
                    guard.add(hook("beforeBatchedPart", "(L" + MODEL_RENDERER + ";)V"));
                    method.instructions.insertBefore(part, guard);
                }
                method.instructions.insert(last, hook("endBatch", "()V"));
                runs++;
            }
            insn = last;
        }
        return runs;
    }

    private static boolean isPartRender(AbstractInsnNode insn) {
        if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL) return false;
        MethodInsnNode call = (MethodInsnNode) insn;
        return MODEL_RENDERER.equals(call.owner)
            && PART_RENDER_DESC.equals(call.desc)
            && (PART_RENDER_MCP.equals(call.name) || PART_RENDER_SRG.equals(call.name));
    }

    /** True for instructions that can sit between two part renders without breaking the batch. */
    private static boolean isInert(AbstractInsnNode insn, Set<LabelNode> branchTargets) {
        if (insn instanceof LabelNode)      return !branchTargets.contains(insn);
        if (insn instanceof LineNumberNode) return true;
        if (insn instanceof VarInsnNode)    return insn.getOpcode() != Opcodes.RET;
        if (insn instanceof FieldInsnNode
         || insn instanceof LdcInsnNode
         || insn instanceof IntInsnNode
         || insn instanceof IincInsnNode)   return true;
        if (insn instanceof TypeInsnNode)   return insn.getOpcode() == Opcodes.CHECKCAST;
        if (insn instanceof InsnNode) {
            int op = insn.getOpcode();
            return !(op >= Opcodes.IRETURN && op <= Opcodes.RETURN) && op != Opcodes.ATHROW;
        }
        // Method calls, jumps, switches, frames, invokedynamic, ...
        return false;
    }

    private static Set<LabelNode> branchTargets(MethodNode method) {
        Set<LabelNode> targets = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof JumpInsnNode) {
                targets.add(((JumpInsnNode) insn).label);
            } else if (insn instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode sw = (TableSwitchInsnNode) insn;
                targets.add(sw.dflt);
                targets.addAll(sw.labels);
            } else if (insn instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode sw = (LookupSwitchInsnNode) insn;
                targets.add(sw.dflt);
                targets.addAll(sw.labels);
            }
        }
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            targets.add(block.start);
            targets.add(block.end);
            targets.add(block.handler);
        }
        return targets;
    }

    private static MethodInsnNode hook(String name, String desc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, name, desc, false);
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        byte first = needle[0];
        int max = haystack.length - needle.length;
        outer:
        for (int i = 0; i <= max; i++) {
            if (haystack[i] != first) continue;
            for (int j = 1; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
