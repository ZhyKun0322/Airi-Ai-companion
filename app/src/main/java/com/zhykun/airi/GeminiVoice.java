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
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.NoRouteToHostException;
import javax.net.ssl.SSLException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

/**
 * Speaks text with Gemini 3.8 TTS. Text remains independent from voice generation;
 * each sentence is synthesized off the UI thread and played through AudioTrack.
 * All callbacks run on the main thread.
 */
public class GeminiVoice {

    public interface Callback {
        /** audio is audibly starting now */
        void onStart();

        void onDone();

        /** no audio could be played at all */
        void onError();

        /** Optional diagnostic detail; older callback implementations remain compatible. */
        default void onError(String reason) {
            onError();
        }
    }

    // Flash-Lite is Google's low-latency TTS model for conversational voice cascades.
    // Use the flagship expressive model only if the preferred model is not available.
    public static final String DEFAULT_MODEL = "gemini-3.8-flash-lite-tts";
    private static final String FALLBACK_MODEL = "gemini-3.8-flash-tts";
    public static final String DEFAULT_VOICE = "Leda"; // Google describes Leda as youthful.

    private static final String TAG = "AiriGeminiVoice";
    // Gemini 3.8 TTS uses POST /v1beta/interactions, not models:streamGenerateContent.
    private static final String API_URL = "https://generativelanguage.googleapis.com/v1beta/interactions";
    private static final String STYLE =
            "Perform as an original Japanese-anime-inspired female character speaking naturally in the user's language, "
            + "not as a generic assistant. Use a youthful, bright mid-to-high pitch, a light "
            + "slightly airy resonance, bouncy conversational rhythm, melodic pitch movement, "
            + "cute natural reactions, and expressive emotional turns. Keep it warm, playful, "
            + "lively, and clear, with quick but understandable phrasing and short natural pauses. "
            + "Avoid a flat, deep, mature, formal, corporate, or narrator-like delivery. "
            + "Read only the supplied words; do not add explanations.";
    // Small startup buffer, balanced against avoiding choppy playback on mobile audio stacks.
    private static final double PREBUFFER_SEC = 0.08;

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

