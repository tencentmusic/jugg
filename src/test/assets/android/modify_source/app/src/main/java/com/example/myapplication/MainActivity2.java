package com.example.myapplication;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;

import java.io.IOException;

public class MainActivity2 extends AppCompatActivity implements View.OnClickListener {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main2);

        findViewById(R.id.button2).setOnClickListener(this);

        new ABC().haha();
        new ABC().haha();
        new ABC().haha();

        AssetManager am=this.getAssets();
        try {
            Bitmap bitmap = BitmapFactory.decodeStream(am.open("test2/2.png"));
            ImageView imageView = findViewById(R.id.imageView);
            imageView.setImageDrawable(new BitmapDrawable(getResources(), bitmap));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onClick(View v) {
        Toast.makeText(this, "3", Toast.LENGTH_SHORT).show();
    }

    private String getToast() {
        return "26877";
    }

    private String toastField = "54434";

    private String toastField2 = "61";

    private String toastField3 = "62";

    private String toastField4 = "63";

    private String toastField44 = "67";

    private static String toastField5 = "65";

    private static String toastField6 = "68";

    private static String toastField7 = "7";
}