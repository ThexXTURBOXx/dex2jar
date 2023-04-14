package com.googlecode.d2j.node.analysis;

import com.googlecode.d2j.node.insn.DexStmtNode;
import java.util.List;

/**
 * Interpreter modeling the same behavior as ASM's analysis framework.
 *
 * @param <V>
 * 		Frame value type.
 */
public abstract class DvmInterpreter<V> {

    /**
     * <ul>
     *     <li>CONST*</li>
     *     <li>SGET*</li>
     *     <li>NEW</li>
     * </ul>
     */
    public abstract V newOperation(DexStmtNode insn);

    /**
     * <ul>
     *     <li>MOVE*</li>
     * </ul>
     */
    public abstract V copyOperation(DexStmtNode insn, V value);

    /**
     * <ul>
     *     <li>NEG*</li>
     *     <li>*_TO_*</li>
     *     <li>IF_*Z</li>
     *     <li>*SWITCH</li>
     *     <li>IGET*</li>
     *     <li>NEW_ARRAY</li>
     *     <li>MONITOR_*</li>
     *     <li>CHECK_CAST</li>
     *     <li>INSTANCEOF</li>
     * </ul>
     */
    public abstract V unaryOperation(DexStmtNode insn, V value);

    /**
     * <ul>
     *     <li>AGET*</li>
     *     <li>IPUT*</li>
     * </ul>
     */
    public abstract V binaryOperation(DexStmtNode insn, V value1, V value2);

    /**
     * <ul>
     *     <li>APUT</li>
     * </ul>
     */
    public abstract V ternaryOperation(DexStmtNode insn, V value1,
                                       V value2, V value3);

    /**
     * <ul>
     *     <li>INVOKE*</li>
     *     <li>MULTIANEWARRAY</li>
     *     <li>FilledNewArrayStmt</li>
     * </ul>
     */
    public abstract V naryOperation(DexStmtNode insn,
                                    List<? extends V> values);

    /**
     * <ul>
     *     <li>RETURN*</li>
     * </ul>
     */
    public abstract void returnOperation(DexStmtNode insn, V value);

}
