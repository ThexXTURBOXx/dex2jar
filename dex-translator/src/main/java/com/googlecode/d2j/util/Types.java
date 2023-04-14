package com.googlecode.d2j.util;

import com.googlecode.d2j.DexException;
import com.googlecode.d2j.DexType;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;

public final class Types {

    /**
     * @param desc
     * 		JVM type descriptor of a method, ex: (II)V
     *
     * @return Array of argument types, ex: [I,I]
     */
    public static String[] getParameterTypeDesc(String desc) {

        if (desc.charAt(0) != '(') {
            throw new DexException("not a validate Method Desc %s", desc);
        }
        int x = desc.lastIndexOf(')');
        if (x < 0) {
            throw new DexException("not a validate Method Desc %s", desc);
        }
        List<String> ps = listDesc(desc.substring(1, x - 1));
        return ps.toArray(new String[0]);
    }

    /**
     * @param desc
     * 		JVM type descriptor of a method ex: (II)V
     *
     * @return The return type descriptor of the method, ex: V
     */
    public static String getReturnTypeDesc(String desc) {
        int x = desc.lastIndexOf(')');
        if (x < 0) {
            throw new DexException("not a validate Method Desc %s", desc);
        }
        return desc.substring(x + 1);
    }

    public static List<String> listDesc(String desc) {
        List<String> list = new ArrayList<>(5);
        if (desc == null) {
            return list;
        }
        char[] chars = desc.toCharArray();
        int i = 0;
        while (i < chars.length) {
            switch (chars[i]) {
            case 'V':
            case 'Z':
            case 'C':
            case 'B':
            case 'S':
            case 'I':
            case 'F':
            case 'J':
            case 'D':
                list.add(Character.toString(chars[i]));
                i++;
                break;
            case '[': {
                int count = 1;
                while (chars[i + count] == '[') {
                    count++;
                }
                if (chars[i + count] == 'L') {
                    count++;
                    while (chars[i + count] != ';') {
                        count++;
                    }
                }
                count++;
                list.add(new String(chars, i, count));
                i += count;
                break;
            }
            case 'L': {
                int count = 1;
                while (chars[i + count] != ';') {
                    ++count;
                }
                count++;
                list.add(new String(chars, i, count));
                i += count;
                break;
            }
            default:
                throw new RuntimeException("can't parse type list: " + desc);
            }
        }
        return list;
    }

    public static String[] buildDexStyleSignature(String signature) {
        int rawLength = signature.length();
        ArrayList<String> pieces = new ArrayList<>(20);

        int at = 0;
        while (at < rawLength) {
            char c = signature.charAt(at);
            int endAt = at + 1;
            if (c == 'L') {
                // Scan to ';' or '<'. Consume ';' but not '<'.
                while (endAt < rawLength) {
                    c = signature.charAt(endAt);
                    if (c == ';') {
                        endAt++;
                        break;
                    } else if (c == '<') {
                        break;
                    }
                    endAt++;
                }
            } else {
                // Scan to 'L' without consuming it.
                while (endAt < rawLength) {
                    c = signature.charAt(endAt);
                    if (c == 'L') {
                        break;
                    }
                    endAt++;
                }
            }

            pieces.add(signature.substring(at, endAt));
            at = endAt;
        }
        return pieces.toArray(new String[0]);
    }

    /**
     * @param type
     *         Type descriptor.
     *
     * @return Size of type in the JVM stack.
     */
    public static int sizeOfType(String type) {
        switch (type.charAt(0)) {
            case 'J':
            case 'D':
                return 2;
            default:
                return 1;
        }
    }

    /**
     * @param types
     *         Input array of ASM types.
     *
     * @return Array of descriptors.
     */
    public static String[] toDescArray(Type[] types) {
        String[] ds = new String[types.length];
        for (int i = 0; i < types.length; i++)
            ds[i] = types[i].getDescriptor();
        return ds;
    }

    /**
     * @param argTypes Argument type descriptors, for a method.
     * @return Size of all arguments combined.
     */
    public static int methodArgsSizeTotal(String[] argTypes) {
        int i = 0;
        for (String s : argTypes) {
            i += sizeOfType(s);
        }
        return i;
    }

	public static String toInternalName(DexType type) {
		return toInternalName(type.desc);
	}

	public static String toInternalName(String desc) {
		return desc.substring(1, desc.length() - 1);
	}
}
