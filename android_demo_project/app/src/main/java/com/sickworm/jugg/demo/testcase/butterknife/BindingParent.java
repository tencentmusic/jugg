package com.sickworm.jugg.demo.testcase.butterknife;

import android.app.Activity;
import butterknife.BindString;

/** Exercises inherited resource bindings without requiring a View constructor. */
public class BindingParent extends Activity {
    @BindString(android.R.string.ok) String inheritedLabel;
}
