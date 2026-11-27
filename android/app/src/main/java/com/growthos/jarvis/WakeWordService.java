package com.growthos.jarvis;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The only part of Jarvis that uses the microphone.
 *
 * While waiting, audio is checked on the phone by {@link WakeWordEngine} in 80 ms pieces and
 * thrown away: never saved, never sent. After "Hey Jarvis" the mic is released, Jarvis says
 * "Yes Boss?", and the phone's speech recognizer hears one command. Then waiting starts again.
 * A visible notification (with Pause and Stop) is shown the whole time.
 */
public final class WakeWordService extends Service {
    private static final String TAG = "JarvisWake";
    // Kept for MainActivity's old receiver; no longer sent.
    public static final String ACTION_COMMAND = "com.growthos.jarvis.WAKE_COMMAND";
    public static final String EXTRA_COMMAND = "command";
    public static final String ACTION_STOP = "com.growthos.jarvis.STOP_WAKE";
    public static final String ACTION_PAUSE = "com.growthos.jarvis.PAUSE_WAKE";
    public static final String ACTION_RESUME = "com.growthos.jarvis.RESUME_WAKE";
    /** Skip the wake word and take one command now (assist gesture). */
    public static final String ACTION_LISTEN_NOW = "com.growthos.jarvis.LISTEN_NOW";

    static final String PREFS = "jarvis_settings";
    static final String KEY_ENABLED = "wake_enabled", KEY_PAUSED_UNTIL = "paused_until", KEY_THRESHOLD = "wake_threshold";
    static final String KEY_LAST_WAKE = "last_wake_at", KEY_LAST_SCORE = "last_wake_score", KEY_LAST_COMMAND = "last_command", KEY_COMMAND_LOG = "command_log";
    static final String KEY_PHRASE = "wake_phrase", KEY_TRAINED_AT = "voice_trained_at";
    static final String KEY_STARTED_AT = "listening_started_at", KEY_START_BATTERY = "listening_start_battery", KEY_CPU = "wake_cpu_ms", KEY_ERROR = "last_error";
    private static final String CHANNEL = "jarvis_wake_word";
    private static final int NOTIFICATION_ID = 812;
    private static final long PAUSE_MS = 30 * 60 * 1000L, COMMAND_TIMEOUT_MS = 12000L, RESPONSE_WATCHDOG_MS = 30000L;
    private static final float DEFAULT_THRESHOLD = 0.5f;

    /** Read by the setup screen. */
    static volatile boolean running;
    private static volatile WakeWordService instance;

    /** Speaks a short note (for example "Message sent") if Jarvis is running. */
    static void announce(String text) {
        WakeWordService w = instance;
        if (w == null) return;
        w.main.post(() -> { if (w.ttsReady && w.tts != null && w.mode != Mode.RESPONDING) w.tts.speak(text, TextToSpeech.QUEUE_ADD, new Bundle(), "jarvis-note"); });
    }
    static volatile String state = "Off";
    /** Live values for the setup screen (same process; nothing is stored). */
    static volatile float liveDb = -120f, liveScore, bestScore, liveMatch, bestMatch;
    static volatile long bestScoreAt;
    static volatile String engineInfo = "";

