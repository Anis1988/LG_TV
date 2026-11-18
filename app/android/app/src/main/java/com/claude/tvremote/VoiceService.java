package com.claude.tvremote;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hands-free voice: listens for the wake phrase ("Hey LG") and runs the command after it,
 * e.g. "Hey LG, turn off". Runs as a foreground service with a notification so it keeps
 * listening with the app closed. Android only allows starting it while the app is open.
 */
public class VoiceService extends Service implements RecognitionListener {
    static final String ACTION_STOP = "com.claude.tvremote.VOICE_STOP";
    private static final String TAG = "TvVoice";
    private static final String CHANNEL = "voice";
    private static final int NOTIF_ID = 44;
    /** After a bare "Hey LG", how long the next phrase counts as the command. */
    private static final long COMMAND_WINDOW_MS = 7000;

    static volatile boolean running = false;
    private static volatile VoiceService current;
    /** Set while the app's own mic button is listening, so the two don't fight over the mic. */
    private static volatile boolean paused = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SpeechRecognizer rec;
    private boolean onDevice;
    private boolean listening;
    private long awaitUntil;
    private boolean buzzedThisUtterance;
    private int errorStreak;
    private String lastText = "";
    private final Runnable listenTask = this::listen;

    // ---- settings shared with the app ----

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(TvClient.PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context ctx) { return prefs(ctx).getBoolean("voiceOn", false); }

    static String wake(Context ctx) { return prefs(ctx).getString("voiceWake", "Hey LG"); }

    static void setWake(Context ctx, String wake) {
        String w = wake == null ? "" : wake.trim();
        if (w.isEmpty()) w = "Hey LG";
        prefs(ctx).edit().putString("voiceWake", w).apply();
        VoiceService s = current;
        if (s != null) s.main.post(() -> s.status(null));
    }

    static boolean hasMic(Context ctx) {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** Turn hands-free on or off. Must be called while the app is in the foreground. */
    static boolean setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("voiceOn", on).apply();
        if (!on) {
            ctx.stopService(new Intent(ctx, VoiceService.class));
            return false;
        }
        return startIfEnabled(ctx);
    }

    static boolean startIfEnabled(Context ctx) {
        if (!enabled(ctx) || !hasMic(ctx) || !SpeechRecognizer.isRecognitionAvailable(ctx)) return false;
        try {
            ContextCompat.startForegroundService(ctx, new Intent(ctx, VoiceService.class));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Could not start listening", e);
            return false;
        }
    }

    /** The app's mic button takes the microphone for a moment. */
    static void pause() {
        paused = true;
        VoiceService s = current;
        if (s != null) s.main.post(s::stopRecognizer);
    }

    static void resume() {
        paused = false;
        VoiceService s = current;
        if (s != null) s.again(400);
    }

