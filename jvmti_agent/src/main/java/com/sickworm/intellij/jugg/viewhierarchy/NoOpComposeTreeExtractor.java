package com.sickworm.intellij.jugg.viewhierarchy;

import android.view.View;

import java.util.Collections;
import java.util.List;

/**
 * NoOpComposeTreeExtractor keeps protocol compatibility while Compose analysis
 * is intentionally not implemented in this phase.
 */
final class NoOpComposeTreeExtractor implements ComposeTreeExtractor {

    @Override
    public List<ComposeNode> extract(View hostView) {
        return Collections.emptyList();
    }
}
