package com.googlecode.d2j.visitors.annotations;

import com.googlecode.d2j.Method;
import com.googlecode.d2j.visitors.DexAnnotationVisitor;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class EnclosingMethodVisitor extends DexAnnotationVisitor {
	private final Consumer<Method> handler;

	public EnclosingMethodVisitor(Consumer<Method> handler) {
		this.handler = handler;
	}

	@Override
	public void visit(String name, Object value) {
		super.visit(name, value);
		if (name.equals("value") && value instanceof Method)
			handler.accept((Method) value);
	}
}
