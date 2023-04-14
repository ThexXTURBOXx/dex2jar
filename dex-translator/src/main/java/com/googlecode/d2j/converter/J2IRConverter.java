package com.googlecode.d2j.converter;

import com.googlecode.d2j.Method;
import com.googlecode.d2j.MethodHandle;
import com.googlecode.d2j.Proto;
import com.googlecode.dex2jar.ir.IrMethod;
import com.googlecode.dex2jar.ir.Trap;
import com.googlecode.dex2jar.ir.expr.Exprs;
import com.googlecode.dex2jar.ir.expr.InvokeCustomExpr;
import com.googlecode.dex2jar.ir.expr.Local;
import com.googlecode.dex2jar.ir.expr.NewMutiArrayExpr;
import com.googlecode.dex2jar.ir.stmt.*;
import com.googlecode.dex2jar.ir.ts.UniqueQueue;
import com.googlecode.dex2jar.tools.Constants;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.JSRInlinerAdapter;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

import java.util.*;

import static com.googlecode.d2j.util.Types.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Converter for Java <i>({@link MethodNode})</i> into the intermediate value type {@link IrMethod}.
 */
public final class J2IRConverter {

    // Inputs

    private final String owner;
    private final MethodNode methodNode;
    private final InsnList insnList;
    private final Map<LabelNode, String> handlerToExceptionType = new IdentityHashMap<>();

    // Analysis

    /**
     * Represents a mapping of indices in the {@link InsnList} to the number of incoming control flow edges.
     * <pre>{@code
     *  parentCount[0] == 1; // The start of the method always has a incoming control flow edge
     *  parentCount[?] == ?; // Any index in the method has a variable number of control flow edges
     * }</pre>
     * If you have the given code:
     * <pre>{@code
     * A:                              // 0
     *  GETFIELD something.field I     // 1
     *  IFEQ B                         // 2
     *    NOP                          // 3
     * B:                              // 4
     *  RETURN                         // 5
     * }</pre>
     * Then the output will be: {@code [ 1, 1, 1, 1, 2, 1 ]}
     * <br>
     * All instructions that do not force a path in control flow naturally flow into the next instruction.
     * This is why all items seen here have at least one parent. The label {@code B:} has two parents because
     * of the fall-through of the prior instruction, and the jump destination of the earlier {@code IFEQ}.
     * <p/>
     * Given this information you can assume the following for any given valid method code:
     * <ul>
     *     <li>{@code parentCount[0] == 1}</li>
     *     <li>{@code parentCount[N] == 1} where N represents any non-label instruction</li>
     *     <li>{@code parentCount[N] >= 1} where N represents any label instruction</li>
     *     <li>{@code parentCount[N] == 0} where N is an instruction that is never visited <i>(Dead code)</i></li>
     * </ul>
     */
    private int[] parentCount;

    /**
     * Represents a mapping of indices in the {@link InsnList} to the state of the stack and local variable table.
     * These frames are populated by the {@link #createInterpreter() interpreter} when visited in
     * {@link #dfs(BitSet[], BitSet, BitSet, Interpreter)}.
     * <ul>
     *     <li>{@code frames[0]} will have an empty stack, and contain only variables declared as method parameters
     *     and the implicit 'this' if the method is non-static</li>
     *     <li>{@code frames[N]} will be {@code null} where N is an instruction that is never visited
     *     <i>(Dead code)</i></li>
     * </ul>
     */
    private JvmFrame[] frames;

    // Outputs

    private IrMethod target;
    private List<Stmt>[] emitStmts;
    private List<Stmt> currentEmit;
    private final List<Stmt> preEmit = new ArrayList<>();
    private final Map<Label, LabelStmt> labelStmtMap = new IdentityHashMap<>();

    private J2IRConverter(String owner, MethodNode methodNode) {
        this.owner = owner;
        this.methodNode = methodNode;
        insnList = methodNode.instructions;
    }

    /**
     * @param owner
     *         Internal name of the class declaring the given method.
     * @param methodNode
     *         The method to convert.
     *
     * @return Converted IR method.
     *
     * @throws AnalyzerException
     *         When the code within the given method could not be interpreted.
     * @throws DeprecatedInstructionException
     *         When the code contains unsupported deprecated instructions such as JSR/RET.
     *         Please use {@link JSRInlinerAdapter} before converting methods.
     */
    public static IrMethod convert(String owner, MethodNode methodNode) throws AnalyzerException {
        return new J2IRConverter(owner, methodNode).convert0();
    }

