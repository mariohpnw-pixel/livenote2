package com.livenotes.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaMuxer;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Live Notes (no AI): continuous speech-to-text with Android's SpeechRecognizer,
 * optionally recording the same audio to an .m4a file at the same time.
 *
 * Android 13+: the microphone is opened once with AudioRecord. Every buffer goes to the
 * AAC encoder AND through a pipe into the recognizer (EXTRA_AUDIO_SOURCE, segmented session),
 * so recording and transcription share one microphone and no words are lost between sentences.
 * Older Android, or a recognizer that refuses an audio pipe: classic restart-per-sentence mode,
 * text only.
 */
public class MainActivity extends Activity {

    private static final int REQ_MIC = 1;
    private static final int RATE = 16000;
    private static final String EXTRA_BIASING = "android.speech.extra.BIASING_STRINGS";

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newCachedThreadPool();

    // ---- recognition session
    private SpeechRecognizer recognizer;
    private volatile boolean running = false;
    private volatile boolean piped = false;
    private int compat = 0;           // 0 = full options, 1 = no formatting/biasing, 2 = classic mic mode
    private boolean gotResult = false;
    private int errorStreak = 0;
    private JSONObject opts = new JSONObject();
    private String pendingOpts = null;

    // ---- audio capture (Android 13+)
    private AudioRecord audioRec;
    private AacWriter writer;
    private String audioFile;
    private Thread audioThread, pipeThread;
    private volatile ParcelFileDescriptor pipeWrite, pipeRead;
    private final LinkedBlockingQueue<byte[]> pipeQ = new LinkedBlockingQueue<>(80);

    // ---- playback
    private MediaPlayer player;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    // =================================================================== events to JS

