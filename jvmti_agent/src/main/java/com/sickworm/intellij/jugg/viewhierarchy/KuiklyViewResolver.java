package com.sickworm.intellij.jugg.viewhierarchy;

import android.text.Layout;
import android.view.View;

import com.sickworm.intellij.jugg.hotfix.LogUtils;

import java.lang.reflect.Field;

/**
 * KuiklyViewResolver extracts text and properties from Kuikly framework views
 * (e.g. KRRichTextView) via reflection.
 *
 * Since jvmti_agent cannot depend on Kuikly SDK directly, all access is done
 * through reflection on class names and field/method names. Failures are caught
 * and logged without crashing; the caller receives empty/default values instead.
 */
public final class KuiklyViewResolver {

    private static final String TAG = "Jugg#KuiklyViewResolver";

    private static final String KR_RICH_TEXT_VIEW = "KRRichTextView";
    private static final String KR_GRADIENT_RICH_TEXT_VIEW = "KRGradientRichTextView";

    private KuiklyViewResolver() {
    }

    /**
     * Check whether the given view is a Kuikly view that this resolver can handle.
     */
    public static boolean canResolve(View view) {
        if (view == null) {
            return false;
        }
        String simpleName = view.getClass().getSimpleName();
        return KR_RICH_TEXT_VIEW.equals(simpleName)
            || KR_GRADIENT_RICH_TEXT_VIEW.equals(simpleName);
    }

    /**
     * Resolve the text content from a Kuikly view.
     * Returns empty string if the view is not a supported Kuikly view or if
     * extraction fails.
     */
    public static ResolveResult resolveText(View view) {
        if (view == null) {
            return ResolveResult.EMPTY;
        }
        String simpleName = view.getClass().getSimpleName();
        if (KR_RICH_TEXT_VIEW.equals(simpleName) || KR_GRADIENT_RICH_TEXT_VIEW.equals(simpleName)) {
            return resolveRichTextViewText(view);
        }
        return ResolveResult.EMPTY;
    }

    /**
     * Extract text from KRRichTextView / KRGradientRichTextView.
     * view.richTextShadow.textProps.text
     */
    private static ResolveResult resolveRichTextViewText(View view) {
        try {
            // Step 1: Get richTextShadow field
            Field richTextShadowField = findField(view.getClass(), "richTextShadow");
            if (richTextShadowField == null) {
                return ResolveResult.EMPTY;
            }
            richTextShadowField.setAccessible(true);
            Object richTextShadow = richTextShadowField.get(view);
            if (richTextShadow == null) {
                return ResolveResult.EMPTY;
            }

            // Step 2: Get textProps field from richTextShadow
            Field textPropsField = findField(richTextShadow.getClass(), "textProps");
            if (textPropsField == null) {
                return ResolveResult.EMPTY;
            }
            textPropsField.setAccessible(true);
            Object textProps = textPropsField.get(richTextShadow);
            if (textProps == null) {
                return ResolveResult.EMPTY;
            }

            // Step 3: Get text field from textProps
            Field textField = findField(textProps.getClass(), "text");
            if (textField == null) {
                return ResolveResult.EMPTY;
            }
            textField.setAccessible(true);
            Object text = textField.get(textProps);
            return ResolveResult.success(text != null ? text.toString() : "");
        } catch (Throwable t) {
            LogUtils.d(TAG, "KuiklyViewResolver: richTextShadow path failed: " + t);
            return ResolveResult.EMPTY;
        }
    }

    /**
     * Search for a declared field by name, walking up the class hierarchy.
     */
    private static Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                // continue to superclass
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /**
     * Result wrapper that carries both the resolved value and an optional error message.
     * When resolving fails, the text is empty and errorMessage contains the stack trace
     * or diagnostic info for inclusion in the dump output.
     */
    public static final class ResolveResult {
        public static final ResolveResult EMPTY = new ResolveResult("", null);

        public final String text;
        public final String errorMessage;

        private ResolveResult(String text, String errorMessage) {
            this.text = text;
            this.errorMessage = errorMessage;
        }

        public static ResolveResult success(String text) {
            return new ResolveResult(text, null);
        }

        public static ResolveResult error(String message) {
            return new ResolveResult("", message);
        }

        public boolean hasError() {
            return errorMessage != null;
        }
    }
}
