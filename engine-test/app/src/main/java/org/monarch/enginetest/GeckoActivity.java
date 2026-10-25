package org.monarch.enginetest;

import android.app.Activity;
import android.os.Bundle;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

public class GeckoActivity extends Activity {
    // GeckoRuntime may only be created once per process.
    private static GeckoRuntime runtime;
    private GeckoSession session;
    private boolean canGoBack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (runtime == null) runtime = GeckoRuntime.create(getApplicationContext());

        session = new GeckoSession();
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onCanGoBack(GeckoSession s, boolean value) { canGoBack = value; }
        });
        session.open(runtime);

        GeckoView view = new GeckoView(this);
        view.setSession(session);
        setContentView(view);
        session.loadUri(MainActivity.normalize(
                getIntent().getStringExtra(MainActivity.EXTRA_URL),
                "resource://android/assets/test.html"));
        view.requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (canGoBack) session.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        session.close();
        super.onDestroy();
    }
}