    @SuppressWarnings("unchecked")
    IrMethod convert0() throws AnalyzerException {
        // Create target baseline from method declaration details
        //  - name/desc/owner/flags
        target = createInitialIrMethod(owner, methodNode);

        // If there are no instructions, no more work needs to be done.
        // Common case for 'native' and 'abstract' methods.
        if (methodNode.instructions.size() == 0)
            return target;

        // Create an array tracking how many incoming edges to the instruction exist.
        parentCount = createInitialParentCounts(insnList);

        // Create an array for tracking outbound control flow edges for each instruction.
        // For any instruction, the associated BitSet marks each offset as:
        //  - set[N] == 0: Insn does not flow into N
        //  - set[N] == 1: Insn flows into N
        BitSet[] exBranch = new BitSet[insnList.size()];

        // Create a bitset for exception handlers.
        // Each entry in the bitset is such that:
        //  - set[N] == 0: Instruction at N is NOT an exception handler start
        //  - set[N] == 1: Instruction at N is a Label and is an exception handler start
        BitSet handlers = new BitSet(insnList.size());
        initExceptionHandlers(handlers, exBranch);

        // Create an interpreter to analyze the stack and local variable table contents
        // for each index in the instructions list.
        Interpreter<JvmValue> interpreter = createInterpreter();

        // Array of frames to store results of the interpreters visitation of the method code.
        frames = new JvmFrame[insnList.size()];

        // Array of statements mapped from the input instructions.
        emitStmts = new ArrayList[insnList.size()];

        // Bitset tracking which instructions were visited (any set[N] == 0 is dead code)
        BitSet access = new BitSet(insnList.size());

        // Visit the JVM method instructions with the ASM interpreter.
        //  - Updates emitted statements array
        //  - Populates frames array
        dfs(exBranch, handlers, access, interpreter);

        // Populate statement-list by iterating over the JVM instructions and seeing what
        // statement values we have associated to each instruction.
        StmtList stmts = target.stmts;
        stmts.addAll(preEmit);
        for (int i = 0; i < insnList.size(); i++) {
            AbstractInsnNode insn = insnList.get(i);
            if (access.get(i)) {
                // Add the associated statements of the visited instructions.
                List<Stmt> es = emitStmts[i];
                if (es != null) {
                    stmts.addAll(es);
                }
            } else {
                // Add unvisited labels.
                if (insn.getType() == AbstractInsnNode.LABEL) {
                    stmts.add(getLabel((LabelNode) insn));
                }
            }
        }

        // Clear statements.
        emitStmts = null;

        // ???
        Queue<JvmValue> queue = new UniqueQueue<>();
        for (int i = 0; i < frames.length; i++) {
            JvmFrame frame = frames[i];
            if (parentCount[i] > 1 && frame != null && access.get(i)) {
                for (int j = 0; j < frame.getLocals(); j++) {
                    JvmValue local = frame.getLocal(j);
                    addToQueue(queue, local);
                }
                for (int j = 0; j < frame.getStackSize(); j++) {
                    JvmValue stack = frame.getStack(j);
                    addToQueue(queue, stack);
                }
            }
        }

        // ???
        while (!queue.isEmpty()) {
            JvmValue value = queue.poll();
            getLocal(value); // Force population of IR values for the given JVM value.
            if (value.parent != null) {
                if (value.parent.local == null) {
                    queue.add(value.parent);
                }
            }
            if (value.otherParent != null) {
                for (JvmValue otherParentValue : value.otherParent) {
                    if (otherParentValue.local == null) {
                        queue.add(otherParentValue);
                    }
                }
            }
        }

        // ???
        Set<com.googlecode.dex2jar.ir.expr.Value> phiValues = new HashSet<>();
        List<LabelStmt> phiLabels = new ArrayList<>();
        for (int i = 0; i < frames.length; i++) {
            JvmFrame frame = frames[i];
            if (parentCount[i] > 1 && frame != null && access.get(i)) {
                LabelNode label = (LabelNode) insnList.get(i);
                LabelStmt labelStmt = getLabel(label);
                List<AssignStmt> phis = new ArrayList<>();
                for (int j = 0; j < frame.getLocals(); j++) {
                    JvmValue local = frame.getLocal(j);
                    addPhi(local, phiValues, phis);
                }
                for (int j = 0; j < frame.getStackSize(); j++) {
                    JvmValue stack = frame.getStack(j);
                    addPhi(stack, phiValues, phis);
                }
                labelStmt.phis = phis;
                phiLabels.add(labelStmt);
            }
        }
        if (phiLabels.size() > 0) {
            target.phiLabels = phiLabels;
        }

        return target;

    }

    /**
     * Populates the handlers bitset, where each set bit represents an offset in the {@link #insnList}
     * that denotes the start range of an exception handler.
     *
     * @param handlers
     *         Handler bitset to populate.
     * @param exBranch
     *         Control flow destination edges.
     */
    private void initExceptionHandlers(BitSet handlers, BitSet[] exBranch) {
        if (methodNode.tryCatchBlocks != null) {
            for (TryCatchBlockNode tcb : methodNode.tryCatchBlocks) {
                target.traps.add(new Trap(getLabel(tcb.start), getLabel(tcb.end),
                        new LabelStmt[]{getLabel(tcb.handler)},
                        new String[]{tcb.type == null ? null : Type.getObjectType(tcb.type).getDescriptor()}));
                int handlerIdx = insnList.indexOf(tcb.handler);
                handlers.set(handlerIdx);

                // Record the exception type at the given index.
                // If there are multiple exceptions at the given handler index, we will squash the type into something common.
                handlerToExceptionType.merge(tcb.handler, tcb.type, (newType, currentType) -> "java/lang/Throwable");

                // Iterate over all instructions contained in the try { ... } range,
                // adding a control flow branch from each instruction to the catch { ... } handler block.
                int itemCountInBlock = 0;
                for (AbstractInsnNode insn = tcb.start.getNext(); insn != tcb.end; insn = insn.getNext()) {
                    itemCountInBlock++;
                    int insnIndex = insnList.indexOf(insn);
                    BitSet ex = exBranch[insnIndex];
                    if (ex == null) {
                        ex = new BitSet(insnList.size());
                        exBranch[insnIndex] = ex;
                    }
                    ex.set(handlerIdx);
                }

                // Increment number of incoming edges for the handler index.
                parentCount[handlerIdx] += itemCountInBlock;
            }
        }
    }

    /**
     * @param stmt
     *         Statement to append to {@link #currentEmit}
     */
    private void emit(Stmt stmt) {
        currentEmit.add(stmt);
    }

    private void addPhi(JvmValue v, Set<com.googlecode.dex2jar.ir.expr.Value> phiValues, List<AssignStmt> phis) {
        if (v != null) {
            if (v.local != null) {
                if (v.parent != null) {
                    phiValues.add(getLocal(v.parent));
                }
                if (v.otherParent != null) {
                    for (JvmValue v2 : v.otherParent) {
                        phiValues.add(getLocal(v2));
                    }
                }
                if (phiValues.size() > 0) {
                    phis.add(Stmts.nAssign(v.local,
                            Exprs.nPhi(phiValues.toArray(new com.googlecode.dex2jar.ir.expr.Value[0]))));
                    phiValues.clear();
                }
            }
        }
    }

    private void addToQueue(Queue<JvmValue> queue, JvmValue value) {
        if (value != null) {
            if (value.local != null) {
                if (value.parent != null) {
                    if (value.parent.local == null) {
                        queue.add(value.parent);
                    }
                }
                if (value.otherParent != null) {
                    for (JvmValue v2 : value.otherParent) {
                        if (v2.local == null) {
                            queue.add(v2);
                        }
                    }
                }
            }
        }
    }

