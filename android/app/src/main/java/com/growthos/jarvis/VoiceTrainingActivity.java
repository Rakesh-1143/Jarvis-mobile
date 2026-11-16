package com.growthos.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * "Train my voice": the user says the wake phrase 6 times and reads one sentence.
 * Recordings stay in memory and are dropped; only feature numbers are saved (TemplateMatcher).
 * The always-on listener is paused while this screen records.
 */
public final class VoiceTrainingActivity extends Activity {
    private static final int SAMPLES = 6, RATE = 16000;
    private static final String SENTENCE = "Today I will check the weather, open my messages, and call my brother in the evening.";
    private static final int TEXT = 0xffe6f7ff, MUTED = 0xff8aa4b4, ACCENT = 0xff00e5ff, GOOD = 0xff4ade80, BG = 0xff02070d;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView step, big, info;
    private EditText phraseInput;
    private Button startButton;
    private volatile boolean cancelled;
    private boolean wasListening;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(BG);
        int p = dp(20);
        l.setPadding(p, p, p, p);
        TextView title = text("Train my voice", 24, TEXT); title.setTypeface(Typeface.DEFAULT_BOLD); l.addView(title);
        l.addView(text("You will say your wake phrase " + SAMPLES + " times, then read one sentence. It takes about 1 minute. "
                + "Recordings are not saved; only numbers that describe your voice stay on this phone.", 14, MUTED));
        l.addView(text("Wake phrase", 13, MUTED));
        phraseInput = new EditText(this);
        phraseInput.setText(WakeWordService.prefs(this).getString(WakeWordService.KEY_PHRASE, "Hey Jarvis"));
        phraseInput.setTextColor(TEXT);
        phraseInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        l.addView(phraseInput);
        step = text("", 15, ACCENT); step.setPadding(0, dp(16), 0, 0); l.addView(step);
        big = text("", 28, TEXT); big.setGravity(Gravity.CENTER); big.setTypeface(Typeface.DEFAULT_BOLD); big.setPadding(0, dp(24), 0, dp(24)); l.addView(big);
        info = text("Use a quiet place. Hold the phone as you normally do.", 14, MUTED); l.addView(info);
        startButton = new Button(this); startButton.setText("Start"); startButton.setAllCaps(false);
        startButton.setOnClickListener(v -> start());
        l.addView(startButton);
        setContentView(l);
    }

    private void start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1); return; }
        String phrase = phraseInput.getText().toString().trim();
        if (phrase.isEmpty()) phrase = "Hey Jarvis";
        final String wake = phrase;
        startButton.setEnabled(false);
        phraseInput.setEnabled(false);
        cancelled = false;
        // Free the microphone: pause the always-on listener while training.
        wasListening = WakeWordService.running;
        if (wasListening) stopService(new android.content.Intent(this, WakeWordService.class));
        new Thread(() -> run(wake), "jarvis-train").start();
    }

    private void run(String phrase) {
        try (WakeWordEngine engine = new WakeWordEngine(asset("wakeword/melspectrogram.onnx"), asset("wakeword/embedding_model.onnx"), asset("wakeword/hey_jarvis_v0.1.onnx"))) {
            Thread.sleep(500);
            VoiceTrainer trainer = new VoiceTrainer(engine);
            int i = 1, tries = 0;
            while (i <= SAMPLES && !cancelled) {
                show("Sample " + i + " of " + SAMPLES, "Get ready…", "");
                short[] audio = record(3500, 600, "Say: \"" + phrase + "\"");
                if (trainer.addWakeSample(audio)) { i++; tries = 0; }
                else if (++tries >= 3) { fail("I couldn't hear you. Check the microphone and try again."); return; }
                else show("Sample " + i + " of " + SAMPLES, "Again, a bit louder", "I didn't hear the phrase.");
                Thread.sleep(400);
            }
            if (cancelled) return;
            show("Almost done", "Read aloud:", SENTENCE);
            Thread.sleep(1500);
            trainer.addOtherSpeech(record(7000, 0, null));
            show("Almost done", "Stay quiet…", "");
            trainer.addOtherSpeech(record(2000, 0, null));
            show("Learning your voice…", "", "");
            TemplateMatcher m = trainer.build();
            m.save(TemplateMatcher.file(this));
            WakeWordService.prefs(this).edit().putString(WakeWordService.KEY_PHRASE, phrase).putLong(WakeWordService.KEY_TRAINED_AT, System.currentTimeMillis()).apply();
            ui.post(() -> {
                step.setText("Done");
                big.setText("Learned \"" + phrase + "\"");
                big.setTextColor(GOOD);
                info.setText("Jarvis will now wake when you say it. If it misses you or wakes by mistake, use Change sensitivity in Jarvis setup, or train again.");
                finishTraining();
            });
        } catch (Throwable t) {
            fail("Training failed: " + t.getClass().getSimpleName());
        }
    }

    /** Records {@code ms} of audio; after {@code promptAfterMs} shows the prompt. Audio stays in memory. */
    private short[] record(int ms, int promptAfterMs, String prompt) throws Exception {
        int total = RATE * ms / 1000;
        short[] out = new short[total];
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192));
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); throw new IllegalStateException("microphone busy"); }
        try {
            rec.startRecording();
            int got = 0, promptAt = RATE * promptAfterMs / 1000;
            boolean prompted = prompt == null;
            while (got < total && !cancelled) {
                int n = rec.read(out, got, Math.min(1600, total - got), AudioRecord.READ_BLOCKING);
                if (n < 0) throw new IllegalStateException("microphone error " + n);
                got += n;
                if (!prompted && got >= promptAt) { prompted = true; final String pr = prompt; ui.post(() -> { big.setText(pr); big.setTextColor(GOOD); }); }
            }
        } finally { try { rec.stop(); } catch (Exception ignored) { } rec.release(); }
        ui.post(() -> big.setTextColor(TEXT));
        return out;
    }

    private void show(String s, String b, String i) { ui.post(() -> { step.setText(s); big.setText(b); info.setText(i); }); }

    private void fail(String why) {
        ui.post(() -> { step.setText("Not finished"); big.setText(""); info.setText(why); startButton.setText("Try again"); startButton.setEnabled(true); phraseInput.setEnabled(true); finishTraining(); });
    }

    private void finishTraining() {
        if (wasListening || WakeWordService.isEnabled(this)) WakeWordService.start(this, null);
        wasListening = false;
    }

    @Override protected void onDestroy() {
        cancelled = true;
        if (wasListening) finishTraining();
        super.onDestroy();
    }

    private byte[] asset(String name) throws java.io.IOException {
        try (InputStream in = getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t; }
}
