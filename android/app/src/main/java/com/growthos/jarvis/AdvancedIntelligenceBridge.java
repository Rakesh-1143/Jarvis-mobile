package com.growthos.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

/** Explicit-consent native boundary. It never captures, launches, or executes by itself. */
public final class AdvancedIntelligenceBridge {
    public static final int SCREEN_CAPTURE_REQUEST = 1201;
    private final Activity activity;
    private final EventSink sink;
    private final java.util.function.BooleanSupplier trusted;
    public interface EventSink { void emit(String event, String payload); }
    /** {@code trusted} says whether KALKI's own site is the page on screen; calls from any other page are ignored. */
    public AdvancedIntelligenceBridge(Activity activity, EventSink sink, java.util.function.BooleanSupplier trusted) { this.activity=activity; this.sink=sink; this.trusted=trusted; }

    @JavascriptInterface public void requestScreenCapture() {
        if(!trusted.getAsBoolean()) return;
        activity.runOnUiThread(() -> {
            MediaProjectionManager manager=(MediaProjectionManager)activity.getSystemService(Activity.MEDIA_PROJECTION_SERVICE);
            if(manager==null){ sink.emit("kalki-native-capability", "{\"execution\":\"screen capture unavailable\"}"); return; }
            activity.startActivityForResult(manager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST);
        });
    }
    @JavascriptInterface public void requestCameraPermission() {
        if(!trusted.getAsBoolean()) return;
        activity.runOnUiThread(() -> {
            if(activity.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) { sink.emit("kalki-native-capability", "{\"camera\":true}"); return; }
            activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, 1202);
        });
    }
    @JavascriptInterface public void chooseSourceFile() {
        if(!trusted.getAsBoolean()) return;
        activity.runOnUiThread(() -> { Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); activity.startActivityForResult(i,1203); });
    }
    @JavascriptInterface public void launchSafeIntent(String action) {
        // Allowlist only; callers cannot pass arbitrary component/package names.
        if(!trusted.getAsBoolean()) return;
        if(!"open_settings".equals(action) && !"open_app_settings".equals(action)) return;
        activity.runOnUiThread(() -> { Intent i="open_settings".equals(action) ? new Intent(Settings.ACTION_SETTINGS) : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:"+activity.getPackageName())); activity.startActivity(i); });
    }
    public void screenResult(boolean granted) { sink.emit("kalki-native-capability", "{\"screenCapture\":"+granted+",\"execution\":\""+(granted?"screen consent granted":"screen consent denied")+"\"}"); }
    public void fileResult(boolean chosen) { sink.emit("kalki-native-capability", "{\"execution\":\""+(chosen?"source selected":"source selection cancelled")+"\"}"); }
}
