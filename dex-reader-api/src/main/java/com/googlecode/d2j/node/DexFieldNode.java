package com.googlecode.d2j.node;

import com.googlecode.d2j.Field;
import com.googlecode.d2j.Visibility;
import com.googlecode.d2j.visitors.DexAnnotationVisitor;
import com.googlecode.d2j.visitors.DexClassVisitor;
import com.googlecode.d2j.visitors.DexFieldVisitor;
import com.googlecode.d2j.visitors.annotations.SignatureVisitor;

import java.util.ArrayList;
import java.util.List;

import static com.googlecode.d2j.DexConstants.*;

/**
 * @author Bob Pan
 */
public class DexFieldNode extends DexFieldVisitor {

    public int access;

    public List<DexAnnotationNode> anns;

    public Object cst;

    public Signature signature;

    public Field field;

    public DexFieldNode(DexFieldVisitor visitor, int access, Field field, Object cst) {
        super(visitor);
        this.access = access;
        this.field = field;
        this.cst = cst;
    }

    public DexFieldNode(int access, Field field, Object cst) {
        super();
        this.access = access;
        this.field = field;
        this.cst = cst;
    }

    public void accept(DexClassVisitor dcv) {
        DexFieldVisitor fv = dcv.visitField(access, field, cst);
        if (fv != null) {
            accept(fv);
            fv.visitEnd();
        }
    }

    public void accept(DexFieldVisitor fv) {
        if (anns != null) {
            for (DexAnnotationNode ann : anns) {
                ann.accept(fv);
            }
        }

        if (signature != null) {
            DexAnnotationVisitor av = fv.visitAnnotation(ANNOTATION_SIGNATURE_TYPE, Visibility.SYSTEM);
            if (av != null) {
                DexAnnotationVisitor array = av.visitArray("value");
                if (array != null) {
                    for (String section : signature.getSections()) {
                        array.visit(null, section);
                    }
                }
            }
        }
    }

    @Override
    public DexAnnotationVisitor visitAnnotation(String name, Visibility visibility) {
        if (anns == null) {
            anns = new ArrayList<>(5);
        }

        if (name.equals(ANNOTATION_SIGNATURE_TYPE)) {
            return new SignatureVisitor(s -> signature = s);
        }

        DexAnnotationNode annotation = new DexAnnotationNode(name, visibility);
        anns.add(annotation);
        return annotation;
    }

}
