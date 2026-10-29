package com.zhykun.airi;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Speaks text with the Gemini TTS API. Audio starts playing as soon as the first chunk arrives,
 * and every sentence of a reply is requested in parallel (played in order) so there is no gap
 * between sentences. All callbacks run on the main thread.
 *
 * Model handling: the preferred model is tried first; if Google rejects it (retired, no access,
 * quota used up) the next one in FALLBACK_MODELS is tried, and the one that failed is skipped for
 * a while so later sentences don't pay for the failure again.
 */
public class GeminiVoice {

    public interface Callback {
        /** audio is audibly starting now */
        void onStart();

        void onDone();

        /** no audio could be played at all */
        void onError();

        /** same as onError(), plus a short reason that can be shown to the user */
        default void onError(String reason) {
            onError();
        }
    }

    // Gemini 3.8 Flash-Lite TTS is Google's low-latency model for voice agents and read-aloud.
    public static final String DEFAULT_MODEL = "gemini-3.8-flash-lite-tts";
    private static final String[] FALLBACK_MODELS = {
            "gemini-3.8-flash-tts",
            "gemini-3.1-flash-tts-preview",   // legacy, Google shuts it down from 17 Nov 2026
            "gemini-2.5-flash-preview-tts"};  // legacy, same date
    public static final String DEFAULT_VOICE = "Leda";

    private static final String TAG = "AiriVoice";
    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    // Gemini 3.8 reads `text` word for word, so acting direction goes in speech_metadata.style
    // (kept short and constant, as Google recommends) and sounds go in as <tags> inside the text.
    private static final String STYLE_38 = "bright, playful and expressive, with natural breath and emotion";
    // the older models take their direction as a sentence in front of the words
    private static final String STYLE_LEGACY =
            "Say the following in the voice of a cute, bright, youthful anime girl. "
            + "Act out sounds like umm, hmph, eh, ehehe, ah and stammering with real breath and emotion, "
            + "never read them flatly: ";

    private static final Pattern RATE = Pattern.compile("rate=(\\d+)");
    private static final Pattern SQUARE_TAG = Pattern.compile("\\[([^\\]]*)\\]");
    private static final Pattern ANGLE_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern HAS_WORD = Pattern.compile("[\\p{L}\\p{N}]");
    // start playing once this much audio is buffered (seconds) to avoid stutter
    private static final double PREBUFFER_SEC = 0.25;

    // markers passed between threads
    private static final Object DONE = new Object();   // this sentence is complete
    private static final Object LAST = new Object();   // no more sentences

    private static class Pcm {
        byte[] data;
        int rate = 24000;
    }

    private static final class HttpError extends IOException {
        final int code;

        HttpError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final class NoAudio extends IOException {
        NoAudio(String message) {
            super(message);
        }
    }

