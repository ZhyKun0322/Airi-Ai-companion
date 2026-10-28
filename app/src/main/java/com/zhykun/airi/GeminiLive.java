package com.zhykun.airi;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Real-time voice chat with Gemini Live over one WebSocket.
 *
 * Microphone audio streams up while she talks, her voice streams back and is played as it
 * arrives, and talking over her cuts her off. The mood of each reply comes through a
 * set_mood function call so the sprite can change. All Listener callbacks run on the main thread.
 */
public class GeminiLive {

    public interface Listener {
        /** The session is open and the microphone is live. */
        void onReady();

        /** What the user has said so far in this turn (from the transcript). */
        void onUserText(String textSoFar);

        /** What Airi has said so far in this turn (from the transcript). */
        void onAiText(String textSoFar);

        /** Airi chose a mood, one of MOODS. */
        void onMood(String mood);

        /** Her voice started playing. */
        void onSpeakingStart();

        /** Her voice finished, or she was interrupted. */
        void onSpeakingEnd();

        /** The session is over. message is a sentence for the user. */
        void onEnded(String message);
    }

    public static final String DEFAULT_MODEL = "gemini-3.8-live";
    public static final String[] MOODS = {"neutral", "happy", "playful", "thinking", "surprised", "angry",
            "sleepy", "embarrassed", "excited", "shy", "confused", "determined"};

    private static final String WS_URL = "wss://generativelanguage.googleapis.com/ws/"
            + "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=";

    private static final int IN_RATE = 16000;                          // microphone, 16-bit mono
    private static final int OUT_RATE = 24000;                         // her voice, 16-bit mono
    private static final int MIC_CHUNK_BYTES = IN_RATE * 2 * 40 / 1000; // 40 ms per message
    private static final int PLAY_SLICE_BYTES = OUT_RATE * 2 * 20 / 1000; // write 20 ms at a time

    // tuning: lower SILENCE_MS makes her answer sooner but may cut you off mid-sentence
    private static final int SILENCE_MS = 450;
    private static final long END_GRACE_MS = 250;   // gap in the audio stream that still counts as one reply
    private static final long TAIL_MS = 400;        // mic stays muted this long after she stops (no-interrupt mode)
    private static final int MAX_QUEUED_BYTES = 96 * 1024; // drop mic audio if the network is this far behind

    private static class Chunk {
        final byte[] data;
        final int epoch;

        Chunk(byte[] data, int epoch) {
            this.data = data;
            this.epoch = epoch;
        }
    }

    private final Context app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean ended = new AtomicBoolean(false);
    private final AtomicInteger epoch = new AtomicInteger(0);
    private final LinkedBlockingQueue<Chunk> playQueue = new LinkedBlockingQueue<>();
    private final Object lock = new Object();

    private volatile boolean running = false;
    private volatile boolean turnDone = true;
    private volatile boolean goAway = false;
    private volatile boolean ready = false;

    private OkHttpClient client;
    private volatile WebSocket ws;
    private AudioRecord rec;
    private volatile AudioTrack track;
    private Thread micThread;
    private Thread playThread;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private AutomaticGainControl agc;

    private String model = DEFAULT_MODEL;
    private String voice = "Leda";
    private String systemPrompt = "";
    private String greeting = null;
    private boolean bargeIn = true;

    // guarded by lock
    private boolean speaking = false;
    private long playUntil = 0;

    // only touched on the WebSocket reader thread
    private final StringBuilder userText = new StringBuilder();
    private final StringBuilder aiText = new StringBuilder();
    private boolean userFresh = true;
    private boolean aiFresh = true;

    // audio routing to restore
    private AudioManager audioManager;
    private int prevMode = AudioManager.MODE_NORMAL;
    private boolean prevSpeaker = false;
    private boolean routed = false;