    // ---- service ----

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        current = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            prefs(this).edit().putBoolean("voiceOn", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, notification(idleText()), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, notification(idleText()));
            }
        } catch (Exception e) {
            // Android 14+ refuses a microphone service started from the background (e.g. after a reboot).
            Log.w(TAG, "Not allowed to listen right now", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        if (!listening) again(0);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        current = null;
        main.removeCallbacksAndMessages(null);
        stopRecognizer();
        if (rec != null) { rec.destroy(); rec = null; }
        worker.shutdown();
        super.onDestroy();
    }

    private String idleText() {
        return "Say “" + wake(this) + ", turn off”";
    }

    private Notification notification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Voice commands", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while the app listens for “Hey LG”");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 500,
            new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 501,
            new Intent(this, VoiceService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_qs_tv)
            .setContentTitle("Listening for “" + wake(this) + "”")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop listening", stop)
            .build();
    }

    /** Update the notification line; null puts back the hint. */
    private void status(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || !running) return;
        try { nm.notify(NOTIF_ID, notification(text == null ? idleText() : text)); } catch (Exception ignored) {}
    }

    // ---- recognizer loop ----

    private void ensureRecognizer() {
        if (rec != null) return;
        onDevice = false;
        if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
            && !prefs(this).getBoolean("voiceCloudOnly", false)) {
            try {
                rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
                onDevice = true;
            } catch (Exception e) {
                rec = null;
            }
        }
        if (rec == null) rec = SpeechRecognizer.createSpeechRecognizer(this);
        rec.setRecognitionListener(this);
    }

    private void listen() {
        if (!running || paused || listening) return;
        if (!hasMic(this)) { stopSelf(); return; }
        ensureRecognizer();
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        buzzedThisUtterance = false;
        try {
            rec.startListening(i);
            listening = true;
        } catch (Exception e) {
            Log.w(TAG, "startListening failed", e);
            resetRecognizer();
            again(1500);
        }
    }

    private void stopRecognizer() {
        listening = false;
        if (rec != null) {
            try { rec.cancel(); } catch (Exception ignored) {}
        }
    }

    private void resetRecognizer() {
        listening = false;
        if (rec != null) {
            try { rec.destroy(); } catch (Exception ignored) {}
            rec = null;
        }
    }

    private void again(long delayMs) {
        main.removeCallbacks(listenTask);
        main.postDelayed(listenTask, delayMs);
    }

    @Override
    public void onResults(Bundle results) {
        listening = false;
        errorStreak = 0;
        ArrayList<String> heard = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        handle(heard);
        again(150);
    }

    @Override
    public void onPartialResults(Bundle partial) {
        // Quick buzz the moment the wake phrase is heard, before the command finishes.
        if (buzzedThisUtterance) return;
        ArrayList<String> heard = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (heard == null) return;
        for (String h : heard) {
            if (VoiceParser.afterWake(h, wake(this)) != null) {
                buzzedThisUtterance = true;
                buzz(25);
                return;
            }
        }
    }

    private void handle(ArrayList<String> heard) {
        if (heard == null || heard.isEmpty()) return;
        String wake = wake(this);
        boolean inWindow = SystemClock.elapsedRealtime() < awaitUntil;
        // Engines return several guesses; use the first one that has the wake phrase.
        for (String h : heard) {
            String rest = VoiceParser.afterWake(h, wake);
            if (rest == null) continue;
            if (rest.isEmpty()) {
                awaitUntil = SystemClock.elapsedRealtime() + COMMAND_WINDOW_MS;
                if (!buzzedThisUtterance) buzz(25);
                status("Listening… say a command");
                tellApp(h, "Listening…", true, false);
                return;
            }
            awaitUntil = 0;
            execute(h, rest);
            return;
        }
        if (inWindow) {
            awaitUntil = 0;
            execute(heard.get(0), heard.get(0));
        }
    }

    private void execute(String heard, String command) {
        lastText = command;
        status("“" + command + "”…");
        worker.execute(() -> {
            VoiceRunner.Result r = VoiceRunner.run(getApplicationContext(), command);
            Log.i(TAG, "\"" + command + "\" -> " + r.message);
            main.post(() -> {
                buzz(r.ok ? 15 : 60);
                status((r.ok ? "✓ " : "✗ ") + r.message);
                Toast.makeText(getApplicationContext(), r.message, Toast.LENGTH_SHORT).show();
                tellApp(heard, r.message, r.ok, true);
                main.postDelayed(() -> { if (lastText.equals(command)) status(null); }, 6000);
            });
        });
    }

    private void tellApp(String heard, String message, boolean ok, boolean done) {
        TvSocketPlugin p = TvSocketPlugin.instance;
        if (p != null) p.voiceEvent(heard, message, ok, done);
    }

    @Override
    public void onError(int error) {
        listening = false;
        long delay;
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                // Normal: nobody spoke. Listen again right away.
                errorStreak = 0;
                delay = 100;
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                prefs(this).edit().putBoolean("voiceOn", false).apply();
                stopSelf();
                return;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                stopRecognizer();
                delay = 1000;
                break;
            case SpeechRecognizer.ERROR_CLIENT:
                resetRecognizer();
                delay = 500;
                break;
            default:
                // Network, audio, server, language not available on the phone...
                errorStreak++;
                // 12/13: the phone's offline engine doesn't have this language.
                if (onDevice && (errorStreak >= 3 || error == 12 || error == 13)) {
                    // The phone's offline engine isn't working: switch to the normal one.
                    prefs(this).edit().putBoolean("voiceCloudOnly", true).apply();
                }
                resetRecognizer();
                delay = Math.min(30_000, 1000L << Math.min(5, errorStreak));
                Log.w(TAG, "Recognizer error " + error + ", retrying in " + delay + " ms");
        }
        again(delay);
    }

    private void buzz(long ms) {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        else v.vibrate(ms);
    }

    @Override public void onReadyForSpeech(Bundle params) {}
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() {}
    @Override public void onEvent(int eventType, Bundle params) {}
}