    private final ExecutorService worker = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Pcm> cache = new LinkedHashMap<String, Pcm>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Pcm> eldest) {
            return size() > 24;
        }
    };
    // model -> time (ms) until which it is skipped because it just failed
    private final Map<String, Long> badUntil = new HashMap<>();

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

    /** Lines added to a session are spoken back to back, in one gapless stream of audio. */
    public static final class Session {
        private static final String END = "\u0000END";
        private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();

        public void add(String line) {
            if (line != null && !line.trim().isEmpty()) {
                queue.offer(line);
            }
        }

        /** no more lines will come */
        public void end() {
            queue.offer(END);
        }
    }

    public void speak(String key, String model, String voice, String text, Callback cb) {
        Session s = begin(key, model, voice, cb);
        s.add(text);
        s.end();
    }

    /** Opens a session: each line added is requested right away, and played in order. */
    public Session begin(String key, String model, String voice, Callback cb) {
        final int id;
        synchronized (this) {
            generation++;
            releaseTrack();
            id = generation;
        }
        final String m = (model == null || !model.matches("[A-Za-z0-9._-]+")) ? DEFAULT_MODEL : model;
        final String v = (voice == null || !voice.matches("[A-Za-z0-9_]+")) ? DEFAULT_VOICE : voice;
        final Session session = new Session();
        worker.execute(() -> run(session, key, m, v, id, cb));
        return session;
    }

    // ---------- model fallback ----------

    private List<String> chain(String preferred) {
        List<String> all = new ArrayList<>();
        all.add(preferred);
        for (String m : FALLBACK_MODELS) {
            if (!all.contains(m)) {
                all.add(m);
            }
        }
        long now = System.currentTimeMillis();
        List<String> usable = new ArrayList<>();
        synchronized (badUntil) {
            for (String m : all) {
                Long until = badUntil.get(m);
                if (until == null || until <= now) {
                    usable.add(m);
                }
            }
        }
        return usable.isEmpty() ? all : usable;
    }

    private void markBad(String model, int code) {
        long ms;
        if (code == 404 || code == 403) {
            ms = 10 * 60_000L; // not available to this key
        } else if (code == 400) {
            ms = 2 * 60_000L;
        } else {
            ms = 30_000L;      // quota or a busy server: try again soon
        }
        synchronized (badUntil) {
            badUntil.put(model, System.currentTimeMillis() + ms);
        }
    }

    // models released before Gemini 3.8 want the old request format
    private static boolean isLegacy(String model) {
        return model.contains("preview-tts") || model.contains("3.1-flash-tts");
    }

    // ---------- run: dispatch sentences, play them in order ----------

    private void run(Session session, String key, String model, String voice, int id, Callback cb) {
        final AtomicBoolean dead = new AtomicBoolean(false);
        final LinkedBlockingQueue<Object> order = new LinkedBlockingQueue<>();
        final boolean[] started = {false};
        worker.execute(() -> dispatch(session, key, model, voice, id, dead, order));

        Sink sink = null;
        String problem = null;
        try {
            while (isCurrent(id)) {
                Object next = order.poll(200, TimeUnit.MILLISECONDS);
                if (next == null) {
                    continue;
                }
                if (next == LAST) {
                    break;
                }
                Fetch f = (Fetch) next;
                boolean lineDone = false;
                while (!lineDone && isCurrent(id)) {
                    Object item = f.out.poll(200, TimeUnit.MILLISECONDS);
                    if (item == null) {
                        continue;
                    }
                    if (item == DONE) {
                        lineDone = true;
                        if (sink != null) {
                            sink.ensurePlaying(); // a very short line still starts now
                        }
                    } else if (item instanceof Throwable) {
                        lineDone = true;
                        problem = describe((Throwable) item);
                        if (!started[0]) {
                            throw new IOException(problem); // nothing played at all: report it now
                        }
                        // otherwise skip this one line and carry on with the next
                    } else {
                        byte[] b = (byte[]) item;
                        if (sink == null) {
                            sink = new Sink(f.rate, id, started, cb);
                        }
                        sink.write(b, b.length);
                    }
                }
            }
            if (!isCurrent(id)) {
                return;
            }
            if (sink != null) {
                sink.finish();
            } else {
                throw new IOException(problem != null ? problem : "no audio came back");
            }
        } catch (Exception e) {
            dead.set(true);
            if (isCurrent(id)) {
                synchronized (this) {
                    if (id == generation) {
                        releaseTrack();
                    }
                }
                final boolean wasStarted = started[0];
                final String reason = describe(e);
                Log.w(TAG, "voice failed: " + reason);
                main.post(() -> {
                    if (isCurrent(id)) {
                        if (wasStarted) {
                            cb.onDone();
                        } else {
                            cb.onError(reason);
                        }
                    }
                });
            }
        }
    }

    private void dispatch(Session session, String key, String model, String voice, int id,
                          AtomicBoolean dead, LinkedBlockingQueue<Object> order) {
        try {
            while (isCurrent(id) && !dead.get()) {
                String line = session.queue.poll(200, TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                if (Session.END.equals(line)) {
                    break;
                }
                // requested straight away, in parallel with the sentences before it
                Fetch f = new Fetch(key, model, voice, line, id);
                order.offer(f);
                worker.execute(f);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            order.offer(LAST);
        }
    }

    private static String describe(Throwable t) {
        String m;
        if (t instanceof UnknownHostException || t instanceof ConnectException
                || t instanceof SocketTimeoutException) {
            m = "can't reach Google (" + t.getClass().getSimpleName() + ")";
        } else {
            m = t.getMessage();
            if (m == null || m.isEmpty()) {
                m = t.getClass().getSimpleName();
            }
        }
        m = m.replaceAll("\\s+", " ").trim();
        return m.length() > 220 ? m.substring(0, 220) + "..." : m;
    }

    // ---------- one sentence ----------

    /** Downloads the audio for one sentence and hands the chunks to the player through `out`. */
    private final class Fetch implements Runnable {
        final String key;
        final String preferred;
        final String voice;
        final String line;
        final int id;
        // byte[] audio chunks (16-bit aligned), then DONE, or a Throwable if it failed
        final LinkedBlockingQueue<Object> out = new LinkedBlockingQueue<>();
        volatile int rate = 24000;
        private boolean emitted = false; // only touched by the fetch thread

        Fetch(String key, String preferred, String voice, String line, int id) {
            this.key = key;
            this.preferred = preferred;
            this.voice = voice;
            this.line = line;
            this.id = id;
        }

        @Override
        public void run() {
            try {
                fetch();
                out.offer(DONE);
            } catch (Throwable t) {
                out.offer(t);
            }
        }

        private void fetch() throws Exception {
            String cacheKey = preferred + "|" + voice + "|" + line;
            Pcm hit;
            synchronized (cache) {
                hit = cache.get(cacheKey);
            }
            if (hit != null) {
                rate = hit.rate;
                out.offer(hit.data);
                return;
            }
            List<String> failures = new ArrayList<>();
            for (String model : chain(preferred)) {
                if (!isCurrent(id)) {
                    return;
                }
                try {
                    stream(model, cacheKey);
                    return;
                } catch (HttpError | NoAudio e) {
                    if (emitted) {
                        throw e; // part of this sentence is already playing: don't repeat it
                    }
                    Log.w(TAG, "model failed: " + e.getMessage());
                    failures.add(e.getMessage());
                    markBad(model, e instanceof HttpError ? ((HttpError) e).code : 0);
                }
            }
            if (!isCurrent(id)) {
                return;
            }
            StringBuilder why = new StringBuilder();
            for (int i = 0; i < failures.size() && i < 2; i++) {
                why.append(i == 0 ? "" : "; ").append(failures.get(i));
            }
            throw new IOException(why.length() == 0 ? "no audio came back" : why.toString());
        }

        /** Streams one request. Throws HttpError / NoAudio if the model could not speak. */
        private void stream(String model, String cacheKey) throws Exception {
            boolean legacy = isLegacy(model);
            String transcript = legacy ? legacyText(line) : transcript38(line);
            if (transcript == null) {
                return; // nothing to say (only a sound cue)
            }
            JSONObject body = legacy ? legacyBody(transcript, voice) : body38(transcript, voice);

            HttpURLConnection conn = (HttpURLConnection) new URL(
                    API_BASE + model + ":streamGenerateContent?alt=sse").openConnection();
            boolean clean = false; // fully read, so the connection can be reused for the next sentence
            try {
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);
                conn.setRequestProperty("x-goog-api-key", key);
                conn.setRequestProperty("content-type", "application/json");
                // no gzip: compressed streams are delivered in blocks, which delays the first sound
                conn.setRequestProperty("Accept-Encoding", "identity");
                try (OutputStream o = conn.getOutputStream()) {
                    o.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    String detail = errorDetail(conn.getErrorStream());
                    clean = true;
                    throw new HttpError(code, model + ": HTTP " + code
                            + (detail.isEmpty() ? "" : " - " + detail));
                }

                ByteArrayOutputStream all = new ByteArrayOutputStream();
                byte spare = 0;
                boolean hasSpare = false;
                boolean first = true;
                String finish = "";
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String sse;
                    while ((sse = reader.readLine()) != null) {
                        if (!isCurrent(id)) {
                            return; // cancelled; the connection is dropped below
                        }
                        if (!sse.startsWith("data:")) {
                            continue;
                        }
                        String json = sse.substring(5).trim();
                        if (json.isEmpty()) {
                            continue;
                        }
                        JSONObject event = new JSONObject(json);
                        JSONArray candidates = event.optJSONArray("candidates");
                        if (candidates == null || candidates.length() == 0) {
                            JSONObject feedback = event.optJSONObject("promptFeedback");
                            if (feedback != null && !feedback.optString("blockReason").isEmpty()) {
                                finish = "blocked: " + feedback.optString("blockReason");
                            }
                            continue;
                        }
                        JSONObject candidate = candidates.getJSONObject(0);
                        String reason = candidate.optString("finishReason");
                        if (!reason.isEmpty() && !"STOP".equals(reason)) {
                            finish = reason;
                        }
                        JSONObject content = candidate.optJSONObject("content");
                        JSONArray parts = content == null ? null : content.optJSONArray("parts");
                        if (parts == null) {
                            continue;
                        }
                        for (int i = 0; i < parts.length(); i++) {
                            JSONObject part = parts.getJSONObject(i);
                            JSONObject inline = part.optJSONObject("inlineData");
                            if (inline == null) {
                                inline = part.optJSONObject("inline_data");
                            }
                            if (inline == null || inline.optString("data").isEmpty()) {
                                continue;
                            }
                            byte[] chunk = Base64.decode(inline.getString("data"), Base64.DEFAULT);
                            String mime = inline.optString("mimeType", inline.optString("mime_type", ""));
                            Matcher rm = RATE.matcher(mime);
                            boolean hasRate = rm.find();
                            int chunkRate = hasRate ? Integer.parseInt(rm.group(1)) : 24000;
                            if (first) {
                                first = false;
                                Pcm p = toPcm(chunk); // strips a WAV header if there is one
                                chunk = p.data;
                                rate = hasRate ? chunkRate : p.rate;
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
                                byte[] piece = usable == chunk.length ? chunk : Arrays.copyOf(chunk, usable);
                                all.write(piece, 0, piece.length);
                                emitted = true;
                                out.offer(piece);
                            }
                        }
                    }
                }
                clean = true;
                if (all.size() == 0) {
                    throw new NoAudio(model + ": no audio" + (finish.isEmpty() ? "" : " (" + finish + ")"));
                }
                Pcm done = new Pcm();
                done.data = all.toByteArray();
                done.rate = rate;
                synchronized (cache) {
                    cache.put(cacheKey, done);
                }
            } finally {
                if (!clean) {
                    conn.disconnect();
                }
            }
        }
    }

    // ---------- request bodies and text ----------

    private static JSONObject body38(String transcript, String voice) throws Exception {
        JSONObject part = new JSONObject()
                .put("text", transcript)
                .put("speech_metadata", new JSONObject().put("style", STYLE_38));
        return new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject()
                        .put("role", "user")
                        .put("parts", new JSONArray().put(part))))
                .put("generationConfig", new JSONObject()
                        .put("responseModalities", new JSONArray().put("AUDIO"))
                        .put("speechConfig", new JSONObject()
                                .put("voiceConfig", new JSONObject().put("voice", voice))));
    }

    private static JSONObject legacyBody(String transcript, String voice) throws Exception {
        return new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject()
                        .put("parts", new JSONArray().put(new JSONObject()
                                .put("text", STYLE_LEGACY + transcript)))))
                .put("generationConfig", new JSONObject()
                        .put("responseModalities", new JSONArray().put("AUDIO"))
                        .put("speechConfig", new JSONObject()
                                .put("voiceConfig", new JSONObject()
                                        .put("prebuiltVoiceConfig", new JSONObject()
                                                .put("voiceName", voice)))));
    }

    private static String tidy(String s) {
        return s.replace("*", "").replaceAll("\\s+", " ").trim();
    }

    /**
     * Gemini 3.8 speaks the text word for word. Voice cues written as [whispers] become the
     * <whispers> sound tag; other [cues] (moods) are not spoken, so they are removed.
     * Returns null when there is nothing left to say.
     */
    static String transcript38(String raw) {
        Matcher m = SQUARE_TAG.matcher(raw);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String tag = soundTag(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(tag.isEmpty() ? " " : " " + tag + " "));
        }
        m.appendTail(sb);
        String text = tidy(sb.toString());
        return HAS_WORD.matcher(ANGLE_TAG.matcher(text).replaceAll("")).find() ? text : null;
    }

    /** The older models understand [square] cues themselves, so the text is left as written. */
    static String legacyText(String raw) {
        String text = tidy(raw);
        return HAS_WORD.matcher(SQUARE_TAG.matcher(text).replaceAll("")).find() ? text : null;
    }

    private static String soundTag(String cue) {
        switch (cue.trim().toLowerCase(Locale.ROOT)) {
            case "whispers":
            case "whisper":
            case "whispering":
                return "<whispers>";
            case "laughs":
            case "laugh":
            case "laughing":
                return "<laugh>";
            case "giggles":
            case "giggle":
                return "<giggle>";
            case "sigh":
            case "sighs":
                return "<sigh>";
            case "gasp":
            case "gasps":
                return "<gasp>";
            case "breath":
            case "breathes":
                return "<breath>";
            default:
                return ""; // moods and other cues are not spoken
        }
    }

    private static String errorDetail(InputStream in) {
        if (in == null) {
            return "";
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) {
                sb.append(l).append('\n');
            }
            String text = sb.toString().trim();
            try {
                JSONObject o = text.startsWith("[")
                        ? new JSONArray(text).getJSONObject(0)
                        : new JSONObject(text);
                String msg = o.getJSONObject("error").optString("message");
                return msg.isEmpty() ? text : msg;
            } catch (Exception notJson) {
                return text;
            }
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- playback ----------

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
            int buf = Math.max(min, rate * 2 * 20); // 20 s of audio, so the next line can be fetched while this one plays
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

        void ensurePlaying() {
            if (!playing && frames > 0) {
                begin();
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
            if (id.equals("fmt ") && pos + 16 <= b.length) {
                p.rate = le32(b, pos + 12);
            }
            if (id.equals("data")) {
                int start = pos + 8;
                // a streamed WAV may not know its length yet (0xFFFFFFFF): take everything left
                int len = size < 0 || start + size > b.length ? b.length - start : size;
                p.data = Arrays.copyOfRange(b, start, start + len);
                break;
            }
            if (size < 0) {
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