    public GeminiLive(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isRunning() {
        return running && !ended.get();
    }

    /**
     * Opens the session. greeting, if not null, is sent as the first thing the user "says"
     * so she greets them straight away.
     */
    public void start(String apiKey, String model, String voice, String systemPrompt,
                      boolean bargeIn, String greeting) {
        if (running) {
            return;
        }
        if (model != null && model.matches("(models/)?[A-Za-z0-9._-]+")) {
            this.model = model;
        }
        if (voice != null && voice.matches("[A-Za-z]+")) {
            this.voice = voice;
        }
        this.systemPrompt = systemPrompt;
        this.bargeIn = bargeIn;
        this.greeting = greeting;
        running = true;

        try {
            routeAudio();
            openAudio();
        } catch (Exception e) {
            finish("I can't use the microphone or speaker right now.");
            return;
        }

        playThread = new Thread(this::playLoop, "airi-play");
        playThread.start();

        client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder().url(WS_URL + apiKey).build();
        ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                try {
                    socket.send(buildSetup().toString());
                } catch (Exception e) {
                    finish("Something went wrong starting the voice chat.");
                }
            }

            @Override
            public void onMessage(WebSocket socket, String text) {
                handle(text);
            }

            @Override
            public void onMessage(WebSocket socket, ByteString bytes) {
                handle(bytes.utf8());
            }

            @Override
            public void onClosing(WebSocket socket, int code, String reason) {
                socket.close(1000, null);
                finish(closeMessage(code, reason));
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                finish(closeMessage(code, reason));
            }

            @Override
            public void onFailure(WebSocket socket, Throwable t, Response response) {
                finish(failureMessage(response));
            }
        });
    }

    /** Sends typed text into the live conversation; she answers out loud. */
    public void sendText(String text) {
        WebSocket w = ws;
        if (w == null || !ready || ended.get() || text == null || text.trim().isEmpty()) {
            return;
        }
        w.send("{\"realtimeInput\":{\"text\":" + JSONObject.quote(text) + "}}");
    }

    /** Ends the session without calling Listener.onEnded. */
    public void stop() {
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        stopInternal();
    }

    // ---------- setup message ----------

    private JSONObject buildSetup() throws Exception {
        String modelName = model.startsWith("models/") ? model : "models/" + model;

        JSONObject generation = new JSONObject()
                .put("responseModalities", new JSONArray().put("AUDIO"))
                .put("speechConfig", new JSONObject()
                        .put("voiceConfig", new JSONObject()
                                .put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice))));

        JSONObject moodParam = new JSONObject()
                .put("type", "STRING")
                .put("enum", new JSONArray(Arrays.asList(MOODS)))
                .put("description", "How Airi feels right now.");
        JSONObject setMood = new JSONObject()
                .put("name", "set_mood")
                .put("description", "Changes Airi's facial expression on screen. Call it at the start of "
                        + "every reply and again whenever her feeling changes.")
                .put("parameters", new JSONObject()
                        .put("type", "OBJECT")
                        .put("properties", new JSONObject().put("mood", moodParam))
                        .put("required", new JSONArray().put("mood")));

        JSONObject activity = new JSONObject()
                .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                .put("prefixPaddingMs", 20)
                .put("silenceDurationMs", SILENCE_MS);

        JSONObject setup = new JSONObject()
                .put("model", modelName)
                .put("generationConfig", generation)
                .put("systemInstruction", new JSONObject()
                        .put("parts", new JSONArray().put(new JSONObject().put("text", systemPrompt))))
                .put("tools", new JSONArray().put(new JSONObject()
                        .put("functionDeclarations", new JSONArray().put(setMood))))
                .put("realtimeInputConfig", new JSONObject().put("automaticActivityDetection", activity))
                .put("inputAudioTranscription", new JSONObject())
                .put("outputAudioTranscription", new JSONObject())
                // keeps long chats going instead of ending at the audio session limit
                .put("contextWindowCompression", new JSONObject().put("slidingWindow", new JSONObject()));
        return new JSONObject().put("setup", setup);
    }

    // ---------- messages from Gemini ----------

    private void handle(String text) {
        try {
            JSONObject o = new JSONObject(text);
            if (o.has("setupComplete")) {
                onSetupComplete();
                return;
            }
            JSONObject content = o.optJSONObject("serverContent");
            if (content != null) {
                handleContent(content);
            }
            JSONObject toolCall = o.optJSONObject("toolCall");
            if (toolCall != null) {
                handleToolCall(toolCall);
            }
            if (o.has("goAway")) {
                goAway = true;
            }
        } catch (Exception ignored) {
            // a message we can't read is skipped
        }
    }

    private void onSetupComplete() {
        if (ready || ended.get()) {
            return;
        }
        ready = true;
        try {
            rec.startRecording();
        } catch (Exception e) {
            finish("I can't use the microphone right now.");
            return;
        }
        micThread = new Thread(this::micLoop, "airi-mic");
        micThread.start();
        post(listener::onReady);
        if (greeting != null) {
            sendText(greeting);
        }
    }

    private void handleContent(JSONObject content) {
        if (content.optBoolean("interrupted")) {
            flushPlayback();
            userFresh = true;
            aiFresh = true;
        }

        JSONObject in = content.optJSONObject("inputTranscription");
        if (in != null) {
            String t = in.optString("text");
            if (!t.isEmpty()) {
                if (userFresh) {
                    userText.setLength(0);
                    userFresh = false;
                }
                userText.append(t);
                final String so = userText.toString();
                post(() -> listener.onUserText(so));
            }
        }

        JSONObject turn = content.optJSONObject("modelTurn");
        if (turn != null) {
            JSONArray parts = turn.optJSONArray("parts");
            for (int i = 0; parts != null && i < parts.length(); i++) {
                JSONObject inline = parts.optJSONObject(i) == null ? null : parts.optJSONObject(i).optJSONObject("inlineData");
                if (inline == null) {
                    continue;
                }
                String data = inline.optString("data");
                if (data.isEmpty()) {
                    continue;
                }
                turnDone = false;
                playQueue.offer(new Chunk(Base64.decode(data, Base64.DEFAULT), epoch.get()));
            }
        }

        JSONObject out = content.optJSONObject("outputTranscription");
        if (out != null) {
            String t = out.optString("text");
            if (!t.isEmpty()) {
                if (aiFresh) {
                    aiText.setLength(0);
                    aiFresh = false;
                }
                aiText.append(t);
                final String so = aiText.toString();
                post(() -> listener.onAiText(so));
            }
        }

        if (content.optBoolean("turnComplete")) {
            turnDone = true;
            userFresh = true;
            aiFresh = true;
        }
    }

    private void handleToolCall(JSONObject toolCall) throws Exception {
        JSONArray calls = toolCall.optJSONArray("functionCalls");
        if (calls == null) {
            return;
        }
        JSONArray responses = new JSONArray();
        for (int i = 0; i < calls.length(); i++) {
            JSONObject call = calls.getJSONObject(i);
            String name = call.optString("name");
            String id = call.optString("id");
            String result = "ok";
            if (name.equals("set_mood")) {
                JSONObject args = call.optJSONObject("args");
                final String mood = args == null ? "" : args.optString("mood");
                if (!mood.isEmpty()) {
                    post(() -> listener.onMood(mood));
                }
            } else {
                result = "unknown function";
            }
            responses.put(new JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("response", new JSONObject().put("result", result)));
        }
        WebSocket w = ws;
        if (w != null && responses.length() > 0) {
            w.send(new JSONObject()
                    .put("toolResponse", new JSONObject().put("functionResponses", responses)).toString());
        }
    }

    // ---------- microphone ----------

    private void micLoop() {
        byte[] buf = new byte[MIC_CHUNK_BYTES];
        while (running) {
            AudioRecord r = rec;
            if (r == null) {
                return;
            }
            int n;
            try {
                n = r.read(buf, 0, buf.length);
            } catch (IllegalStateException e) {
                return;
            }
            if (n < 0) {
                return;
            }
            if (n == 0 || mutedForPlayback()) {
                continue;
            }
            WebSocket w = ws;
            if (w == null || w.queueSize() > MAX_QUEUED_BYTES) {
                continue;
            }
            String b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP);
            w.send("{\"realtimeInput\":{\"audio\":{\"data\":\"" + b64
                    + "\",\"mimeType\":\"audio/pcm;rate=" + IN_RATE + "\"}}}");
        }
    }

    // when interrupting is off, the mic is not sent while she talks so she can't hear herself
    private boolean mutedForPlayback() {
        if (bargeIn) {
            return false;
        }
        synchronized (lock) {
            return speaking || SystemClock.elapsedRealtime() < playUntil + TAIL_MS;
        }
    }

    // ---------- playback ----------

    private void playLoop() {
        while (running) {
            Chunk c;
            try {
                c = playQueue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (c == null) {
                continue;
            }
            int off = 0;
            while (off < c.data.length && running && c.epoch == epoch.get()) {
                AudioTrack t = track;
                if (t == null) {
                    return;
                }
                int n = Math.min(PLAY_SLICE_BYTES, c.data.length - off);
                int w;
                try {
                    w = t.write(c.data, off, n);
                } catch (IllegalStateException e) {
                    return;
                }
                if (w <= 0) {
                    break;
                }
                off += w;
                noteWritten(w, c.epoch);
            }
        }
    }

    private void noteWritten(int bytes, int chunkEpoch) {
        boolean startNow = false;
        synchronized (lock) {
            if (chunkEpoch != epoch.get()) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            long ms = bytes * 1000L / (OUT_RATE * 2L);
            playUntil = Math.max(playUntil, now) + ms;
            if (!speaking) {
                speaking = true;
                startNow = true;
            }
        }
        if (startNow) {
            main.post(() -> {
                if (ended.get()) {
                    return;
                }
                main.removeCallbacks(speakWatch);
                listener.onSpeakingStart();
                main.postDelayed(speakWatch, 60);
            });
        }
    }

    // decides when her voice is really over (audio played out and the turn is complete)
    private final Runnable speakWatch = new Runnable() {
        @Override
        public void run() {
            boolean end;
            synchronized (lock) {
                if (!speaking) {
                    return;
                }
                long now = SystemClock.elapsedRealtime();
                end = now >= playUntil && (turnDone || now >= playUntil + END_GRACE_MS) && playQueue.isEmpty();
                if (end) {
                    speaking = false;
                }
            }
            if (end) {
                if (!ended.get()) {
                    listener.onSpeakingEnd();
                }
            } else {
                main.postDelayed(this, 60);
            }
        }
    };

    // she was interrupted: drop everything queued and silence the speaker now
    private void flushPlayback() {
        boolean wasSpeaking;
        synchronized (lock) {
            epoch.incrementAndGet();
            playQueue.clear();
            wasSpeaking = speaking;
            speaking = false;
            playUntil = 0;
        }
        AudioTrack t = track;
        if (t != null) {
            try {
                t.pause();
                t.flush();
                t.play();
            } catch (IllegalStateException ignored) {
                // track already released
            }
        }
        if (wasSpeaking) {
            main.post(() -> {
                main.removeCallbacks(speakWatch);
                if (!ended.get()) {
                    listener.onSpeakingEnd();
                }
            });
        }
    }

    // ---------- audio setup and cleanup ----------

    private void openAudio() {
        int minIn = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minIn, MIC_CHUNK_BYTES * 4));
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("microphone not available");
        }
        int session = rec.getAudioSessionId();
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(session);
            if (aec != null) {
                aec.setEnabled(true);
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(session);
            if (ns != null) {
                ns.setEnabled(true);
            }
        }
        if (AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(session);
            if (agc != null) {
                agc.setEnabled(true);
            }
        }

        int minOut = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(OUT_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(minOut * 2, OUT_RATE * 2 * 3 / 10))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();
        if (t.getState() != AudioTrack.STATE_INITIALIZED) {
            t.release();
            throw new IllegalStateException("speaker not available");
        }
        t.play(); // keep it running so the first word has no start-up delay
        track = t;
    }

    // voice-call mode gives echo cancellation; send sound to the speaker unless a headset is connected
    @SuppressWarnings("deprecation")
    private void routeAudio() {
        audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }
        prevMode = audioManager.getMode();
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        routed = true;
        if (Build.VERSION.SDK_INT >= 31) {
            AudioDeviceInfo speaker = null;
            boolean external = false;
            for (AudioDeviceInfo d : audioManager.getAvailableCommunicationDevices()) {
                switch (d.getType()) {
                    case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                        speaker = d;
                        break;
                    case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                    case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                    case AudioDeviceInfo.TYPE_USB_HEADSET:
                    case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                    case AudioDeviceInfo.TYPE_BLE_HEADSET:
                        external = true;
                        break;
                    default:
                        break;
                }
            }
            if (!external && speaker != null) {
                audioManager.setCommunicationDevice(speaker);
            }
        } else {
            prevSpeaker = audioManager.isSpeakerphoneOn();
            if (!audioManager.isWiredHeadsetOn() && !audioManager.isBluetoothScoOn()) {
                audioManager.setSpeakerphoneOn(true);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void restoreAudio() {
        if (!routed || audioManager == null) {
            return;
        }
        routed = false;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                audioManager.clearCommunicationDevice();
            } else {
                audioManager.setSpeakerphoneOn(prevSpeaker);
            }
            audioManager.setMode(prevMode);
        } catch (Exception ignored) {
            // leave the audio settings as they are
        }
    }

    private void finish(String message) {
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        stopInternal();
        main.post(() -> listener.onEnded(message));
    }

    private void stopInternal() {
        running = false;
        ready = false;
        main.removeCallbacks(speakWatch);
        WebSocket w = ws;
        if (w != null) {
            try {
                w.close(1000, "bye");
            } catch (Exception ignored) {
                // already closed
            }
        }
        if (micThread != null) {
            micThread.interrupt();
        }
        if (playThread != null) {
            playThread.interrupt();
        }
        AudioRecord r = rec;
        rec = null;
        if (r != null) {
            try {
                r.stop();
            } catch (Exception ignored) {
                // never started
            }
            r.release();
        }
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try {
                t.pause();
                t.flush();
            } catch (Exception ignored) {
                // already stopped
            }
            t.release();
        }
        if (aec != null) {
            aec.release();
        }
        if (ns != null) {
            ns.release();
        }
        if (agc != null) {
            agc.release();
        }
        playQueue.clear();
        restoreAudio();
        if (client != null) {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }

    private void post(Runnable r) {
        main.post(() -> {
            if (!ended.get()) {
                r.run();
            }
        });
    }

    // ---------- messages for the user ----------

    private static final String KEY_MESSAGE = "My key was rejected. Please check the API key in Settings.";

    private String closeMessage(int code, String reason) {
        String r = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (r.contains("api key") || r.contains("permission") || r.contains("unauthenticated")) {
            return KEY_MESSAGE;
        }
        if (r.contains("not found") || r.contains("not supported")) {
            return "That voice model isn't available. Try another live model name in Settings.";
        }
        if (r.contains("quota") || r.contains("exhausted") || r.contains("rate")) {
            return "I've used up my voice chats for now. Try again in a bit.";
        }
        if (goAway || code == 1000 || code == 1001) {
            return "Our voice chat timed out. Tap Talk to start again.";
        }
        String detail = reason == null ? "" : reason.trim();
        if (detail.length() > 140) {
            detail = detail.substring(0, 140);
        }
        return detail.isEmpty() ? "The voice chat ended (code " + code + ")." : detail;
    }

    private String failureMessage(Response response) {
        if (response == null) {
            return "I can't reach the internet right now.";
        }
        int code = response.code();
        if (code == 400 || code == 401 || code == 403) {
            return KEY_MESSAGE;
        }
        if (code == 404) {
            return "That voice model isn't available. Try another live model name in Settings.";
        }
        if (code == 429) {
            return "I've used up my voice chats for now. Try again in a bit.";
        }
        if (code >= 500) {
            return "The AI service is busy right now. Try again soon.";
        }
        return "Something went wrong (error " + code + ").";
    }
}
