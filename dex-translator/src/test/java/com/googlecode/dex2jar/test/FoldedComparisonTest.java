package com.googlecode.dex2jar.test;

import com.googlecode.d2j.DexConstants;
import com.googlecode.d2j.DexLabel;
import com.googlecode.d2j.Method;
import com.googlecode.d2j.node.DexClassNode;
import com.googlecode.d2j.reader.Op;
import com.googlecode.d2j.visitors.DexCodeVisitor;
import com.googlecode.d2j.visitors.DexMethodVisitor;
import org.junit.jupiter.api.Test;

import static com.googlecode.d2j.reader.Op.CONST;
import static com.googlecode.d2j.reader.Op.IF_GE;
import static com.googlecode.d2j.reader.Op.IF_GT;
import static com.googlecode.d2j.reader.Op.IF_LE;
import static com.googlecode.d2j.reader.Op.IF_LT;
import static com.googlecode.d2j.reader.Op.RETURN;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A comparison with a constant zero as its first operand ("if-gt v0, p0" with v0 = 0, i.e. 0 > p0)
 * is converted to a one-operand jump on p0, so the operator must be mirrored (p0 < 0). kotlinc emits
 * this shape for "for (i in 0..lastIndex)", where the loop must be skipped on an empty range.
 */
public class FoldedComparisonTest implements DexConstants {

    /** static boolean NAME(int x) { if (0 OP x) return false; return true; } */
    private static void method(DexClassNode cv, String name, Op op) {
        DexMethodVisitor mv = cv.visitMethod(ACC_PUBLIC | ACC_STATIC, new Method("La;", name, new String[]{"I"}, "Z"));
        DexCodeVisitor code = mv.visitCode();
        code.visitRegister(2);
        DexLabel taken = new DexLabel();
        code.visitConstStmt(CONST, 0, 0);
        code.visitJumpStmt(op, 0, 1, taken);
        code.visitConstStmt(CONST, 0, 1);
        code.visitStmt1R(RETURN, 0);
        code.visitLabel(taken);
        code.visitConstStmt(CONST, 0, 0);
        code.visitStmt1R(RETURN, 0);
        code.visitEnd();
        mv.visitEnd();
    }

    @Test
    public void constantZeroOnTheLeft() throws Exception {
        DexClassNode cv = new DexClassNode(ACC_PUBLIC, "La;", "Ljava/lang/Object;", null);
        method(cv, "gt", IF_GT);
        method(cv, "ge", IF_GE);
        method(cv, "lt", IF_LT);
        method(cv, "le", IF_LE);
        cv.visitEnd();
        Class<?> clz = TestUtils.defineClass("a", TestUtils.translateAndCheck(cv));
        for (int x : new int[]{-1, 0, 1}) {
            assertEquals(!(0 > x), clz.getMethod("gt", int.class).invoke(null, x), "!(0 > " + x + ")");
            assertEquals(!(0 >= x), clz.getMethod("ge", int.class).invoke(null, x), "!(0 >= " + x + ")");
            assertEquals(!(0 < x), clz.getMethod("lt", int.class).invoke(null, x), "!(0 < " + x + ")");
            assertEquals(!(0 <= x), clz.getMethod("le", int.class).invoke(null, x), "!(0 <= " + x + ")");
        }
    }
}