    /**
     * @param insnList
     *         Instructions to operate off of.
     *
     * @return Array containing a mapping of instruction offsets, to the number of inbound control flow edges.
     */
    private static int[] createInitialParentCounts(InsnList insnList) {
        int[] parentCount = new int[insnList.size()];

        // Initial state, method entry-point always has inbound control flow edge.
        parentCount[0] = 1;

        // Iterate over all instructions, counting inbound control flow edges per each instruction
        // and storing the results in the array defined above.
        for (AbstractInsnNode insn = insnList.getFirst(); insn != null; insn = insn.getNext()) {
            // Add an edge to the destinations of jump instructions.
            if (insn.getType() == AbstractInsnNode.JUMP_INSN) {
                JumpInsnNode jump = (JumpInsnNode) insn;
                parentCount[insnList.indexOf(jump.label)]++;
            }

            int op = insn.getOpcode();

            // Add an edge to all destinations of switch(...) instructions.
            if (op == Opcodes.TABLESWITCH || op == Opcodes.LOOKUPSWITCH) {
                if (op == Opcodes.TABLESWITCH) {
                    TableSwitchInsnNode tsin = (TableSwitchInsnNode) insn;
                    for (LabelNode label : tsin.labels) {
                        parentCount[insnList.indexOf(label)]++;
                    }
                    parentCount[insnList.indexOf(tsin.dflt)]++;
                } else {
                    LookupSwitchInsnNode lsin = (LookupSwitchInsnNode) insn;
                    for (LabelNode label : lsin.labels) {
                        parentCount[insnList.indexOf(label)]++;
                    }
                    parentCount[insnList.indexOf(lsin.dflt)]++;
                }
            }

            // For any instruction that does not FORCE the execution to jump to a new destination,
            // we will add an edge from this instruction to the next instruction.
            // This is shown by incrementing the number of inbound edges in the next instruction.
            if ((op < Opcodes.GOTO || op > Opcodes.RETURN) && op != Opcodes.ATHROW) {
                AbstractInsnNode next = insn.getNext();
                if (next != null) {
                    parentCount[insnList.indexOf(insn.getNext())]++;
                }
            }
        }
        return parentCount;
    }

    /**
     * @param owner
     *         Internal name of class defining the method.
     * @param source
     *         The method declaration to pull information from.
     *
     * @return Basic IR method with declaration details mirroring those from the provided {@code source} method.
     */
    private static IrMethod createInitialIrMethod(String owner, MethodNode source) {
        IrMethod target = new IrMethod();
        target.name = source.name;
        target.owner = "L" + owner + ";";
        target.ret = Type.getReturnType(source.desc).getDescriptor();
        Type[] args = Type.getArgumentTypes(source.desc);
        String[] sArgs = new String[args.length];
        target.args = sArgs;
        for (int i = 0; i < args.length; i++) {
            sArgs[i] = args[i].getDescriptor();
        }
        target.isStatic = 0 != (source.access & Opcodes.ACC_STATIC);
        return target;
    }

    /**
     * Visits the {@link #methodNode}'s {@link #insnList instructions} with the given interpreter.
     *
     * @param exBranch
     *         Array mapping instruction offsets to bitsets representing which instructions are potential branch targets.
     * @param handlers
     *         Bitset representing which instructions are catch { ... } block handler start positions.
     * @param access
     *         Bitset tracking which instructions were visited <i>(any {@code set[N] == 0} is dead code)</i>
     * @param interpreter
     *         Interpreter instance to handle updating frame states, and {@link #currentEmit statement emission}.
     *
     * @throws AnalyzerException
     *         When something in the method code breaks analysis.
     */
    private void dfs(BitSet[] exBranch, BitSet handlers, BitSet access,
                     Interpreter<JvmValue> interpreter) throws AnalyzerException {
        currentEmit = preEmit;

        // Create the first frame to begin our analysis with.
        JvmFrame first = initFirstFrame(methodNode, target);
        if (parentCount[0] > 1) {
            mergeFull(first, 0);
        } else {
            frames[0] = first;
        }

        // Keep reference local, minor performance optimization
        InsnList insnList = this.insnList;

        // Track visited instruction control flow.
        // Control flows will push to the stack,
        // and visiting them to handle their subsequent analysis will pop them off.
        Stack<AbstractInsnNode> stack = new Stack<>();
        stack.push(insnList.getFirst());

        // Temporary frame which we will operate on with our interpreter.
        JvmFrame tmp = new JvmFrame(methodNode.maxLocals, methodNode.maxStack);
        while (!stack.isEmpty()) {
            AbstractInsnNode insn = stack.pop();
            int index = insnList.indexOf(insn);

            // Check if we've already visited this instruction.
            // If so, we skip execution.
            if (access.get(index)) continue;
            access.set(index);

            // Update current emission statements target.
            // Any item we create IR statements for will relate to the current instruction at this index.
            setCurrentEmit(index);

            // Current frame to operate on.
            JvmFrame frame = frames[index];

            // Handle emitting statements for labels
            if (insn.getType() == AbstractInsnNode.LABEL) {
                // Emit label mappings
                LabelNode labelNode = (LabelNode) insn;
                emit(getLabel(labelNode));

                // Emit handling for catch { ... } block ranges.
                // These will replace the stack with a 'java/lang/Throwable' or subtype.
                if (handlers.get(index)) {
                    String exType = handlerToExceptionType.getOrDefault(labelNode, "java/lang/Throwable");
                    Local ex = newLocal();
                    emit(Stmts.nIdentity(ex, Exprs.nExceptionRef("L" + exType + ";")));
                    frame.clearStack();
                    frame.push(new JvmValue(1, ex));
                }
            }

            // ???
            BitSet ex = exBranch[index];
            if (ex != null) {
                for (int i = ex.nextSetBit(0); i >= 0; i = ex.nextSetBit(i + 1)) {
                    mergeLocals(frame, i);
                    stack.push(insnList.get(i));
                }
            }

            // Copy state of 'frame' into 'tmp' and execute the interpreter in our 'tmp' frame.
            tmp.init(frame);
            tmp.execute(insn, interpreter);

            // Handle jump instruction, push their destination to our to-visit stack.
            int op = insn.getOpcode();
            if (insn.getType() == AbstractInsnNode.JUMP_INSN) {
                JumpInsnNode jump = (JumpInsnNode) insn;
                stack.push(jump.label);
                mergeFull(tmp, insnList.indexOf(jump.label));
            }

            // Handle switch instructions, push their destinations to our to-visit stack.
            if (op == Opcodes.TABLESWITCH) {
                TableSwitchInsnNode tsin = (TableSwitchInsnNode) insn;
                for (LabelNode label : tsin.labels) {
                    stack.push(label);
                    mergeFull(tmp, insnList.indexOf(label));
                }
                stack.push(tsin.dflt);
                mergeFull(tmp, insnList.indexOf(tsin.dflt));

            } else if (op == Opcodes.LOOKUPSWITCH) {
                LookupSwitchInsnNode lsin = (LookupSwitchInsnNode) insn;
                for (LabelNode label : lsin.labels) {
                    stack.push(label);
                    mergeFull(tmp, insnList.indexOf(label));
                }
                stack.push(lsin.dflt);
                mergeFull(tmp, insnList.indexOf(lsin.dflt));
            }

            // Handle all non-control flow modifying instructions.
            // Every instruction in this range should allow the control flow to progress to the next instruction.
            if ((op < Opcodes.GOTO || op > Opcodes.RETURN) && op != Opcodes.ATHROW) {
                stack.push(insn.getNext());
                mergeFull(tmp, index + 1);
            }

            // cleanup frame it is useless
            if (parentCount[index] <= 1) {
                frames[index] = null;
            }
        }
    }

