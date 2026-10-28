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
import java.io.ByteArrayOutputStream;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Speaks text with the Gemini TTS API, streaming: audio starts playing as soon as the
 * first chunk arrives instead of waiting for the whole clip. All callbacks run on the main thread.
 */
public class GeminiVoice {

    public interface Callback {
        /** audio is audibly starting now */
        void onStart();

        void onDone();

        /** no audio could be played at all */
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
    private static final Pattern RATE = Pattern.compile("rate=(\\d+)");
    // start playing once this much audio is buffered (seconds) to avoid stutter
    private static final double PREBUFFER_SEC = 0.25;

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
    private int generation = 0;

    public synchronized void stop() {
        generation++;
        releaseTrack();
    }

    private void releaseTrack() {
        if (track != null) {
            try {
                track.pause();
                track.flush();
            } catch (Exception ignored) {
                // already stopped
            }
            try {
                track.release();
            } catch (Exception ignored) {
                // already released
            }
            track = null;
        }
    }

    private synchronized boolean isCurrent(int id) {
        return id == generation;
    }

    public void speak(String key, String model, String voice, String text, Callback cb) {
        final int id;
        synchronized (this) {
            generation++;
            releaseTrack();
            id = generation;
        }
        worker.execute(() -> {
            boolean[] started = {false};
            try {
                try {
                    stream(key, model, voice, text, id, started, cb);
                } catch (Exception first) {
                    if (started[0] || model.equals(FALLBACK_MODEL) || !isCurrent(id)) {
                        throw first;
                    }
                    stream(key, FALLBACK_MODEL, voice, text, id, started, cb);
                }
            } catch (Exception e) {
                if (isCurrent(id)) {
                    final boolean wasStarted = started[0];
                    main.post(() -> {
                        if (wasStarted) {
                            cb.onDone();
                        } else {
                            cb.onError();
                        }
                    });
                }
            }
        });
    }

    /** Streams audio for one request. Throws if nothing could be played. */
    private void stream(String key, String model, String voice, String text, int id,
                        boolean[] started, Callback cb) throws Exception {
        if (!model.matches("[A-Za-z0-9._-]+")) {
            model = DEFAULT_MODEL;
        }
        if (!voice.matches("[A-Za-z]+")) {
            voice = DEFAULT_VOICE;
        }
        String cacheKey = model + "|" + voice + "|" + text;
        Pcm hit;
        synchronized (cache) {
            hit = cache.get(cacheKey);
        }
        if (hit != null) {
            Sink sink = new Sink(hit.rate, id, started, cb);
            sink.write(hit.data, hit.data.length);
            sink.finish();
            return;
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

        HttpURLConnection conn = (HttpURLConnection) new URL(
                API_BASE + model + ":streamGenerateContent?alt=sse").openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
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

            ByteArrayOutputStream all = new ByteArrayOutputStream();
            Sink sink = null;
            byte spare = 0;
            boolean hasSpare = false;
            boolean first = true;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!isCurrent(id)) {
                        return;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String json = line.substring(5).trim();
                    if (json.isEmpty()) {
                        continue;
                    }
                    JSONArray candidates = new JSONObject(json).optJSONArray("candidates");
                    if (candidates == null || candidates.length() == 0) {
                        continue;
                    }
                    JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
                    JSONArray parts = content == null ? null : content.optJSONArray("parts");
                    if (parts == null) {
                        continue;
                    }
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject inline = parts.getJSONObject(i).optJSONObject("inlineData");
                        if (inline == null || inline.optString("data").isEmpty()) {
                            continue;
                        }
                        byte[] chunk = Base64.decode(inline.getString("data"), Base64.DEFAULT);
                        int rate = 24000;
                        Matcher rm = RATE.matcher(inline.optString("mimeType"));
                        boolean hasRate = rm.find();
                        if (hasRate) {
                            rate = Integer.parseInt(rm.group(1));
                        }
                        if (first) {
                            first = false;
                            Pcm p = toPcm(chunk); // strips a WAV header if there is one
                            chunk = p.data;
                            if (!hasRate) {
                                rate = p.rate;
                            }
                            sink = new Sink(rate, id, started, cb);
                        }
                        if (hasSpare) { // keep samples 16-bit aligned across chunks
                            byte[] joined = new byte[chunk.length + 1];
                            joined[0] = spare;
                            System.arraycopy(chunk, 0, joined, 1, chunk.length);
                            chunk = joined;
                            hasSpare = false;
                        }
                        int usable = chunk.length & ~1;
                        if (usable != chunk.length) {
                            spare = chunk[chunk.length - 1];
                            hasSpare = true;
                        }
                        if (usable > 0) {
                            all.write(chunk, 0, usable);
                            sink.write(chunk, usable);
                        }
                    }
                }
            }
            if (sink == null) {
                throw new IOException("no audio");
            }
            Pcm done = new Pcm();
            done.data = all.toByteArray();
            done.rate = sink.rate;
            synchronized (cache) {
                cache.put(cacheKey, done);
            }
            sink.finish();
        } finally {
            conn.disconnect();
        }
    }

    /** Plays PCM chunks through one streaming AudioTrack as they arrive. */
    private class Sink {
        final int rate;
        final int id;
        final boolean[] started;
        final Callback cb;
        final AudioTrack t;
        long frames = 0;
        boolean playing = false;

        Sink(int rate, int id, boolean[] started, Callback cb) {
            this.rate = rate;
            this.id = id;
            this.started = started;
            this.cb = cb;
            int min = AudioTrack.getMinBufferSize(rate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int buf = Math.max(min, rate); // about half a second
            t = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    buf,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            synchronized (GeminiVoice.this) {
                if (id != generation) {
                    t.release();
                    throw new IllegalStateException("stopped");
                }
                track = t;
            }
        }

        void write(byte[] data, int len) {
            int off = 0;
            while (off < len) {
                int n = t.write(data, off, len - off); // blocks until there is room
                if (n <= 0) {
                    throw new IllegalStateException("audio closed");
                }
                off += n;
                frames += n / 2;
                if (!playing && frames >= rate * PREBUFFER_SEC) {
                    begin();
                }
            }
        }

        private void begin() {
            playing = true;
            started[0] = true;
            t.play();
            main.post(() -> {
                if (isCurrent(id)) {
                    cb.onStart();
                }
            });
        }

        void finish() throws InterruptedException {
            if (!playing && frames > 0) {
                begin(); // short clip that never reached the prebuffer size
            }
            if (!playing) {
                throw new IllegalStateException("no audio");
            }
            try {
                t.stop(); // in stream mode, buffered audio keeps playing out
            } catch (Exception ignored) {
                // released by stop()
            }
            long deadline = System.currentTimeMillis() + frames * 1000L / rate + 2000;
            try {
                while (isCurrent(id) && System.currentTimeMillis() < deadline
                        && t.getPlaybackHeadPosition() < frames) {
                    Thread.sleep(30);
                }
            } catch (IllegalStateException ignored) {
                // released by stop()
            }
            boolean current;
            synchronized (GeminiVoice.this) {
                current = id == generation;
                if (current) {
                    releaseTrack();
                }
            }
            if (current) {
                main.post(cb::onDone);
            }
        }
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
