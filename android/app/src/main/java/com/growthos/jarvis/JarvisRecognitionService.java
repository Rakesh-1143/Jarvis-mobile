package com.growthos.jarvis;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import java.util.List;

/**
 * Android requires every assistant to name a speech recognition service, and when the user
 * picks KALKI as assistant Android may also make it the phone's default voice input.
 * KALKI has no speech engine of its own, so this passes every request on to the phone's
 * real recognizer (usually the Google app). Without it, voice typing on the phone would break.
 */
public final class JarvisRecognitionService extends RecognitionService {
    private SpeechRecognizer inner;

    /** The phone's real speech recognizer (never this app). Prefers the Google app. */
    static ComponentName findDelegate(Context c) {
        List<ResolveInfo> list = c.getPackageManager().queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), PackageManager.GET_META_DATA);
        ComponentName fallback = null;
        for (ResolveInfo r : list) {
            if (r.serviceInfo == null || c.getPackageName().equals(r.serviceInfo.packageName)) continue;
            ComponentName cn = new ComponentName(r.serviceInfo.packageName, r.serviceInfo.name);
            if ("com.google.android.googlequicksearchbox".equals(cn.getPackageName())) return cn;
            if (fallback == null) fallback = cn;
        }
        return fallback;
    }

    @Override protected void onStartListening(Intent intent, Callback callback) {
        release();
        ComponentName delegate = findDelegate(this);
        if (delegate == null) { send(() -> callback.error(SpeechRecognizer.ERROR_CLIENT)); return; }
        inner = SpeechRecognizer.createSpeechRecognizer(this, delegate);
        inner.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { send(() -> callback.readyForSpeech(b)); }
            @Override public void onBeginningOfSpeech() { send(callback::beginningOfSpeech); }
            @Override public void onRmsChanged(float v) { send(() -> callback.rmsChanged(v)); }
            @Override public void onBufferReceived(byte[] b) { send(() -> callback.bufferReceived(b)); }
            @Override public void onEndOfSpeech() { send(callback::endOfSpeech); }
            @Override public void onError(int e) { send(() -> callback.error(e)); release(); }
            @Override public void onResults(Bundle b) { send(() -> callback.results(b)); release(); }
            @Override public void onPartialResults(Bundle b) { send(() -> callback.partialResults(b)); }
            @Override public void onEvent(int t, Bundle b) { }
        });
        try { inner.startListening(intent); }
        catch (Exception e) { send(() -> callback.error(SpeechRecognizer.ERROR_CLIENT)); release(); }
    }

    @Override protected void onStopListening(Callback callback) { if (inner != null) { try { inner.stopListening(); } catch (Exception ignored) { } } }
    @Override protected void onCancel(Callback callback) { release(); }
    @Override public void onDestroy() { release(); super.onDestroy(); }

    private void release() { SpeechRecognizer r = inner; inner = null; if (r != null) { try { r.destroy(); } catch (Exception ignored) { } } }

    private interface Call { void run() throws RemoteException; }
    private static void send(Call c) { try { c.run(); } catch (RemoteException | RuntimeException ignored) { } }
}
