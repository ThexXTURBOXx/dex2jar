package com.googlecode.d2j.visitors.annotations;

import com.googlecode.d2j.visitors.DexAnnotationVisitor;

import java.util.function.BiConsumer;

public class InnerClassVisitor extends DexAnnotationVisitor {
	private final BiConsumer<String, Integer> handler;
	private String innerName;
	private int innerAccess;

	public InnerClassVisitor(BiConsumer<String, Integer> handler) {
		this.handler = handler;
	}

	@Override
	public void visit(String name, Object value) {
		super.visit(name, value);
		if (name.equals("name"))
			innerName = String.valueOf(value);
		else if (name.equals("accessFlags"))
			innerAccess = (Integer) value;
	}

	@Override
	public void visitEnd() {
		handler.accept(innerName, innerAccess);
	}
}
