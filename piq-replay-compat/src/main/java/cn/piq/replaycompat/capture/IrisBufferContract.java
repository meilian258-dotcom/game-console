package cn.piq.replaycompat.capture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import org.objectweb.asm.tree.*;

/** Pinned to the reviewed Iris 1.8.14-beta.1+mc1.21.1 buffer implementation. */
public final class IrisBufferContract {
    public static final String TARGET="net.irisshaders.batchedentityrendering.impl.FullyBufferedMultiBufferSource";
    public static final String GET="(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;";
    public static final String END="(Lnet/minecraft/client/renderer/RenderType;)V";
    private static final String REVIEWED="c71d329af463bde7d63bd7da549063ce641b27426536d8020d306f429c76b80c";
    private IrisBufferContract() {}

    public static boolean supported(ClassNode node) { return REVIEWED.equals(fingerprint(node)); }

    /** Ignore debug/frames but include every executable instruction, operand and jump target. */
    public static String fingerprint(ClassNode node) {
        StringBuilder text=new StringBuilder(node.name).append('|').append(node.superName);
        for(String selector:new String[]{"getBuffer"+GET,"endBatch"+END}) {
            var matches=node.methods.stream().filter(m->(m.name+m.desc).equals(selector)).toList();
            if(matches.size()!=1)return "missing-or-ambiguous-method";
            MethodNode method=matches.getFirst();text.append('|').append(method.access).append(':').append(selector);
            var indices=new IdentityHashMap<AbstractInsnNode,Integer>();int index=0;
            for(var insn:method.instructions){indices.put(insn,index);if(insn.getOpcode()>=0)index++;}
            for(var insn:method.instructions) {
                if(insn.getOpcode()<0)continue;
                text.append(';').append(insn.getOpcode());
                if(insn instanceof VarInsnNode i)text.append(':').append(i.var);
                else if(insn instanceof IntInsnNode i)text.append(':').append(i.operand);
                else if(insn instanceof TypeInsnNode i)text.append(':').append(i.desc);
                else if(insn instanceof FieldInsnNode i)text.append(':').append(i.owner).append('.').append(i.name).append(i.desc);
                else if(insn instanceof MethodInsnNode i)text.append(':').append(i.owner).append('.').append(i.name).append(i.desc).append(':').append(i.itf);
                else if(insn instanceof JumpInsnNode i)text.append(':').append(indices.get(i.label));
                else if(insn instanceof LdcInsnNode i)text.append(':').append(i.cst.getClass().getName()).append(':').append(i.cst);
                else if(insn instanceof IincInsnNode i)text.append(':').append(i.var).append(':').append(i.incr);
                else if(!(insn instanceof InsnNode))return "unreviewed-instruction";
            }
            if(!method.tryCatchBlocks.isEmpty())return "unreviewed-exception-handler";
        }
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
