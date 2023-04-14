package com.googlecode.d2j.dex;

import com.googlecode.d2j.Method;
import com.googlecode.d2j.node.DexMethodNode;
import org.objectweb.asm.MethodVisitor;

/**
 * Handler outline for reacting to erroneous outputs from {@link Dex2AsmWithHandler}.
 */
public interface DexExceptionHandler {

    /**
     * Called when a specific method is not known to be the root cause of the error.
     * Uncaught exceptions also fall under this umbrella.
     *
     * @param e
     *         The exception thrown when handling translation and writing of classes.
     */
    void handleClassFileException(Exception e);

    /**
     * Called when a specific method has erroneous formatting, causing the error.
     *
     * @param method
     *         Method outline being translated.
     * @param methodNode
     *         Method implementation details.
     * @param mv
     *         Visitor.
     * @param e
     *         The translation exception.
     */
    void handleMethodTranslateException(Method method, DexMethodNode methodNode, MethodVisitor mv, Exception e);

}
