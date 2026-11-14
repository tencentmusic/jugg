package com.sickworm.jugg.demo.testcase.butterknife;

import android.os.Bundle;
import android.widget.TextView;
import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import butterknife.Unbinder;
import com.example.myapplication.R;

/** Shows Java, Kotlin, inherited fields and listeners for manual incremental verification. */
public class ButterKnifeDemoActivity extends BindingParent {
    @BindView(R.id.butterknife_java_title) TextView title;
    private Unbinder javaBinding;
    private Unbinder kotlinBinding;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_butterknife);
        javaBinding = ButterKnife.bind(this);
        kotlinBinding = ButterKnife.bind(new KotlinBindings(), getWindow().getDecorView());
        title.setText("Java binding: " + inheritedLabel);
    }

    @OnClick(R.id.butterknife_java_title) void clicked() {
        title.setText("Java listener updated");
    }

    @Override protected void onDestroy() {
        kotlinBinding.unbind();
        javaBinding.unbind();
        super.onDestroy();
    }
}
