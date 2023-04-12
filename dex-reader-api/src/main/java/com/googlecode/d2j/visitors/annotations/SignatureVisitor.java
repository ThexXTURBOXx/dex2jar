package com.googlecode.d2j.visitors.annotations;

import com.googlecode.d2j.node.Signature;
import com.googlecode.d2j.visitors.DexAnnotationVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public class SignatureVisitor extends DexAnnotationVisitor {
	private final Consumer<Signature> handler;

	public SignatureVisitor(Consumer<Signature> handler) {
		this.handler = handler;
	}

	@Override
	public DexAnnotationVisitor visitArray(String name) {
		if (name.equals("value")) {
			List<String> parts = new ArrayList<>();
			return new DexAnnotationVisitor() {
				@Override
				public void visit(String name, Object value) {
					super.visit(name, value);
					if (value instanceof String)
						parts.add((String) value);
				}

				@Override
				public void visitEnd() {
					super.visitEnd();
					handler.accept(new Signature(Collections.unmodifiableList(parts)));
				}
			};
		}
		return super.visitArray(name);
	}
}
