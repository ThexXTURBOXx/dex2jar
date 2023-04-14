package com.googlecode.d2j.converter;

import com.googlecode.d2j.DexLabel;
import com.googlecode.d2j.DexType;
import com.googlecode.d2j.Field;
import com.googlecode.d2j.Method;
import com.googlecode.d2j.node.DexCodeNode;
import com.googlecode.d2j.node.DexDebugNode;
import com.googlecode.d2j.node.TryCatchNode;
import com.googlecode.d2j.node.analysis.DvmFrame;
import com.googlecode.d2j.node.analysis.DvmInterpreter;
import com.googlecode.d2j.node.insn.BaseSwitchStmtNode;
import com.googlecode.d2j.node.insn.ConstStmtNode;
import com.googlecode.d2j.node.insn.DexLabelStmtNode;
import com.googlecode.d2j.node.insn.DexStmtNode;
import com.googlecode.d2j.node.insn.FieldStmtNode;
import com.googlecode.d2j.node.insn.FillArrayDataStmtNode;
import com.googlecode.d2j.node.insn.FilledNewArrayStmtNode;
import com.googlecode.d2j.node.insn.JumpStmtNode;
import com.googlecode.d2j.node.insn.MethodCustomStmtNode;
import com.googlecode.d2j.node.insn.MethodPolymorphicStmtNode;
import com.googlecode.d2j.node.insn.MethodStmtNode;
import com.googlecode.d2j.node.insn.PackedSwitchStmtNode;
import com.googlecode.d2j.node.insn.SparseSwitchStmtNode;
import com.googlecode.d2j.node.insn.Stmt2R1NNode;
import com.googlecode.d2j.node.insn.TypeStmtNode;
import com.googlecode.d2j.reader.Op;
import com.googlecode.dex2jar.ir.IrMethod;
import com.googlecode.dex2jar.ir.Trap;
import com.googlecode.dex2jar.ir.TypeClass;
import com.googlecode.dex2jar.ir.expr.Exprs;
import com.googlecode.dex2jar.ir.expr.Local;
import com.googlecode.dex2jar.ir.expr.Value;
import com.googlecode.dex2jar.ir.stmt.AssignStmt;
import com.googlecode.dex2jar.ir.stmt.LabelStmt;
import com.googlecode.dex2jar.ir.stmt.Stmt;
import com.googlecode.dex2jar.ir.stmt.StmtList;
import com.googlecode.dex2jar.ir.stmt.Stmts;
import com.googlecode.dex2jar.ir.ts.UniqueQueue;
import org.objectweb.asm.tree.AbstractInsnNode;

import java.util.*;

import static com.googlecode.d2j.util.Types.*;

import static com.googlecode.dex2jar.ir.expr.Exprs.nAdd;
import static com.googlecode.dex2jar.ir.expr.Exprs.nAnd;
import static com.googlecode.dex2jar.ir.expr.Exprs.nArray;
import static com.googlecode.dex2jar.ir.expr.Exprs.nArrayValue;
import static com.googlecode.dex2jar.ir.expr.Exprs.nCast;
import static com.googlecode.dex2jar.ir.expr.Exprs.nCheckCast;
import static com.googlecode.dex2jar.ir.expr.Exprs.nDCmpg;
import static com.googlecode.dex2jar.ir.expr.Exprs.nDCmpl;
import static com.googlecode.dex2jar.ir.expr.Exprs.nDiv;
import static com.googlecode.dex2jar.ir.expr.Exprs.nFCmpg;
import static com.googlecode.dex2jar.ir.expr.Exprs.nFCmpl;
import static com.googlecode.dex2jar.ir.expr.Exprs.nField;
import static com.googlecode.dex2jar.ir.expr.Exprs.nInstanceOf;
import static com.googlecode.dex2jar.ir.expr.Exprs.nInt;
import static com.googlecode.dex2jar.ir.expr.Exprs.nInvokeCustom;
import static com.googlecode.dex2jar.ir.expr.Exprs.nInvokeInterface;
import static com.googlecode.dex2jar.ir.expr.Exprs.nInvokeNew;
import static com.googlecode.dex2jar.ir.expr.Exprs.nInvokePolymorphic;
import static com.googlecode.dex2jar.ir.expr.Exprs.nInvokeSpecial;
import static com.googlecode.dex2jar.ir.expr.Exprs.nInvokeStatic;
import static com.googlecode.dex2jar.ir.expr.Exprs.nInvokeVirtual;
import static com.googlecode.dex2jar.ir.expr.Exprs.nLCmp;
import static com.googlecode.dex2jar.ir.expr.Exprs.nLength;
import static com.googlecode.dex2jar.ir.expr.Exprs.nLong;
import static com.googlecode.dex2jar.ir.expr.Exprs.nMul;
import static com.googlecode.dex2jar.ir.expr.Exprs.nNeg;
import static com.googlecode.dex2jar.ir.expr.Exprs.nNew;
import static com.googlecode.dex2jar.ir.expr.Exprs.nNewArray;
import static com.googlecode.dex2jar.ir.expr.Exprs.nNot;
import static com.googlecode.dex2jar.ir.expr.Exprs.nOr;
import static com.googlecode.dex2jar.ir.expr.Exprs.nRem;
import static com.googlecode.dex2jar.ir.expr.Exprs.nShl;
import static com.googlecode.dex2jar.ir.expr.Exprs.nShr;
import static com.googlecode.dex2jar.ir.expr.Exprs.nStaticField;
import static com.googlecode.dex2jar.ir.expr.Exprs.nString;
import static com.googlecode.dex2jar.ir.expr.Exprs.nSub;
import static com.googlecode.dex2jar.ir.expr.Exprs.nType;
import static com.googlecode.dex2jar.ir.expr.Exprs.nUshr;
import static com.googlecode.dex2jar.ir.expr.Exprs.nXor;
import static com.googlecode.dex2jar.ir.stmt.Stmts.nAssign;
import static com.googlecode.dex2jar.ir.stmt.Stmts.nFillArrayData;
import static com.googlecode.dex2jar.ir.stmt.Stmts.nGoto;
import static com.googlecode.dex2jar.ir.stmt.Stmts.nIf;
import static com.googlecode.dex2jar.ir.stmt.Stmts.nLock;
import static com.googlecode.dex2jar.ir.stmt.Stmts.nLookupSwitch;
import static com.googlecode.dex2jar.ir.stmt.Stmts.nNop;
import static com.googlecode.dex2jar.ir.stmt.Stmts.nReturn;
import static com.googlecode.dex2jar.ir.stmt.Stmts.nReturnVoid;
import static com.googlecode.dex2jar.ir.stmt.Stmts.nTableSwitch;
import static com.googlecode.dex2jar.ir.stmt.Stmts.nThrow;
import static com.googlecode.dex2jar.ir.stmt.Stmts.nUnLock;
import static com.googlecode.dex2jar.ir.stmt.Stmts.nVoidInvoke;

public class Dex2IRConverter {

    // Inputs

    private final DexCodeNode dexCodeNode;
    private final Method method;
    private final boolean isStatic;
    private final List<DexStmtNode> stmtsList;

