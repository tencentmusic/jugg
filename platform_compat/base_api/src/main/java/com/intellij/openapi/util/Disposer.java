package com.intellij.openapi.util;

import com.intellij.openapi.Disposable;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class Disposer {

    private final static Map<Disposable, Set<Disposable>> childrenMap = new HashMap<>();

    public static void register(@NotNull Disposable parent, @NotNull Disposable child) throws Exception {
        synchronized (childrenMap) {
            Set<Disposable> childrenList = childrenMap.computeIfAbsent(parent, k -> new HashSet<>());
            childrenList.add(child);
        }
    }

    public static void dispose(@NotNull Disposable disposable) {
        synchronized (childrenMap) {
            Set<Disposable> childrenList = childrenMap.remove(disposable);
            if (childrenList != null) {
                for (Disposable child : childrenList) {
                    dispose(child);
                }
            }
            disposable.dispose();
        }
    }
}
