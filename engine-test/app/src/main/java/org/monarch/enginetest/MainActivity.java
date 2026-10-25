package org.monarch.enginetest;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Launcher: pick an engine and an optional URL (blank = bundled test page). */
public class MainActivity extends Activity {
    static final String EXTRA_URL = "url";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        EditText url = new EditText(this);
        url.setHint("URL (leave blank for test page)");
        url.setSingleLine(true);
        root.addView(url);

        Button web = new Button(this);
        web.setText("Open in WebView");
        web.setOnClickListener(v -> open(WebViewActivity.class, url.getText().toString()));
        root.addView(web);

        Button gecko = new Button(this);
        gecko.setText("Open in GeckoView");
        gecko.setOnClickListener(v -> open(GeckoActivity.class, url.getText().toString()));
        root.addView(gecko);

        setContentView(root);
    }

    private void open(Class<?> engine, String url) {
        startActivity(new Intent(this, engine).putExtra(EXTRA_URL, url.trim()));
    }

    static String normalize(String url, String testPage) {
        if (url == null || url.isEmpty()) return testPage;
        return url.contains("://") ? url : "https://" + url;
    }
}
