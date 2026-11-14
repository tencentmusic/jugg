package com.sickworm.jugg.demo.sdk;

import android.content.Context;
import android.util.AttributeSet;
import androidx.constraintlayout.widget.ConstraintLayout;

/** SDK-only view used to reproduce XML type changes without recompiling the SDK. */
public class SdkConstraintLayout extends ConstraintLayout {
    public SdkConstraintLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
}
