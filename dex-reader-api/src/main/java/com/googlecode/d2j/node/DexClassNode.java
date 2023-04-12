package com.googlecode.d2j.node;

import com.googlecode.d2j.DexType;
import com.googlecode.d2j.Field;
import com.googlecode.d2j.Method;
import com.googlecode.d2j.Visibility;
import com.googlecode.d2j.visitors.*;
import com.googlecode.d2j.visitors.annotations.*;

import java.util.ArrayList;
import java.util.List;

import static com.googlecode.d2j.DexConstants.*;

/**
 * @author Bob Pan
 */
public class DexClassNode extends DexClassVisitor {
	public int access;

	public List<DexAnnotationNode> anns;

	public String className;

	public String superClass;

	public String[] interfaceNames;

	public List<DexFieldNode> fields;

	public List<DexMethodNode> methods;

	public String source;

	public Signature signature;

	public String enclosingClass;

	public Method enclosingMethod;

	public List<DexType> memberClasses;

	public String innerClassName;

	public int innerClassAccess;

	public DexClassNode(DexClassVisitor v, int access, String className, String superClass, String[] interfaceNames) {
		super(v);
		this.access = access;
		this.className = className;
		this.superClass = superClass;
		this.interfaceNames = interfaceNames;
	}

	public DexClassNode(int access, String className, String superClass, String[] interfaceNames) {
		this(null, access, className, superClass, interfaceNames);
	}

	public void accept(DexClassVisitor dcv) {
		if (anns != null) {
			for (DexAnnotationNode ann : anns) {
				ann.accept(dcv);
			}
		}

		if (fields != null) {
			for (DexFieldNode f : fields) {
				f.accept(dcv);
			}
		}

		if (methods != null) {
			for (DexMethodNode m : methods) {
				m.accept(dcv);
			}
		}

		if (source != null) {
			dcv.visitSource(source);
		}

		if (signature != null) {
			DexAnnotationVisitor av = dcv.visitAnnotation(ANNOTATION_SIGNATURE_TYPE, Visibility.SYSTEM)
					.visitArray("value");
			for (String section : signature.getSections()) {
				av.visit(null, section);
			}
		}

		if (isInnerClass()) {
			DexAnnotationVisitor av = dcv.visitAnnotation(ANNOTATION_INNER_CLASS_TYPE, Visibility.SYSTEM);
			av.visit("name", innerClassName);
			av.visit("accessFlags", innerClassAccess);
		}

		if (enclosingClass != null) {
			dcv.visitAnnotation(ANNOTATION_ENCLOSING_CLASS_TYPE, Visibility.SYSTEM)
					.visit("value", enclosingClass);
		}

		if (enclosingMethod != null) {
			dcv.visitAnnotation(ANNOTATION_ENCLOSING_METHOD_TYPE, Visibility.SYSTEM)
					.visit("value", enclosingMethod);
		}
	}

	/**
	 * @return {@code true} when this class represents an inner class of another.
	 */
	public boolean isInnerClass() {
		return enclosingClass != null || enclosingMethod != null;
	}

	/**
	 * @return {@code true} when this class represents an anonymous inner class.
	 */
	public boolean isAnonymousInnerClass() {
		return innerClassName == null;
	}

	public void accept(DexFileVisitor dfv) {
		DexClassVisitor dcv = dfv.visit(access, className, superClass, interfaceNames);
		if (dcv != null) {
			accept(dcv);
			dcv.visitEnd();
		}
	}

	@Override
	public DexAnnotationVisitor visitAnnotation(String name, Visibility visibility) {
		// TODO: Maybe add CLI parameter to enable this?
        /*if (name.equals("Lkotlin/Metadata;")) {
            return null; // clean kotlin metadata, or maybe parse it?
        }*/
		if (anns == null) {
			anns = new ArrayList<>(5);
		}
		switch (name) {
			case ANNOTATION_ENCLOSING_CLASS_TYPE:
				return new EnclosingClassVisitor(c -> enclosingClass = c);
			case ANNOTATION_ENCLOSING_METHOD_TYPE:
				return new EnclosingMethodVisitor(m -> enclosingMethod = m);
			case ANNOTATION_MEMBER_CLASSES_TYPE:
				return new MemberClassesVisitor(c -> memberClasses = c);
			case ANNOTATION_INNER_CLASS_TYPE:
				return new InnerClassVisitor((c, a) -> {
					innerClassName = c;
					innerClassAccess = a;
				});
			case ANNOTATION_SIGNATURE_TYPE:
				return new SignatureVisitor(s -> signature = s);
				/*
			case "Lkotlin/Metadata;":
				// TODO: Parse the meta-data into an object form for easy access
				//       If users want to strip the data, they can clear said object.
				return null;
				 */
		}
		DexAnnotationNode annotation = new DexAnnotationNode(name, visibility);
		anns.add(annotation);
		return annotation;
	}

	@Override
	public DexFieldVisitor visitField(int accessFlags, Field field, Object value) {
		if (fields == null) {
			fields = new ArrayList<>();
		}
		DexFieldNode fieldNode = new DexFieldNode(accessFlags, field, value);
		fields.add(fieldNode);
		return fieldNode;
	}

	@Override
	public DexMethodVisitor visitMethod(int accessFlags, Method method) {
		if (methods == null) {
			methods = new ArrayList<>();
		}
		DexMethodNode methodNode = new DexMethodNode(accessFlags, method);
		methods.add(methodNode);
		return methodNode;
	}

	@Override
	public void visitSource(String file) {
		this.source = file;
	}

}
