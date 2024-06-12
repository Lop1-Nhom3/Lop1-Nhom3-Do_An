package com.example.ble_app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.ble_app.R;

import java.util.ArrayList;

public class ChildActivity extends AppCompatActivity {

    private ListView lv;
    private Button btnback;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_child);

        lv = findViewById(R.id.lv);
        btnback = findViewById(R.id.btnback);

        Intent intent = getIntent();
        ArrayList<MainActivity.HeartRateData> heartRates = intent.getParcelableArrayListExtra("heartRates");

        ArrayList<String> heartRateData = new ArrayList<>();
        if (heartRates != null) {
            for (MainActivity.HeartRateData data : heartRates) {
                String entry = "Time: " +  data.thoigian +
                        "\nNow: " + (float) data.nhiptimhientai / 100 +
                        "\nMax: " + (float) data.nhiptimlonnhat / 100 +
                        "\nMin: " + (float) data.nhiptimnhonhat / 100 +
                        "\nAverage: " + (float) data.nhiptimtrungbinh / 100 + "\n";
                heartRateData.add(entry);
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, heartRateData);
        lv.setAdapter(adapter);

        btnback.setOnClickListener(v -> finish());
    }
}