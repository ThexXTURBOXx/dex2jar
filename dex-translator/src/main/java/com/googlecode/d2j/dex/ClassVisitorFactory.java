package com.googlecode.d2j.dex;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

public interface ClassVisitorFactory {
    /**
     * When implementing, if the visitor created is a {@link ClassWriter} you must use {@link ClassWriter#COMPUTE_MAXS}
     * as the input flags if the class being made is <i>new</i> rather than a <i>modification</i> of an existing class.
     *
     * @param classInternalName
     *         Internal class name,
     *
     * @return A visitor to generate class files.
     */
    ClassVisitor create(String classInternalName);

}
