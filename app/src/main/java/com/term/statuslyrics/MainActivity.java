package com.term.statuslyrics;

import android.app.Activity;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView tv = new TextView(this);
        tv.setPadding(32, 32, 32, 32);
        tv.setTextSize(14f);
        tv.setLineSpacing(0f, 1.15f);
        tv.setMovementMethod(new ScrollingMovementMethod());
        tv.setText(getString(R.string.main_desc));
        setContentView(tv);
    }
}