    /** Cancels active synthesis and releases the worker when the activity is destroyed. */
    public void close() {
        stop();
        worker.shutdownNow();
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

    /** Opens a session: the first line added starts the request right away, later lines follow it. */
    public Session begin(String key, String model, String voice, Callback cb) {
        final int id;
        synchronized (this) {
            generation++;
            releaseTrack();
            id = generation;
        }
        final String selectedModel = normalizeModel(model);
        final String selectedVoice = (voice == null || !voice.matches("[A-Za-z]+"))
                ? DEFAULT_VOICE : voice;
        final Session session = new Session();
        worker.execute(() -> run(session, key, selectedModel, selectedVoice, id, cb));
        return session;
    }

    /** Move old saved preview-model preferences to the supported stable TTS models. */
    private String normalizeModel(String model) {
        if (model == null || model.trim().isEmpty()) {
            return DEFAULT_MODEL;
        }
        String candidate = model.trim();
        // Migrate previous preview and flagship defaults to the lower-latency stable model.
        if (candidate.equals("gemini-3.1-flash-tts-preview")
                || candidate.equals("gemini-2.5-flash-preview-tts")
                || candidate.equals("gemini-2.5-pro-preview-tts")
                || candidate.equals("gemini-3.8-flash-tts")) {
            return DEFAULT_MODEL;
        }
        return candidate.matches("[A-Za-z0-9._-]+") ? candidate : DEFAULT_MODEL;
    }

    private void run(Session session, String key, String model, String voice, int id, Callback cb) {
        Sink[] sink = {null};
        boolean[] started = {false};
        try {
            while (isCurrent(id)) {
                // Don't leave the first phrase waiting behind a long queue-poll timeout.
                String line = session.queue.poll(20, TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                if (Session.END.equals(line)) {
                    break;
                }
                try {
                    long framesBeforeLine = sink[0] == null ? 0 : sink[0].frames;
                    try {
                        stream(key, model, voice, line, id, sink, started, cb);
                    } catch (Exception first) {
                        long framesAfterFailure = sink[0] == null ? 0 : sink[0].frames;
                        boolean partialLineWasWritten = framesAfterFailure > framesBeforeLine;
                        if (partialLineWasWritten && !started[0] && framesBeforeLine == 0) {
                            synchronized (GeminiVoice.this) {
                                if (id == generation) {
                                    releaseTrack();
                                }
                            }
                            sink[0] = null;
                            partialLineWasWritten = false;
                        }
                        if (partialLineWasWritten || model.equals(FALLBACK_MODEL)
                                || !isCurrent(id) || !shouldTryFallback(first)) {
                            throw first;
                        }
                        Log.w(TAG, "Primary TTS model unavailable; trying fallback: " + safeMessage(first));
                        stream(key, FALLBACK_MODEL, voice, line, id, sink, started, cb);
                        // Keep using the fallback for the rest of this reply.
                        model = FALLBACK_MODEL;
                    }
                    if (sink[0] != null) {
                        sink[0].ensurePlaying(); // a very short first line still starts now
                    }
                } catch (Exception e) {
                    if (!started[0]) {
                        throw e; // nothing played at all
                    }
                    // otherwise skip this one line and carry on with the next
                }
            }
            if (isCurrent(id) && sink[0] != null) {
                sink[0].finish();
            } else if (isCurrent(id)) {
                throw new IOException("no audio");
            }
        } catch (Exception e) {
            Log.w(TAG, "Gemini speech request failed: " + safeMessage(e));
            final String friendly = friendlyError(e);
            if (isCurrent(id)) {
                synchronized (this) {
                    if (id == generation) {
                        releaseTrack();
                    }
                }
                final boolean wasStarted = started[0];
                main.post(() -> {
                    if (!isCurrent(id)) {
                        return;
                    }
                    if (wasStarted) {
                        cb.onDone();
                    } else {
                        cb.onError(friendly);
                    }
                });
            }
        }
    }

    /** Streams one spoken line using the Gemini Interactions REST API. */
    private void stream(String key, String model, String voice, String text, int id,
                        Sink[] sinkRef, boolean[] started, Callback cb) throws Exception {
        if (model == null || !model.matches("[A-Za-z0-9._-]+")) {
            model = DEFAULT_MODEL;
        }
        if (voice == null || !voice.matches("[A-Za-z]+")) {
            voice = DEFAULT_VOICE;
        }

        final String cacheKey = model + "|" + voice + "|" + text;
        Pcm cached;
        synchronized (cache) {
            cached = cache.get(cacheKey);
        }
        if (cached != null) {
            if (sinkRef[0] == null) {
                sinkRef[0] = new Sink(cached.rate, id, started, cb);
            }
            sinkRef[0].write(cached.data, cached.data.length);
            return;
        }

        String transcript = prepareTranscript(text);
        String deliveryStyle = styleForText(text);
        JSONObject textPart = new JSONObject()
                .put("type", "text")
                .put("text", transcript)
                .put("annotations", new JSONArray().put(new JSONObject()
                        .put("type", "speech_metadata")
                        .put("style", deliveryStyle)));
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("input", new JSONArray().put(new JSONObject()
                        .put("type", "user_input")
                        .put("content", new JSONArray().put(textPart))))
                .put("response_format", new JSONObject()
                        .put("type", "audio")
                        .put("mime_type", "audio/l16")
                        .put("sample_rate", 24000))
                .put("generation_config", new JSONObject()
                        .put("speech_config", new JSONArray().put(new JSONObject()
                                .put("voice", voice))))
                .put("stream", true);

        HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);
            conn.setRequestProperty("x-goog-api-key", key);
            conn.setRequestProperty("content-type", "application/json; charset=utf-8");
            conn.setRequestProperty("accept", "text/event-stream");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String detail = readErrorBody(conn.getErrorStream());
                throw new IOException("Gemini TTS HTTP " + code
                        + (detail.isEmpty() ? "" : ": " + detail));
            }

            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte spare = 0;
            boolean hasSpare = false;
            String streamError = "";
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
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty() || "[DONE]".equals(payload)) {
                        continue;
                    }

                    JSONObject event;
                    try {
                        event = new JSONObject(payload);
                    } catch (Exception malformedEvent) {
                        Log.w(TAG, "Ignoring malformed Gemini TTS stream event");
                        continue;
                    }
                    JSONObject delta = event.optJSONObject("delta");
                    String partType = delta == null
                            ? event.optString("type", "") : delta.optString("type", "");
                    String encoded = delta == null
                            ? event.optString("data", "") : delta.optString("data", "");