    // Analysis

    private int[] parentCount;
    private Dex2IrFrame[] frames;

    // Outputs

    private IrMethod target;
    private List<Stmt>[] emitStmts;
    private List<Stmt> currentEmit;
    private final List<Stmt> preEmit = new ArrayList<>();
    private final Map<DexLabel, LabelStmt> dexLabelToStmt = new IdentityHashMap<>();
    private final Map<DexLabel, DexLabelStmtNode> labelToContainingNode = new IdentityHashMap<>();

    private Dex2IRConverter(boolean isStatic, Method method, DexCodeNode dexCodeNode) {
        this.isStatic = isStatic;
        this.method = method;
        this.dexCodeNode = dexCodeNode;

        // Note that the contents of this list ARE modified further below.
        stmtsList = dexCodeNode.stmts;
    }

    /**
     * @param isStatic
     *         Flag to generate indicate the input method is static.
     * @param method
     *         Method declaration to convert.
     * @param dexCodeNode
     *         Code from the method to convert.
     *
     * @return Intermediate converted format of the method.
     */
    public static IrMethod convert(boolean isStatic, Method method, DexCodeNode dexCodeNode) {
        return new Dex2IRConverter(isStatic, method, dexCodeNode).convert();
    }

    /**
     * @return Intermediate converted format of the method.
     */
    private IrMethod convert() {
        // Create the output method instance based on details of the method declaration.
        IrMethod irMethod = new IrMethod();
        irMethod.args = method.getParameterTypes();
        irMethod.ret = method.getReturnType();
        irMethod.owner = method.getOwner();
        irMethod.name = method.getName();
        irMethod.isStatic = isStatic;
        target = irMethod;

        // Map all input dex-statements to their indices in the statements list.
        // Record mapping of labels to their containing nodes.
        for (int i = 0; i < stmtsList.size(); i++) {
            DexStmtNode stmtNode = stmtsList.get(i);
            stmtNode.index = i;
            if (stmtNode instanceof DexLabelStmtNode) {
                DexLabelStmtNode dexLabelStmtNode = (DexLabelStmtNode) stmtNode;
                labelToContainingNode.put(dexLabelStmtNode.label, dexLabelStmtNode);
            }
        }

        // Apply fix for some edge cases with broken exception handlers.
        fixExceptionHandlers();

        // Create an array for tracking outbound control flow edges for each statement.
        // For any statement, the associated BitSet marks each offset as:
        //  - set[N] == 0: Insn does not flow into N
        //  - set[N] == 1: Insn flows into N
        BitSet[] exBranch = new BitSet[stmtsList.size()];

        // Create an array tracking how many incoming edges to the statement exist.
        createInitialParentCounts();

        // Create a bitset for exception handlers.
        // Each entry in the bitset is such that:
        //  - set[N] == 0: statement at N is NOT an exception handler start
        //  - set[N] == 1: statement at N is a Label and is an exception handler start
        BitSet handlers = new BitSet(stmtsList.size());
        initExceptionHandlers(exBranch, handlers);

        // Create an interpreter to analyze the stack and local variable table contents
        // for each index in the statements list.
        DvmInterpreter<DvmValue> interpreter = createInterpreter();

        // Array of frames to store results of the interpreters visitation of the method code.
        frames = new Dex2IrFrame[stmtsList.size()];

        // Array of statements mapped from the input statements.
        emitStmts = new List[stmtsList.size()];

        // Bitset tracking which statements were visited (any set[N] == 0 is dead code)
        BitSet access = new BitSet(stmtsList.size());

        // Visit the dex method statements with the ASM interpreter.
        //  - Updates emitted statements array
        //  - Populates frames array
        dfs(exBranch, handlers, access, interpreter);

        // Populate statement-list by iterating over the dex statements and seeing what
        // statement values we have associated to each input statement.
        StmtList stmts = target.stmts;
        stmts.addAll(preEmit);
        for (int i = 0; i < stmtsList.size(); i++) {
            DexStmtNode p = stmtsList.get(i);
            if (access.get(i)) {
                // Add the associated statements of the visited statements.
                List<Stmt> es = emitStmts[i];
                if (es != null) {
                    stmts.addAll(es);
                }
            } else {
                // Add unvisited labels.
                if (p instanceof DexLabelStmtNode) {
                    stmts.add(getLabel(((DexLabelStmtNode) p).label));
                }
            }
        }

        // Clear statements.
        emitStmts = null;


        // A standard linked-list may run out of memory as reported: https://github.com/pxb1988/dex2jar/issues/501
        // This can be solved by using a unique-queue which denies duplicate entries.
        Queue<DvmValue> queue = new UniqueQueue<>();
        for (int i = 0; i < frames.length; i++) {
            Dex2IrFrame frame = frames[i];
            if (parentCount[i] > 1 && frame != null && access.get(i)) {
                for (int j = 0; j < frame.getTotalRegisters(); j++) {
                    DvmValue v = frame.getReg(j);
                    addToQueue(queue, v);
                }
            }
        }

        while (!queue.isEmpty()) {
            DvmValue v = queue.poll();
            getLocal(v);
            if (v.parent != null) {
                if (v.parent.local == null) {
                    queue.add(v.parent);
                }
            }
            if (v.otherParent != null) {
                for (DvmValue v2 : v.otherParent) {
                    if (v2.local == null) {
                        queue.add(v2);
                    }
                }
            }
        }

        Set<com.googlecode.dex2jar.ir.expr.Value> phiValues = new HashSet<>();
        List<LabelStmt> phiLabels = new ArrayList<>();
        for (int i = 0; i < frames.length; i++) {
            Dex2IrFrame frame = frames[i];
            if (parentCount[i] > 1 && frame != null && access.get(i)) {
                DexStmtNode p = stmtsList.get(i);
                LabelStmt labelStmt = getLabel(((DexLabelStmtNode) p).label);
                List<AssignStmt> phis = new ArrayList<>();
                for (int j = 0; j < frame.getTotalRegisters(); j++) {
                    DvmValue v = frame.getReg(j);
                    addPhi(v, phiValues, phis);
                }

                labelStmt.phis = phis;
                phiLabels.add(labelStmt);
            }
        }
        if (phiLabels.size() > 0) {
            target.phiLabels = phiLabels;
        }

        supplementLineNumber(dexCodeNode);

        return target;
    }

    // TODO: Validate this resolves https://github.com/pxb1988/dex2jar/issues/165
    private void supplementLineNumber(DexCodeNode dexCodeNode) {
        if (dexCodeNode == null || dexCodeNode.debugNode == null || dexCodeNode.debugNode.debugNodes == null) {
            return;
        }
        Map<DexLabel, Integer> lineNumber = new IdentityHashMap<>();
        for (DexDebugNode.DexDebugOpNode debugNode : dexCodeNode.debugNode.debugNodes) {
            if (debugNode instanceof DexDebugNode.DexDebugOpNode.LineNumber) {
                lineNumber.put(debugNode.label, ((DexDebugNode.DexDebugOpNode.LineNumber) debugNode).line);
            }
        }
        for (Map.Entry<DexLabel, LabelStmt> entry : dexLabelToStmt.entrySet()) {
            Integer line = lineNumber.get(entry.getKey());
            if (line != null) {
                entry.getValue().lineNumber = line;
            }
        }
    }

