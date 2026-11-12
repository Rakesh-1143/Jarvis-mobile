package com.growthos.jarvis;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Android WebView does not support the Web Speech API (webkitSpeechRecognition),
 * so the in-app mic uses Android's own SpeechRecognizer and sends results to the page
 * as "jarvis-native-speech" events: {type:"start"|"result"|"error"|"end", text, error}.
 */
final class SpeechBridge {
    interface Sink { void send(String json); }
    private final Activity activity;
    private final Sink sink;
    private SpeechRecognizer recognizer;
    private boolean active;
    private TextToSpeech tts;
    private boolean ttsReady;
    private String pendingText, pendingLang;

    SpeechBridge(Activity activity, Sink sink) { this.activity = activity; this.sink = sink; }

    boolean isAvailable() { return SpeechRecognizer.isRecognitionAvailable(activity); }

    /** Must run on the UI thread. */
    void start(String lang) {
        if (!isAvailable()) { emit("error", null, "service-not-allowed"); emit("end", null, null); return; }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
            recognizer.setRecognitionListener(new Listener());
        }
        if (active) { try { recognizer.cancel(); } catch (Exception ignored) { } }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        String l = (lang == null || lang.trim().isEmpty()) ? "en-IN" : lang.trim();
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, l);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, l);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.getPackageName());
        try { active = true; recognizer.startListening(i); }
        catch (Exception e) { active = false; emit("error", null, "audio-capture"); emit("end", null, null); }
    }

    /** Must run on the UI thread. */
    void stop() { if (recognizer != null && active) { try { recognizer.stopListening(); } catch (Exception ignored) { } } }

    /** Speaks text with Android TextToSpeech; sends {type:"tts-end"} when done. */
    void speak(String text, String lang) {
        if (text == null || text.trim().isEmpty()) { emit("tts-end", null, null); return; }
        if (tts == null) {
            pendingText = text; pendingLang = lang;
            tts = new TextToSpeech(activity, status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (!ttsReady) { pendingText = null; emit("tts-end", null, null); return; }
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { emit("tts-end", null, null); }
                    @Override public void onError(String id) { emit("tts-end", null, null); }
                    @Override public void onStop(String id, boolean interrupted) { emit("tts-end", null, null); }
                });
                if (pendingText != null) { String t = pendingText; pendingText = null; say(t, pendingLang); }
            });
            return;
        }
        if (!ttsReady) { pendingText = text; pendingLang = lang; return; }
        say(text, lang);
    }

    void stopSpeaking() { pendingText = null; if (tts != null && ttsReady) tts.stop(); }

    private void say(String text, String lang) {
        Locale locale = Locale.forLanguageTag(lang == null || lang.trim().isEmpty() ? "en-IN" : lang.trim());
        int r = tts.setLanguage(locale);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(new Locale("en", "IN"));
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kalki-reply");
    }

    void destroy() { if (tts != null) { tts.stop(); tts.shutdown(); tts = null; } active = false; if (recognizer != null) { try { recognizer.destroy(); } catch (Exception ignored) { } recognizer = null; } }

    private void emit(String type, String text, String error) {
        try {
            JSONObject o = new JSONObject(); o.put("type", type);
            if (text != null) o.put("text", text);
            if (error != null) o.put("error", error);
            sink.send(o.toString());
        } catch (Exception ignored) { }
    }

    private static String errorCode(int e) {
        switch (e) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "no-speech";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "not-allowed";
            case SpeechRecognizer.ERROR_AUDIO: return "audio-capture";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER: return "network";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "busy";
            default: return "unknown";
        }
    }

    private final class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle b) { emit("start", null, null); }
        @Override public void onResults(Bundle b) {
            active = false;
            ArrayList<String> list = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String text = list == null || list.isEmpty() || list.get(0) == null ? "" : list.get(0).trim();
            if (!text.isEmpty()) emit("result", text, null);
            emit("end", null, null);
        }
        @Override public void onError(int e) {
            boolean wasActive = active; active = false;
            if (e == SpeechRecognizer.ERROR_CLIENT && !wasActive) return; // our own cancel/stop
            emit("error", null, errorCode(e)); emit("end", null, null);
        }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onEndOfSpeech() { }
        @Override public void onPartialResults(Bundle b) { }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEvent(int t, Bundle b) { }
        @Override public void onRmsChanged(float r) { }
    }
}