    /**
     * Called from {@link #dfs(BitSet[], BitSet, BitSet, Interpreter)} before each instruction is visited.
     * <p/>
     * Sets the {@link #currentEmit} to the {@link #emitStmts} at the given index.
     * The current-emit list contains the list of {@link Stmt} items that map to the {@link AbstractInsnNode}
     * at the given position in the {@link #insnList}.
     *
     * @param index
     *         Current instruction index to visit.
     */
    private void setCurrentEmit(int index) {
        List<Stmt> stmts = emitStmts[index];
        if (stmts == null) {
            stmts = new ArrayList<>(1);
            emitStmts[index] = stmts;
        }
        currentEmit = stmts;
    }

    /**
     * @return Interpreter that also emits {@link Stmt} values as code is interpreted.
     */
    private Interpreter<JvmValue> createInterpreter() {
        return new Interpreter<JvmValue>(Constants.ASM_VERSION) {
            private JvmValue emitValue(int size, com.googlecode.dex2jar.ir.expr.Value value) {
                Local local = newLocal();
                emit(Stmts.nAssign(local, value));
                return new JvmValue(size, local);
            }

            @Override
            public JvmValue newValue(Type type) {
                return null;
            }

            @Override
            public JvmValue newOperation(AbstractInsnNode insn) {
                int opcode = insn.getOpcode();
                switch (opcode) {
                    case ACONST_NULL:
                        return emitValue(1, Exprs.nNull());
                    case ICONST_M1:
                    case ICONST_0:
                    case ICONST_1:
                    case ICONST_2:
                    case ICONST_3:
                    case ICONST_4:
                    case ICONST_5:
                        return emitValue(1, Exprs.nInt(opcode - ICONST_0));
                    case LCONST_0:
                    case LCONST_1:
                        return emitValue(2, Exprs.nLong(opcode - LCONST_0));
                    case FCONST_0:
                    case FCONST_1:
                    case FCONST_2:
                        return emitValue(1, Exprs.nFloat(opcode - FCONST_0));
                    case DCONST_0:
                    case DCONST_1:
                        return emitValue(2, Exprs.nDouble(opcode - DCONST_0));
                    case BIPUSH:
                    case SIPUSH:
                        return emitValue(1, Exprs.nInt(((IntInsnNode) insn).operand));
                    case LDC:
                        Object cst = ((LdcInsnNode) insn).cst;
                        if (cst instanceof Integer) {
                            return emitValue(1, Exprs.nInt((Integer) cst));
                        } else if (cst instanceof Float) {
                            return emitValue(1, Exprs.nFloat((Float) cst));
                        } else if (cst instanceof Long) {
                            return emitValue(2, Exprs.nLong((Long) cst));
                        } else if (cst instanceof Double) {
                            return emitValue(2, Exprs.nDouble((Double) cst));
                        } else if (cst instanceof String) {
                            return emitValue(1, Exprs.nString((String) cst));
                        } else if (cst instanceof Type) {
                            Type type = (Type) cst;
                            int sort = type.getSort();
                            if (sort == Type.OBJECT || sort == Type.ARRAY) {
                                return emitValue(1, Exprs.nType(type.getDescriptor()));
                            } else if (sort == Type.METHOD) {
                                throw new UnsupportedOperationException("Not supported yet.");
                            } else {
                                throw new IllegalArgumentException("Illegal LDC constant " + cst);
                            }
                        } else if (cst instanceof Handle) {
                            throw new UnsupportedOperationException("Not supported yet.");
                        } else {
                            throw new IllegalArgumentException("Illegal LDC constant " + cst);
                        }
                    case GETSTATIC:
                        FieldInsnNode fin = (FieldInsnNode) insn;
                        return emitValue(Type.getType(fin.desc).getSize(), Exprs.nStaticField("L" + fin.owner + ";", fin.name,
                                fin.desc));
                    case NEW:
                        return emitValue(1, Exprs.nNew("L" + ((TypeInsnNode) insn).desc + ";"));
                    default:
                        String location = owner + "." + methodNode.name + methodNode.desc;
                        throw new Error("Unsupported instruction in '" + location + "': " + opcode);
                }
            }

            @Override
            public JvmValue copyOperation(AbstractInsnNode insn, JvmValue value) {
                return emitValue(value.getSize(), getLocal(value));
            }

            @Override
            public JvmValue unaryOperation(AbstractInsnNode insn, JvmValue value0) throws AnalyzerException {
                Local local = value0 == null ? null : getLocal(value0);
                int opcode = insn.getOpcode();
                switch (opcode) {
                    case INEG:
                        return emitValue(1, Exprs.nNeg(local, "I"));
                    case IINC:
                        return emitValue(1, Exprs.nAdd(local, Exprs.nInt(((IincInsnNode) insn).incr), "I"));
                    case L2I:
                        return emitValue(1, Exprs.nCast(local, "J", "I"));
                    case F2I:
                        return emitValue(1, Exprs.nCast(local, "F", "I"));
                    case D2I:
                        return emitValue(1, Exprs.nCast(local, "D", "I"));
                    case I2B:
                        return emitValue(1, Exprs.nCast(local, "I", "B"));
                    case I2C:
                        return emitValue(1, Exprs.nCast(local, "I", "C"));
                    case I2S:
                        return emitValue(1, Exprs.nCast(local, "I", "S"));
                    case FNEG:
                        return emitValue(1, Exprs.nNeg(local, "F"));
                    case I2F:
                        return emitValue(1, Exprs.nCast(local, "I", "F"));
                    case L2F:
                        return emitValue(1, Exprs.nCast(local, "J", "F"));
                    case D2F:
                        return emitValue(1, Exprs.nCast(local, "D", "F"));
                    case LNEG:
                        return emitValue(2, Exprs.nNeg(local, "J"));
                    case I2L:
                        return emitValue(2, Exprs.nCast(local, "I", "J"));
                    case F2L:
                        return emitValue(2, Exprs.nCast(local, "F", "J"));
                    case D2L:
                        return emitValue(2, Exprs.nCast(local, "D", "J"));
                    case DNEG:
                        return emitValue(2, Exprs.nNeg(local, "D"));
                    case I2D:
                        return emitValue(2, Exprs.nCast(local, "I", "D"));
                    case L2D:
                        return emitValue(2, Exprs.nCast(local, "J", "D"));
                    case F2D:
                        return emitValue(2, Exprs.nCast(local, "F", "D"));
                    case IFEQ:
                        emit(Stmts.nIf(Exprs.nEq(local, Exprs.nInt(0), "I"),
                                getLabel(((JumpInsnNode) insn).label)));
                        return null;
                    case IFNE:
                        emit(Stmts.nIf(Exprs.nNe(local, Exprs.nInt(0), "I"),
                                getLabel(((JumpInsnNode) insn).label)));
                        return null;
                    case IFLT:
                        emit(Stmts.nIf(Exprs.nLt(local, Exprs.nInt(0), "I"),
                                getLabel(((JumpInsnNode) insn).label)));
                        return null;
                    case IFGE:
                        emit(Stmts.nIf(Exprs.nGe(local, Exprs.nInt(0), "I"),
                                getLabel(((JumpInsnNode) insn).label)));
                        return null;
                    case IFGT:
                        emit(Stmts.nIf(Exprs.nGt(local, Exprs.nInt(0), "I"),
                                getLabel(((JumpInsnNode) insn).label)));
                        return null;
                    case IFLE:
                        emit(Stmts.nIf(Exprs.nLe(local, Exprs.nInt(0), "I"),
                                getLabel(((JumpInsnNode) insn).label)));
                        return null;
                    case TABLESWITCH: {
                        TableSwitchInsnNode ts = (TableSwitchInsnNode) insn;
                        LabelStmt[] targets = new LabelStmt[ts.labels.size()];
                        for (int i = 0; i < ts.labels.size(); i++) {
                            targets[i] = getLabel(ts.labels.get(i));
                        }
                        emit(Stmts.nTableSwitch(local, ts.min, targets, getLabel(ts.dflt)));
                        return null;
                    }
                    case LOOKUPSWITCH: {
                        LookupSwitchInsnNode ls = (LookupSwitchInsnNode) insn;
                        LabelStmt[] targets = new LabelStmt[ls.labels.size()];
                        int[] lookupValues = new int[ls.labels.size()];
                        for (int i = 0; i < ls.labels.size(); i++) {
                            targets[i] = getLabel(ls.labels.get(i));
                            lookupValues[i] = ls.keys.get(i);
                        }
                        emit(Stmts.nLookupSwitch(local, lookupValues, targets, getLabel(ls.dflt)));
                        return null;
                    }
                    case IRETURN:
                    case LRETURN:
                    case FRETURN:
                    case DRETURN:
                    case ARETURN:
                        // skip, move to returnOperation
                        return null;
                    case PUTSTATIC: {
                        FieldInsnNode fin = (FieldInsnNode) insn;
                        emit(Stmts.nAssign(Exprs.nStaticField("L" + fin.owner + ";", fin.name, fin.desc), local));
                        return null;
                    }
                    case GETFIELD: {
                        FieldInsnNode fin = (FieldInsnNode) insn;
                        Type fieldType = Type.getType(fin.desc);
                        return emitValue(fieldType.getSize(), Exprs.nField(local, "L" + fin.owner + ";", fin.name, fin.desc));
                    }
                    case NEWARRAY:
                        switch (((IntInsnNode) insn).operand) {
                            case T_BOOLEAN:
                                return emitValue(1, Exprs.nNewArray("Z", local));
                            case T_CHAR:
                                return emitValue(1, Exprs.nNewArray("C", local));
                            case T_BYTE:
                                return emitValue(1, Exprs.nNewArray("B", local));
                            case T_SHORT:
                                return emitValue(1, Exprs.nNewArray("S", local));
                            case T_INT:
                                return emitValue(1, Exprs.nNewArray("I", local));
                            case T_FLOAT:
                                return emitValue(1, Exprs.nNewArray("F", local));
                            case T_DOUBLE:
                                return emitValue(1, Exprs.nNewArray("D", local));
                            case T_LONG:
                                return emitValue(1, Exprs.nNewArray("J", local));
                            default:
                                throw new AnalyzerException(insn, "Invalid array type");
                        }
                    case ANEWARRAY:
                        String desc = "L" + ((TypeInsnNode) insn).desc + ";";
                        return emitValue(1, Exprs.nNewArray(desc, local));
                    case ARRAYLENGTH:
                        return emitValue(1, Exprs.nLength(local));
                    case ATHROW:
                        emit(Stmts.nThrow(local));
                        return null;
                    case CHECKCAST:
                        String orgDesc = ((TypeInsnNode) insn).desc;
                        desc = orgDesc.startsWith("[") ? orgDesc : ("L" + orgDesc + ";");
                        return emitValue(1, Exprs.nCheckCast(local, desc));
                    case INSTANCEOF:
                        return emitValue(1, Exprs.nInstanceOf(local, "L" + ((TypeInsnNode) insn).desc + ";"));
                    case MONITORENTER:
                        emit(Stmts.nLock(local));
                        return null;
                    case MONITOREXIT:
                        emit(Stmts.nUnLock(local));
                        return null;
                    case IFNULL:
                        emit(Stmts.nIf(Exprs.nEq(local, Exprs.nNull(), "L"),
                                getLabel(((JumpInsnNode) insn).label)));
                        return null;
                    case IFNONNULL:
                        emit(Stmts.nIf(Exprs.nNe(local, Exprs.nNull(), "L"),
                                getLabel(((JumpInsnNode) insn).label)));
                        return null;
                    case GOTO: // special case
                        emit(Stmts.nGoto(getLabel(((JumpInsnNode) insn).label)));
                        return null;
                    default:
                        String location = owner + "." + methodNode.name + methodNode.desc;
                        throw new Error("Unsupported instruction in '" + location + "': " + opcode);
                }
            }

            @Override
            public JvmValue binaryOperation(AbstractInsnNode insn, JvmValue value10, JvmValue value20) {
                Local local1 = getLocal(value10);
                Local local2 = getLocal(value20);
                int opcode = insn.getOpcode();
                switch (opcode) {
                    case IALOAD:
                        return emitValue(1, Exprs.nArray(local1, local2, "I"));
                    case BALOAD:
                        return emitValue(1, Exprs.nArray(local1, local2, "B"));
                    case CALOAD:
                        return emitValue(1, Exprs.nArray(local1, local2, "C"));
                    case SALOAD:
                        return emitValue(1, Exprs.nArray(local1, local2, "S"));
                    case FALOAD:
                        return emitValue(1, Exprs.nArray(local1, local2, "F"));
                    case AALOAD:
                        return emitValue(1, Exprs.nArray(local1, local2, "L"));
                    case DALOAD:
                        return emitValue(1, Exprs.nArray(local1, local2, "D"));
                    case LALOAD:
                        return emitValue(1, Exprs.nArray(local1, local2, "J"));
                    case IADD:
                        return emitValue(1, Exprs.nAdd(local1, local2, "I"));
                    case ISUB:
                        return emitValue(1, Exprs.nSub(local1, local2, "I"));
                    case IMUL:
                        return emitValue(1, Exprs.nMul(local1, local2, "I"));
                    case IDIV:
                        return emitValue(1, Exprs.nDiv(local1, local2, "I"));
                    case IREM:
                        return emitValue(1, Exprs.nRem(local1, local2, "I"));
                    case ISHL:
                        return emitValue(1, Exprs.nShl(local1, local2, "I"));
                    case ISHR:
                        return emitValue(1, Exprs.nShr(local1, local2, "I"));
                    case IUSHR:
                        return emitValue(1, Exprs.nUshr(local1, local2, "I"));
                    case IAND:
                        return emitValue(1, Exprs.nAnd(local1, local2, "I"));
                    case IOR:
                        return emitValue(1, Exprs.nOr(local1, local2, "I"));
                    case IXOR:
                        return emitValue(1, Exprs.nXor(local1, local2, "I"));
                    case FADD:
                        return emitValue(1, Exprs.nAdd(local1, local2, "F"));
                    case FSUB:
                        return emitValue(1, Exprs.nSub(local1, local2, "F"));
                    case FMUL:
                        return emitValue(1, Exprs.nMul(local1, local2, "F"));
                    case FDIV:
                        return emitValue(1, Exprs.nDiv(local1, local2, "F"));
                    case FREM:
                        return emitValue(1, Exprs.nRem(local1, local2, "F"));
                    case LADD:
                        return emitValue(2, Exprs.nAdd(local1, local2, "J"));
                    case LSUB:
                        return emitValue(2, Exprs.nSub(local1, local2, "J"));
                    case LMUL:
                        return emitValue(2, Exprs.nMul(local1, local2, "J"));
                    case LDIV:
                        return emitValue(2, Exprs.nDiv(local1, local2, "J"));
                    case LREM:
                        return emitValue(2, Exprs.nRem(local1, local2, "J"));
                    case LSHL:
                        return emitValue(2, Exprs.nShl(local1, local2, "J"));
                    case LSHR:
                        return emitValue(2, Exprs.nShr(local1, local2, "J"));
                    case LUSHR:
                        return emitValue(2, Exprs.nUshr(local1, local2, "J"));
                    case LAND:
                        return emitValue(2, Exprs.nAnd(local1, local2, "J"));
                    case LOR:
                        return emitValue(2, Exprs.nOr(local1, local2, "J"));
                    case LXOR:
                        return emitValue(2, Exprs.nXor(local1, local2, "J"));

                    case DADD:
                        return emitValue(2, Exprs.nAdd(local1, local2, "D"));
                    case DSUB:
                        return emitValue(2, Exprs.nSub(local1, local2, "D"));
                    case DMUL:
                        return emitValue(2, Exprs.nMul(local1, local2, "D"));
                    case DDIV:
                        return emitValue(2, Exprs.nDiv(local1, local2, "D"));
                    case DREM:
                        return emitValue(2, Exprs.nRem(local1, local2, "D"));

                    case LCMP:
                        return emitValue(2, Exprs.nLCmp(local1, local2));
                    case FCMPL:
                        return emitValue(1, Exprs.nFCmpl(local1, local2));
                    case FCMPG:
                        return emitValue(1, Exprs.nFCmpg(local1, local2));
                    case DCMPL:
                        return emitValue(2, Exprs.nDCmpl(local1, local2));
                    case DCMPG:
                        return emitValue(2, Exprs.nDCmpg(local1, local2));

                    case IF_ICMPEQ:
                        emit(Stmts.nIf(Exprs.nEq(local1, local2, "I"),
                                getLabel(((JumpInsnNode) insn).label)));
                        return null;
                    case IF_ICMPNE:
                        emit(Stmts.nIf(Exprs.nNe(local1, local2, "I"),
                                getLabel(((JumpInsnNode) insn).label)));
                        return null;
                    case IF_ICMPLT:
                        emit(Stmts.nIf(Exprs.nLt(local1, local2, "I"),
                                getLabel(((JumpInsnNode) insn).label)));
                        return null;
                    case IF_ICMPGE:
                        emit(Stmts.nIf(Exprs.nGe(local1, local2, "I"),
                                getLabel(((JumpInsnNode) insn).label)));
                        return null;
                    case IF_ICMPGT:
                        emit(Stmts.nIf(Exprs.nGt(local1, local2, "I"),
                                getLabel(((JumpInsnNode) insn).label)));
                        return null;
                    case IF_ICMPLE:
                        emit(Stmts.nIf(Exprs.nLe(local1, local2, "I"),
                                getLabel(((JumpInsnNode) insn).label)));
                        return null;
                    case IF_ACMPEQ:
                        emit(Stmts.nIf(Exprs.nEq(local1, local2, "L"),
                                getLabel(((JumpInsnNode) insn).label)));
                        return null;
                    case IF_ACMPNE:
                        emit(Stmts.nIf(Exprs.nNe(local1, local2, "L"),
                                getLabel(((JumpInsnNode) insn).label)));
                        return null;
                    case PUTFIELD:
                        FieldInsnNode fin = (FieldInsnNode) insn;
                        emit(Stmts.nAssign(Exprs.nField(local1, "L" + fin.owner + ";", fin.name, fin.desc), local2));
                        return null;
                    default:
                        String location = owner + "." + methodNode.name + methodNode.desc;
                        throw new Error("Unsupported instruction in '" + location + "': " + opcode);
                }
            }

            @Override
            public JvmValue ternaryOperation(AbstractInsnNode insn, JvmValue value1, JvmValue value2, JvmValue value3) {
                Local local1 = getLocal(value1);
                Local local2 = getLocal(value2);
                Local local3 = getLocal(value3);
                switch (insn.getOpcode()) {
                    case IASTORE:
                        emit(Stmts.nAssign(Exprs.nArray(local1, local2, "I"),
                                local3));
                        break;
                    case LASTORE:
                        emit(Stmts.nAssign(Exprs.nArray(local1, local2, "J"),
                                local3));
                        break;
                    case FASTORE:
                        emit(Stmts.nAssign(Exprs.nArray(local1, local2, "F"),
                                local3));
                        break;
                    case DASTORE:
                        emit(Stmts.nAssign(Exprs.nArray(local1, local2, "D"),
                                local3));
                        break;
                    case AASTORE:
                        emit(Stmts.nAssign(Exprs.nArray(local1, local2, "L"),
                                local3));
                        break;
                    case BASTORE:
                        emit(Stmts.nAssign(Exprs.nArray(local1, local2, "B"),
                                local3));
                        break;
                    case CASTORE:
                        emit(Stmts.nAssign(Exprs.nArray(local1, local2, "C"),
                                local3));
                        break;
                    case SASTORE:
                        emit(Stmts.nAssign(Exprs.nArray(local1, local2, "S"),
                                local3));
                        break;
                    default:
                        break;
                }

                return null;
            }

            @Override
            public JvmValue naryOperation(AbstractInsnNode insn, List<? extends JvmValue> xvalues) {
                com.googlecode.dex2jar.ir.expr.Value[] values =
                        new com.googlecode.dex2jar.ir.expr.Value[xvalues.size()];
                for (int i = 0; i < xvalues.size(); i++) {
                    values[i] = getLocal(xvalues.get(i));
                }
                if (insn.getOpcode() == MULTIANEWARRAY) {
                    MultiANewArrayInsnNode multi = (MultiANewArrayInsnNode) insn;
                    NewMutiArrayExpr n = Exprs.nNewMutiArray(multi.desc.replaceAll("\\[+", ""), multi.dims, values);
                    return emitValue(Type.getType(multi.desc).getSize(), n);
                } else if (insn.getOpcode() == INVOKEDYNAMIC) {
                    InvokeDynamicInsnNode mi = (InvokeDynamicInsnNode) insn;
                    Handle bsm = mi.bsm;
                    Type[] a = Type.getArgumentTypes(bsm.getDesc());
                    String[] params = new String[a.length];
                    for (int i = 0; i < a.length; i += 1) {
                        params[i] = a[i].getDescriptor();
                    }
                    Method method = new Method("L" + bsm.getOwner() + ";", bsm.getName(), params,
                            Type.getReturnType(bsm.getDesc()).getDescriptor());
                    MethodHandle mh = new MethodHandle(MethodHandle.getTypeFromAsmOpcode(bsm.getTag()), method);
                    Type[] t = Type.getArgumentTypes(mi.desc);
                    String[] paramDesc = new String[t.length];
                    for (int i = 0; i < t.length; i += 1) {
                        paramDesc[i] = t[i].getDescriptor();
                    }
                    InvokeCustomExpr d = Exprs.nInvokeCustom(values, mi.name, new Proto(paramDesc,
                            Type.getReturnType(mi.desc).getDescriptor()), mh, mi.bsmArgs);
                    return emitValue(Type.getReturnType(mi.desc).getSize(), d);
                } else {
                    MethodInsnNode mi = (MethodInsnNode) insn;
                    com.googlecode.dex2jar.ir.expr.Value v = null;
                    String ret = Type.getReturnType(mi.desc).getDescriptor();
                    String owner = "L" + mi.owner + ";";
                    String[] ps = toDescArray(Type.getArgumentTypes(mi.desc));
                    switch (insn.getOpcode()) {
                        case INVOKEVIRTUAL:
                            v = Exprs.nInvokeVirtual(values, owner, mi.name, ps, ret);
                            break;
                        case INVOKESPECIAL:
                            v = Exprs.nInvokeSpecial(values, owner, mi.name, ps, ret);
                            break;
                        case INVOKESTATIC:
                            v = Exprs.nInvokeStatic(values, owner, mi.name, ps, ret);
                            break;
                        case INVOKEINTERFACE:
                            v = Exprs.nInvokeInterface(values, owner, mi.name, ps, ret);
                            break;
                        default:
                            break;
                    }
                    if ("V".equals(ret)) {
                        emit(Stmts.nVoidInvoke(v));
                        return null;
                    } else {
                        return emitValue(Type.getReturnType(mi.desc).getSize(), v);
                    }
                }
            }

            @Override
            public JvmValue merge(JvmValue v, JvmValue w) {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            public void returnOperation(AbstractInsnNode insn, JvmValue value, JvmValue expected) {
                switch (insn.getOpcode()) {
                    case IRETURN:
                    case LRETURN:
                    case FRETURN:
                    case DRETURN:
                    case ARETURN:
                        emit(Stmts.nReturn(getLocal(value)));
                        break;
                    case RETURN:
                        emit(Stmts.nReturnVoid());
                        break;
                    default:
                        break;
                }
            }
        };
    }

    /**
     * Gets the associated IR {@link Local} for the given {@link JvmValue}.
     *
     * @param value
     *         JVM value to get IR local of.
     *
     * @return IR local for the given value.
     */
    private Local getLocal(JvmValue value) {
        Local local = value.local;
        if (local == null) {
            value.local = newLocal();
            local = value.local;
        }
        return local;
    }

    /**
     * @param labelNode
     *         Input JVM/ASM label.
     *
     * @return Associated IR label statement.
     */
    private LabelStmt getLabel(LabelNode labelNode) {
        Label label = labelNode.getLabel();
        LabelStmt ls = labelStmtMap.get(label);
        if (ls == null) {
            ls = Stmts.nLabel();
            labelStmtMap.put(label, ls);
        }
        return ls;
    }

    /**
     * Assigns local variable values' {@link JvmValue#parent parent relations} in the destination frame
     * to the values in the provided source frame.
     *
     * @param src
     *         Current source frame.
     * @param destination
     *         Destination frame to flow into.
     *
     * @see #mergeLocals(JvmFrame, JvmFrame)
     */
    private void mergeLocals(JvmFrame src, int destination) {
        JvmFrame distFrame = frames[destination];
        if (distFrame == null) {
            distFrame = new JvmFrame(methodNode.maxLocals, methodNode.maxStack);
            frames[destination] = distFrame;
        }
        mergeLocals(src, distFrame);
    }

    /**
     * Assigns local variable values' {@link JvmValue#parent parent relations} in the destination frame
     * to the values in the provided source frame.
     *
     * @param source
     *         Current source frame.
     * @param destination
     *         Destination frame to flow into.
     */
    private void mergeLocals(JvmFrame source, JvmFrame destination) {
        // Relate the locals the destination frame, to the values in the source frame.
        for (int i = 0; i < source.getLocals(); i++) {
            JvmValue sourceLocal = source.getLocal(i);
            JvmValue destinationLocal = destination.getLocal(i);
            if (sourceLocal != null) {
                if (destinationLocal == null) {
                    destinationLocal = new JvmValue(sourceLocal.getSize());
                    destination.setLocal(i, destinationLocal);
                }
                relate(sourceLocal, destinationLocal);
            }
        }
    }

    /**
     * Ensures contents of the frame at the given {@code destination} are compatible with the source frame.
     * <br>
     * Assigns stack and local variable values' {@link JvmValue#parent parent relations} in the destination frame
     * to the values in the provided source frame.
     *
     * @param sourceFrame
     *         Current source frame.
     * @param destination
     *         Destination frame to flow into.
     */
    private void mergeFull(JvmFrame sourceFrame, int destination) {
        JvmFrame destinationFrame = frames[destination];
        if (destinationFrame == null) {
            frames[destination] = new JvmFrame(methodNode.maxLocals, methodNode.maxStack);
            destinationFrame = frames[destination];
        }
        if (parentCount[destination] > 1) {
            // Relate the locals the destination frame, to the values in the source frame.
            mergeLocals(sourceFrame, destinationFrame);

            // Merge stack states such that the destination takes on values to be compatible with our source frame.
            if (sourceFrame.getStackSize() > 0) {
                if (destinationFrame.getStackSize() == 0) {
                    // Destination frame is likely not set-up, so we will just push our values onto it.
                    for (int i = 0; i < sourceFrame.getStackSize(); i++) {
                        destinationFrame.push(new JvmValue(sourceFrame.getStack(i).getSize()));
                    }
                } else if (destinationFrame.getStackSize() != sourceFrame.getStackSize()) {
                    // Destination frame is set up, but stack sizes do not match.
                    // Something in the code is wrong.
                    throw new RuntimeException("stack not balanced");
                }

                // Relate all stack values of the destination frame, to the source frame.
                for (int i = 0; i < sourceFrame.getStackSize(); i++) {
                    JvmValue sourceStackValue = sourceFrame.getStack(i);
                    JvmValue destinationStackValue = destinationFrame.getStack(i);
                    relate(sourceStackValue, destinationStackValue);
                }
            }
        } else {
            // Source frame seems to not be visited (now control flow branch inputs)
            // Can just do basic init (copy operation)
            destinationFrame.init(sourceFrame);
        }
    }

    /**
     * Update's the child's {@link JvmValue#parent} and {@link JvmValue#otherParent} attributes
     * to link to the given parent value.
     *
     * @param parent
     *         Parent value to use.
     * @param child
     *         Child with parent relations to update to point to the {@code parent}.
     */
    private static void relate(JvmValue parent, JvmValue child) {
        if (child.parent == null) {
            child.parent = parent;
        } else if (child.parent != parent) {
            if (child.otherParent == null) {
                child.otherParent = new HashSet<>(5);
            }
            child.otherParent.add(parent);
        }
    }

    /**
     * @param methodNode
     *         Original method from ASM to pull maximum stack and local variable table sizes from.
     * @param target
     *         IR method to pull additional information from, like the type of {@code this} variables.
     *
     * @return Initial frame for analysis in {@link #dfs(BitSet[], BitSet, BitSet, Interpreter)}.
     */
    private JvmFrame initFirstFrame(MethodNode methodNode, IrMethod target) {
        // Create frame with stack/local size expectations from the given method.
        JvmFrame first = new JvmFrame(methodNode.maxLocals, methodNode.maxStack);
        int varIndex = 0;
        if (!target.isStatic) {
            // Target method is not static, so it will have a 'this' variable
            // to the instance of the current class.
            Local thiz = newLocal();
            emit(Stmts.nIdentity(thiz, Exprs.nThisRef(target.owner)));
            first.setLocal(varIndex++, new JvmValue(1, thiz));
        }

        // Add variables for all parameter values.
        for (int i = 0; i < target.args.length; i++) {
            Local local = newLocal();
            emit(Stmts.nIdentity(local, Exprs.nParameterRef(target.args[i], i)));
            int sizeOfType = sizeOfType(target.args[i]);
            first.setLocal(varIndex, new JvmValue(sizeOfType, local));
            varIndex += sizeOfType;
        }
        return first;
    }

    /**
     * @return New local with the {@link Local#lsIndex} of the next available slot.
     */
    private Local newLocal() {
        Local local = Exprs.nLocal(target.locals.size());
        target.locals.add(local);
        return local;
    }

    private class JvmFrame extends Frame<JvmValue> {
        private JvmFrame(int nLocals, int nStack) {
            super(nLocals, nStack);
        }

        @Override
        public void execute(AbstractInsnNode insn, Interpreter<JvmValue> interpreter) throws AnalyzerException {
            // Handled externally
            if (insn.getType() == AbstractInsnNode.FRAME
                    || insn.getType() == AbstractInsnNode.LINE
                    || insn.getType() == AbstractInsnNode.LABEL) {
                return;
            }

            // Overrides for some specific edge case handling.
            if (insn.getOpcode() == Opcodes.RETURN) {
                interpreter.returnOperation(insn, null, null);
            } else if (insn.getOpcode() == Opcodes.GOTO) {
                interpreter.unaryOperation(insn, null);
            } else if (insn.getOpcode() == RET || insn.getOpcode() == JSR) {
                throw new DeprecatedInstructionException(owner, methodNode, insnList.indexOf(insn));
            } else {
                super.execute(insn, interpreter);
            }
        }
    }

    public static class JvmValue implements Value {

        private final int size;

        public JvmValue parent;

        public Set<JvmValue> otherParent;

        public Local local;

        public JvmValue(int size, Local local) {
            this.size = size;
            this.local = local;
        }

        public JvmValue(int size) {
            this.size = size;
        }

        @Override
        public int getSize() {
            return size;
        }

    }
}
