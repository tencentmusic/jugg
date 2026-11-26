package com.sickworm.intellij.jugg.viewhierarchy;

import com.sickworm.intellij.jugg.hotfix.LogUtils;
import com.sickworm.intellij.jugg.internal.dragonfly.compose.node.ComposeNode;
import com.sickworm.intellij.jugg.internal.dragonfly.compose.node.ComposeNodeContext;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/** Joins Compose's merged semantics tree to Dragonfly's composition tree by layout identity. */
final class ComposeSemantics {
    private static final Object MULTIPLE_LAYOUTS = new Object();
    private final Method group;
    private final Method groupNode;
    private final Method owner;
    private final Method semanticsOwner;
    private final Method layoutId;
    private final Method root;
    private final Method id;
    private final Method children;
    private final Method config;
    private final Method iterator;
    private final Method keyName;
    private final Class<?> layoutType;
    private final int maxNodes;
    private final Map<ComposeNodeContext, Object> layouts = new IdentityHashMap<>();
    private final Map<Object, Map<Integer, Object>> nodesByOwner = new IdentityHashMap<>();
    private boolean loggedFailure;

    ComposeSemantics(ClassLoader loader, int maxNodes) throws ReflectiveOperationException {
        this.maxNodes = maxNodes;
        Class<?> groupType = loader.loadClass("androidx.compose.runtime.tooling.CompositionGroup");
        layoutType = loader.loadClass("androidx.compose.ui.node.LayoutNode");
        Class<?> ownerType = loader.loadClass("androidx.compose.ui.node.Owner");
        Class<?> semanticsOwnerType = loader.loadClass("androidx.compose.ui.semantics.SemanticsOwner");
        Class<?> nodeType = loader.loadClass("androidx.compose.ui.semantics.SemanticsNode");
        Class<?> configurationType = loader.loadClass("androidx.compose.ui.semantics.SemanticsConfiguration");
        Class<?> keyType = loader.loadClass("androidx.compose.ui.semantics.SemanticsPropertyKey");

        group = ComposeNodeContext.class.getMethod("getGroup");
        groupNode = groupType.getMethod("getNode");
        owner = layoutOwnerMethod(layoutType);
        layoutId = layoutType.getMethod("getSemanticsId");
        semanticsOwner = semanticsOwnerMethod(loader, ownerType);
        root = semanticsOwnerType.getMethod("getRootSemanticsNode");
        id = nodeType.getMethod("getId");
        children = nodeType.getMethod("getChildren");
        config = nodeType.getMethod("getConfig");
        iterator = configurationType.getMethod("iterator");
        keyName = keyType.getMethod("getName");
    }

    private static Method layoutOwnerMethod(Class<?> type) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            String name = method.getName();
            if (method.getParameterTypes().length == 0 && (name.equals("getOwner") || name.startsWith("getOwner$"))) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + ".getOwner");
    }

    private static Method semanticsOwnerMethod(ClassLoader loader, Class<?> ownerType)
        throws ReflectiveOperationException {
        try {
            return ownerType.getMethod("getSemanticsOwner");
        } catch (NoSuchMethodException missing) {
            // Compose 1.5–1.7 exposes the accessor only on the Android owner.
            return loader.loadClass("androidx.compose.ui.platform.AndroidComposeView").getMethod("getSemanticsOwner");
        }
    }

    void enrich(ComposeNode source, ViewNode target) {
        try {
            Object layout = findLayout(source.getContext(), 0);
            if (layout == null || layout == MULTIPLE_LAYOUTS) return;
            // A named wrapper and its child can describe the same layout. Prefer the inner node.
            for (ComposeNode child : source.getChildren()) {
                if (findLayout(child.getContext(), 0) == layout) return;
            }
            Object layoutOwner = owner.invoke(layout);
            if (layoutOwner == null) return;
            Map<Integer, Object> nodes = nodesByOwner.get(layoutOwner);
            if (nodes == null) {
                nodes = indexNodes(layoutOwner);
                nodesByOwner.put(layoutOwner, nodes);
            }
            Object node = nodes.remove((Integer) layoutId.invoke(layout));
            if (node != null) {
                applyProperties(target, (Iterator<?>) iterator.invoke(config.invoke(node)), keyName);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (!loggedFailure) {
                loggedFailure = true;
                LogUtils.w("ComposeSemantics", "Unable to read Compose semantics: " + failure);
            }
        }
    }

    private Object findLayout(ComposeNodeContext context, int depth) throws ReflectiveOperationException {
        if (depth == 60 || layouts.size() >= maxNodes) return null;
        if (layouts.containsKey(context)) return layouts.get(context);
        Object candidate = groupNode.invoke(group.invoke(context));
        if (!layoutType.isInstance(candidate)) {
            candidate = null;
            for (ComposeNodeContext child : context.getChildren()) {
                Object layout = findLayout(child, depth + 1);
                if (layout == null) continue;
                if (layout == MULTIPLE_LAYOUTS || (candidate != null && candidate != layout)) {
                    candidate = MULTIPLE_LAYOUTS;
                    break;
                }
                candidate = layout;
            }
        }
        layouts.put(context, candidate);
        return candidate;
    }

    private Map<Integer, Object> indexNodes(Object layoutOwner) throws ReflectiveOperationException {
        Map<Integer, Object> nodes = new HashMap<>();
        ArrayDeque<Object> pending = new ArrayDeque<>();
        pending.add(root.invoke(semanticsOwner.invoke(layoutOwner)));
        while (!pending.isEmpty() && nodes.size() < maxNodes) {
            Object node = pending.removeFirst();
            nodes.put((Integer) id.invoke(node), node);
            for (Object child : (Iterable<?>) children.invoke(node)) {
                pending.addLast(child);
            }
        }
        return nodes;
    }

    static void applyProperties(ViewNode target, Iterator<?> properties, Method keyName)
        throws ReflectiveOperationException {
        while (properties.hasNext()) {
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) properties.next();
            String key = (String) keyName.invoke(entry.getKey());
            switch (key) {
                case "OnClick":
                    target.clickable = true;
                    break;
                case "Disabled":
                    target.enabled = false;
                    break;
                case "ContentDescription":
                    if (entry.getValue() instanceof Iterable) {
                        for (Object description : (Iterable<?>) entry.getValue()) {
                            if (description instanceof String && !((String) description).isEmpty()) {
                                target.contentDesc = (String) description;
                                break;
                            }
                        }
                    }
                    break;
                default:
                    break;
            }
        }
    }
}
