package com.sickworm.jugg.demo.testcase.databinding.library1;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import com.example.library1.R;
import com.example.library1.databinding.ActivityDataBindingJavaDemoLibrary1Binding;

public class DataBindingJavaDemoActivityLibrary1 extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityDataBindingJavaDemoLibrary1Binding binding = DataBindingUtil.setContentView(this, R.layout.activity_data_binding_java_demo_library1);
        binding.setUser(new User("Jugg User", 25));
    }

    public static class User {
        public final String name;
        public final int age;

        public User(String name, int age) {
            this.name = name;
            this.age = age;
        }
    }
}