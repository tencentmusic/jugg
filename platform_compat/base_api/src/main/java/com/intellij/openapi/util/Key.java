package com.intellij.openapi.util;

public class Key<T> {
    public static <T> Key<T> create(String name) {
        return new Key<>();
    }
}
