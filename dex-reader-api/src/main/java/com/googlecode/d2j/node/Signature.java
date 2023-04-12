package com.googlecode.d2j.node;

import com.googlecode.d2j.DexConstants;

import java.util.List;

/**
 * Wrapper type for the contents of {@link DexConstants#ANNOTATION_SIGNATURE_TYPE}.
 * <p/>
 * The {@link #getSections() sections} represent the literal array values from the DEX file.
 * <br>
 * The {@link #getConcat() content} represents the combined string of the sections.
 */
public class Signature {
	private final List<String> sections;
	private final String concat;

	public Signature(List<String> sections) {
		this.sections = sections;
		StringBuilder sb = new StringBuilder();
		for (String section : sections) {
			sb.append(section);
		}
		this.concat = sb.toString();
	}

	public List<String> getSections() {
		return sections;
	}

	public String getConcat() {
		return concat;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		Signature signature = (Signature) o;

		return concat.equals(signature.concat);
	}

	@Override
	public int hashCode() {
		return concat.hashCode();
	}
}