                    if (!encoded.isEmpty() && "audio".equals(partType)) {
                        byte[] chunk = Base64.decode(encoded, Base64.DEFAULT);
                        if (hasSpare) {
                            byte[] joined = new byte[chunk.length + 1];
                            joined[0] = spare;
                            System.arraycopy(chunk, 0, joined, 1, chunk.length);
                            chunk = joined;
                            hasSpare = false;
                        }
                        int usable = chunk.length & ~1; // keep complete 16-bit samples
                        if (usable != chunk.length) {
                            spare = chunk[chunk.length - 1];
                            hasSpare = true;
                        }
                        if (usable > 0) {
                            if (sinkRef[0] == null) {
                                sinkRef[0] = new Sink(24000, id, started, cb);
                            }
                            all.write(chunk, 0, usable);
                            sinkRef[0].write(chunk, usable); // playback begins while later chunks arrive
                        }
                    } else {
                        JSONObject error = event.optJSONObject("error");
                        if (error != null) {
                            streamError = error.optString("message", "");
                        }
                    }
                }
            }

            if (all.size() == 0) {
                throw new IOException(streamError.isEmpty()
                        ? "Gemini TTS stream returned no audio chunks" : "Gemini TTS: " + streamError);
            }
            Pcm done = new Pcm();
            done.data = all.toByteArray();
            done.rate = 24000;
            synchronized (cache) {
                cache.put(cacheKey, done);
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Plays synthesized PCM through a single AudioTrack and drains it before cleanup. */
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
            // A small streaming buffer reduces latency and avoids very large AudioTrack allocations.
            int buf = Math.max(min, rate * 2);
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
            if (t.getState() != AudioTrack.STATE_INITIALIZED) {
                t.release();
                throw new IllegalStateException("AudioTrack could not initialize");
            }
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
                begin(); // very short clips still need to start playback
            }
            if (!playing) {
                throw new IllegalStateException("no audio");
            }

            // Drain the queued samples before stopping. Stopping first can truncate audio
            // and some Android audio implementations reset the playback-head position.
            long deadline = System.currentTimeMillis() + (frames * 1000L / rate) + 5000L;
            while (isCurrent(id) && System.currentTimeMillis() < deadline) {
                long played;
                try {
                    played = Integer.toUnsignedLong(t.getPlaybackHeadPosition());
                } catch (IllegalStateException released) {
                    break; // a stop/new reply released this track
                }
                if (played >= frames) {
                    break;
                }
                Thread.sleep(20);
            }

            boolean current;
            synchronized (GeminiVoice.this) {
                current = id == generation;
                if (current) {
                    try {
                        if (t.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                            t.stop();
                        }
                    } catch (Exception ignored) {
                        // track was already stopped or released
                    }
                    releaseTrack();
                }
            }
            if (current) {
                main.post(() -> {
                    if (isCurrent(id)) {
                        cb.onDone();
                    }
                });
            }
        }
    }

    /** Vary the acting style with Airi's mood tags while keeping the transcript itself clean. */
    private static String styleForText(String text) {
        String lower = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        String style = STYLE;
        if (lower.contains("[excited]") || lower.contains("[surprised]")) {
            style += " For this line, sound extra bright and excited, with lively upward inflection and a quick, delighted reaction.";
        } else if (lower.contains("[shy]") || lower.contains("[embarrassed]")) {
            style += " For this line, sound bashful and sweet, slightly softer and hesitant, with a small upward lilt.";
        } else if (lower.contains("[angry]") || lower.contains("[determined]")) {
            style += " For this line, sound spirited and emphatic, with animated energy but no shouting.";
        } else if (lower.contains("[sleepy]")) {
            style += " For this line, sound gentle, drowsy, and softly expressive.";
        } else if (lower.contains("[sad]") || lower.contains("[confused]")) {
            style += " For this line, sound concerned and sincere, with a softer, questioning lilt.";
        } else if (lower.contains("[playful]") || lower.contains("[happy]")) {
            style += " For this line, sound warmly playful and cheerful, with a light, bouncy cadence.";
        }
        return style;
    }

    private static String prepareTranscript(String text) {
        if (text == null) {
            return "";
        }
        // Convert Airi's optional stage cues to supported inline vocal tags.
        String prepared = text
                .replaceAll("(?i)\\[whispers\\]", "<whispers>")
                .replaceAll("(?i)\\[laughs?\\]", "<laugh>")
                .replaceAll("(?i)\\[sighs?\\]", "<sigh>")
                .replaceAll("(?i)\\[gasps?\\]", "<gasp>")
                .replaceAll("(?i)\\[(excited|curious|happy|playful|shy|embarrassed|angry|determined|sleepy|sad|confused|surprised|neutral|thinking)\\]", "")
                .replaceAll("\\[[^\\]]*\\]", "")
                .replaceAll("\\s+", " ")
                .trim();
        return prepared.isEmpty() ? text.trim() : prepared;
    }

    /** Retry another model only for an unavailable model, never for quota/auth/network errors. */
    private static boolean shouldTryFallback(Exception e) {
        String m = safeMessage(e).toLowerCase(java.util.Locale.ROOT);
        return m.contains("http 404") || m.contains("model_not_found")
                || m.contains("model not found") || m.contains("unknown model");
    }

    /** Turn transport/API errors into actionable messages without ever showing the API key. */
    private static String friendlyError(Exception e) {
        String m = safeMessage(e);
        String lower = m.toLowerCase(java.util.Locale.ROOT);
        int status = httpStatus(m);
        String detail = apiDetail(m);
        String extra = detail.isEmpty() ? "" : " Details: " + detail;

        if (e instanceof UnknownHostException || lower.contains("unknownhostexception")
                || lower.contains("unable to resolve host") || lower.contains("no address associated")) {
            return "Can't reach Gemini. Check Wi-Fi/mobile data, then try again.";
        }
        if (e instanceof SocketTimeoutException || lower.contains("timed out")
                || lower.contains("sockettimeoutexception")) {
            return "Gemini voice timed out. Check your connection and retry.";
        }
        if (e instanceof ConnectException || e instanceof NoRouteToHostException
                || e instanceof SSLException || lower.contains("failed to connect")) {
            return "Couldn't connect securely to Gemini. Check your internet connection and retry.";
        }

        switch (status) {
            case 400:
                return "Gemini rejected the voice request (HTTP 400). Check the TTS model/request settings." + extra;
            case 401:
                return "Gemini rejected the API key (HTTP 401). Re-enter a valid key from Google AI Studio." + extra;
            case 402:
                return "Gemini requires billing or available prepaid credit (HTTP 402). Check Google AI Studio billing." + extra;
            case 403:
                return "Gemini denied API/model access (HTTP 403). Check key restrictions, project eligibility, and billing in Google AI Studio." + extra;
            case 404:
                return "Gemini could not find the TTS model/endpoint (HTTP 404). Check model access or update the app." + extra;
            case 429:
                return "Gemini rate limit or quota reached (HTTP 429). Wait for the limit to reset, then retry." + extra;
            case 500:
            case 502:
            case 503:
            case 504:
                return "Gemini's service is temporarily failing (HTTP " + status + "). Try again later." + extra;
            default:
                break;
        }

        if (lower.contains("audiotrack") || lower.contains("audio closed")
                || lower.contains("no audio chunks")) {
            return "Gemini returned no playable audio. Check the selected TTS model and try again." + extra;
        }
        if (detail.length() > 0) {
            return "Gemini voice request failed: " + detail;
        }
        return "Gemini voice failed: " + (m.length() > 180 ? m.substring(0, 180) : m);
    }

    private static int httpStatus(String message) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)HTTP\\s+(\\d{3})").matcher(message == null ? "" : message);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static String apiDetail(String message) {
        if (message == null) return "";
        int start = message.indexOf("{");
        if (start < 0) return "";
        try {
            JSONObject root = new JSONObject(message.substring(start));
            JSONObject error = root.optJSONObject("error");
            if (error == null) return "";
            String detail = error.optString("message", "").trim();
            if (detail.isEmpty()) return "";
            // API errors shouldn't include secrets, but scrub anything that resembles a key.
            detail = detail.replaceAll("(?i)(key|api_key)\\s*[:=]\\s*[^,\\s]+", "$1=[redacted]");
            return detail.length() > 180 ? detail.substring(0, 180) : detail;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readErrorBody(InputStream in) {
        if (in == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (result.length() > 600) {
                    break;
                }
                result.append(line);
            }
            return result.length() > 600 ? result.substring(0, 600) : result.toString();
        } catch (IOException ignored) {
            return "";
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 700 ? message.substring(0, 700) : message;
    }


}
