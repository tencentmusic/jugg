package com.sickworm.intellij.jugg.viewhierarchy;

import android.view.View;

import java.util.List;

/**
 * ComposeTreeExtractor defines the extension point for extracting Compose semantics
 * from a host View (for example AndroidComposeView).
 *
 * Current release ships with a no-op implementation and keeps this interface as
 * a stable contract for future Compose support.
 */
public interface ComposeTreeExtractor {

    List<ComposeNode> extract(View hostView);
}
