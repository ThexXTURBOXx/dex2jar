package com.googlecode.dex2jar.ir.ts;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Used to manage all additional constructors that need to be generated during IR transformation
 */
public class ConstructorGenerator {
    public static final class ConstructorPair {
        private final String owner;
        private final String[] parameterTypes;

        public ConstructorPair(String owner, String[] parameterTypes) {
            this.owner = owner;
            this.parameterTypes = parameterTypes;
        }

        public String getOwner() {
            return owner;
        }

        public String[] getParameterTypes() {
            return parameterTypes;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ConstructorPair)) return false;
            ConstructorPair that = (ConstructorPair) o;
            return Objects.equals(owner, that.owner) && Objects.deepEquals(parameterTypes, that.parameterTypes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(owner, Arrays.hashCode(parameterTypes));
        }
    }

    public static final class ConstructorMismatchException extends RuntimeException {
        private final String oldBase;
        private final String newBase;

        public ConstructorMismatchException(String oldBase, String newBase) {
            super(String.format("Internal error: Mismatch in constructor types, expected base class %s, but got %s", oldBase, newBase));
            this.oldBase = oldBase;
            this.newBase = newBase;
        }

        public String getOldBase() {
            return oldBase;
        }

        public String getNewBase() {
            return newBase;
        }
    }

    private final Map<ConstructorPair, String> constructors = new ConcurrentHashMap<>();

    public void Add(String owner, String[] parameterTypes, String baseOwner) {
        ConstructorPair index = new ConstructorPair(owner, parameterTypes);
        String oldValue = constructors.put(index, baseOwner);
        if (oldValue != null && !oldValue.equals(baseOwner)) {
            throw new ConstructorMismatchException(oldValue, baseOwner);
        }
    }

    public boolean isEmpty() {
        return constructors.isEmpty();
    }

    public Iterable<Map.Entry<ConstructorPair, String>> entries() {
        return constructors.entrySet();
    }
}
