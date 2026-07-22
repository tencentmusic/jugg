package com.sickworm.intellij.jugg.viewhierarchy;

import java.util.List;

/**
 * ElementFinder performs selector lookup on one Dragonfly hierarchy snapshot.
 */
public class ElementFinder {

    private final DragonflyHierarchySource.Snapshot snapshot;

    public ElementFinder(DragonflyHierarchySource.Snapshot snapshot) {
        this.snapshot = snapshot;
    }

    /**
     * Find elements with AND logic: all non-empty selectors must match.
     */
    public List<MatchedElement> find(
        String text,
        String resourceId,
        String contentDesc,
        String className
    ) {
        return find(text, resourceId, contentDesc, className, false);
    }

    /**
     * Find elements with AND logic. Window scope is fixed when the snapshot is captured;
     * topWindowOnly remains in this method for existing callers.
     */
    public List<MatchedElement> find(
        String text,
        String resourceId,
        String contentDesc,
        String className,
        boolean topWindowOnly
    ) {
        return snapshot.find(text, resourceId, contentDesc, className, true);
    }

    /**
     * Find elements for read-only inspection, including non-visible views that remain in
     * the hierarchy. This is intentionally separate from actionable lookup used by tap.
     */
    public List<MatchedElement> findInspectable(
        String text,
        String resourceId,
        String contentDesc,
        String className,
        boolean topWindowOnly
    ) {
        return snapshot.find(text, resourceId, contentDesc, className, false);
    }

    /**
     * Return clickable candidates for debugging when a selector has no match.
     */
    public List<MatchedElement> findClickableCandidates(int limit) {
        return findClickableCandidates(limit, false);
    }

    /**
     * Return clickable candidates for debugging. Window scope is fixed by the snapshot.
     */
    public List<MatchedElement> findClickableCandidates(int limit, boolean topWindowOnly) {
        return snapshot.findClickableCandidates(limit);
    }
}
