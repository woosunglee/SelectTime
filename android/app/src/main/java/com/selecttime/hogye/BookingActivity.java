package com.selecttime.hogye;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.webkit.WebView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class BookingActivity extends AppCompatActivity implements AucWebAutomator.Listener {
    private TextView statusView;
    private AucWebAutomator automator;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_booking);
        statusView = findViewById(R.id.bookingStatus);
        WebView webView = findViewById(R.id.webView);
        automator = new AucWebAutomator(this, webView, this);
        automator.start();
    }

    @Override
    public void onStatus(String message) {
        statusView.setText(message);
    }

    @Override
    public void onFinished(boolean success, String message) {
        statusView.setText(message);
    }
}
