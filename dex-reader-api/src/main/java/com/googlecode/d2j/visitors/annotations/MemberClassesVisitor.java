package com.googlecode.d2j.visitors.annotations;

import com.googlecode.d2j.DexType;
import com.googlecode.d2j.visitors.DexAnnotationVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class MemberClassesVisitor extends DexAnnotationVisitor {
	private final Consumer<List<DexType>> handler;

	public MemberClassesVisitor(Consumer<List<DexType>> handler) {
		this.handler = handler;
	}

	@Override
	public DexAnnotationVisitor visitArray(String name) {
		if (name.equals("value")) {
			List<DexType> list = new ArrayList<>(2);
			return new DexAnnotationVisitor() {
				@Override
				public void visit(String name, Object value) {
					super.visit(name, value);
					if (value instanceof DexType)
						list.add((DexType) value);
				}

				@Override
				public void visitEnd() {
					handler.accept(list);
				}
			};
		}
		return super.visitArray(name);
	}
}
