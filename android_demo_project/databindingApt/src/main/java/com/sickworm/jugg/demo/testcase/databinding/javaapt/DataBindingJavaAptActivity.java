package com.sickworm.jugg.demo.testcase.databinding.javaapt;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import com.example.databindingapt.R;
import com.example.databindingapt.databinding.ActivityDataBindingJavaAptBinding;

/**
 * Demo activity for DataBinding compiled by Java APT instead of KAPT.
 */
public class DataBindingJavaAptActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityDataBindingJavaAptBinding binding =
            DataBindingUtil.setContentView(this, R.layout.activity_data_binding_java_apt);
        binding.setTitle("Java APT DataBinding");
    }
}
