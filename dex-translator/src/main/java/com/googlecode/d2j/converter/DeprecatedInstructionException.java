package com.googlecode.d2j.converter;

import org.objectweb.asm.commons.JSRInlinerAdapter;
import org.objectweb.asm.tree.MethodNode;

public class DeprecatedInstructionException extends RuntimeException {
	private final String owner;
	private final MethodNode method;
	private final int index;

	public DeprecatedInstructionException(String owner, MethodNode method, int index) {
		super("JSR/RET are deprecated JVM instructions. Location of offense: " +
				" '" + owner + "." + method.name + " @" + index + "\n" +
				"Please use " + JSRInlinerAdapter.class + " before converting methods with JSR/RET.");
		this.owner = owner;
		this.method = method;
		this.index = index;
	}

	/**
	 * @return Class name declaring the {@link #getMethod() method} with the deprecated instruction.
	 */
	public String getOwner() {
		return owner;
	}

	/**
	 * @return Method containing the deprecated instruction.
	 */
	public MethodNode getMethod() {
		return method;
	}

	/**
	 * @return Index of the instruction in the {@link #getMethod()} for the offending instruction.
	 */
	public int getIndex() {
		return index;
	}
}
