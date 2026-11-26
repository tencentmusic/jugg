package com.intellij.openapi.util;

import com.intellij.openapi.Disposable;
import org.jetbrains.annotations.NotNull;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Provides the standalone-compatible subset of IntelliJ's identity-based disposable tree. */
public final class Disposer {

    private static final Object lock = new Object();
    private static final Map<Disposable, List<Disposable>> childrenMap = new IdentityHashMap<>();
    private static final Map<Disposable, Disposable> parentMap = new IdentityHashMap<>();
    private static final ReferenceQueue<Disposable> disposedQueue = new ReferenceQueue<>();
    private static final Set<IdentityWeakReference> disposedSet = new HashSet<>();

    private Disposer() {
    }

    public static @NotNull Disposable newDisposable() {
        return new Disposable() {
            @Override
            public void dispose() {
            }

            @Override
            public String toString() {
                return "newDisposable";
            }
        };
    }

    public static void register(@NotNull Disposable parent, @NotNull Disposable child) {
        synchronized (lock) {
            cleanupDisposedReferences();
            if (isDisposedLocked(parent)) {
                throw new IllegalStateException("Parent has already been disposed: " + parent);
            }
            registerLocked(parent, child);
        }
    }

    public static boolean isDisposed(@NotNull Disposable disposable) {
        synchronized (lock) {
            cleanupDisposedReferences();
            return isDisposedLocked(disposable);
        }
    }

    public static void dispose(@NotNull Disposable disposable) {
        List<Disposable> disposables = new ArrayList<>();
        synchronized (lock) {
            cleanupDisposedReferences();
            collectForDisposal(disposable, disposables);
        }
        disposeAll(disposables);
    }

    private static void registerLocked(Disposable parent, Disposable child) {
        if (parent == child) {
            throw new IllegalArgumentException("Cannot register a disposable to itself");
        }
        for (Disposable ancestor = parent; ancestor != null; ancestor = parentMap.get(ancestor)) {
            if (ancestor == child) {
                throw new IllegalArgumentException("Cannot create a disposable cycle");
            }
        }
        Disposable oldParent = parentMap.get(child);
        if (oldParent == parent) {
            throw new IllegalStateException("Child is already registered with parent");
        }
        if (oldParent != null) {
            removeChild(oldParent, child);
        }
        removeDisposedLocked(child);
        parentMap.put(child, parent);
        childrenMap.computeIfAbsent(parent, key -> new ArrayList<>()).add(child);
    }

    private static void collectForDisposal(Disposable disposable, List<Disposable> disposables) {
        if (isDisposedLocked(disposable)) {
            return;
        }
        markDisposedLocked(disposable);
        Disposable parent = parentMap.remove(disposable);
        if (parent != null) {
            removeChild(parent, disposable);
        }
        List<Disposable> children = childrenMap.remove(disposable);
        if (children != null) {
            for (int i = children.size() - 1; i >= 0; i--) {
                Disposable child = children.get(i);
                parentMap.remove(child);
                collectForDisposal(child, disposables);
            }
        }
        disposables.add(disposable);
    }

    private static void disposeAll(List<Disposable> disposables) {
        Throwable failure = null;
        for (int i = disposables.size() - 1; i >= 0; i--) {
            Disposable disposable = disposables.get(i);
            if (disposable instanceof Disposable.Parent) {
                failure = invokeSafely(((Disposable.Parent) disposable)::beforeTreeDispose, failure);
            }
        }
        for (Disposable disposable : disposables) {
            failure = invokeSafely(disposable::dispose, failure);
        }
        if (failure != null) {
            rethrow(failure);
        }
    }

    private static Throwable invokeSafely(Runnable action, Throwable failure) {
        try {
            action.run();
        } catch (Throwable throwable) {
            if (failure == null) {
                return throwable;
            }
            failure.addSuppressed(throwable);
        }
        return failure;
    }

    private static void rethrow(Throwable throwable) {
        if (throwable instanceof Error) {
            throw (Error) throwable;
        }
        if (throwable instanceof RuntimeException) {
            throw (RuntimeException) throwable;
        }
        throw new RuntimeException(throwable);
    }

    private static void removeChild(Disposable parent, Disposable child) {
        List<Disposable> children = childrenMap.get(parent);
        if (children == null) {
            return;
        }
        for (int i = children.size() - 1; i >= 0; i--) {
            if (children.get(i) == child) {
                children.remove(i);
                break;
            }
        }
        if (children.isEmpty()) {
            childrenMap.remove(parent);
        }
    }

    private static boolean isDisposedLocked(Disposable disposable) {
        return disposedSet.contains(new IdentityWeakReference(disposable));
    }

    private static void markDisposedLocked(Disposable disposable) {
        disposedSet.add(new IdentityWeakReference(disposable, disposedQueue));
    }

    private static void removeDisposedLocked(Disposable disposable) {
        disposedSet.remove(new IdentityWeakReference(disposable));
    }

    private static void cleanupDisposedReferences() {
        IdentityWeakReference reference;
        while ((reference = (IdentityWeakReference) disposedQueue.poll()) != null) {
            disposedSet.remove(reference);
        }
    }

    private static final class IdentityWeakReference extends WeakReference<Disposable> {
        private final int identityHashCode;

        private IdentityWeakReference(Disposable disposable) {
            super(disposable);
            identityHashCode = System.identityHashCode(disposable);
        }

        private IdentityWeakReference(Disposable disposable, ReferenceQueue<Disposable> queue) {
            super(disposable, queue);
            identityHashCode = System.identityHashCode(disposable);
        }

        @Override
        public int hashCode() {
            return identityHashCode;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof IdentityWeakReference)) {
                return false;
            }
            Disposable disposable = get();
            return disposable != null && disposable == ((IdentityWeakReference) obj).get();
        }
    }
}