    private void emit(JSONObject o) {
        String code = "window.__native && window.__native.ev(" + o.toString() + ")";
        ui.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    private void emit(String type, Object... kv) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            for (int i = 0; i + 1 < kv.length; i += 2) o.put(String.valueOf(kv[i]), kv[i + 1]);
            emit(o);
        } catch (Exception ignored) { }
    }

    private void notice(String text) {
        emit("notice", "text", text);
    }

    private File audioDir() {
        File d = new File(getFilesDir(), "audio");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    // =================================================================== session control

    private void startSession() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            emit("state", "state", "idle", "reason", "unavailable");
            return;
        }
        running = true;
        gotResult = false;
        errorStreak = 0;
        compat = 0;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        boolean wantAudio = opts.optBoolean("audio", true);
        piped = false;
        if (wantAudio) {
            if (Build.VERSION.SDK_INT >= 33) {
                piped = startAudio();
                if (!piped) notice("เปิดการบันทึกเสียงไม่สำเร็จ — ถอดข้อความอย่างเดียว");
            } else {
                notice("Android ต่ำกว่า 13 บันทึกเสียงพร้อมถอดข้อความไม่ได้ — ถอดข้อความอย่างเดียว");
            }
        }
        if (!piped) compat = 2;

        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(listener);
        }
        emit("state", "state", "listening", "reason", "", "mode", piped ? "audio" : "text");
        startRecognition();
    }

    private void stopSession(String reason) {
        if (!running) return;
        running = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (piped) {
            stopAudio();     // closing the pipe lets the recognizer finish the last sentence
        } else if (recognizer != null) {
            try { recognizer.stopListening(); } catch (Exception ignored) { }
        }
        emit("state", "state", "idle", "reason", reason);
    }

    private Intent baseIntent() {
        String lang = opts.optString("lang", "th-TH");
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, opts.optBoolean("offline", false));
        if (!piped) {
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        }
        if (Build.VERSION.SDK_INT >= 33 && compat == 0) {
            if (opts.optBoolean("punct", true)) {
                i.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY);
                i.putExtra(RecognizerIntent.EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION, true);
            }
            i.putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false);
            JSONArray bias = opts.optJSONArray("bias");
            if (bias != null && bias.length() > 0) {
                ArrayList<String> list = new ArrayList<>();
                for (int k = 0; k < bias.length() && k < 500; k++) {
                    String w = bias.optString(k).trim();
                    if (!w.isEmpty()) list.add(w);
                }
                i.putStringArrayListExtra(EXTRA_BIASING, list);
            }
        }
        return i;
    }

    private void startRecognition() {
        if (!running || recognizer == null) return;
        Intent i = baseIntent();
        if (piped) {
            try {
                closePipe();
                ParcelFileDescriptor[] pp = ParcelFileDescriptor.createPipe();
                pipeRead = pp[0];
                pipeWrite = pp[1];
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipeRead);
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE);
                i.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
            } catch (IOException e) {
                fallBackToClassic();
                i = baseIntent();
            }
        }
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            ui.postDelayed(this::startRecognition, 500);
        }
    }

    private void restartSoon(long delay) {
        ui.postDelayed(() -> {
            if (!running) return;
            try { recognizer.cancel(); } catch (Exception ignored) { }
            startRecognition();
        }, delay);
    }

    /** The recognizer refused the audio pipe: keep the transcript going, give up on recording. */
    private void fallBackToClassic() {
        if (piped) {
            piped = false;
            stopAudio();
            notice("ระบบถอดเสียงของเครื่องนี้ใช้ร่วมกับการบันทึกเสียงไม่ได้ — ถอดข้อความอย่างเดียว (ปิด 'บันทึกเสียง' ในตั้งค่าเพื่อไม่ให้ขึ้นข้อความนี้)");
        }
        compat = 2;
    }

    // =================================================================== recognizer callbacks

    private void emitFinal(Bundle b) {
        if (b == null) return;
        ArrayList<String> texts = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (texts == null || texts.isEmpty() || texts.get(0) == null || texts.get(0).trim().isEmpty()) return;
        float[] conf = b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
        try {
            JSONObject o = new JSONObject();
            o.put("type", "final");
            o.put("text", texts.get(0));
            JSONArray alts = new JSONArray();
            for (int k = 1; k < texts.size(); k++) if (texts.get(k) != null) alts.put(texts.get(k));
            o.put("alts", alts);
            o.put("conf", conf != null && conf.length > 0 ? conf[0] : -1);
            emit(o);
        } catch (Exception ignored) { }
        gotResult = true;
        errorStreak = 0;
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override public void onRmsChanged(float rmsdB) {
            if (!piped && running) emit("level", "v", Math.max(0f, Math.min(1f, (rmsdB + 2f) / 12f)));
        }

        @Override public void onPartialResults(Bundle partial) {
            ArrayList<String> t = partial == null ? null : partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (t != null && !t.isEmpty() && t.get(0) != null && !t.get(0).isEmpty()) emit("partial", "text", t.get(0));
        }

        @Override public void onResults(Bundle results) {
            emit("partial", "text", "");
            emitFinal(results);
            if (running && !piped) ui.postDelayed(MainActivity.this::startRecognition, 60);
        }

        @Override public void onSegmentResults(Bundle segment) {
            emit("partial", "text", "");
            emitFinal(segment);
        }

        @Override public void onEndOfSegmentedSession() {
            if (running && piped) restartSoon(50);
        }

        @Override public void onError(int error) {
            if (!running) return;
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                stopSession("not_granted");
                return;
            }
            boolean optionError = error == SpeechRecognizer.ERROR_CLIENT
                    || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                    || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                    || (Build.VERSION.SDK_INT >= 33 && error == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT);
            if (!gotResult && optionError && compat < 2) {
                // step down: first drop formatting/biasing, then drop the audio pipe
                compat++;
                if (compat == 2) fallBackToClassic();
                restartSoon(300);
                return;
            }
            if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                stopSession("lang");
                return;
            }
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    restartSoon(60);
                    return;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    restartSoon(300);
                    return;
                default:
                    errorStreak++;
                    if (errorStreak >= 6) {
                        stopSession(error == SpeechRecognizer.ERROR_NETWORK
                                || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                                || error == SpeechRecognizer.ERROR_SERVER ? "network" : "error");
                    } else {
                        restartSoon(500L * errorStreak);
                    }
            }
        }
    };

    // =================================================================== audio capture

    @SuppressWarnings("MissingPermission")
    private boolean startAudio() {
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            audioRec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE) * 2);
            if (audioRec.getState() != AudioRecord.STATE_INITIALIZED) {
                audioRec.release();
                audioRec = null;
                return false;
            }
            audioFile = opts.optString("file", "rec_" + System.currentTimeMillis() + ".m4a");
            writer = new AacWriter(new File(audioDir(), audioFile), RATE);
            pipeQ.clear();
            audioRec.startRecording();
            final AudioRecord rec = audioRec;
            final AacWriter w = writer;
            audioThread = new Thread(() -> audioLoop(rec, w), "audio");
            pipeThread = new Thread(this::pipeLoop, "pipe");
            audioThread.start();
            pipeThread.start();
            emit("segment", "phase", "start", "file", audioFile);
            return true;
        } catch (Exception e) {
            if (audioRec != null) { try { audioRec.release(); } catch (Exception ignored) { } audioRec = null; }
            if (writer != null) { writer.close(); writer = null; }
            return false;
        }
    }

    private void audioLoop(AudioRecord rec, AacWriter w) {
        byte[] buf = new byte[RATE / 10 * 2]; // 100 ms
        long lastLevel = 0;
        while (running && piped) {
            int n = rec.read(buf, 0, buf.length);
            if (n <= 0) continue;
            try { w.write(buf, n); } catch (Exception ignored) { }
            byte[] copy = Arrays.copyOf(buf, n);
            if (!pipeQ.offer(copy)) { pipeQ.poll(); pipeQ.offer(copy); }
            long now = System.currentTimeMillis();
            if (now - lastLevel > 100) {
                lastLevel = now;
                double sum = 0;
                for (int k = 0; k + 1 < n; k += 2) {
                    int v = (short) ((buf[k] & 0xff) | (buf[k + 1] << 8));
                    sum += (double) v * v;
                }
                double rms = Math.sqrt(sum / Math.max(1, n / 2)) / 32768.0;
                double lv = Math.max(0, Math.min(1, (20 * Math.log10(rms + 1e-9) + 55) / 45));
                emit("level", "v", lv);
            }
        }
    }

    private void pipeLoop() {
        FileOutputStream out = null;
        ParcelFileDescriptor outFor = null;
        while (running && piped) {
            byte[] b;
            try { b = pipeQ.poll(200, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { break; }
            if (b == null) continue;
            ParcelFileDescriptor p = pipeWrite;
            if (p == null) continue;
            try {
                if (out == null || outFor != p) {
                    out = new FileOutputStream(p.getFileDescriptor());
                    outFor = p;
                }
                out.write(b);
            } catch (IOException e) {
                out = null;     // recognizer closed its end; a restart will hand us a new pipe
                outFor = null;
            }
        }
    }

    private void closePipe() {
        ParcelFileDescriptor w = pipeWrite, r = pipeRead;
        pipeWrite = null;
        pipeRead = null;
        try { if (w != null) w.close(); } catch (IOException ignored) { }
        try { if (r != null) r.close(); } catch (IOException ignored) { }
    }

    private void stopAudio() {
        final AudioRecord rec = audioRec;
        final AacWriter w = writer;
        final Thread at = audioThread, pt = pipeThread;
        final String file = audioFile;
        audioRec = null;
        writer = null;
        audioThread = null;
        pipeThread = null;
        pool.submit(() -> {
            try { if (at != null) at.join(1500); } catch (InterruptedException ignored) { }
            try { if (pt != null) pt.join(1500); } catch (InterruptedException ignored) { }
            if (rec != null) {
                try { rec.stop(); } catch (Exception ignored) { }
                try { rec.release(); } catch (Exception ignored) { }
            }
            ui.post(this::closePipe);
            if (w != null) {
                long dur = w.durationMs();
                boolean ok = w.close();
                emit("segment", "phase", "end", "file", file, "dur", ok ? dur : 0);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && pendingOpts != null) {
            try { opts = new JSONObject(pendingOpts); } catch (Exception e) { opts = new JSONObject(); }
            pendingOpts = null;
            startSession();
        } else {
            emit("state", "state", "idle", "reason", "not_granted");
        }
    }

    // =================================================================== playback

    private void stopPlayer() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }

    // =================================================================== export

    private void exportAudio(String filesJson, String name, boolean share, String text) {
        File tmp = new File(getCacheDir(), "export.m4a");
        MediaMuxer mux = null;
        try {
            JSONArray files = new JSONArray(filesJson);
            mux = new MediaMuxer(tmp.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = -1;
            long base = 0;
            ByteBuffer buf = ByteBuffer.allocate(1 << 16);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (int k = 0; k < files.length(); k++) {
                File f = new File(audioDir(), files.getString(k));
                if (!f.exists()) continue;
                MediaExtractor ex = new MediaExtractor();
                try {
                    ex.setDataSource(f.getPath());
                    if (ex.getTrackCount() == 0) continue;
                    ex.selectTrack(0);
                    if (track < 0) {
                        track = mux.addTrack(ex.getTrackFormat(0));
                        mux.start();
                    }
                    long last = 0;
                    while (true) {
                        buf.clear();
                        int n = ex.readSampleData(buf, 0);
                        if (n < 0) break;
                        last = ex.getSampleTime();
                        info.offset = 0;
                        info.size = n;
                        info.presentationTimeUs = base + last;
                        info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        mux.writeSampleData(track, buf, info);
                        ex.advance();
                    }
                    base += last + 64000; // one AAC frame at 16 kHz
                } finally {
                    ex.release();
                }
            }
            if (track < 0) {
                emit("export", "ok", false, "msg", "ไม่มีไฟล์เสียงในบันทึกนี้");
                return;
            }
            mux.stop();
            mux.release();
            mux = null;

            String display = (name == null || name.trim().isEmpty() ? "LiveNotes" : name.trim())
                    .replaceAll("[\\\\/:*?\"<>|]", "_") + ".m4a";
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, display);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4");
            String dir = Build.VERSION.SDK_INT >= 31 ? Environment.DIRECTORY_RECORDINGS : Environment.DIRECTORY_MUSIC;
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, dir + "/LiveNotes");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (uri == null) throw new IOException("insert failed");
            try (OutputStream os = getContentResolver().openOutputStream(uri); InputStream in = new FileInputStream(tmp)) {
                if (os == null) throw new IOException("open failed");
                byte[] b = new byte[1 << 16];
                int n;
                while ((n = in.read(b)) > 0) os.write(b, 0, n);
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, v, null, null);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();

            if (share) {
                final Uri u = uri;
                ui.post(() -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("audio/mp4");
                    send.putExtra(Intent.EXTRA_STREAM, u);
                    if (text != null && !text.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, text);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "แชร์ไฟล์เสียง"));
                });
                emit("export", "ok", true, "msg", "บันทึกไว้ที่ " + dir + "/LiveNotes/" + display);
            } else {
                emit("export", "ok", true, "msg", "บันทึกไว้ที่ " + dir + "/LiveNotes/" + display);
            }
        } catch (Exception e) {
            emit("export", "ok", false, "msg", "บันทึกไฟล์เสียงไม่สำเร็จ: " + e.getMessage());
        } finally {
            if (mux != null) { try { mux.release(); } catch (Exception ignored) { } }
        }
    }

    // =================================================================== bridge

    private class Bridge {
        @JavascriptInterface public int sdk() { return Build.VERSION.SDK_INT; }

        @JavascriptInterface public boolean hasSpeech() {
            return SpeechRecognizer.isRecognitionAvailable(MainActivity.this);
        }

        @JavascriptInterface public void startRec(String optsJson) {
            ui.post(() -> {
                if (running) return;
                stopPlayer();
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingOpts = optsJson;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                    return;
                }
                try { opts = new JSONObject(optsJson); } catch (Exception e) { opts = new JSONObject(); }
                startSession();
            });
        }

        @JavascriptInterface public void stopRec() {
            ui.post(() -> stopSession("stopped"));
        }

        @JavascriptInterface public void play(String file, int offsetMs) {
            ui.post(() -> {
                stopPlayer();
                try {
                    MediaPlayer mp = new MediaPlayer();
                    mp.setDataSource(new File(audioDir(), file).getPath());
                    mp.prepare();
                    mp.seekTo(Math.max(0, offsetMs));
                    mp.setOnCompletionListener(m -> emit("ended", "file", file));
                    mp.start();
                    player = mp;
                } catch (Exception e) {
                    emit("ended", "file", file, "error", true);
                }
            });
        }

        @JavascriptInterface public void pause() {
            ui.post(() -> { if (player != null && player.isPlaying()) player.pause(); });
        }

        @JavascriptInterface public void resume() {
            ui.post(() -> { if (player != null) player.start(); });
        }

        @JavascriptInterface public void stopPlay() {
            ui.post(MainActivity.this::stopPlayer);
        }

        @JavascriptInterface public int position() {
            try { return player == null ? -1 : player.getCurrentPosition(); } catch (Exception e) { return -1; }
        }

        @JavascriptInterface public void exportAudio(String filesJson, String name, boolean share, String text) {
            pool.submit(() -> MainActivity.this.exportAudio(filesJson, name, share, text));
        }

        @JavascriptInterface public void deleteFiles(String filesJson) {
            pool.submit(() -> {
                try {
                    JSONArray a = new JSONArray(filesJson);
                    for (int k = 0; k < a.length(); k++) {
                        //noinspection ResultOfMethodCallIgnored
                        new File(audioDir(), a.getString(k)).delete();
                    }
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface public void copy(String text) {
            ui.post(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("LiveNotes", text));
            });
        }

        @JavascriptInterface public void shareText(String text) {
            ui.post(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(send, "แชร์ข้อความ"));
            });
        }

        @JavascriptInterface public void toast(String text) {
            ui.post(() -> Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface public void exit() {
            ui.post(MainActivity.this::finish);
        }
    }

    // =================================================================== lifecycle

    @Override
    protected void onPause() {
        super.onPause();
        if (running) stopSession("hidden");
        if (player != null && player.isPlaying()) {
            player.pause();
            emit("paused");
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null) web.evaluateJavascript("window.__native && window.__native.back()", null);
    }

    @Override
    protected void onDestroy() {
        running = false;
        if (piped) stopAudio();
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        stopPlayer();
        pool.shutdown();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
