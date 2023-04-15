package com.googlecode.d2j.dex;

import com.android.dx.dex.code.DalvCode;
import com.android.dx.dex.file.ClassDefItem;
import com.android.dx.dex.file.DexFile;
import com.android.dx.dex.file.EncodedField;
import com.android.dx.dex.file.EncodedMethod;
import com.android.dx.rop.annotation.Annotation;
import com.android.dx.rop.annotation.AnnotationVisibility;
import com.android.dx.rop.annotation.Annotations;
import com.android.dx.rop.cst.*;
import com.android.dx.rop.type.StdTypeList;
import com.android.dx.rop.type.Type;
import com.googlecode.d2j.node.DexAnnotationNode;
import com.googlecode.d2j.node.DexClassNode;
import com.googlecode.d2j.node.DexFieldNode;
import com.googlecode.d2j.node.DexMethodNode;

public class Dex2DxDef {
	public static ClassDefItem appendToDexFile(DexFile dexFile, DexClassNode classNode) {
		int flags = classNode.access;
		CstType thisClass = CstType.intern(Type.intern(classNode.className));
		CstType superClass = classNode.superClass == null ? null : CstType.intern(Type.intern(classNode.superClass));
		StdTypeList interfaces = new StdTypeList(classNode.interfaceNames.length);
		for (int i = 0; i < classNode.interfaceNames.length; i++) {
			Type interfaceType = Type.intern(classNode.interfaceNames[i]);
			interfaces.set(i, interfaceType);
		}
		CstString sourceFile = classNode.source == null ? null : new CstString(classNode.source);
		ClassDefItem classDef = new ClassDefItem(thisClass, flags, superClass, interfaces, sourceFile);

		for (DexFieldNode field : classNode.fields) {
			CstNat fieldNameType = new CstNat(new CstString(field.field.getName()), new CstString(field.field.getType()));
			CstFieldRef fieldRef = new CstFieldRef(thisClass, fieldNameType);
			EncodedField encodedField = new EncodedField(fieldRef, field.access);

			if (!field.anns.isEmpty()) {
				Annotations annotations = new Annotations();
				// TODO: Want to handle annotations that are system ones (which we pull out into fields)
				//    AND 3rd party annotations which always stay in the 'anns' list
				for (DexAnnotationNode ann : field.anns) {
					CstType annType = CstType.intern(Type.intern(ann.type));
					AnnotationVisibility visibility = AnnotationVisibility.values()[ann.visibility.ordinal()];
					annotations.add(new Annotation(annType, visibility));
				}
				if (annotations.size() > 0)
					classDef.addFieldAnnotations(fieldRef, annotations, dexFile);
			}

			if (field.isStatic()) {
				// TODO: Need a way to easily know the cst type, and map to appropriate CstX type
				Constant constant = null;
				classDef.addStaticField(encodedField, constant);
			} else {
				classDef.addInstanceField(encodedField);
			}
		}

		for (DexMethodNode method : classNode.methods) {
			CstNat methodNameType = new CstNat(new CstString(method.method.getName()), new CstString(method.method.getDesc()));
			CstMethodRef methodRef = new CstMethodRef(thisClass, methodNameType);
			StdTypeList throwsList = new StdTypeList(0); // TODO: Need to extract throws annotation to write this back
			DalvCode code = null; // TODO: This is gonna be a lot of work
			EncodedMethod encodedMethod = new EncodedMethod(methodRef, method.access, code, throwsList);

			if (!method.anns.isEmpty()) {
				Annotations annotations = new Annotations();
				// TODO: Want to handle annotations that are system ones (which we pull out into fields)
				//    AND 3rd party annotations which always stay in the 'anns' list
				for (DexAnnotationNode ann : method.anns) {
					CstType annType = CstType.intern(Type.intern(ann.type));
					AnnotationVisibility visibility = AnnotationVisibility.values()[ann.visibility.ordinal()];
					annotations.add(new Annotation(annType, visibility));
				}

				if (annotations.size() > 0)
					classDef.addMethodAnnotations(methodRef, annotations, dexFile);

				// TODO: When to use: addParameterAnnotations(...) ???
			}

			if (method.isStatic() || method.isPrivate() || method.isConstructor() || method.isStaticInitializer()) {
				classDef.addDirectMethod(encodedMethod);
			} else {
				classDef.addVirtualMethod(encodedMethod);
			}
		}

		classDef.addContents(dexFile);
		return classDef;
	}
}