    /**
     * <pre>{@code
     * L1:
     *    STMTs
     * L2:
     *    RETURN
     * L1~L2 > L2 Exception
     * }</pre>
     * <p/>
     * fix to
     * <p/>
     * <pre>{@code
     * L1:
     *    STMTs
     * L2:
     *    RETURN
     * L3:                  // INSERTED
     *    goto L2           // INSERTED
     * L1~L2 > L3 Exception // Destination switched
     * }</pre>
     */
    private void fixExceptionHandlers() {
        // Skip if there are no exception handlers.
        if (dexCodeNode.tryStmts == null)
            return;

        Queue<Integer> indices = new LinkedList<>();
        Set<Integer> handlers = new TreeSet<>();
        for (TryCatchNode tcb : dexCodeNode.tryStmts) {
            for (DexLabel label : tcb.handler) {
                int index = indexOf(label);
                indices.add(index + 1); // add the next insn after label
                handlers.add(index);
            }
        }
        indices.add(0);

        Map<Integer, DexLabel> needChange = new HashMap<>();
        BitSet access = new BitSet(stmtsList.size());
        while (!indices.isEmpty()) {
            Integer keyIndex = indices.poll();
            int index = keyIndex;

            // Skip if already visited
            if (access.get(index))
                continue;
            access.set(index);

            // If the handler contains the current index, the control flow has
            // entered into an exception handler here.
            if (handlers.contains(keyIndex))
                needChange.put(keyIndex, null);

            // Update which indices to visit based on control flow of the statement.
            DexStmtNode node = stmtsList.get(keyIndex);
            if (node.op == null) {
                indices.add(index + 1);
            } else {
                Op op = node.op;
                if (op.canContinue()) {
                    indices.add(index + 1);
                }
                if (op.canBranch()) {
                    JumpStmtNode jump = (JumpStmtNode) node;
                    indices.add(indexOf(jump.label));
                }
                if (op.canSwitch()) {
                    for (DexLabel dexLabel : ((BaseSwitchStmtNode) node).labels) {
                        indices.add(indexOf(dexLabel));
                    }
                }
            }
        }

        // If there are indices marked as needing to be changed, that means we've encountered
        // an exception handler block start here.
        if (needChange.size() > 0) {
            for (TryCatchNode tcb : dexCodeNode.tryStmts) {
                DexLabel[] handler = tcb.handler;
                for (int i = 0; i < handler.length; i++) {
                    DexLabel handlerLabel = handler[i];
                    int index = indexOf(handlerLabel);
                    if (needChange.containsKey(index)) {
                        DexLabel toChangeLabel = needChange.get(index);
                        if (toChangeLabel == null) {
                            // Create label instance and mark as to-change
                            toChangeLabel = new DexLabel();
                            needChange.put(index, toChangeLabel);

                            // Add the label to the input statements list.
                            DexLabelStmtNode dexStmtNode = new DexLabelStmtNode(toChangeLabel);
                            dexStmtNode.index = stmtsList.size();
                            stmtsList.add(dexStmtNode);
                            labelToContainingNode.put(toChangeLabel, dexStmtNode);

                            // Insert a GOTO jump to the handler and add it to the method as well.
                            JumpStmtNode jumpStmtNode = new JumpStmtNode(Op.GOTO, 0, 0, handlerLabel);
                            jumpStmtNode.index = stmtsList.size();
                            stmtsList.add(jumpStmtNode);
                        }
                        handler[i] = toChangeLabel;
                    }
                }
            }
        }
    }

    /**
     * Populates the handlers bitset, where each set bit represents an offset in the {@link #stmtsList}
     * that denotes the start range of an exception handler.
     *
     * @param handlers
     *         Handler bitset to populate.
     * @param exBranch
     *         Control flow destination edges.
     */
    private void initExceptionHandlers( BitSet[] exBranch, BitSet handlers) {
        if (dexCodeNode.tryStmts != null) {
            for (TryCatchNode tcb : dexCodeNode.tryStmts) {
                for (DexLabel h : tcb.handler) {
                    handlers.set(indexOf(h));
                }
                boolean hasEx = false;
                int endIndex = indexOf(tcb.end);
                for (int p = indexOf(tcb.start) + 1; p < endIndex; p++) {
                    DexStmtNode stmt = stmtsList.get(p);
                    if (stmt.op != null && stmt.op.canThrow()) {
                        hasEx = true;
                        BitSet x = exBranch[p];
                        if (x == null) {
                            exBranch[p] = new BitSet(stmtsList.size());
                            x = exBranch[p];
                        }
                        for (DexLabel h : tcb.handler) {
                            int hIndex = indexOf(h);
                            x.set(hIndex);
                            parentCount[hIndex]++;
                        }
                    }
                }
                if (hasEx) {
                    target.traps.add(new Trap(getLabel(tcb.start), getLabel(tcb.end), getLabels(tcb.handler),
                            tcb.type));
                }
            }
        }
    }

    private void addPhi(DvmValue v, Set<com.googlecode.dex2jar.ir.expr.Value> phiValues, List<AssignStmt> phis) {
        if (v != null) {
            if (v.local != null) {
                if (v.parent != null) {
                    phiValues.add(getLocal(v.parent));
                }
                if (v.otherParent != null) {
                    for (DvmValue v2 : v.otherParent) {
                        phiValues.add(getLocal(v2));
                    }
                }
                if (phiValues.size() > 0) {
                    phis.add(Stmts.nAssign(v.local, Exprs
                            .nPhi(phiValues.toArray(new Value[0]))));
                    phiValues.clear();
                }
            }
        }
    }

    private void addToQueue(Queue<DvmValue> queue, DvmValue v) {
        if (v != null) {
            if (v.local != null) {
                if (v.parent != null) {
                    if (v.parent.local == null) {
                        queue.add(v.parent);
                    }
                }
                if (v.otherParent != null) {
                    for (DvmValue v2 : v.otherParent) {
                        if (v2.local == null) {
                            queue.add(v2);
                        }
                    }
                }
            }
        }
    }

    /**
     * Called from {@link #dfs(BitSet[], BitSet, BitSet, DvmInterpreter)} before each statement is visited.
     * <p/>
     * Sets the {@link #currentEmit} to the {@link #emitStmts} at the given index.
     * The current-emit list contains the list of {@link Stmt} items that map to the {@link AbstractInsnNode}
     * at the given position in the {@link #stmtsList}.
     *
     * @param index
     *         Current statement index to visit.
     */
    private void setCurrentEmit(int index) {
        currentEmit = emitStmts[index];
        if (currentEmit == null) {
            emitStmts[index] = new ArrayList<>(1);
            currentEmit = emitStmts[index];
        }
    }

