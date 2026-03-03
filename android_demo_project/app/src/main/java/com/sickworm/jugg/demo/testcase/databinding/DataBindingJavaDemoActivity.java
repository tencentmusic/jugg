package com.sickworm.jugg.demo.testcase.databinding;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import com.example.myapplication.R;
import com.example.myapplication.databinding.ActivityDataBindingJavaDemoBinding;

public class DataBindingJavaDemoActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityDataBindingJavaDemoBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_data_binding_java_demo);
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