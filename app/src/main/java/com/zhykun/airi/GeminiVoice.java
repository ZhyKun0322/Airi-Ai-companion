package com.zhykun.airi;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Speaks text with the Gemini TTS API. All callbacks run on the main thread.
 * Handles both raw 16-bit PCM (older TTS models) and WAV (newer ones).
 */
public class GeminiVoice {

    public interface Callback {
        void onStart();

        void onDone();

        void onError();
    }

    public static final String DEFAULT_MODEL = "gemini-3.1-flash-tts-preview";
    private static final String FALLBACK_MODEL = "gemini-2.5-flash-preview-tts";
    public static final String DEFAULT_VOICE = "Leda";

    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String STYLE =
            "Say the following in the voice of a cute, bright, youthful anime girl. "
            + "Act out sounds like umm, hmph, eh, ehehe, ah and stammering with real breath and emotion, "
            + "never read them flatly: ";

    private static class Pcm {
        byte[] data;
        int rate = 24000;
    }

    private final ExecutorService worker = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Pcm> cache = new LinkedHashMap<String, Pcm>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Pcm> eldest) {
            return size() > 24;
        }
    };

    private AudioTrack track;
    private Runnable finishTask;
    private int generation = 0;
    private boolean started = false;
    private Runnable waitTask;

    public synchronized void stop() {
        generation++;
        started = false;
        if (waitTask != null) {
            main.removeCallbacks(waitTask);
            waitTask = null;
        }
        if (finishTask != null) {
            main.removeCallbacks(finishTask);
            finishTask = null;
        }
        releaseTrack();
    }

    private void releaseTrack() {
        if (track != null) {
            try {
                track.stop();
            } catch (Exception ignored) {
                // already stopped
            }
            track.release();
            track = null;
        }
    }

    private synchronized boolean isCurrent(int id) {
        return id == generation;
    }

    public void speak(String key, String model, String voice, String text, long maxWaitMs, Callback cb) {
        stop();
        final int id;
        synchronized (this) {
            id = generation;
            // if the audio is not ready in time, give up so the caller can use the phone voice
            waitTask = () -> {
                synchronized (GeminiVoice.this) {
                    if (id != generation || started) {
                        return;
                    }
                    generation++;
                    waitTask = null;
                }
                cb.onError();
            };
            main.postDelayed(waitTask, maxWaitMs);
        }
        worker.execute(() -> {
            try {
                Pcm pcm;
                try {
                    pcm = fetch(key, model, voice, text);
                } catch (Exception first) {
                    if (model.equals(FALLBACK_MODEL) || !isCurrent(id)) {
                        throw first;
                    }
                    pcm = fetch(key, FALLBACK_MODEL, voice, text);
                }
                if (isCurrent(id)) {
                    play(pcm, id, cb);
                }
            } catch (Exception e) {
                if (isCurrent(id)) {
                    main.post(cb::onError);
                }
            }
        });
    }

    private synchronized void play(Pcm pcm, int id, Callback cb) {
        if (id != generation || pcm.data.length < 2) {
            return;
        }
        started = true;
        if (waitTask != null) {
            main.removeCallbacks(waitTask);
            waitTask = null;
        }
        AudioTrack t = new AudioTrack(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(pcm.rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                pcm.data.length,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE);
        t.write(pcm.data, 0, pcm.data.length);
        track = t;
        long ms = pcm.data.length / 2L * 1000L / pcm.rate;
        finishTask = () -> {
            synchronized (GeminiVoice.this) {
                if (id != generation) {
                    return;
                }
                finishTask = null;
                releaseTrack();
            }
            cb.onDone();
        };
        t.play();
        main.post(cb::onStart);
        main.postDelayed(finishTask, ms + 250);
    }

    private Pcm fetch(String key, String model, String voice, String text) throws Exception {
        if (!model.matches("[A-Za-z0-9._-]+")) {
            model = DEFAULT_MODEL;
        }
        if (!voice.matches("[A-Za-z]+")) {
            voice = DEFAULT_VOICE;
        }
        String cacheKey = model + "|" + voice + "|" + text;
        synchronized (cache) {
            Pcm hit = cache.get(cacheKey);
            if (hit != null) {
                return hit;
            }
        }

        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject()
                        .put("parts", new JSONArray().put(new JSONObject().put("text", STYLE + text)))))
                .put("generationConfig", new JSONObject()
                        .put("responseModalities", new JSONArray().put("AUDIO"))
                        .put("speechConfig", new JSONObject()
                                .put("voiceConfig", new JSONObject()
                                        .put("prebuiltVoiceConfig", new JSONObject()
                                                .put("voiceName", voice)))));

        HttpURLConnection conn =
                (HttpURLConnection) new URL(API_BASE + model + ":generateContent").openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);
            conn.setRequestProperty("x-goog-api-key", key);
            conn.setRequestProperty("content-type", "application/json");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("TTS error " + code);
            }
            String response = readAll(conn.getInputStream());
            JSONArray candidates = new JSONObject(response).optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                throw new IOException("no audio");
            }
            JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            if (parts != null) {
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject inline = parts.getJSONObject(i).optJSONObject("inlineData");
                    if (inline != null && !inline.optString("data").isEmpty()) {
                        Pcm pcm = toPcm(Base64.decode(inline.getString("data"), Base64.DEFAULT));
                        synchronized (cache) {
                            cache.put(cacheKey, pcm);
                        }
                        return pcm;
                    }
                }
            }
            throw new IOException("no audio");
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    // raw PCM is used as is, a WAV file is unwrapped (assumes mono 16-bit)
    private static Pcm toPcm(byte[] b) {
        Pcm p = new Pcm();
        p.data = b;
        boolean riff = b.length > 44 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F';
        if (!riff) {
            return p;
        }
        int pos = 12;
        while (pos + 8 <= b.length) {
            String id = new String(b, pos, 4, StandardCharsets.US_ASCII);
            int size = le32(b, pos + 4);
            if (size < 0) {
                break;
            }
            if (id.equals("fmt ") && pos + 16 <= b.length) {
                p.rate = le32(b, pos + 12);
            }
            if (id.equals("data")) {
                int start = pos + 8;
                int len = start + size > b.length ? b.length - start : size;
                p.data = Arrays.copyOfRange(b, start, start + len);
                break;
            }
            pos += 8 + size + (size & 1);
        }
        return p;
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8 | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }
}