    /**
     * Visits the {@link #dexCodeNode}'s {@link #stmtsList statements} with the given interpreter.
     *
     * @param exBranch
     *         Array mapping statement offsets to bitsets representing which statements are potential branch targets.
     * @param handlers
     *         Bitset representing which statements are catch { ... } block handler start positions.
     * @param access
     *         Bitset tracking which statements were visited <i>(any {@code set[N] == 0} is dead code)</i>
     * @param interpreter
     *         Interpreter instance to handle updating frame states, and {@link #currentEmit statement emission}.
     */
    private void dfs(BitSet[] exBranch, BitSet handlers, BitSet access, DvmInterpreter<DvmValue> interpreter) {
        currentEmit = preEmit;

        // Create the first frame to begin our analysis with.
        Dex2IrFrame first = initFirstFrame(dexCodeNode, target);
        if (parentCount[0] > 1) {
            merge(first, 0);
        } else {
            frames[0] = first;
        }

        // Keep reference local, minor performance optimization
        List<DexStmtNode> stmtsList = this.stmtsList;

        // Track visited statement control flow.
        // Control flows will push to the stack,
        // and visiting them to handle their subsequent analysis will pop them off.
        Stack<DexStmtNode> stack = new Stack<>();
        stack.push(stmtsList.get(0));

        // Temporary frame which we will operate on with our interpreter.
        Dex2IrFrame tmp = new Dex2IrFrame(dexCodeNode.totalRegister);
        while (!stack.isEmpty()) {
            DexStmtNode statement = stack.pop();
            int index = statement.index;

            // Check if we've already visited this statement.
            // If so, we skip execution.
            if (access.get(index)) continue;
            access.set(index);

            // Update current emission statements target.
            // Any item we create IR statements for will relate to the current statement at this index.
            setCurrentEmit(index);

            // Current frame to operate on.
            Dex2IrFrame frame = frames[index];

            // Handle emitting statements for labels
            if (statement instanceof DexLabelStmtNode) {
                emit(getLabel(((DexLabelStmtNode) statement).label));
                if (handlers.get(index)) {
                    Local ex = newLocal();
                    emit(Stmts.nIdentity(ex, Exprs.nExceptionRef("Ljava/lang/Throwable;")));
                    frame.setTmp(new DvmValue(ex));
                }
            }

            // ???
            BitSet ex = exBranch[index];
            if (ex != null) {
                for (int i = ex.nextSetBit(0); i >= 0; i = ex.nextSetBit(i + 1)) {
                    merge(frame, i);
                    stack.push(stmtsList.get(i));
                }
            }

            // Copy state of 'frame' into 'tmp' and execute the interpreter in our 'tmp' frame.
            tmp.init(frame);

            // Handle emitting statements.
            // The switch case here handles some edge cases not implemented in the interpreter logic.
            try {
                if (statement.op != null) {
                    switch (statement.op) {
                    case RETURN_VOID:
                        emit(nReturnVoid());
                        break;
                    case GOTO:
                    case GOTO_16:
                    case GOTO_32:
                        emit(nGoto(getLabel(((JumpStmtNode) statement).label)));
                        break;
                    case NOP:
                        emit(nNop());
                        break;
                    case BAD_OP:
                        emit(nThrow(nInvokeNew(new Value[]{nString("bad dex opcode")}, new String[]{
                                        "Ljava/lang/String;"},
                                "Ljava/lang/VerifyError;")));
                        break;
                    default:
                        tmp.execute(statement, interpreter);
                        break;
                    }
                }
            } catch (Exception exception) {
                throw new RuntimeException("Fail on Op " + statement.op + " index " + index, exception);
            }

            // Queue next statement to visit based on how they affect control flow.
            if (statement.op != null) {
                Op op = statement.op;
                if (op.canBranch()) {
                    JumpStmtNode jump = (JumpStmtNode) statement;
                    int targetIndex = indexOf(jump.label);
                    stack.push(stmtsList.get(targetIndex));
                    merge(tmp, targetIndex);
                }
                if (op.canSwitch()) {
                    BaseSwitchStmtNode switchStmtNode = (BaseSwitchStmtNode) statement;
                    for (DexLabel label : switchStmtNode.labels) {
                        int targetIndex = indexOf(label);
                        stack.push(stmtsList.get(targetIndex));
                        merge(tmp, targetIndex);
                    }
                }
                if (op.canContinue()) {
                    stack.push(stmtsList.get(index + 1));
                    merge(tmp, index + 1);
                }
            } else {
                stack.push(stmtsList.get(index + 1));
                merge(tmp, index + 1);
            }

            // cleanup frame it is useless
            if (parentCount[index] <= 1) {
                frames[index] = null;
            }
        }
    }

