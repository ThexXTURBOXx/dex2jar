package com.googlecode.d2j.visitors.annotations;

import com.googlecode.d2j.visitors.DexAnnotationVisitor;

import java.util.function.Consumer;

public class EnclosingClassVisitor extends DexAnnotationVisitor {
	private final Consumer<String> handler;

	public EnclosingClassVisitor(Consumer<String> handler) {
		this.handler = handler;
	}

	@Override
	public void visit(String name, Object value) {
		super.visit(name, value);
		if (name.equals("value"))
			handler.accept(String.valueOf(value));
	}
}