    private enum Mode { WAITING, RESPONDING, PAUSED }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Mode mode = Mode.WAITING;
    private volatile boolean stopping;
    private Thread audioThread;
    private WakeWordEngine engine;
    private TextToSpeech tts;
    private boolean ttsReady;
    private SpeechRecognizer recognizer;
    private Runnable afterSpeech;
    private boolean commandOnly;

    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, MODE_PRIVATE); }

    public static boolean isEnabled(Context c) { return prefs(c).getBoolean(KEY_ENABLED, false); }

    /** Turns always-on listening on or off and starts or stops the service. */
    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_ENABLED, on).putLong(KEY_PAUSED_UNTIL, 0).apply();
        if (on) start(c, null); else c.stopService(new Intent(c, WakeWordService.class));
    }

    /** Starts the service if allowed. Returns false when Android refuses. */
    public static boolean start(Context c, String action) {
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false;
        Intent i = new Intent(c, WakeWordService.class);
        if (action != null) i.setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "start refused", e);
            prefs(c).edit().putString(KEY_ERROR, "Android did not allow starting: " + e.getClass().getSimpleName()).apply();
            return false;
        }
    }

    static final String KEY_GREET = "greet_unlock", KEY_SAVE_BATTERY = "save_battery", KEY_SCREEN_OFF = "listen_screen_off";
    static final int LOW_BATTERY = 20;
    private boolean powerHold;

    /** Why listening should sleep now to save battery, or null. The assist gesture still works while sleeping. */
    String powerReason() {
        SharedPreferences p = prefs(this);
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (!p.getBoolean(KEY_SCREEN_OFF, true) && pm != null && !pm.isInteractive()) return "screen is off";
        if (!p.getBoolean(KEY_SAVE_BATTERY, true)) return null;
        if (pm != null && pm.isPowerSaveMode()) return "Battery Saver is on";
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        if (bm != null && !bm.isCharging() && bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) <= LOW_BATTERY) return "battery is " + LOW_BATTERY + "% or less";
        return null;
    }

    private final android.content.BroadcastReceiver powerReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { main.post(WakeWordService.this::checkPower); }
    };

    private void checkPower() {
        if (stopping) return;
        String why = powerReason();
        if (why != null && mode == Mode.WAITING) {
            powerHold = true;
            mode = Mode.PAUSED; // the audio loop stops and releases the microphone
            state = "Sleeping: " + why;
            updateNotification("Sleeping to save battery (" + why + "). Long-press Home to talk.", false);
        } else if (why == null && powerHold && mode == Mode.PAUSED) {
            powerHold = false;
            startWaiting();
        }
    }
    private long lastGreetAt;

    /** "Hello Boss!" once per unlock, only when it won't disturb: not in a call, not silent/vibrate, no music playing. */
    private final android.content.BroadcastReceiver unlockReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!Intent.ACTION_USER_PRESENT.equals(i.getAction()) || !prefs(c).getBoolean(KEY_GREET, true)) return;
            long now = SystemClock.elapsedRealtime();
            if (now - lastGreetAt < 30_000L || mode == Mode.RESPONDING || !ttsReady || tts == null) return;
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null || am.getMode() != AudioManager.MODE_NORMAL || am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL || am.isMusicActive()) return;
            lastGreetAt = now;
            String hello = "Hello " + prefs(c).getString(KEY_NAME, "Boss") + "!";
            if (MyVoice.on(c)) MyVoice.speak(c, hello, prefs(c).getFloat(KEY_RATE, 1.0f), ok -> { if (!ok && tts != null) tts.speak(hello, TextToSpeech.QUEUE_ADD, new Bundle(), "jarvis-greet"); });
            else tts.speak(hello, TextToSpeech.QUEUE_ADD, new Bundle(), "jarvis-greet");
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        MyVoice.warmUp(this);
        try { registerReceiver(unlockReceiver, new android.content.IntentFilter(Intent.ACTION_USER_PRESENT)); } catch (Exception ignored) { }
        try {
            android.content.IntentFilter f = new android.content.IntentFilter();
            for (String a : new String[]{android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED, Intent.ACTION_BATTERY_CHANGED, Intent.ACTION_POWER_CONNECTED,
                    Intent.ACTION_POWER_DISCONNECTED, Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF}) f.addAction(a);
            registerReceiver(powerReceiver, f);
        } catch (Exception ignored) { }
        createChannel();
        if (!goForeground(notification("Starting…", false))) { stopSelf(); return; }
        running = true;
        stopping = false;
        prefs(this).edit().putLong(KEY_STARTED_AT, System.currentTimeMillis()).putInt(KEY_START_BATTERY, batteryPercent()).remove(KEY_ERROR).apply();
        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (!ttsReady) return;
            int r = tts.setLanguage(new Locale("en", "IN"));
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US);
            applySavedVoice();
            tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { main.post(WakeWordService.this::speechFinished); }
                @Override public void onError(String id) { main.post(WakeWordService.this::speechFinished); }
            });
        });
    }

    private boolean goForeground(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTIFICATION_ID, n);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "startForeground refused", e);
            prefs(this).edit().putString(KEY_ERROR, "Android did not allow the microphone service: " + e.getClass().getSimpleName()).apply();
            return false;
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) return START_NOT_STICKY;
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) { prefs(this).edit().putBoolean(KEY_ENABLED, false).apply(); stopSelf(); return START_NOT_STICKY; }
        if (ACTION_PAUSE.equals(action)) { pause(); return START_STICKY; }
        if (ACTION_RESUME.equals(action)) { prefs(this).edit().putLong(KEY_PAUSED_UNTIL, 0).apply(); mode = Mode.WAITING; }
        if (ACTION_LISTEN_NOW.equals(action)) {
            commandOnly = !isEnabled(this);
            if (mode != Mode.RESPONDING) onWake(-1f);
            return START_NOT_STICKY;
        }
        if (!isEnabled(this)) { stopSelf(); return START_NOT_STICKY; }
        long pausedUntil = prefs(this).getLong(KEY_PAUSED_UNTIL, 0);
        if (pausedUntil > System.currentTimeMillis()) { pause(); return START_STICKY; }
        if (mode != Mode.RESPONDING) startWaiting();
        return START_STICKY;
    }

    // ---------- waiting for "Hey Jarvis" ----------

    private void startWaiting() {
        pending.clear();
        if (stopping) return;
        if (!isEnabled(this)) { stopSelf(); return; }
        mode = Mode.WAITING;
        String why = powerReason();
        if (why != null) { mode = Mode.WAITING; checkPower(); return; }
        powerHold = false;
        state = "Listening for \"" + prefs(this).getString(KEY_PHRASE, "Hey Jarvis") + "\"";
        updateNotification("Say \"" + prefs(this).getString(KEY_PHRASE, "Hey Jarvis") + "\". Audio stays on this phone.", false);
        if (audioThread != null && audioThread.isAlive()) return;
        audioThread = new Thread(this::audioLoop, "jarvis-wake");
        audioThread.setPriority(Thread.NORM_PRIORITY - 1);
        audioThread.start();
    }

    private void audioLoop() {
        try {
            if (engine == null) engine = new WakeWordEngine(asset("wakeword/melspectrogram.onnx"), asset("wakeword/embedding_model.onnx"), asset("wakeword/hey_jarvis_v0.1.onnx"));
            else engine.reset();
            engineInfo = engine.backend;
        } catch (Throwable t) {
            fail("Wake word engine could not start: " + t.getClass().getSimpleName());
            return;
        }
        int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, WakeWordEngine.CHUNK * 2 * 4));
        } catch (SecurityException e) { fail("Microphone permission is missing"); return; }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); retryLater("Microphone is busy. Trying again."); return; }
        float threshold = prefs(this).getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD);
        // Personal check from "Train my voice" (if done). Sensitivity loosens or tightens it.
        TemplateMatcher personal = TemplateMatcher.load(TemplateMatcher.file(this));
        float personalLimit = personal == null ? 0f : personal.threshold * (threshold <= 0.36f ? 1.25f : threshold >= 0.64f ? 0.85f : 1f);
        int span = personal == null ? 0 : personal.span();
        // The built-in model only knows "Hey Jarvis"; for another trained phrase only the personal check is used.
        boolean useBase = AppMatcher.norm(prefs(this).getString(KEY_PHRASE, "Hey Jarvis")).replace(" ", "").equals("heyjarvis");
        short[] chunk = new short[WakeWordEngine.CHUNK];
        long cpuNanos = 0, chunks = 0;
        minuteModelNanos = minuteMatchNanos = 0; minuteSkipped = 0; java.util.Arrays.fill(minuteDb, 0); engine.takeTimes();
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        try {
            rec.startRecording();
            while (!stopping && mode == Mode.WAITING) {
                int n = rec.read(chunk, 0, chunk.length, AudioRecord.READ_BLOCKING);
                if (n < 0) { retryLater("Microphone stopped (" + n + "). Trying again."); return; }
                if (n != chunk.length) continue;
                long t0 = SystemClock.elapsedRealtimeNanos();
                float score = engine.process(chunk);
                liveDb = engine.lastRms <= 1f ? -90f : (float) (20 * Math.log10(engine.lastRms / 32768.0));
                liveScore = score;
                long t1 = SystemClock.elapsedRealtimeNanos();
                float match = 0f;
                if (personal != null && !engine.lastSkipped) {
                    float d = personal.distance(engine.lastFrames(span));
                    match = d <= 0 ? 2f : Math.min(2f, personalLimit / d);
                }
                liveMatch = match;
                long t2 = SystemClock.elapsedRealtimeNanos();
                cpuNanos += t2 - t0;
                minuteModelNanos += t1 - t0;
                minuteMatchNanos += t2 - t1;
                if (engine.lastSkipped) minuteSkipped++;
                minuteDb[Math.max(0, Math.min(minuteDb.length - 1, (int) ((liveDb + 90f) / 3f)))]++;
                if (++chunks % 750 == 0) { // about once a minute
                    prefs(this).edit().putFloat(KEY_CPU, cpuNanos / 1e6f / chunks).apply();
                    engineInfo = engine.backend + String.format(Locale.US, " · %.0f%% silence skipped", engine.skippedShare() * 100);
                    // One line a minute so battery use can be checked with "adb logcat -s JarvisWake" (no audio, just numbers).
                    long[] mt = engine.takeTimes();
                    Log.i(TAG, String.format(Locale.US, "last minute: models %.1f ms (mel %.1f, embedding %.1f, wake %.1f) + voice check %.1f ms per 80 ms chunk, %d%% silence skipped, loudness p20 %d / p50 %d / p90 %d dBFS, background %.0f dBFS",
                            minuteModelNanos / 1e6 / 750, mt[0] / 1e6 / 750, mt[1] / 1e6 / 750, mt[2] / 1e6 / 750, minuteMatchNanos / 1e6 / 750, minuteSkipped * 100 / 750,
                            dbPercentile(20), dbPercentile(50), dbPercentile(90), engine.backgroundDb()));
                    minuteModelNanos = minuteMatchNanos = 0; minuteSkipped = 0; java.util.Arrays.fill(minuteDb, 0);
                }
                long now = SystemClock.elapsedRealtime();
                if (now - bestScoreAt > 10000) { bestScore = 0; bestMatch = 0; bestScoreAt = now; }
                bestScore = Math.max(bestScore, score);
                bestMatch = Math.max(bestMatch, match);
                boolean woke = (useBase && score >= threshold) || match >= 1f;
                if (!woke) continue;
                score = Math.max(score, Math.min(1f, match));
                if (!inCall(am)) {
                    final float s = score;
                    mode = Mode.RESPONDING;
                    main.post(() -> onWake(s));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "audio loop", t);
            retryLater("Listening error. Trying again.");
        } finally {
            try { rec.stop(); } catch (Exception ignored) { }
            rec.release();
        }
    }

    private long minuteModelNanos, minuteMatchNanos;
    private int minuteSkipped;
    /** Loudness of the last minute's chunks in 3 dB steps from -90 dBFS. */
    private final int[] minuteDb = new int[31];
    private int dbPercentile(int pct) {
        int total = 0; for (int c : minuteDb) total += c;
        int need = Math.max(1, total * pct / 100), seen = 0;
        for (int i = 0; i < minuteDb.length; i++) { seen += minuteDb[i]; if (seen >= need) return -90 + i * 3; }
        return 0;
    }

    private int retries;
    private void retryLater(String why) {
        main.post(() -> {
            if (stopping) return;
            if (++retries > 5) { fail(why + " Gave up after 5 tries."); return; }
            updateNotification(why, false);
            main.postDelayed(this::startWaiting, 5000L * retries);
        });
    }

    private void fail(String why) {
        prefs(this).edit().putString(KEY_ERROR, why).apply();
        main.post(() -> { state = "Stopped: " + why; stopSelf(); });
    }

    private static boolean inCall(AudioManager am) {
        if (am == null) return false;
        int m = am.getMode();
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE;
    }

    private void pause() {
        long until = System.currentTimeMillis() + PAUSE_MS;
        prefs(this).edit().putLong(KEY_PAUSED_UNTIL, until).apply();
        mode = Mode.PAUSED;
        state = "Paused until " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(until));
        updateNotification(state + ". The microphone is off.", true);
        main.removeCallbacks(resumeAfterPause);
        main.postDelayed(resumeAfterPause, PAUSE_MS);
    }

    private final Runnable resumeAfterPause = () -> { prefs(this).edit().putLong(KEY_PAUSED_UNTIL, 0).apply(); startWaiting(); };

    // ---------- after "Hey Jarvis" ----------

    private final Runnable watchdog = () -> { Log.w(TAG, "response watchdog"); remember("", "stopped waiting after 30 s"); endInteraction(); };

    /** score < 0 means started by the assist gesture, not the wake word. */
    private void onWake(float score) {
        mode = Mode.RESPONDING;
        state = "Listening to your command";
        retries = 0;
        followUps = 0;
        pending.clear();
        if (score >= 0) prefs(this).edit().putLong(KEY_LAST_WAKE, System.currentTimeMillis()).putFloat(KEY_LAST_SCORE, score).apply();
        updateNotification("Listening to your command…", false);
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, RESPONSE_WATCHDOG_MS);
        // Give the audio thread time to release the microphone before the recognizer needs it.
        say("Yes Boss?", this::listenForCommand);
    }

    private void listenForCommand() { listen(this::handleCommand); }

    /** Listens once and passes the recognizer's guesses to the handler. */
    private void listen(java.util.function.Consumer<List<String>> handler) { listen(handler, 0, false); }

    private void listen(java.util.function.Consumer<List<String>> handler, int attempt) { listen(handler, attempt, false); }

    /**
     * Google's recognizer sometimes ends a new session at once with "no match" when it starts right
     * after Jarvis spoke, or misses a short answer like "yes". Such misses are retried twice.
     */
    /** quiet = a miss ends the conversation silently (used for follow-ups). */
    private void listen(java.util.function.Consumer<List<String>> handler, int attempt, boolean quiet) {
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, RESPONSE_WATCHDOG_MS);
        try {
            ComponentName delegate = JarvisRecognitionService.findDelegate(this);
            recognizer = delegate != null ? SpeechRecognizer.createSpeechRecognizer(this, delegate) : SpeechRecognizer.createSpeechRecognizer(this);
        } catch (Exception e) { say("Sorry Boss, speech recognition is not available.", this::endInteraction); return; }
        final long startedAt = SystemClock.elapsedRealtime();
        final boolean[] began = {false};
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onResults(Bundle b) {
                ArrayList<String> r = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                releaseRecognizer();
                handler.accept(r == null ? new ArrayList<>() : r);
            }
            @Override public void onError(int e) {
                releaseRecognizer();
                boolean miss = e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                if (miss && attempt < 2) {
                    long ms = SystemClock.elapsedRealtime() - startedAt;
                    remember("", "speech miss " + e + " after " + ms + " ms" + (began[0] ? " (heard voice)" : "") + ", listening again");
                    if (began[0]) say("Sorry Boss, please say that again.", () -> listen(handler, attempt + 1, quiet));
                    else main.postDelayed(() -> listen(handler, attempt + 1, quiet), 400);
                    return;
                }
                String msg = e == SpeechRecognizer.ERROR_NETWORK || e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || e == SpeechRecognizer.ERROR_SERVER
                        ? "Boss, internet is unavailable. I can still perform offline actions once speech works offline."
                        : e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ? "Sorry Boss, I didn't catch that." : "Sorry Boss, I couldn't listen just now.";
                if (quiet && miss) { followUps = 0; endInteraction(); return; }
                remember("", "speech error " + e + " (" + speechError(e) + ")");
                say(msg, WakeWordService.this::endInteraction);
            }
            @Override public void onReadyForSpeech(Bundle b) { }
            @Override public void onBeginningOfSpeech() { began[0] = true; }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onPartialResults(Bundle b) { }
            @Override public void onEvent(int t, Bundle b) { }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        try { recognizer.startListening(i); }
        catch (Exception e) { releaseRecognizer(); say("Sorry Boss, I couldn't listen just now.", this::endInteraction); return; }
        main.postDelayed(commandTimeout, COMMAND_TIMEOUT_MS);
    }

    private final Runnable commandTimeout = () -> { if (recognizer != null) { remember("", "no answer from speech recognizer in 12 s"); releaseRecognizer(); endInteraction(); } };

    private static String speechError(int e) {
        switch (e) {
            case SpeechRecognizer.ERROR_NO_MATCH: return "no words recognized";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "no speech heard";
            case SpeechRecognizer.ERROR_NETWORK: case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: case SpeechRecognizer.ERROR_SERVER: return "network";
            case SpeechRecognizer.ERROR_AUDIO: return "microphone busy or audio error";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "recognizer busy";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "permission";
            case SpeechRecognizer.ERROR_CLIENT: return "client";
            default: return "other";
        }
    }

    private void releaseRecognizer() {
        main.removeCallbacks(commandTimeout);
        SpeechRecognizer r = recognizer; recognizer = null;
        if (r != null) { try { r.cancel(); } catch (Exception ignored) { } try { r.destroy(); } catch (Exception ignored) { } }
    }

    /** The recognizer gives up to 3 guesses of what was said; use the first one that makes sense. */
    private void handleCommand(List<String> guesses) {
        String text = guesses.isEmpty() || guesses.get(0) == null ? "" : guesses.get(0);
        VoiceCommand c = VoiceCommand.parse(text);
        List<AppMatcher.App> apps = null;
        if (c.type == VoiceCommand.Type.OPEN_APP || c.type == VoiceCommand.Type.SEARCH || c.type == VoiceCommand.Type.UNKNOWN) {
            apps = launcherApps();
            for (String g : guesses) {
                VoiceCommand gc = VoiceCommand.parse(g);
                if (gc.type == VoiceCommand.Type.OPEN_APP && AppMatcher.match(gc.target, apps) != null) { c = gc; text = g; break; }
                if (c.type == VoiceCommand.Type.UNKNOWN && gc.type != VoiceCommand.Type.UNKNOWN) { c = gc; text = g; }
            }
        }
        execute(text, c, guesses, apps, false);
    }

    /** Runs one allowed command. fromBrain = the command came from Gemini's reading of the words. */
    private void execute(final String heard, VoiceCommand c, List<String> guesses, List<AppMatcher.App> apps, boolean fromBrain) {
        if (apps == null) apps = launcherApps();
        if (VoiceCommand.isPrivate(c.type) && phoneLocked()) {
            remember(heard, "refused on lock screen: " + c.type);
            say("Boss, please unlock your phone first. I don't do that on the lock screen.", this::endInteraction);
            return;
        }
        switch (c.type) {
            case BRIEFING: {
                remember(heard, "briefing");
                final String base = Extras.briefing(this, prefs(this).getString(KEY_NAME, "Boss"));
                if (!GeminiClient.online(this)) { say(base, this::endInteraction); return; }
                updateNotification("Preparing your briefing…", false);
                final String city = prefs(this).getString(KEY_CITY, "Hyderabad");
                new Thread(() -> {
                    String w = Weather.now(city);
                    main.post(() -> { if (mode == Mode.RESPONDING) say(base + " " + w, this::endInteraction); });
                }, "jarvis-briefing").start();
                return;
            }
            case NEXT_ALARM: { String r = Extras.nextAlarm(this); remember(heard, "next alarm"); say(r, this::endInteraction); return; }
            case CALENDAR: {
                String r = Extras.calendar(this, "tomorrow".equals(c.target) ? 1 : 0);
                remember(heard, "calendar " + c.target);
                say(r, this::endInteraction);
                return;
            }
            case DND: {
                boolean on = "on".equals(c.target);
                String r = Extras.dnd(this, on);
                remember(heard, "do not disturb " + c.target + (r == null ? " (needs access)" : ""));
                if (r != null) { say(r, this::endInteraction); return; }
                say("Boss, turn on notification reading in Jarvis setup so I can switch Do Not Disturb. Opening the setting now.",
                        () -> { AppOpener.open(this, new Intent("android.settings.ZEN_MODE_SETTINGS")); endInteraction(); });
                return;
            }
            case RINGER: {
                String r = Extras.ringer(this, c.target);
                remember(heard, "ringer " + c.target + (r == null ? " (needs access)" : ""));
                say(r != null ? r : "Boss, Android needs Do Not Disturb access for that. Turn on notification reading in Jarvis setup.", this::endInteraction);
                return;
            }
            case CALCULATE: {
                String r = Calc.answer(c.message);
                remember(heard, "calculated " + c.message);
                say(r == null ? "Boss, I couldn't work that out." : r, this::endInteraction);
                return;
            }
            case MISSED_CALLS: { String r = Extras.speakMissed(this); remember(heard, "missed calls"); say(r, this::endInteraction); return; }
            case CALL_BACK: {
                if (!Extras.has(this, Manifest.permission.READ_CALL_LOG)) { remember(heard, "call history permission needed"); say("Boss, please allow call history in Jarvis setup first.", this::endInteraction); return; }
                String[] last = Extras.lastCaller(this);
                if (last == null) { remember(heard, "no recent caller"); say("Boss, I don't see any recent calls.", this::endInteraction); return; }
                callNumber(heard, last[0], last[1]);
                return;
            }
            case REMEMBER: {
                try {
                    boolean ok = Memory.add(this, c.message);
                    remember(heard, ok ? "remembered a fact" : "refused to store a secret");
                    say(ok ? "Okay Boss, I'll remember that." : "Boss, I don't store passwords, PINs, codes or bank details.", this::endInteraction);
                } catch (Exception e) { remember(heard, "memory failed: " + e); say("Boss, I couldn't save that.", this::endInteraction); }
                return;
            }
            case FORGET: {
                if ("everything".equals(c.target)) {
                    say("Forget everything you told me to remember, Boss?", () -> listen(r -> {
                        boolean yes = false; for (String g : r) if (g != null && VoiceCommand.isYes(g)) { yes = true; break; }
                        if (yes) { Memory.clear(this); GeminiClient.forgetConversation(); remember(heard, "memory cleared"); say("Done, Boss. I've forgotten everything.", this::endInteraction); }
                        else { remember(heard, "memory kept"); say("Okay Boss, I'll keep it.", this::endInteraction); }
                    }));
                    return;
                }
                int n;
                try { n = Memory.forget(this, c.target); } catch (Exception e) { n = 0; }
                remember(heard, "forgot " + n + " fact(s)");
                say(n == 0 ? "Boss, I don't have anything saved about " + c.target + "." : "Okay Boss, forgotten.", this::endInteraction);
                return;
            }
            case READ_MEMORY: { remember(heard, "read memory"); say(Memory.speak(this), this::endInteraction); return; }
            case REPEAT:
                remember(heard, "repeat");
                say(lastSpoken.isEmpty() ? "Boss, I haven't said anything yet." : lastSpoken, this::endInteraction);
                return;
            case SCREENSHOT: case LOCK: {
                if (!WhatsAppSender.running()) { remember(heard, "Jarvis control is off"); say(CONTROL_OFF, this::endInteraction); return; }
                final boolean shot = c.type == VoiceCommand.Type.SCREENSHOT;
                remember(heard, shot ? "screenshot" : "lock");
                // Speak first so the screenshot doesn't catch Jarvis's own notification change.
                say(shot ? "Taking a screenshot, Boss." : "Locking the phone, Boss.", () -> main.postDelayed(() -> {
                    boolean ok = WhatsAppSender.global(shot ? android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
                            : android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                    if (!ok) remember(heard, "failed: " + (shot ? "screenshot" : "lock"));
                    endInteraction();
                }, 400));
                return;
            }
            case OPEN_APP: {
                AppMatcher.App app = AppMatcher.match(c.target, apps);
                if (app == null && !fromBrain && GeminiClient.hasKey(this) && GeminiClient.online(this)) { brain(heard, guesses, apps); return; }
                if (app == null) { remember(heard, "app not found: " + c.target); say("Boss, I could not find " + c.target + ". That application is not installed.", this::endInteraction); return; }
                Intent launch = getPackageManager().getLaunchIntentForPackage(app.packageName);
                if (launch == null) { remember(heard, app.label + " cannot be opened"); say("Boss, " + app.label + " cannot be opened.", this::endInteraction); return; }
                remember(heard, "opened " + app.label);
                say("Opening " + app.label + ".", () -> { AppOpener.open(this, launch); endInteraction(); });
                return;
            }
            case SCROLL: {
                boolean down = !"up".equals(c.target);
                if (!WhatsAppSender.running()) { remember(heard, "scroll: Jarvis control is off"); say("Boss, turn on Jarvis control in Jarvis setup so I can scroll.", this::endInteraction); return; }
                boolean ok = WhatsAppSender.scroll(down);
                remember(heard, ok ? "scrolled " + c.target : "could not scroll; screen: " + WhatsAppSender.describeScreen());
                if (ok) endInteraction(); else say("Boss, I can't scroll here.", this::endInteraction);
                return;
            }
            case PLAY_ITEM: {
                if (!WhatsAppSender.running()) { remember(heard, "play: Jarvis control is off"); say("Boss, turn on Jarvis control in Jarvis setup so I can play videos.", this::endInteraction); return; }
                int n = Integer.parseInt(c.target);
                String title = WhatsAppSender.playVideo(n);
                remember(heard, title == null ? "no video found; screen: " + WhatsAppSender.describeScreen() : "played video " + n + ": " + title);
                if (title == null) say("Boss, I couldn't find a video on the screen. Open YouTube first.", this::endInteraction);
                else say("Playing " + (title.isEmpty() ? "the video" : title) + ".", this::endInteraction);
                return;
            }
            case MEDIA: {
                int key = "pause".equals(c.target) ? android.view.KeyEvent.KEYCODE_MEDIA_PAUSE : "next".equals(c.target) ? android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                        : "previous".equals(c.target) ? android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS : android.view.KeyEvent.KEYCODE_MEDIA_PLAY;
                remember(heard, "media " + c.target);
                // After Jarvis finishes speaking, so the video's sound focus is back.
                main.postDelayed(() -> {
                    AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                    if (am != null) {
                        am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key));
                        am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key));
                    }
                }, 300);
                endInteraction();
                return;
            }
            case BACK: case HOME: case RECENTS: case NOTIFICATIONS: {
                if (!WhatsAppSender.running()) { remember(heard, "Jarvis control is off"); say(CONTROL_OFF, this::endInteraction); return; }
                int g = c.type == VoiceCommand.Type.BACK ? android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                        : c.type == VoiceCommand.Type.HOME ? android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                        : c.type == VoiceCommand.Type.RECENTS ? android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                        : android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
                boolean ok = WhatsAppSender.global(g);
                remember(heard, ok ? "done: " + c.type : "failed: " + c.type);
                endInteraction();
                return;
            }
            case TAP: {
                if (!WhatsAppSender.running()) { remember(heard, "Jarvis control is off"); say(CONTROL_OFF, this::endInteraction); return; }
                String label = WhatsAppSender.tap(c.target);
                remember(heard, label == null ? "nothing called '" + c.target + "' on screen; screen: " + WhatsAppSender.describeScreen() : "tapped " + label);
                if (label == null) say("Boss, I can't find " + c.target + " on the screen.", this::endInteraction); else endInteraction();
                return;
            }
            case TYPE: {
                if (!WhatsAppSender.running()) { remember(heard, "Jarvis control is off"); say(CONTROL_OFF, this::endInteraction); return; }
                String r = WhatsAppSender.type(c.message);
                remember(heard, "type: " + r);
                switch (r) {
                    case "ok": endInteraction(); break;
                    case "password": say("Boss, I don't type into password boxes.", this::endInteraction); break;
                    case "none": say("Boss, there is no text box on the screen. Say tap search first.", this::endInteraction); break;
                    case "blocked": say("Boss, I don't control this app.", this::endInteraction); break;
                    default: say("Boss, I couldn't type there.", this::endInteraction);
                }
                return;
            }
            case READ_SCREEN: {
                if (!WhatsAppSender.running()) { remember(heard, "Jarvis control is off"); say(CONTROL_OFF, this::endInteraction); return; }
                String seen = WhatsAppSender.readScreen();
                remember(heard, seen == null ? "read: app not allowed" : "read screen");
                say(seen == null ? "Boss, I don't read this app." : seen.isEmpty() ? "Boss, I see no text on the screen." : seen, this::endInteraction);
                return;
            }
            case HELP:
                remember(heard, "help");
                say("Boss, I can open apps, search YouTube or Google, send WhatsApp messages, make phone or WhatsApp calls, set alarms, timers and reminders, "
                        + "take notes, tell the time, battery and weather, navigate, take a photo or screenshot, lock the phone, read notifications and missed calls, give your daily briefing, read your calendar, "
                        + "switch Do Not Disturb or silent mode, do maths, and tap, type or scroll on the screen. Tell me to remember things about you. "
                        + "Or just ask me anything, and ask follow-up questions without saying Hey Jarvis again.", this::endInteraction);
                return;
            case SET_NAME: {
                if ("city".equals(c.message)) {
                    prefs(this).edit().putString(KEY_CITY, c.target).apply();
                    remember(heard, "city set to " + c.target);
                    say("Okay Boss, I'll use " + c.target + " for the weather.", this::endInteraction);
                    return;
                }
                String nm = c.target.length() > 30 ? c.target.substring(0, 30) : c.target;
                nm = nm.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + nm.substring(1);
                prefs(this).edit().putString(KEY_NAME, nm).apply();
                remember(heard, "name set to " + nm);
                say("Okay, I'll call you " + nm + ".", this::endInteraction);
                return;
            }
            case CHANGE_VOICE: {
                if (!ttsReady || tts == null) { remember(heard, "voice: speech not ready"); endInteraction(); return; }
                List<android.speech.tts.Voice> vs = jarvisVoices();
                if ("default".equals(c.target) || vs.isEmpty()) {
                    prefs(this).edit().remove(KEY_VOICE).apply();
                    tts.setLanguage(new Locale("en", "IN"));
                    remember(heard, "voice reset");
                    say(vs.isEmpty() ? "Boss, this phone has only one voice for Indian English." : "Okay Boss, back to my normal voice.", this::endInteraction);
                    return;
                }
                String cur = prefs(this).getString(KEY_VOICE, "");
                int i = 0;
                for (int k = 0; k < vs.size(); k++) if (vs.get(k).getName().equals(cur)) { i = k + 1; break; }
                android.speech.tts.Voice v = vs.get(i % vs.size());
                tts.setVoice(v);
                prefs(this).edit().putString(KEY_VOICE, v.getName()).apply();
                remember(heard, "voice " + (i % vs.size() + 1) + " of " + vs.size() + ": " + v.getName());
                say("This is voice " + (i % vs.size() + 1) + " of " + vs.size() + ", Boss. Say change your voice again to hear the next one.", this::endInteraction);
                return;
            }
            case SPEECH_SPEED: {
                float r = prefs(this).getFloat(KEY_RATE, 1.0f);
                r = "faster".equals(c.target) ? Math.min(2.0f, r + 0.2f) : "slower".equals(c.target) ? Math.max(0.6f, r - 0.2f) : 1.0f;
                prefs(this).edit().putFloat(KEY_RATE, r).apply();
                remember(heard, "speech rate " + r);
                say("Okay Boss, is this better?", this::endInteraction);
                return;
            }
            case WHATSAPP_CALL:
                if (!fromBrain && unknownPerson(c, guesses)) { brain(heard, guesses, apps); return; }
                whatsappCall(heard, c);
                return;
            case REMINDER: {
                String task = c.message;
                if (c.target.startsWith("+")) {
                    int secs = Integer.parseInt(c.target.substring(1));
                    String len = secs % 3600 == 0 ? secs / 3600 + " hour" + (secs == 3600 ? "" : "s") : secs % 60 == 0 ? secs / 60 + " minute" + (secs == 60 ? "" : "s") : secs + " seconds";
                    remember(heard, "reminder in " + len + ": " + task);
                    say("Okay Boss, I'll remind you to " + task + " in " + len + ".", () -> { AppOpener.open(this, PhoneActions.timer(secs).putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, task)); endInteraction(); });
                } else {
                    String[] hm = c.target.split(":");
                    int h = Integer.parseInt(hm[0]), mi = Integer.parseInt(hm[1]);
                    String when = String.format(java.util.Locale.ENGLISH, "%d:%02d %s", h % 12 == 0 ? 12 : h % 12, mi, h < 12 ? "AM" : "PM");
                    remember(heard, "reminder " + when + ": " + task);
                    say("Okay Boss, I'll remind you to " + task + " at " + when + ".", () -> { AppOpener.open(this, PhoneActions.alarm(h, mi).putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, task)); endInteraction(); });
                }
                return;
            }
            case NOTE:
                try { Notes.add(this, c.message); remember(heard, "note saved"); say("Noted, Boss.", this::endInteraction); }
                catch (Exception e) { remember(heard, "note failed: " + e); say("Boss, I couldn't save the note.", this::endInteraction); }
                return;
            case READ_NOTES: remember(heard, "read notes"); say(Notes.speak(this, 5), this::endInteraction); return;
            case CLEAR_NOTES:
                say("Delete all your notes, Boss?", () -> listen(r -> {
                    boolean yes = false; for (String g : r) if (g != null && VoiceCommand.isYes(g)) { yes = true; break; }
                    if (yes) { Notes.clear(this); remember(heard, "notes deleted"); say("All notes deleted, Boss.", this::endInteraction); }
                    else { remember(heard, "notes kept"); say("Okay Boss, I kept them.", this::endInteraction); }
                }));
                return;
            case NAVIGATE: {
                final String place = c.target;
                remember(heard, "navigate to " + place);
                say("Starting directions to " + place + ".", () -> { AppOpener.open(this, PhoneActions.navigate(place)); endInteraction(); });
                return;
            }
            case WEATHER: {
                if (!GeminiClient.online(this)) { remember(heard, "weather offline"); say("Boss, internet is unavailable. I can still perform offline actions.", this::endInteraction); return; }
                String city = c.target.isEmpty() ? prefs(this).getString(KEY_CITY, "Hyderabad") : c.target;
                remember(heard, "weather " + city);
                updateNotification("Checking the weather…", false);
                new Thread(() -> { String w = Weather.now(city); main.post(() -> { if (mode == Mode.RESPONDING) say(w, this::endInteraction); }); }, "jarvis-weather").start();
                return;
            }
            case PHOTO: {
                boolean selfie = "selfie".equals(c.target);
                remember(heard, "photo" + (selfie ? " (selfie)" : ""));
                say(WhatsAppSender.running() ? "Smile, Boss. Three, two, one." : "Opening the camera, Boss.", () -> {
                    AppOpener.open(this, PhoneActions.camera(selfie));
                    // With Jarvis control on, press the shutter once the camera is ready.
                    if (WhatsAppSender.running()) main.postDelayed(() -> {
                        String t = WhatsAppSender.tap("shutter");
                        if (t == null) t = WhatsAppSender.tap("take photo");
                        if (t == null) t = WhatsAppSender.tap("capture");
                        remember(heard, t == null ? "camera opened; shutter not found: " + WhatsAppSender.describeScreen() : "photo taken (" + t + ")");
                    }, 3500);
                    endInteraction();
                });
                return;
            }
            case READ_NOTIFICATIONS: {
                if (!JarvisNotifications.connected()) { remember(heard, "notification access off"); say("Boss, turn on notification reading in Jarvis setup first.", this::endInteraction); return; }
                String r = JarvisNotifications.readRecent(this, c.target, 5);
                remember(heard, "read notifications" + (c.target.isEmpty() ? "" : " from " + c.target));
                say(r == null ? "Boss, I couldn't read notifications." : r, this::endInteraction);
                return;
            }
            case TIME: remember(heard, "time"); say(PhoneActions.time(), this::endInteraction); return;
            case DATE: remember(heard, "date"); say(PhoneActions.date(), this::endInteraction); return;
            case BATTERY: remember(heard, "battery"); say(PhoneActions.battery(this), this::endInteraction); return;
            case VOLUME: { String r = PhoneActions.volume(this, c.target); remember(heard, "volume " + c.target); say(r, this::endInteraction); return; }
            case TORCH: { String r = PhoneActions.torch(this, "on".equals(c.target)); remember(heard, r); say(r, this::endInteraction); return; }
            case ALARM: {
                String[] hm = c.target.split(":");
                int h = Integer.parseInt(hm[0]), mi = Integer.parseInt(hm[1]);
                String when = String.format(java.util.Locale.ENGLISH, "%d:%02d %s", h % 12 == 0 ? 12 : h % 12, mi, h < 12 ? "AM" : "PM");
                remember(heard, "alarm " + when);
                say("Alarm set for " + when + ", Boss.", () -> { AppOpener.open(this, PhoneActions.alarm(h, mi)); endInteraction(); });
                return;
            }
            case TIMER: {
                int secs = Integer.parseInt(c.target);
                String len = secs % 3600 == 0 ? secs / 3600 + " hour" + (secs == 3600 ? "" : "s") : secs % 60 == 0 ? secs / 60 + " minute" + (secs == 60 ? "" : "s") : secs + " seconds";
                remember(heard, "timer " + len);
                say("Timer for " + len + ", Boss.", () -> { AppOpener.open(this, PhoneActions.timer(secs)); endInteraction(); });
                return;
            }
            case SETTINGS: {
                final String page = c.target;
                remember(heard, "settings " + page);
                boolean toggle = heard.toLowerCase(java.util.Locale.ROOT).matches(".*\\b(turn|switch)\\b.*");
                say(toggle ? "Boss, Android doesn't let apps switch " + c.target + " by themselves. Opening its settings." : "Opening " + c.target + " settings.",
                        () -> { AppOpener.open(this, PhoneActions.settings(page)); endInteraction(); });
                return;
            }
            case ASK_APP: {
                AppMatcher.App ai = AppMatcher.match(c.target, apps == null ? launcherApps() : apps);
                if (ai == null) { remember(heard, c.target + " not installed"); say("Boss, " + c.target + " is not installed.", this::endInteraction); return; }
                Intent share = PhoneActions.shareText(ai.packageName, c.message);
                if (getPackageManager().resolveActivity(share, 0) == null) { remember(heard, ai.label + " can't take text"); say("Boss, " + ai.label + " doesn't accept questions from other apps.", this::endInteraction); return; }
                remember(heard, "asked " + ai.label + ": " + c.message);
                say("Asking " + ai.label + ".", () -> { AppOpener.open(this, share); endInteraction(); });
                return;
            }
            case CALL_CONTROL: {
                String r = PhoneActions.callControl(this, c.target);
                remember(heard, "call " + c.target + (r == null ? "" : ": " + r));
                if (r == null) endInteraction(); else say(r, this::endInteraction);
                return;
            }
            case CALL:
                if (!fromBrain && unknownPerson(c, guesses)) { brain(heard, guesses, apps); return; }
                callSomeone(heard, c.target);
                return;
            case SEARCH: {
                // "search latest movies" while YouTube is open searches YouTube.
                String fg = WhatsAppSender.foregroundApp();
                if (c.target.isEmpty() && WhatsAppSender.YOUTUBE.equals(fg)) c = VoiceCommand.parse("search " + c.message + " on youtube");
                else if (c.target.isEmpty()) remember(heard, "web search; screen: " + WhatsAppSender.describeScreen());
                AppMatcher.App app = c.target.isEmpty() ? null : AppMatcher.match(c.target, apps);
                if (!c.target.isEmpty() && app == null && !c.target.matches("(?:google|web|internet)")) {
                    remember(heard, "app not found: " + c.target);
                    say("Boss, I could not find " + c.target + ". That application is not installed.", this::endInteraction);
                    return;
                }
                Intent search = Searches.intent(this, app == null ? null : app.packageName, c.message);
                String where = app == null ? "Google" : app.label;
                if (search == null) {
                    Intent launch = getPackageManager().getLaunchIntentForPackage(app.packageName);
                    remember(heard, "no search in " + where + ", opened it");
                    say("Boss, I can't search inside " + where + " yet. Opening " + where + ".", () -> { if (launch != null) AppOpener.open(this, launch); endInteraction(); });
                    return;
                }
                remember(heard, "searched " + where + " for: " + c.message);
                say("Searching " + c.message + " on " + where + ".", () -> { AppOpener.open(this, search); endInteraction(); });
                return;
            }
            case SEND_MESSAGE:
                if (!fromBrain && unknownPerson(c, guesses)) { brain(heard, guesses, apps); return; }
                sendMessage(heard, c, guesses);
                return;
            case STOP_LISTENING:
                remember(heard, "stopped listening");
                prefs(this).edit().putBoolean(KEY_ENABLED, false).apply();
                say("Okay Boss. I have stopped listening.", this::stopSelf);
                return;
            case CANCEL:
                remember(heard, "cancelled");
                say("Okay Boss.", this::endInteraction);
                return;
            default: {
                // Just an app name, like "Hotstar".
                for (String g : guesses) {
                    if (g == null || g.trim().isEmpty() || g.trim().split("\\s+").length > 3) continue;
                    AppMatcher.App app = AppMatcher.match(g, apps);
                    Intent launch = app == null ? null : getPackageManager().getLaunchIntentForPackage(app.packageName);
                    if (launch != null) {
                        remember(g, "opened " + app.label);
                        say("Opening " + app.label + ".", () -> { AppOpener.open(this, launch); endInteraction(); });
                        return;
                    }
                }
                if (heard.isEmpty()) { remember(heard, "nothing understood"); say("Sorry Boss, I didn't catch that.", this::endInteraction); return; }
                if (fromBrain) { remember(heard, "not understood"); say("Sorry Boss, I didn't understand that.", this::endInteraction); return; }
                brain(heard, guesses, apps);
            }
        }
    }

    /**
     * The "brain": Gemini reads what was said (all recognizer guesses) and picks one of Jarvis's
     * allowed actions, or answers the question. Its choice is checked by VoiceCommand.fromBrain, and
     * risky actions still ask first (calls, messages). Only words and app names are sent, never audio.
     */
    private void brain(String heard, List<String> guesses, List<AppMatcher.App> apps) {
        if (!GeminiClient.hasKey(this)) { remember(heard, "not a known command (no Gemini key)"); say("Boss, I didn't understand that. Save your Gemini key in KALKI so I can understand more.", this::endInteraction); return; }
        if (!GeminiClient.online(this)) { remember(heard, "not a known command (offline)"); say("Boss, internet is unavailable. I can still perform offline actions.", this::endInteraction); return; }
        updateNotification("Thinking…", false);
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, RESPONSE_WATCHDOG_MS + 20000);
        List<String> names = new ArrayList<>();
        String fg = WhatsAppSender.foregroundApp(), onScreen = "";
        for (AppMatcher.App a : apps) {
            if (WhatsAppSender.blocked(a.packageName)) continue; // never tell Gemini which bank or payment apps you use
            names.add(a.label);
            if (a.packageName.equals(fg)) onScreen = a.label;
        }
        final String screenApp = onScreen;
        final List<AppMatcher.App> appList = apps;
        final String name = prefs(this).getString(KEY_NAME, "Boss"), city = prefs(this).getString(KEY_CITY, "Hyderabad");
        final String facts = phoneLocked() ? "" : Memory.forPrompt(this); // personal facts stay out while locked
        new Thread(() -> {
            GeminiClient.Brain r = GeminiClient.understand(this, guesses, names, screenApp, name, city, facts);
            String live = null;
            if (r != null && r.actions.isEmpty() && r.live) {
                main.post(() -> updateNotification("Searching the web…", false));
                live = GeminiClient.askLive(this, heard, name, city);
            }
            final String liveAnswer = live;
            main.post(() -> {
                if (mode != Mode.RESPONDING) return;
                if (r == null) { remember(heard, "brain unavailable"); say("Boss, I couldn't reach Gemini right now.", this::endInteraction); return; }
                List<VoiceCommand> steps = new ArrayList<>();
                for (String[] x : r.actions) {
                    VoiceCommand bc = VoiceCommand.fromBrain(x[0], x[1], x[2]);
                    if (bc == null) { remember(heard, "brain gave invalid step " + x[0] + " " + x[1]); continue; }
                    steps.add(bc);
                }
                if (steps.isEmpty()) {
                    String ans = liveAnswer != null ? liveAnswer : r.answer;
                    remember(heard, liveAnswer != null ? "answered (live)" : "answered");
                    GeminiClient.noteSpoken(ans);
                    if (ans.isEmpty()) { say("Sorry Boss, I didn't understand that.", this::endInteraction); return; }
                    say(ans, () -> followUp(appList));
                    return;
                }
                StringBuilder what = new StringBuilder();
                for (VoiceCommand bc : steps) what.append(what.length() > 0 ? ", then " : "").append(bc.type).append(bc.target.isEmpty() ? "" : " " + bc.target);
                remember(heard, "understood as " + what);
                pending.clear();
                for (int i = 1; i < steps.size(); i++) pending.add(steps.get(i));
                pendingHeard = heard; pendingGuesses = guesses;
                execute(heard, steps.get(0), guesses, appList, true);
            });
        }, "jarvis-brain").start();
    }

    /** Steps still to run from a multi-step request ("set an alarm at 6 and turn on the torch"). */
    private final java.util.ArrayDeque<VoiceCommand> pending = new java.util.ArrayDeque<>();
    private String pendingHeard = "";
    private List<String> pendingGuesses = new ArrayList<>();
    private int followUps = 0;

    /** Runs the next queued step, if any. Returns true if one was started. */
    private boolean runPending() {
        if (pending.isEmpty() || mode != Mode.RESPONDING || stopping) { pending.clear(); return false; }
        final VoiceCommand next = pending.poll();
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, RESPONSE_WATCHDOG_MS);
        main.postDelayed(() -> { if (mode == Mode.RESPONDING) execute(pendingHeard, next, pendingGuesses, null, true); }, 1200);
        return true;
    }

    /**
     * After the brain answers, listen a few seconds more without "Hey Jarvis", so a follow-up question or
     * "and tomorrow?" works like a conversation. Silence ends it quietly. At most 3 follow-ups in a row.
     */
    private void followUp(List<AppMatcher.App> apps) {
        if (followUps >= 3 || stopping || mode != Mode.RESPONDING || !GeminiClient.online(this)) { followUps = 0; endInteraction(); return; }
        followUps++;
        updateNotification("Listening for a follow-up…", false);
        listen(r -> {
            String t = r.isEmpty() || r.get(0) == null ? "" : r.get(0).trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty() || t.matches("(?:no|nothing|no thanks|no thank you|thanks|thank you|ok|okay|that's all|thats all|done|bye|stop|cancel)(?: jarvis| boss)?")) {
                followUps = 0;
                if (t.startsWith("thank")) say("You're welcome, Boss.", this::endInteraction); else endInteraction();
                return;
            }
            handleCommand(r);
        }, 2, true);
    }

    /**
     * WhatsApp message by voice: finds the contact, asks for the words if missing, reads the
     * message back and asks "Send it, Boss?". Only a spoken yes lets WhatsAppSender tap Send.
     * Without the accessibility switch on, the chat opens with the message typed and the user taps Send.
     */
    private void sendMessage(String heard, VoiceCommand c, List<String> guesses) {
        String pkg = Contacts.whatsappPackage(this);
        if (pkg == null) { remember(heard, "WhatsApp not installed"); say("Boss, WhatsApp is not installed.", this::endInteraction); return; }
        if (!Contacts.allowed(this)) {
            remember(heard, "contacts permission needed");
            say("Boss, please allow contacts in Jarvis setup, then ask me again.", this::endInteraction);
            return;
        }
        List<AppMatcher.App> people = Contacts.withNumbers(this);
        AppMatcher.App who = AppMatcher.matchName(c.target, people);
        VoiceCommand used = c;
        // Another guess of the recognizer may have the name right.
        for (int i = 0; who == null && i < guesses.size(); i++) {
            VoiceCommand gc = VoiceCommand.parse(guesses.get(i));
            if (gc.type == VoiceCommand.Type.SEND_MESSAGE) { who = AppMatcher.matchName(gc.target, people); if (who != null) used = gc; }
        }
        if (who == null) { remember(heard, "contact not found: " + c.target); say("Boss, I couldn't find " + c.target + " in your contacts.", this::endInteraction); return; }
        AppMatcher.App person = who;
        String text = used.message.isEmpty() ? c.message : used.message;
        if (text.isEmpty()) {
            say("What should I say to " + person.label + "?", () -> listen(r -> {
                String words = r.isEmpty() || r.get(0) == null ? "" : r.get(0).trim();
                if (words.isEmpty() || VoiceCommand.isNo(words)) { remember(heard, "cancelled"); say("Okay Boss, not sent.", this::endInteraction); return; }
                confirmAndSend(heard, pkg, person, words);
            }));
            return;
        }
        confirmAndSend(heard, pkg, person, text);
    }

    private void confirmAndSend(String heard, String pkg, AppMatcher.App person, String text) {
        if (!WhatsAppSender.isOn(this)) {
            remember(heard, "WhatsApp chat with " + person.label + ", typed (send switch off)");
            say("Opening WhatsApp chat with " + person.label + ". Please tap send, Boss. To send by voice, turn on Jarvis send in Jarvis setup.",
                    () -> { AppOpener.open(this, Contacts.whatsappChat(pkg, person.packageName, text)); endInteraction(); });
            return;
        }
        say("Message to " + person.label + ": " + text + ". Send it, Boss?", () -> listen(r -> {
            boolean yes = false, no = false;
            for (String g : r) { if (g == null) continue; if (VoiceCommand.isYes(g)) { yes = true; break; } if (VoiceCommand.isNo(g)) no = true; }
            if (!yes) {
                remember(heard, "not sent to " + person.label + (no ? " (you said no)" : " (no yes heard)"));
                say("Okay Boss, not sent.", this::endInteraction);
                return;
            }
            remember(heard, "sending to " + person.label + ": " + text);
            WhatsAppSender.arm(pkg, text);
            AppOpener.open(this, Contacts.whatsappChat(pkg, person.packageName, text));
            endInteraction();
        }));
    }

    static final String KEY_CITY = "home_city";

    /** "WhatsApp video call Ravi": confirm by voice, open the chat, Jarvis control taps the call button. */
    private void whatsappCall(String heard, VoiceCommand c) {
        String pkg = Contacts.whatsappPackage(this);
        if (pkg == null) { remember(heard, "WhatsApp not installed"); say("Boss, WhatsApp is not installed.", this::endInteraction); return; }
        if (!Contacts.allowed(this)) { remember(heard, "contacts permission needed"); say("Boss, please allow contacts in Jarvis setup.", this::endInteraction); return; }
        AppMatcher.App p = AppMatcher.matchName(c.target, Contacts.withNumbers(this));
        if (p == null) { remember(heard, "contact not found: " + c.target); say("Boss, I couldn't find " + c.target + " in your contacts.", this::endInteraction); return; }
        boolean video = "video".equals(c.message);
        String kind = video ? "video call" : "voice call";
        say("WhatsApp " + kind + " to " + p.label + ", Boss?", () -> listen(r -> {
            boolean yes = false;
            for (String g : r) if (g != null && (VoiceCommand.isYes(g) || g.trim().toLowerCase(java.util.Locale.ROOT).matches("(?:yes\\s+)?call(?:\\s+(?:him|her|them|now))?"))) { yes = true; break; }
            if (!yes) { remember(heard, "WhatsApp call to " + p.label + " cancelled"); say("Okay Boss, not calling.", this::endInteraction); return; }
            if (!WhatsAppSender.running()) {
                remember(heard, "opened chat with " + p.label + " (Jarvis control off)");
                say("Opening " + p.label + "'s chat. Tap the " + kind + " button, Boss.", () -> { AppOpener.open(this, Contacts.whatsappChat(pkg, p.packageName, "")); endInteraction(); });
                return;
            }
            remember(heard, "WhatsApp " + kind + " to " + p.label);
            WhatsAppSender.armCall(pkg, video);
            AppOpener.open(this, Contacts.whatsappChat(pkg, p.packageName, ""));
            endInteraction();
        }));
    }

    private static final String CONTROL_OFF = "Boss, turn on Jarvis control in Jarvis setup first.";

    /** "call Ravi" or "call 98765 43210": confirms by voice, then calls (or opens the dialer without the permission). */
    private void callSomeone(String heard, String who) {
        String digits = who.replaceAll("[^0-9+]", "");
        String name;
        String number;
        if (digits.replace("+", "").length() >= 3 && who.replaceAll("[0-9+\\s-]", "").isEmpty()) { number = digits; name = who; }
        else {
            if (!Contacts.allowed(this)) { remember(heard, "contacts permission needed"); say("Boss, please allow contacts in Jarvis setup.", this::endInteraction); return; }
            AppMatcher.App p = AppMatcher.matchName(who, Contacts.withNumbers(this));
            if (p == null) { remember(heard, "contact not found: " + who); say("Boss, I couldn't find " + who + " in your contacts.", this::endInteraction); return; }
            name = p.label; number = p.packageName;
        }
        callNumber(heard, name, number);
    }

    /**
     * True when the name isn't a contact or number but the brain could work it out ("my wife", "amma's
     * brother", a misheard name). Only when the brain is available, so offline behaviour is unchanged.
     */
    private boolean unknownPerson(VoiceCommand c, List<String> guesses) {
        String who = c.target;
        if (who == null || who.trim().isEmpty() || !Contacts.allowed(this)) return false;
        if (who.replaceAll("[0-9+\\s-]", "").isEmpty()) return false;
        if (!GeminiClient.hasKey(this) || !GeminiClient.online(this)) return false;
        List<AppMatcher.App> people = Contacts.withNumbers(this);
        if (AppMatcher.matchName(who, people) != null) return false;
        if (guesses != null) for (String g : guesses) {
            VoiceCommand gc = VoiceCommand.parse(g);
            if (gc.type == c.type && !gc.target.isEmpty() && AppMatcher.matchName(gc.target, people) != null) return false;
        }
        return true;
    }

    /** Asks "Call X, Boss?" and calls only after a spoken yes. */
    private void callNumber(String heard, final String label, final String n) {
        say("Call " + label + ", Boss?", () -> listen(r -> {
            boolean yes = false;
            for (String g : r) if (g != null && (VoiceCommand.isYes(g) || g.trim().toLowerCase(java.util.Locale.ROOT).matches("(?:yes\\s+)?call(?:\\s+(?:him|her|them|it|now))?"))) { yes = true; break; }
            if (!yes) { remember(heard, "call to " + label + " cancelled"); say("Okay Boss, not calling.", this::endInteraction); return; }
            boolean direct = PhoneActions.canCall(this);
            remember(heard, (direct ? "calling " : "dialer opened for ") + label);
            AppOpener.open(this, PhoneActions.call(this, n));
            endInteraction();
        }));
    }

    /** Keeps the last 5 commands and what Jarvis did, for the setup report. Words only, never audio. */
    private void remember(String heard, String result) {
        SharedPreferences p = prefs(this);
        String line = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(new java.util.Date())
                + " \"" + (heard.isEmpty() ? "(nothing)" : heard) + "\" -> " + result;
        String old = p.getString(KEY_COMMAND_LOG, "");
        String[] lines = old.isEmpty() ? new String[0] : old.split("\n");
        StringBuilder b = new StringBuilder(line);
        for (int i = 0; i < Math.min(4, lines.length); i++) b.append('\n').append(lines[i]);
        p.edit().putString(KEY_LAST_COMMAND, heard).putString(KEY_COMMAND_LOG, b.toString()).apply();
    }

    /** Apps with an icon in the app drawer. */
    private boolean phoneLocked() {
        android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked() && km.isKeyguardSecure();
    }

    private List<AppMatcher.App> launcherApps() { return launcherApps(this); }

    private static List<AppMatcher.App> appCache;
    private static long appCacheAt;
    private static final long APP_CACHE_MS = 2 * 60 * 1000;

    /** Installed apps with a launcher icon. Cached for 2 minutes: reading every label is slow on a busy phone. */
    static synchronized List<AppMatcher.App> launcherApps(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (appCache != null && now - appCacheAt < APP_CACHE_MS) return appCache;
        appCache = java.util.Collections.unmodifiableList(loadLauncherApps(c));
        appCacheAt = now;
        return appCache;
    }

    private static List<AppMatcher.App> loadLauncherApps(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<AppMatcher.App> apps = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
            if (r.activityInfo == null || !r.activityInfo.enabled) continue;
            CharSequence label = r.loadLabel(pm);
            if (label != null) apps.add(new AppMatcher.App(label.toString(), r.activityInfo.packageName));
        }
        return apps;
    }

    private void endInteraction() {
        main.removeCallbacks(watchdog);
        releaseRecognizer();
        afterSpeech = null;
        if (runPending()) return;
        if (commandOnly && !isEnabled(this)) { stopSelf(); return; }
        commandOnly = false;
        startWaiting();
    }

    // ---------- speaking ----------

    static final String KEY_NAME = "user_name", KEY_RATE = "speech_rate", KEY_VOICE = "tts_voice";

    /** Offline voices installed for Indian English (Google's voices on most phones), in a stable order. */
    private List<android.speech.tts.Voice> jarvisVoices() {
        List<android.speech.tts.Voice> out = new ArrayList<>();
        try {
            for (android.speech.tts.Voice v : tts.getVoices()) {
                Locale l = v.getLocale();
                if (l == null || !"en".equals(l.getLanguage()) || !"IN".equals(l.getCountry())) continue;
                if (v.isNetworkConnectionRequired() || v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                out.add(v);
            }
        } catch (Exception ignored) { }
        out.sort((a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    private void applySavedVoice() {
        String name = prefs(this).getString(KEY_VOICE, "");
        if (name.isEmpty()) return;
        for (android.speech.tts.Voice v : jarvisVoices()) if (v.getName().equals(name)) { try { tts.setVoice(v); } catch (Exception ignored) { } return; }
    }

    /** Last thing Jarvis said, for "repeat that". */
    private String lastSpoken = "";

    private void say(String text, Runnable then) {
        afterSpeech = then;
        if (text != null && !text.isEmpty() && !text.equals(lastSpoken) && !text.startsWith("Boss, I haven't said")) lastSpoken = text;
        GeminiClient.noteSpoken(text);
        String name = prefs(this).getString(KEY_NAME, "Boss");
        if (text != null && !"Boss".equals(name)) text = text.replace("Boss", name);
        if (text != null && MyVoice.on(this)) {
            // The user's own voice (offline). If it fails, fall back to the phone voice.
            if (tts != null) try { tts.stop(); } catch (Exception ignored) { }
            final String said = text;
            final Runnable then2 = then;
            MyVoice.speak(this, said, prefs(this).getFloat(KEY_RATE, 1.0f), ok -> {
                if (afterSpeech != then2) return; // something newer is being said
                if (ok) speechFinished(); else phoneVoice(said);
            });
            return;
        }
        phoneVoice(text);
    }

    private void phoneVoice(String text) {
        if (!ttsReady || tts == null || text == null) { main.post(this::speechFinished); return; }
        try { tts.setSpeechRate(prefs(this).getFloat(KEY_RATE, 1.0f)); } catch (Exception ignored) { }
        Bundle p = new Bundle();
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, p, "jarvis-" + SystemClock.elapsedRealtime());
    }

    private void speechFinished() {
        Runnable r = afterSpeech; afterSpeech = null;
        if (r != null) r.run();
    }

    // ---------- notification ----------

    private Notification notification(String text, boolean paused) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, AssistantSetupActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, WakeWordService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent toggle = PendingIntent.getService(this, 2, new Intent(this, WakeWordService.class).setAction(paused ? ACTION_RESUME : ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.kalki_logo)
                .setContentTitle(paused ? "Jarvis is paused" : "Jarvis is on")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, paused ? "Resume" : "Pause 30 min", toggle).build())
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
    }

    private void updateNotification(String text, boolean paused) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && running) nm.notify(NOTIFICATION_ID, notification(text, paused));
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Jarvis microphone", NotificationManager.IMPORTANCE_LOW));
    }

    // ---------- helpers ----------

    private byte[] asset(String name) throws java.io.IOException {
        try (InputStream in = getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    int batteryPercent() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        return bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        MyVoice.release();
        try { unregisterReceiver(unlockReceiver); } catch (Exception ignored) { }
        try { unregisterReceiver(powerReceiver); } catch (Exception ignored) { }
        stopping = true;
        running = false;
        state = "Off";
        mode = Mode.WAITING;
        main.removeCallbacksAndMessages(null);
        releaseRecognizer();
        Thread t = audioThread; audioThread = null;
        if (t != null) { try { t.join(1500); } catch (InterruptedException ignored) { } }
        if (engine != null) { engine.close(); engine = null; }
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