    /**
     * Update's the child's {@link DvmValue#parent} and {@link DvmValue#otherParent} attributes
     * to link to the given parent value.
     *
     * @param parent
     *         Parent value to use.
     * @param child
     *         Child with parent relations to update to point to the {@code parent}.
     */
    private void relate(DvmValue parent, DvmValue child) {
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
     * Ensures contents of the frame at the given {@code destination} are compatible with the source frame.
     * <br>
     * Assigns stack and local variable values' {@link DvmValue#parent parent relations} in the destination frame
     * to the values in the provided source frame.
     *
     * @param sourceFrame
     *         Current source frame.
     * @param destination
     *         Destination frame to flow into.
     */
   private void merge(Dex2IrFrame sourceFrame, int destination) {
        Dex2IrFrame destinationFrame = frames[destination];
        if (destinationFrame == null) {
            frames[destination] = new Dex2IrFrame(dexCodeNode.totalRegister);
            destinationFrame = frames[destination];
        }
        if (parentCount[destination] > 1) {
            // Merge register states such that the destination takes on values to be compatible with our source frame.
            for (int i = 0; i < sourceFrame.getTotalRegisters(); i++) {
                DvmValue sourceRegister = sourceFrame.getReg(i);
                DvmValue destinationRegister = destinationFrame.getReg(i);
                if (sourceRegister != null) {
                    if (destinationRegister == null) {
                        destinationRegister = new DvmValue();
                        destinationFrame.setReg(i, destinationRegister);
                    }
                    relate(sourceRegister, destinationRegister);
                }
            }
        } else {
            // Source frame seems to not be visited (now control flow branch inputs)
            // Can just do basic init (copy operation)
            destinationFrame.init(sourceFrame);
        }
    }

    /**
     * @return New local with the {@link Local#lsIndex} of the next available slot.
     */
    private Local newLocal() {
        Local local = Exprs.nLocal(target.locals.size());
        target.locals.add(local);
        return local;
    }

    /**
     * @param stmt
     *         Statement to append to {@link #currentEmit}
     */
    private void emit(Stmt stmt) {
        currentEmit.add(stmt);
    }

    private Dex2IrFrame initFirstFrame(DexCodeNode methodNode, IrMethod target) {
        Dex2IrFrame first = new Dex2IrFrame(methodNode.totalRegister);
        int x = methodNode.totalRegister - methodArgsSizeTotal(target.args);
        if (!target.isStatic) { // not static
            Local thiz = newLocal();
            emit(Stmts.nIdentity(thiz, Exprs.nThisRef(target.owner)));
            first.setReg(x - 1, new DvmValue(thiz));
        }
        for (int i = 0; i < target.args.length; i++) {
            Local p = newLocal();
            emit(Stmts.nIdentity(p, Exprs.nParameterRef(target.args[i], i)));
            first.setReg(x, new DvmValue(p));
            x += sizeOfType(target.args[i]);
        }

            for (int i = 0; i < first.getTotalRegisters(); i++) {
                if (first.getReg(i) == null) {
                    Local local = newLocal();
                    emit(nAssign(local, nInt(0)));
                    first.setReg(i, new DvmValue(local));
                }
            }

        return first;
    }

    /**
     * @return Interpreter that also emits {@link Stmt} values as code is interpreted.
     */
    private DvmInterpreter<DvmValue> createInterpreter() {
        return new DvmInterpreter<DvmValue>() {
            private DvmValue emitValue(com.googlecode.dex2jar.ir.expr.Value value) {
                Local local = newLocal();
                emit(Stmts.nAssign(local, value));
                return new DvmValue(local);
            }

            private void emitNotFindOperand(DexStmtNode insn) {
                String msg;
                switch (insn.op) {
                    case MOVE_RESULT:
                    case MOVE_RESULT_OBJECT:
                    case MOVE_RESULT_WIDE:
                        msg = "can't get operand(s) for " + insn.op + ", wrong position ?";
                        break;
                    default:
                        msg = "can't get operand(s) for " + insn.op + ", out-of-range or not initialized ?";
                        break;
                }

                System.err.println("WARN: " + msg);
                emit(nThrow(nInvokeNew(new Value[]{nString("d2j: " + msg)},
                        new String[]{"Ljava/lang/String;"}, "Ljava/lang/VerifyError;")));
            }

            @Override
            public DvmValue newOperation(DexStmtNode insn) {
                switch (insn.op) {
                case CONST:
                case CONST_16:
                case CONST_4:
                case CONST_HIGH16:
                    return emitValue(nInt((Integer) ((ConstStmtNode) insn).value));
                case CONST_WIDE:
                case CONST_WIDE_16:
                case CONST_WIDE_32:
                case CONST_WIDE_HIGH16:
                    return emitValue(nLong((Long) ((ConstStmtNode) insn).value));
                case CONST_CLASS:
                    return emitValue(nType((DexType) ((ConstStmtNode) insn).value));
                case CONST_STRING:
                case CONST_STRING_JUMBO:
                    return emitValue(nString((String) ((ConstStmtNode) insn).value));
                case SGET:
                case SGET_BOOLEAN:
                case SGET_BYTE:
                case SGET_CHAR:
                case SGET_OBJECT:
                case SGET_SHORT:
                case SGET_WIDE:
                    Field field = ((FieldStmtNode) insn).field;
                    return emitValue(nStaticField(field.getOwner(), field.getName(), field.getType()));
                case NEW_INSTANCE:
                    return emitValue(nNew(((TypeStmtNode) insn).type));
                default:
                }
                return null;
            }

            @Override
            public DvmValue copyOperation(DexStmtNode insn, DvmValue value) {
                if (value == null) {
                    emitNotFindOperand(insn);
                    return emitValue(nInt(0));
                }
                return emitValue(getLocal(value));
            }

            @Override
            public DvmValue unaryOperation(DexStmtNode insn, DvmValue value) {
                if (value == null) {
                    emitNotFindOperand(insn);
                    return emitValue(nInt(0));
                }
                Local local = getLocal(value);
                switch (insn.op) {
                case NOT_INT:
                    return emitValue(nNot(local, "I"));
                case NOT_LONG:
                    return emitValue(nNot(local, "J"));

                case NEG_DOUBLE:
                    return emitValue(nNeg(local, "D"));

                case NEG_FLOAT:
                    return emitValue(nNeg(local, "F"));

                case NEG_INT:
                    return emitValue(nNeg(local, "I"));

                case NEG_LONG:
                    return emitValue(nNeg(local, "J"));
                case INT_TO_BYTE:
                    return emitValue(nCast(local, "I", "B"));

                case INT_TO_CHAR:
                    return emitValue(nCast(local, "I", "C"));

                case INT_TO_DOUBLE:
                    return emitValue(nCast(local, "I", "D"));

                case INT_TO_FLOAT:
                    return emitValue(nCast(local, "I", "F"));

                case INT_TO_LONG:
                    return emitValue(nCast(local, "I", "J"));

                case INT_TO_SHORT:
                    return emitValue(nCast(local, "I", "S"));

                case FLOAT_TO_DOUBLE:
                    return emitValue(nCast(local, "F", "D"));

                case FLOAT_TO_INT:
                    return emitValue(nCast(local, "F", "I"));

                case FLOAT_TO_LONG:
                    return emitValue(nCast(local, "F", "J"));

                case DOUBLE_TO_FLOAT:
                    return emitValue(nCast(local, "D", "F"));

                case DOUBLE_TO_INT:
                    return emitValue(nCast(local, "D", "I"));

                case DOUBLE_TO_LONG:
                    return emitValue(nCast(local, "D", "J"));

                case LONG_TO_DOUBLE:
                    return emitValue(nCast(local, "J", "D"));

                case LONG_TO_FLOAT:
                    return emitValue(nCast(local, "J", "F"));

                case LONG_TO_INT:
                    return emitValue(nCast(local, "J", "I"));

                case ARRAY_LENGTH:
                    return emitValue(nLength(local));

                case IF_EQZ:
                    emit(nIf(Exprs
                            .nEq(local, nInt(0), TypeClass.ZIL.name), getLabel(((JumpStmtNode) insn).label)));
                    return null;

                case IF_GEZ:
                    emit(nIf(Exprs.nGe(local, nInt(0), "I"), getLabel(((JumpStmtNode) insn).label)));
                    return null;

                case IF_GTZ:
                    emit(nIf(Exprs.nGt(local, nInt(0), "I"), getLabel(((JumpStmtNode) insn).label)));
                    return null;

                case IF_LEZ:
                    emit(nIf(Exprs.nLe(local, nInt(0), "I"), getLabel(((JumpStmtNode) insn).label)));
                    return null;

                case IF_LTZ:
                    emit(nIf(Exprs.nLt(local, nInt(0), "I"), getLabel(((JumpStmtNode) insn).label)));
                    return null;

                case IF_NEZ:
                    emit(nIf(Exprs
                            .nNe(local, nInt(0), TypeClass.ZIL.name), getLabel(((JumpStmtNode) insn).label)));
                    return null;

                case PACKED_SWITCH:
                case SPARSE_SWITCH:
                    DexLabel[] labels = ((BaseSwitchStmtNode) insn).labels;
                    LabelStmt[] lss = new LabelStmt[labels.length];
                    for (int i = 0; i < labels.length; i++) {
                        lss[i] = getLabel(labels[i]);
                    }
                    LabelStmt d = new LabelStmt();
                    if (insn.op == Op.PACKED_SWITCH) {
                        emit(nTableSwitch(local, ((PackedSwitchStmtNode) insn).firstCase, lss, d));
                    } else {
                        emit(nLookupSwitch(local, ((SparseSwitchStmtNode) insn).cases, lss, d));
                    }
                    emit(d);
                    return null;

                case SPUT:
                case SPUT_BOOLEAN:
                case SPUT_BYTE:
                case SPUT_CHAR:
                case SPUT_OBJECT:
                case SPUT_SHORT:
                case SPUT_WIDE: {
                    Field field = ((FieldStmtNode) insn).field;
                    emit(nAssign(nStaticField(field.getOwner(), field.getName(), field.getType()), local));
                    return null;
                }
                case IGET:
                case IGET_BOOLEAN:
                case IGET_BYTE:
                case IGET_CHAR:
                case IGET_OBJECT:
                case IGET_SHORT:
                case IGET_WIDE: {
                    Field field = ((FieldStmtNode) insn).field;
                    return emitValue(nField(local, field.getOwner(), field.getName(), field.getType()));
                }
                case INSTANCE_OF:
                    return emitValue(nInstanceOf(local, ((TypeStmtNode) insn).type));

                case NEW_ARRAY:
                    return emitValue(nNewArray(((TypeStmtNode) insn).type.substring(1), local));

                case CHECK_CAST:
                    return emitValue(nCheckCast(local, ((TypeStmtNode) insn).type));

                case MONITOR_ENTER:
                    emit(nLock(local));
                    return null;
                case MONITOR_EXIT:
                    emit(nUnLock(local));
                    return null;
                case THROW:
                    emit(nThrow(local));
                    return null;
                case ADD_INT_LIT16:
                case ADD_INT_LIT8:
                    return emitValue(nAdd(local, nInt(((Stmt2R1NNode) insn).content), "I"));

                case RSUB_INT_LIT8:
                case RSUB_INT://
                    return emitValue(nSub(nInt(((Stmt2R1NNode) insn).content), local, "I"));

                case MUL_INT_LIT8:
                case MUL_INT_LIT16:
                    return emitValue(nMul(local, nInt(((Stmt2R1NNode) insn).content), "I"));

                case DIV_INT_LIT16:
                case DIV_INT_LIT8:
                    return emitValue(nDiv(local, nInt(((Stmt2R1NNode) insn).content), "I"));

                case REM_INT_LIT16:
                case REM_INT_LIT8:
                    return emitValue(nRem(local, nInt(((Stmt2R1NNode) insn).content), "I"));

                case AND_INT_LIT16:
                case AND_INT_LIT8:
                    return emitValue(nAnd(local, nInt(((Stmt2R1NNode) insn).content),
                            ((Stmt2R1NNode) insn).content < 0
                                    || ((Stmt2R1NNode) insn).content > 1
                                    ? "I"
                                    : TypeClass.ZI.name));

                case OR_INT_LIT16:
                case OR_INT_LIT8:
                    return emitValue(nOr(local, nInt(((Stmt2R1NNode) insn).content),
                            ((Stmt2R1NNode) insn).content < 0
                                    || ((Stmt2R1NNode) insn).content > 1
                                    ? "I"
                                    : TypeClass.ZI.name));

                case XOR_INT_LIT16:
                case XOR_INT_LIT8:
                    return emitValue(nXor(local, nInt(((Stmt2R1NNode) insn).content),
                            ((Stmt2R1NNode) insn).content < 0
                                    || ((Stmt2R1NNode) insn).content > 1
                                    ? "I"
                                    : TypeClass.ZI.name));

                case SHL_INT_LIT8:
                    return emitValue(nShl(local, nInt(((Stmt2R1NNode) insn).content), "I"));

                case SHR_INT_LIT8:
                    return emitValue(nShr(local, nInt(((Stmt2R1NNode) insn).content), "I"));

                case USHR_INT_LIT8:
                    return emitValue(nUshr(local, nInt(((Stmt2R1NNode) insn).content), "I"));
                case FILL_ARRAY_DATA:
                    emit(nFillArrayData(local, nArrayValue(((FillArrayDataStmtNode) insn).array)));
                    return null;
                default:
                    break;
                }
                throw new RuntimeException();
            }

            @Override
            public DvmValue binaryOperation(DexStmtNode insn, DvmValue value1, DvmValue value2) {
                if (value1 == null || value2 == null) {
                    emitNotFindOperand(insn);
                    return emitValue(nInt(0));
                }
                Local local1 = getLocal(value1);
                Local local2 = getLocal(value2);
                switch (insn.op) {
                case AGET:
                    return emitValue(nArray(local1, local2, TypeClass.IF.name));

                case AGET_BOOLEAN:
                    return emitValue(nArray(local1, local2, "Z"));

                case AGET_BYTE:
                    return emitValue(nArray(local1, local2, "B"));

                case AGET_CHAR:
                    return emitValue(nArray(local1, local2, "C"));

                case AGET_OBJECT:
                    return emitValue(nArray(local1, local2, "L"));

                case AGET_SHORT:
                    return emitValue(nArray(local1, local2, "S"));

                case AGET_WIDE:
                    return emitValue(nArray(local1, local2, TypeClass.JD.name));

                case CMP_LONG:
                    return emitValue(nLCmp(local1, local2));

                case CMPG_DOUBLE:
                    return emitValue(nDCmpg(local1, local2));

                case CMPG_FLOAT:
                    return emitValue(nFCmpg(local1, local2));

                case CMPL_DOUBLE:
                    return emitValue(nDCmpl(local1, local2));

                case CMPL_FLOAT:
                    return emitValue(nFCmpl(local1, local2));

                case ADD_DOUBLE:
                    return emitValue(nAdd(local1, local2, "D"));

                case ADD_FLOAT:
                    return emitValue(nAdd(local1, local2, "F"));

                case ADD_INT:
                    return emitValue(nAdd(local1, local2, "I"));

                case ADD_LONG:
                    return emitValue(nAdd(local1, local2, "J"));

                case SUB_DOUBLE:
                    return emitValue(nSub(local1, local2, "D"));

                case SUB_FLOAT:
                    return emitValue(nSub(local1, local2, "F"));

                case SUB_INT:
                    return emitValue(nSub(local1, local2, "I"));

                case SUB_LONG:
                    return emitValue(nSub(local1, local2, "J"));

                case MUL_DOUBLE:
                    return emitValue(nMul(local1, local2, "D"));

                case MUL_FLOAT:
                    return emitValue(nMul(local1, local2, "F"));

                case MUL_INT:
                    return emitValue(nMul(local1, local2, "I"));

                case MUL_LONG:
                    return emitValue(nMul(local1, local2, "J"));

                case DIV_DOUBLE:
                    return emitValue(nDiv(local1, local2, "D"));

                case DIV_FLOAT:
                    return emitValue(nDiv(local1, local2, "F"));

                case DIV_INT:
                    return emitValue(nDiv(local1, local2, "I"));

                case DIV_LONG:
                    return emitValue(nDiv(local1, local2, "J"));

                case REM_DOUBLE:
                    return emitValue(nRem(local1, local2, "D"));

                case REM_FLOAT:
                    return emitValue(nRem(local1, local2, "F"));

                case REM_INT:
                    return emitValue(nRem(local1, local2, "I"));

                case REM_LONG:
                    return emitValue(nRem(local1, local2, "J"));

                case AND_INT:
                    return emitValue(nAnd(local1, local2, TypeClass.ZI.name));

                case AND_LONG:
                    return emitValue(nAnd(local1, local2, "J"));

                case OR_INT:
                    return emitValue(nOr(local1, local2, TypeClass.ZI.name));

                case OR_LONG:
                    return emitValue(nOr(local1, local2, "J"));

                case XOR_INT:
                    return emitValue(nXor(local1, local2, TypeClass.ZI.name));

                case XOR_LONG:
                    return emitValue(nXor(local1, local2, "J"));

                case SHL_INT:
                    return emitValue(nShl(local1, local2, "I"));

                case SHL_LONG:
                    return emitValue(nShl(local1, local2, "J"));

                case SHR_INT:
                    return emitValue(nShr(local1, local2, "I"));

                case SHR_LONG:
                    return emitValue(nShr(local1, local2, "J"));

                case USHR_INT:
                    return emitValue(nUshr(local1, local2, "I"));

                case USHR_LONG:
                    return emitValue(nUshr(local1, local2, "J"));

                case IF_EQ:
                    emit(nIf(Exprs
                            .nEq(local1, local2, TypeClass.ZIL.name), getLabel(((JumpStmtNode) insn).label)));
                    return null;

                case IF_GE:
                    emit(nIf(Exprs.nGe(local1, local2, "I"), getLabel(((JumpStmtNode) insn).label)));
                    return null;

                case IF_GT:
                    emit(nIf(Exprs.nGt(local1, local2, "I"), getLabel(((JumpStmtNode) insn).label)));
                    return null;

                case IF_LE:
                    emit(nIf(Exprs.nLe(local1, local2, "I"), getLabel(((JumpStmtNode) insn).label)));
                    return null;

                case IF_LT:
                    emit(nIf(Exprs.nLt(local1, local2, "I"), getLabel(((JumpStmtNode) insn).label)));
                    return null;

                case IF_NE:
                    emit(nIf(Exprs
                            .nNe(local1, local2, TypeClass.ZIL.name), getLabel(((JumpStmtNode) insn).label)));
                    return null;

                case IPUT:
                case IPUT_BOOLEAN:
                case IPUT_BYTE:
                case IPUT_CHAR:
                case IPUT_OBJECT:
                case IPUT_SHORT:
                case IPUT_WIDE:
                    Field field = ((FieldStmtNode) insn).field;
                    emit(nAssign(nField(local1, field.getOwner(), field.getName(), field.getType()), local2));
                    return null;

                case ADD_DOUBLE_2ADDR:
                    return emitValue(nAdd(local1, local2, "D"));

                case ADD_FLOAT_2ADDR:
                    return emitValue(nAdd(local1, local2, "F"));

                case ADD_INT_2ADDR:
                    return emitValue(nAdd(local1, local2, "I"));

                case ADD_LONG_2ADDR:
                    return emitValue(nAdd(local1, local2, "J"));

                case SUB_DOUBLE_2ADDR:
                    return emitValue(nSub(local1, local2, "D"));

                case SUB_FLOAT_2ADDR:
                    return emitValue(nSub(local1, local2, "F"));

                case SUB_INT_2ADDR:
                    return emitValue(nSub(local1, local2, "I"));

                case SUB_LONG_2ADDR:
                    return emitValue(nSub(local1, local2, "J"));

                case MUL_DOUBLE_2ADDR:
                    return emitValue(nMul(local1, local2, "D"));

                case MUL_FLOAT_2ADDR:
                    return emitValue(nMul(local1, local2, "F"));

                case MUL_INT_2ADDR:
                    return emitValue(nMul(local1, local2, "I"));

                case MUL_LONG_2ADDR:
                    return emitValue(nMul(local1, local2, "J"));

                case DIV_DOUBLE_2ADDR:
                    return emitValue(nDiv(local1, local2, "D"));

                case DIV_FLOAT_2ADDR:
                    return emitValue(nDiv(local1, local2, "F"));

                case DIV_INT_2ADDR:
                    return emitValue(nDiv(local1, local2, "I"));

                case DIV_LONG_2ADDR:
                    return emitValue(nDiv(local1, local2, "J"));

                case REM_DOUBLE_2ADDR:
                    return emitValue(nRem(local1, local2, "D"));

                case REM_FLOAT_2ADDR:
                    return emitValue(nRem(local1, local2, "F"));

                case REM_INT_2ADDR:
                    return emitValue(nRem(local1, local2, "I"));

                case REM_LONG_2ADDR:
                    return emitValue(nRem(local1, local2, "J"));

                case AND_INT_2ADDR:
                    return emitValue(nAnd(local1, local2, TypeClass.ZI.name));

                case AND_LONG_2ADDR:
                    return emitValue(nAnd(local1, local2, "J"));

                case OR_INT_2ADDR:
                    return emitValue(nOr(local1, local2, TypeClass.ZI.name));

                case OR_LONG_2ADDR:
                    return emitValue(nOr(local1, local2, "J"));

                case XOR_INT_2ADDR:
                    return emitValue(nXor(local1, local2, TypeClass.ZI.name));

                case XOR_LONG_2ADDR:
                    return emitValue(nXor(local1, local2, "J"));

                case SHL_INT_2ADDR:
                    return emitValue(nShl(local1, local2, "I"));

                case SHL_LONG_2ADDR:
                    return emitValue(nShl(local1, local2, "J"));

                case SHR_INT_2ADDR:
                    return emitValue(nShr(local1, local2, "I"));

                case SHR_LONG_2ADDR:
                    return emitValue(nShr(local1, local2, "J"));

                case USHR_INT_2ADDR:
                    return emitValue(nUshr(local1, local2, "I"));

                case USHR_LONG_2ADDR:
                    return emitValue(nUshr(local1, local2, "J"));
                default:
                    break;
                }
                throw new RuntimeException();
            }

            @Override
            public DvmValue ternaryOperation(DexStmtNode insn, DvmValue value1, DvmValue value2, DvmValue value3) {
                if (value1 == null || value2 == null || value3 == null) {
                    emitNotFindOperand(insn);
                    return emitValue(nInt(0));
                }
                Local localArray = getLocal(value1);
                Local localIndex = getLocal(value2);
                Local localValue = getLocal(value3);
                switch (insn.op) {
                case APUT:
                    emit(nAssign(nArray(localArray, localIndex, TypeClass.IF.name), localValue));
                    break;
                case APUT_BOOLEAN:
                    emit(nAssign(nArray(localArray, localIndex, "Z"), localValue));
                    break;
                case APUT_BYTE:
                    emit(nAssign(nArray(localArray, localIndex, "B"), localValue));
                    break;
                case APUT_CHAR:
                    emit(nAssign(nArray(localArray, localIndex, "C"), localValue));
                    break;
                case APUT_OBJECT:
                    emit(nAssign(nArray(localArray, localIndex, "L"), localValue));
                    break;
                case APUT_SHORT:
                    emit(nAssign(nArray(localArray, localIndex, "S"), localValue));
                    break;
                case APUT_WIDE:
                    emit(nAssign(nArray(localArray, localIndex, TypeClass.JD.name), localValue));
                    break;
                default:
                    break;
                }
                return null;
            }

            @Override
            public DvmValue naryOperation(DexStmtNode insn, List<? extends DvmValue> values) {
                for (DvmValue v : values) {
                    if (v == null) {
                        emitNotFindOperand(insn);
                        return emitValue(nInt(0));
                    }
                }


                switch (insn.op) {
                case FILLED_NEW_ARRAY:
                case FILLED_NEW_ARRAY_RANGE:
                    DvmValue value = new DvmValue();
                    FilledNewArrayStmtNode filledNewArrayStmtNode = (FilledNewArrayStmtNode) insn;
                    String type = filledNewArrayStmtNode.type;

                    String elem = type.substring(1);
                    emit(nAssign(getLocal(value), nNewArray(elem, nInt(values.size()))));
                    for (int i = 0; i < values.size(); i++) {
                        emit(nAssign(nArray(getLocal(value), nInt(i), elem), getLocal(values.get(i))));
                    }

                    return value;
                case INVOKE_CUSTOM:
                case INVOKE_CUSTOM_RANGE: {
                    Value[] vs = new Value[values.size()];
                    for (int i = 0; i < vs.length; i++) {
                        vs[i] = getLocal(values.get(i));
                    }
                    MethodCustomStmtNode n = (MethodCustomStmtNode) insn;
                    Value invoke = nInvokeCustom(vs, n.name, n.proto, n.bsm, n.bsmArgs);
                    if ("V".equals(n.getProto().getReturnType())) {
                        emit(nVoidInvoke(invoke));
                        return null;
                    } else {
                        return emitValue(invoke);
                    }
                }
                case INVOKE_POLYMORPHIC:
                case INVOKE_POLYMORPHIC_RANGE: {
                    Value[] vs = new Value[values.size()];
                    for (int i = 0; i < vs.length; i++) {
                        vs[i] = getLocal(values.get(i));
                    }
                    MethodPolymorphicStmtNode n = (MethodPolymorphicStmtNode) insn;
                    Value invoke = nInvokePolymorphic(vs, n.proto, n.method);
                    if ("V".equals(n.getProto().getReturnType())) {
                        emit(nVoidInvoke(invoke));
                        return null;
                    } else {
                        return emitValue(invoke);
                    }
                }
                default:
                    Op op = insn.op;
                    Value[] vs = new Value[values.size()];
                    for (int i = 0; i < vs.length; i++) {
                        vs[i] = getLocal(values.get(i));
                    }

                    Method method = ((MethodStmtNode) insn).method;
                    Value invoke;
                    switch (op) {
                    case INVOKE_VIRTUAL_RANGE:
                    case INVOKE_VIRTUAL:
                        invoke = nInvokeVirtual(vs, method.getOwner(), method.getName(), method
                                        .getParameterTypes(),
                                method.getReturnType());
                        break;
                    case INVOKE_SUPER_RANGE:
                    case INVOKE_DIRECT_RANGE:
                    case INVOKE_SUPER:
                    case INVOKE_DIRECT:
                        invoke = nInvokeSpecial(vs, method.getOwner(), method.getName(), method
                                        .getParameterTypes(),
                                method.getReturnType());
                        break;
                    case INVOKE_STATIC_RANGE:
                    case INVOKE_STATIC:
                        invoke = nInvokeStatic(vs, method.getOwner(), method.getName(), method
                                        .getParameterTypes(),
                                method.getReturnType());
                        break;
                    case INVOKE_INTERFACE_RANGE:
                    case INVOKE_INTERFACE:
                        invoke = nInvokeInterface(vs, method.getOwner(), method.getName(), method
                                        .getParameterTypes(),
                                method.getReturnType());
                        break;
                    default:
                        throw new RuntimeException();
                    }
                    if ("V".equals(method.getReturnType())) {
                        emit(nVoidInvoke(invoke));
                        return null;
                    } else {
                        return emitValue(invoke);
                    }

                }


            }

            @Override
            public void returnOperation(DexStmtNode insn, DvmValue value) {
                if (value == null) {
                    emitNotFindOperand(insn);
                    return;
                }
                emit(nReturn(getLocal(value)));
            }
        };
    }

    /**
     * @param handler
     *         Input dex labels.
     *
     * @return Associated IR label statements.
     */
    private LabelStmt[] getLabels(DexLabel[] handler) {
        LabelStmt[] ts = new LabelStmt[handler.length];
        for (int i = 0; i < handler.length; i++) {
            ts[i] = getLabel(handler[i]);
        }
        return ts;
    }

    /**
     * @param label
     *         Input dex label.
     *
     * @return Associated IR label statement.
     */
    private LabelStmt getLabel(DexLabel label) {
        LabelStmt ls = dexLabelToStmt.get(label);
        if (ls == null) {
            ls = Stmts.nLabel();
            dexLabelToStmt.put(label, ls);
        }
        return ls;
    }

    /**
     * Gets the associated IR {@link Local} for the given {@link DvmValue}.
     *
     * @param value
     *         DVM value to get IR local of.
     *
     * @return IR local for the given value.
     */
    private Local getLocal(DvmValue value) {
        Local local = value.local;
        if (local == null) {
            value.local = newLocal();
            local = value.local;
        }
        return local;
    }

    /**
     * Populates {@link #parentCount} with an array containing a mapping of statement offsets,
     * to the number of inbound control flow edges.
     */
    private void createInitialParentCounts() {
        parentCount = new int[stmtsList.size()];

        // Initial state, method entry-point always has inbound control flow edge.
        parentCount[0] = 1;

        // Iterate over all statements, counting inbound control flow edges per each statement
        // and storing the results in the array defined above.
        for (DexStmtNode stmt : stmtsList) {
            Op op = stmt.op;
            if (op == null) {
                // Increment the statement (Should be a label, as the op is null)
                // if it is not the last one.
                if (stmt.index < parentCount.length - 1) {
                    parentCount[stmt.index + 1]++;
                }
            } else {
                // Increment control flow destinations of flow modifying statements.
                if (op.canBranch()) {
                    parentCount[indexOf(((JumpStmtNode) stmt).label)]++;
                }
                if (op.canSwitch()) {
                    BaseSwitchStmtNode switchStmtNode = (BaseSwitchStmtNode) stmt;
                    for (DexLabel label : switchStmtNode.labels) {
                        parentCount[indexOf(label)]++;
                    }
                }
                if (op.canContinue()) {
                    parentCount[stmt.index + 1]++;
                }
            }
        }
    }

    private int indexOf(DexLabel label) {
        DexLabelStmtNode dexLabelStmtNode = labelToContainingNode.get(label);
        return dexLabelStmtNode.index;
    }

    static class Dex2IrFrame extends DvmFrame<DvmValue> {
        Dex2IrFrame(int totalRegister) {
            super(totalRegister);
        }
    }

    static class DvmValue {

        public DvmValue parent;

        public Set<DvmValue> otherParent;

        Local local;

        DvmValue(Local local) {
            this.local = local;
        }

        DvmValue() {
        }

    }
}
