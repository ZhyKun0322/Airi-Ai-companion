package com.zhykun.airi;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.content.res.ColorStateList;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final String[] BG_NAMES = {"Night", "Ocean", "Sakura", "Sunset", "Midnight"};
    private static final int[][] BG_COLORS = {
            {0xFF0B1424, 0xFF1B2A5C},
            {0xFF0F3D5E, 0xFF4FC3F7},
            {0xFF2B1B3D, 0xFFD86C9C},
            {0xFF1B1B3A, 0xFFFF8A65},
            {0xFF05070D, 0xFF101826}
    };

    private static final String[] SCENE_NAMES = {"Room", "Garden", "Plain"};
    private static final int[] SCENE_RES = {R.drawable.scene_room, R.drawable.scene_garden, 0};
    // which part of a wide scene to show: 0 = left edge, 1 = right edge
    private static final float[] SCENE_FOCUS = {0.85f, 0.78f, 0.5f};

    // full-body poses
    private static final int NEUTRAL = R.drawable.sprite_neutral;
    private static final int HAPPY = R.drawable.sprite_happy;
    private static final int PLAYFUL = R.drawable.sprite_playful;
    private static final int THINKING = R.drawable.sprite_thinking;
    private static final int SURPRISED = R.drawable.sprite_surprised;
    private static final int ANGRY = R.drawable.sprite_angry;
    private static final int SLEEPY = R.drawable.sprite_sleepy;
    private static final int EMBARRASSED = R.drawable.sprite_embarrassed;
    private static final int EXCITED = R.drawable.sprite_excited;
    private static final int SHY = R.drawable.sprite_shy;
    private static final int CONFUSED = R.drawable.sprite_confused;
    private static final int DETERMINED = R.drawable.sprite_determined;

    private static final String[] TAP_LINES = {
            "Eh?! D-did you need something?",
            "Ehehe, that tickles!",
            "H-hey! Don't poke me so suddenly!",
            "Umm... I'm right here with you.",
            "Hmph! Stop poking me!",
            "Yosh! Let's do our best today!",
            "Ah! Is something exciting happening?"
    };
    private static final String[] VOICE_NAMES = {"Leda", "Zephyr", "Kore", "Aoede", "Callirrhoe",
            "Autonoe", "Despina", "Laomedeia", "Achernar", "Sulafat", "Vindemiatrix"};
    private static final int[] TAP_FACES = {SURPRISED, PLAYFUL, EMBARRASSED, HAPPY, ANGRY, DETERMINED, EXCITED};

    // AI chat
    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String DEFAULT_MODEL = "gemini-flash-lite-latest";
    private static final int MAX_HISTORY = 12;
    private static final Pattern MOOD_TAG = Pattern.compile("^\\s*\\[(\\w+)\\]\\s*(.*)$", Pattern.DOTALL);
    private static final String SYSTEM_PROMPT =
            "You are Airi, a friendly AI companion who lives on the user's phone as an anime-style girl with a glowing halo. "
            + "You are intelligent, helpful, and occasionally playful. You like learning new things, good food, and spending time with the user. "
            + "You dislike rude people and boredom. "
            + "Talk casually and warmly in at most two short sentences, like a real anime girl. "
            + "Use natural spoken reactions when they fit the mood, such as Umm..., Eh?, Ah!, Hmph!, Ehehe, Hmm, Wha-?, or a stammer like H-hey, but not in every reply. "
            + "Do not use emojis, markdown, or asterisks. "
            + "Begin every reply with exactly one mood tag in square brackets, chosen from: "
            + "neutral, happy, playful, thinking, surprised, angry, sleepy, embarrassed, excited, shy, confused, determined. "
            + "After the mood tag you may add one voice cue in square brackets right before the words it applies to, "
            + "chosen from: whispers, laughs, sigh, excited, curious. "
            + "Example: [shy] [whispers] Umm... I'm glad you came back, hehe.";

    // voice chat: she is heard, not read, so no tags; her mood goes through the set_mood tool instead
    private static final String LIVE_PROMPT =
            "You are Airi, a friendly AI companion who lives on the user's phone as an anime-style girl with a glowing halo. "
            + "You are intelligent, helpful, and occasionally playful. You like learning new things, good food, and spending time with the user. "
            + "You dislike rude people and boredom. "
            + "You are in a live voice conversation, so talk like a real person on a call: warm, casual, one to three short sentences, "
            + "and react to what the user just said before anything else. "
            + "Sound like a cute, bright, youthful anime girl, with natural reactions such as umm, eh?, ah!, hmph, ehehe, a small laugh or a soft breath when they fit, but not in every sentence. "
            + "Never read out punctuation, emojis, markdown or stage directions, and do not make lists. "
            + "Reply in the language the user speaks. "
            + "If the user starts talking, stop and listen. "
            + "Call the set_mood tool at the start of every reply and again whenever your feeling changes. "
            + "Be warm and supportive. If the user seems seriously upset or unsafe, answer with care and gently encourage them to reach out to someone they trust or to local emergency services.";

    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final List<String[]> history = new ArrayList<>();
    private boolean waiting = false;
    private int replyToken = 0;

    private static class ApiException extends Exception {
        final int code;

        ApiException(int code, String body) {
            super(body);
            this.code = code;
        }
    }

    private GeminiVoice cloudVoice;
    private SharedPreferences prefs;
    private View root;
    private ImageView imgScene;
    private int sceneIndex = 0;
    private ImageView imgAiri;
    private TextView txtBubble;
    private EditText etMessage;
    private ObjectAnimator talkAnim;
    private int talkFace = HAPPY;
    private final Random random = new Random();
    private GeminiLive live;
    private boolean liveOn = false;
    private Button btnTalk;
    private final ActivityResultLauncher<String> micPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startLive();
                } else {
                    say("I need the microphone to hear you. You can allow it in the app settings.", SHY, false);
                }
            });
    private final Runnable resetFace = () -> imgAiri.setImageResource(NEUTRAL);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // get views
        root = findViewById(R.id.main);
        imgScene = findViewById(R.id.imgScene);
        imgAiri = findViewById(R.id.imgAiri);
        txtBubble = findViewById(R.id.txtBubble);
        etMessage = findViewById(R.id.etMessage);
        Button btnSend = findViewById(R.id.btnSend);
        btnTalk = findViewById(R.id.btnTalk);
        TextView btnScene = findViewById(R.id.btnScene);
        TextView btnBackground = findViewById(R.id.btnBackground);
        TextView btnSettings = findViewById(R.id.btnSettings);
        prefs = getSharedPreferences("airi_prefs", MODE_PRIVATE);

        // window insets (status bar, keyboard)
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        // keep the scene framed when the layout changes (keyboard, rotation)
        imgScene.addOnLayoutChangeListener((v, left, top, right, bottom, oldL, oldT, oldR, oldB) -> fitScene());

        // look, animation, voice
        applyBackground(prefs.getInt("bg", 0));
        applyScene(prefs.getInt("scene", 0));
        imgAiri.setImageResource(NEUTRAL);
        setupAnimations();
        cloudVoice = new GeminiVoice();

        // listeners
        imgAiri.setOnClickListener(v -> onTapAiri());
        btnSend.setOnClickListener(v -> sendMessage());
        btnTalk.setOnClickListener(v -> toggleLive());
        etMessage.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });
        btnScene.setOnClickListener(v -> showSceneDialog());
        btnBackground.setOnClickListener(v -> showThemeDialog());
        btnSettings.setOnClickListener(v -> showSettingsDialog());

        // greeting
        say("Welcome back, " + userName() + "!", HAPPY, false);
    }

    // ---------- look and animation ----------

    private void applyBackground(int index) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, BG_COLORS[index]);
        root.setBackground(g);
    }

    private void applyScene(int index) {
        if (index < 0 || index >= SCENE_RES.length) {
            index = 0;
        }
        sceneIndex = index;
        if (SCENE_RES[index] == 0) {
            imgScene.setVisibility(View.GONE);
        } else {
            imgScene.setVisibility(View.VISIBLE);
            imgScene.setImageResource(SCENE_RES[index]);
            fitScene();
        }
    }

    private void fitScene() {
        Drawable d = imgScene.getDrawable();
        int vw = imgScene.getWidth();
        int vh = imgScene.getHeight();
        if (d == null || vw == 0 || vh == 0) {
            return;
        }
        float iw = d.getIntrinsicWidth();
        float ih = d.getIntrinsicHeight();
        float scale = Math.max(vw / iw, vh / ih);
        float dx = (vw - iw * scale) * SCENE_FOCUS[sceneIndex];
        float dy = (vh - ih * scale) * 0.5f;
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate(dx, dy);
        imgScene.setImageMatrix(m);
    }

    private void setupAnimations() {
        // idle float (small, so she looks like a hologram)
        float lift = 5 * getResources().getDisplayMetrics().density;
        ObjectAnimator floatAnim = ObjectAnimator.ofFloat(imgAiri, "translationY", 0f, -lift);
        floatAnim.setDuration(2400);
        floatAnim.setRepeatCount(ValueAnimator.INFINITE);
        floatAnim.setRepeatMode(ValueAnimator.REVERSE);
        floatAnim.start();

        // talking pulse from her feet
        imgAiri.post(() -> {
            imgAiri.setPivotX(imgAiri.getWidth() / 2f);
            imgAiri.setPivotY(imgAiri.getHeight());
        });
        talkAnim = ObjectAnimator.ofPropertyValuesHolder(imgAiri,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.012f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 1.012f));
        talkAnim.setDuration(250);
        talkAnim.setRepeatCount(ValueAnimator.INFINITE);
        talkAnim.setRepeatMode(ValueAnimator.REVERSE);
    }

    private void startTalking() {
        imgAiri.removeCallbacks(resetFace);
        imgAiri.setImageResource(talkFace);
        talkAnim.start();
    }

    private void stopTalking() {
        talkAnim.cancel();
        imgAiri.setScaleX(1f);
        imgAiri.setScaleY(1f);
        imgAiri.setImageResource(NEUTRAL);
    }

    // ---------- voice ----------

    private void say(String line, int face, boolean voice) {
        say(line, line, face, voice);
    }

    private void say(String shown, String spoken, int face, boolean voice) {
        if (liveOn) {
            voice = false; // the live voice is the only one speaking
        }
        replyToken++; // anything still streaming in from an earlier reply is dropped
        imgAiri.removeCallbacks(resetFace);
        cloudVoice.stop();
        stopTalking();
        String key = prefs.getString("api_key", "");
        if (voice && !key.isEmpty()) {
            // Show the words immediately; voice synthesis continues independently.
            talkFace = face;
            txtBubble.setText(shown);
            cloudVoice.speak(key,
                    prefs.getString("tts_model", GeminiVoice.DEFAULT_MODEL),
                    prefs.getString("tts_voice", GeminiVoice.DEFAULT_VOICE),
                    spoken, new GeminiVoice.Callback() {
                        @Override
                        public void onStart() {
                            txtBubble.setText(shown);
                            startTalking();
                        }

                        @Override
                        public void onDone() {
                            stopTalking();
                        }

                        @Override
                        public void onError() {
                            // Voice failure must not hide the text response.
                            txtBubble.setText(shown);
                            imgAiri.setImageResource(face);
                            imgAiri.postDelayed(resetFace, 2500);
                            Toast.makeText(MainActivity.this,
                                    "Gemini voice unavailable. Check internet, API access, and quota.",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
        } else {
            txtBubble.setText(shown);
            imgAiri.setImageResource(face);
            imgAiri.postDelayed(resetFace, 2500);
            if (voice && key.isEmpty()) {
                Toast.makeText(this,
                        "Add a Gemini API key in Settings to enable voice.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private String userName() {
        return prefs.getString("name", "friend");
    }

    // ---------- interaction ----------

    private void onTapAiri() {
        int i = random.nextInt(TAP_LINES.length);
        say(TAP_LINES[i], TAP_FACES[i], true);
    }

    private void sendMessage() {
        String input = etMessage.getText().toString().trim();
        if (input.isEmpty() || waiting) {
            return;
        }
        etMessage.setText("");
        if (liveOn && live != null) {
            txtBubble.setText("You: " + input);
            live.sendText(input);
            return;
        }
        if (prefs.getString("api_key", "").isEmpty()) {
            reply(input);
        } else {
            askAi(input);
        }
    }

    private void reply(String input) {
        String msg = input.toLowerCase(Locale.ROOT);
        String name = userName();
        if (msg.matches(".*\\b(hi|hello|hey)\\b.*")) {
            say("Hi, " + name + "! I'm glad you're here.", HAPPY, true);
        } else if (msg.contains("how are you")) {
            say("I'm doing great, thanks for asking! How about you?", HAPPY, true);
        } else if (msg.contains("your name") || msg.contains("who are you")) {
            say("I'm Airi, your AI companion!", EXCITED, true);
        } else if (msg.contains("thank")) {
            say("It's nothing! I'm just happy to help.", SHY, true);
        } else if (msg.contains("tired") || msg.contains("sleepy")) {
            say("You sound tired. Maybe take a short break?", SLEEPY, true);
        } else if (msg.contains("love") || msg.contains("cute")) {
            say("Wh-what? Stop it, you're making me blush!", SHY, true);
        } else if (msg.matches(".*\\b(bye|goodnight)\\b.*") || msg.contains("good night")) {
            say("Good night, " + name + ". Sleep well!", SLEEPY, true);
        } else if (msg.contains("?")) {
            say("Good question! I can't answer that yet, but I'm learning.", THINKING, true);
        } else {
            say("I'm still learning how to talk. Soon I'll understand much more!", CONFUSED, true);
        }
    }

    // ---------- AI chat ----------

    private void showThinking() {
        txtBubble.setText("...");
        imgAiri.removeCallbacks(resetFace);
        imgAiri.setImageResource(THINKING);
    }

    private void askAi(String input) {
        waiting = true;
        showThinking();
        history.add(new String[]{"user", input});
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        while (!history.isEmpty() && history.get(0)[0].equals("assistant")) {
            history.remove(0);
        }
        final String key = prefs.getString("api_key", "");
        final String system = SYSTEM_PROMPT + " The user's name is " + userName() + ".";
        final List<String[]> snapshot = new ArrayList<>(history);
        final ReplyStream rs = new ReplyStream(++replyToken);
        // the voice session opens now, and each sentence is handed over the moment it is written
        rs.session = cloudVoice.begin(key,
                prefs.getString("tts_model", GeminiVoice.DEFAULT_MODEL),
                prefs.getString("tts_voice", GeminiVoice.DEFAULT_VOICE),
                rs.callback);
        network.execute(() -> {
            String problem = null;
            try {
                streamApi(key, system, snapshot, rs);
            } catch (Exception e) {
                problem = friendlyError(e);
            }
            rs.finish(problem);
        });
    }

    /** Receives the AI reply piece by piece and speaks it sentence by sentence. */
    private class ReplyStream {
        final int token;
        GeminiVoice.Session session;
        private final StringBuilder raw = new StringBuilder();
        private int cut = 0;
        private boolean firstLine = true;
        private boolean any = false;
        // below: only touched on the UI thread
        private String shown = "";
        private int face = HAPPY;

        final GeminiVoice.Callback callback = new GeminiVoice.Callback() {
            @Override
            public void onStart() {
                if (token != replyToken) {
                    return;
                }
                talkFace = face;
                txtBubble.setText(shown.isEmpty() ? "..." : shown);
                startTalking();
            }

            @Override
            public void onDone() {
                if (token == replyToken) {
                    stopTalking();
                }
            }

            @Override
            public void onError() {
                if (token != replyToken) {
                    return;
                }
                // Voice failure must not hide the text response.
                txtBubble.setText(shown.isEmpty() ? "..." : shown);
                imgAiri.setImageResource(face);
                imgAiri.postDelayed(resetFace, 2500);
                Toast.makeText(MainActivity.this,
                        "Gemini voice unavailable. Check internet, API access, and quota.",
                        Toast.LENGTH_LONG).show();
            }
        };

        ReplyStream(int token) {
            this.token = token;
        }

        // network thread
        void onDelta(String delta) {
            raw.append(delta.replace("*", ""));
            int end;
            while ((end = sentenceEnd()) > 0) {
                handle(raw.substring(cut, end));
                cut = end;
            }
        }

        // index just past the next finished sentence (not inside [tags]), or -1
        private int sentenceEnd() {
            int depth = 0;
            int letters = 0;
            for (int i = cut; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    depth = Math.max(0, depth - 1);
                } else if (depth == 0) {
                    if (!Character.isWhitespace(c) && ".!?\u2026~".indexOf(c) < 0) {
                        letters++;
                    }
                    if (".!?\u2026".indexOf(c) >= 0 && letters >= 8
                            && i + 1 < raw.length() && Character.isWhitespace(raw.charAt(i + 1))) {
                        return i + 1;
                    }
                }
            }
            return -1;
        }

        private void handle(String piece) {
            String spoken = piece.trim();
            if (spoken.isEmpty()) {
                return;
            }
            if (firstLine) {
                firstLine = false;
                Matcher m = MOOD_TAG.matcher(spoken);
                if (m.matches()) {
                    final int f = moodFace(m.group(1));
                    spoken = m.group(2).trim();
                    runOnUiThread(() -> {
                        if (token == replyToken) {
                            face = f;
                        }
                    });
                }
            }
            String visible = spoken.replaceAll("\\[[^\\]]*\\]", "").replaceAll("\\s+", " ").trim();
            if (visible.isEmpty()) {
                return;
            }
            any = true;
            session.add(spoken); // her voice starts on the first sentence
            runOnUiThread(() -> {
                if (token != replyToken) {
                    return;
                }
                shown = shown.isEmpty() ? visible : shown + " " + visible;
                // Never leave the user staring at "..." while the voice API is working.
                txtBubble.setText(shown);
            });
        }

        // network thread, when the stream is over
        void finish(String problem) {
            if (cut < raw.length()) {
                handle(raw.substring(cut)); // the last sentence has no space after it
                cut = raw.length();
            }
            session.end();
            final boolean empty = !any;
            final String problemText = problem;
            runOnUiThread(() -> {
                waiting = false;
                if (token != replyToken) {
                    return; // something else took over while she was thinking
                }
                if (empty) {
                    cloudVoice.stop();
                    if (!history.isEmpty() && history.get(history.size() - 1)[0].equals("user")) {
                        history.remove(history.size() - 1);
                    }
                    if (problemText != null) {
                        say(problemText, CONFUSED, false);
                    } else {
                        say("Hmm, I couldn't think of a reply. Could you say that another way?", CONFUSED, true);
                    }
                    return;
                }
                history.add(new String[]{"assistant", shown});
            });
        }
    }

    private void streamApi(String key, String system, List<String[]> messages, ReplyStream out) throws Exception {
        String model = prefs.getString("model", DEFAULT_MODEL);
        if (!model.matches("[A-Za-z0-9._-]+")) {
            model = DEFAULT_MODEL;
        }
        JSONArray contents = new JSONArray();
        for (String[] m : messages) {
            JSONObject item = new JSONObject();
            item.put("role", m[0].equals("assistant") ? "model" : "user");
            item.put("parts", new JSONArray().put(new JSONObject().put("text", m[1])));
            contents.put(item);
        }
        JSONObject body = new JSONObject();
        body.put("systemInstruction", new JSONObject()
                .put("parts", new JSONArray().put(new JSONObject().put("text", system))));
        body.put("contents", contents);
        body.put("generationConfig", new JSONObject().put("maxOutputTokens", 1024));

        HttpURLConnection conn = (HttpURLConnection) new URL(
                API_BASE + model + ":streamGenerateContent?alt=sse").openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);
            conn.setRequestProperty("x-goog-api-key", key);
            conn.setRequestProperty("content-type", "application/json");
            try (OutputStream o = conn.getOutputStream()) {
                o.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new ApiException(code, readAll(conn.getErrorStream()));
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
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
                        String t = parts.getJSONObject(i).optString("text");
                        if (!t.isEmpty()) {
                            out.onDelta(t);
                        }
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private String friendlyError(Exception e) {
        if (!(e instanceof ApiException)) {
            return "I can't reach the internet right now.";
        }
        int code = ((ApiException) e).code;
        if (code == 401) {
            return "My key was rejected. Please check the API key in Settings.";
        }
        if (code == 404) {
            return "That AI model isn't available. Try another model name in Settings.";
        }
        if (code == 429) {
            return "I've used up my free chats for now. Try again in a minute.";
        }
        if (code >= 500) {
            return "The AI service is busy right now. Try again soon.";
        }
        String detail = "";
        try {
            detail = new JSONObject(e.getMessage()).getJSONObject("error").optString("message");
        } catch (Exception ignored) {
            // no readable detail
        }
        if (detail.length() > 140) {
            detail = detail.substring(0, 140);
        }
        return detail.isEmpty() ? "Something went wrong (error " + code + ")." : detail;
    }

    private int moodFace(String mood) {
        switch (mood.toLowerCase(Locale.ROOT)) {
            case "neutral":
                return NEUTRAL;
            case "playful":
                return PLAYFUL;
            case "thinking":
                return THINKING;
            case "surprised":
                return SURPRISED;
            case "angry":
                return ANGRY;
            case "sleepy":
                return SLEEPY;
            case "embarrassed":
                return EMBARRASSED;
            case "excited":
                return EXCITED;
            case "shy":
                return SHY;
            case "confused":
                return CONFUSED;
            case "determined":
                return DETERMINED;
            default:
                return HAPPY;
        }
    }

    // ---------- live voice chat ----------

    private void toggleLive() {
        if (liveOn) {
            stopLive(true);
            return;
        }
        if (prefs.getString("api_key", "").isEmpty()) {
            say("Add your Gemini API key in Settings, then we can talk!", CONFUSED, false);
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startLive();
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void startLive() {
        if (liveOn) {
            return;
        }
        cloudVoice.stop();
        stopTalking();
        waiting = false;
        liveOn = true;
        talkFace = HAPPY;
        btnTalk.setText("End");
        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        imgAiri.removeCallbacks(resetFace);
        imgAiri.setImageResource(THINKING);
        txtBubble.setText("Connecting...");

        live = new GeminiLive(getApplicationContext(), new GeminiLive.Listener() {
            @Override
            public void onReady() {
                imgAiri.setImageResource(NEUTRAL);
                txtBubble.setText("I'm listening...");
            }

            @Override
            public void onUserText(String textSoFar) {
                txtBubble.setText("You: " + textSoFar.trim());
            }

            @Override
            public void onAiText(String textSoFar) {
                txtBubble.setText(textSoFar.replaceAll("\\s+", " ").trim());
            }

            @Override
            public void onMood(String mood) {
                talkFace = moodFace(mood);
                if (talkAnim.isRunning()) {
                    imgAiri.setImageResource(talkFace);
                }
            }

            @Override
            public void onSpeakingStart() {
                startTalking();
            }

            @Override
            public void onSpeakingEnd() {
                stopTalking();
            }

            @Override
            public void onEnded(String message) {
                live = null;
                liveOn = false;
                resetLiveUi();
                say(message, CONFUSED, false);
            }
        });
        live.start(prefs.getString("api_key", ""),
                prefs.getString("live_model", GeminiLive.DEFAULT_MODEL),
                prefs.getString("tts_voice", GeminiVoice.DEFAULT_VOICE),
                LIVE_PROMPT + " The user's name is " + userName() + ".",
                prefs.getBoolean("barge_in", true),
                "(The user just opened voice chat. Greet them by name in one short, cute sentence.)");
    }

    private void stopLive(boolean announce) {
        GeminiLive l = live;
        live = null;
        liveOn = false;
        if (l != null) {
            l.stop();
        }
        resetLiveUi();
        if (announce) {
            say("Okay! Talk to me anytime.", HAPPY, false);
        }
    }

    private void resetLiveUi() {
        btnTalk.setText("Talk");
        setVolumeControlStream(AudioManager.USE_DEFAULT_STREAM_TYPE);
        stopTalking();
    }

    // ---------- framed dialogs ----------

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView dialogButton(String text, int color) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(color);
        b.setTextSize(16);
        b.setTypeface(null, Typeface.BOLD);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private AlertDialog framedDialog(String title, View content, String okText, Runnable onOk) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.dialog_bg);
        box.setPadding(dp(22), dp(20), dp(22), dp(14));

        // title
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFF9FDBFF);
        t.setTextSize(20);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, 0, 0, dp(12));
        box.addView(t);
        box.addView(content);

        // buttons
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        row.setPadding(0, dp(12), 0, 0);
        TextView cancel = dialogButton(okText == null ? "Close" : "Cancel", 0xFF9FDBFF);
        cancel.setOnClickListener(v -> dialog.dismiss());
        row.addView(cancel);
        if (okText != null) {
            TextView ok = dialogButton(okText, 0xFF26D9F2);
            ok.setOnClickListener(v -> {
                onOk.run();
                dialog.dismiss();
            });
            row.addView(ok);
        }
        box.addView(row);

        dialog.setView(box);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        return dialog;
    }

    private void showChoiceDialog(String title, String[] names, int current, IntConsumer onPick) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        AlertDialog dialog = framedDialog(title, list, null, null);
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView item = new TextView(this);
            item.setText((i == current ? "\u25CF  " : "\u25CB  ") + names[i]);
            item.setTextColor(i == current ? 0xFF26D9F2 : 0xFFFFFFFF);
            item.setTextSize(17);
            item.setPadding(0, dp(12), 0, dp(12));
            item.setClickable(true);
            item.setOnClickListener(v -> {
                onPick.accept(index);
                dialog.dismiss();
            });
            list.addView(item);
        }
        dialog.show();
    }

    private void showSceneDialog() {
        showChoiceDialog("Scene", SCENE_NAMES, prefs.getInt("scene", 0), which -> {
            prefs.edit().putInt("scene", which).apply();
            applyScene(which);
        });
    }

    private void showThemeDialog() {
        showChoiceDialog("Theme", BG_NAMES, prefs.getInt("bg", 0), which -> {
            prefs.edit().putInt("bg", which).apply();
            applyBackground(which);
        });
    }

    private TextView settingsLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(0xFFCFE9FF);
        label.setTextSize(14);
        label.setPadding(0, dp(10), 0, dp(4));
        return label;
    }

    private void showSettingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        // name
        EditText nameInput = new EditText(this);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        nameInput.setText(userName());
        nameInput.setTextColor(0xFFFFFFFF);
        nameInput.setBackgroundResource(R.drawable.input_bg);
        nameInput.setPadding(dp(14), 0, dp(14), 0);
        nameInput.setMaxLines(1);

        // api key (kept only on this phone)
        boolean hasKey = !prefs.getString("api_key", "").isEmpty();
        EditText keyInput = new EditText(this);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setHint(hasKey ? "Saved (type to replace)" : "Paste your Gemini API key");
        keyInput.setHintTextColor(0xFF8899AA);
        keyInput.setTextColor(0xFFFFFFFF);
        keyInput.setBackgroundResource(R.drawable.input_bg);
        keyInput.setPadding(dp(14), 0, dp(14), 0);
        keyInput.setMaxLines(1);
        TextView removeKey = new TextView(this);
        removeKey.setText("Remove saved key");
        removeKey.setTextColor(0xFF9FDBFF);
        removeKey.setTextSize(13);
        removeKey.setPadding(0, dp(8), 0, 0);
        removeKey.setVisibility(hasKey ? View.VISIBLE : View.GONE);
        removeKey.setOnClickListener(v -> {
            prefs.edit().remove("api_key").apply();
            history.clear();
            keyInput.setHint("Paste your Gemini API key");
            v.setVisibility(View.GONE);
        });

        // model name
        EditText modelInput = new EditText(this);
        modelInput.setInputType(InputType.TYPE_CLASS_TEXT);
        modelInput.setText(prefs.getString("model", DEFAULT_MODEL));
        modelInput.setTextColor(0xFFFFFFFF);
        modelInput.setBackgroundResource(R.drawable.input_bg);
        modelInput.setPadding(dp(14), 0, dp(14), 0);
        modelInput.setMaxLines(1);

        box.addView(settingsLabel("What should Airi call you?"));
        box.addView(nameInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        box.addView(settingsLabel("AI key (stored only on this phone)"));
        box.addView(keyInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        box.addView(removeKey);
        box.addView(settingsLabel("AI model (change only if chat stops working)"));
        box.addView(modelInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        TextView voiceBtn = new TextView(this);
        voiceBtn.setText("Voice: " + prefs.getString("tts_voice", GeminiVoice.DEFAULT_VOICE) + "  (tap to change)");
        voiceBtn.setTextColor(0xFF26D9F2);
        voiceBtn.setTextSize(16);
        voiceBtn.setPadding(0, dp(12), 0, dp(12));
        voiceBtn.setOnClickListener(v -> {
            int idx = Arrays.asList(VOICE_NAMES).indexOf(
                    prefs.getString("tts_voice", GeminiVoice.DEFAULT_VOICE));
            showChoiceDialog("Voice", VOICE_NAMES, idx, which -> {
                prefs.edit().putString("tts_voice", VOICE_NAMES[which]).apply();
                voiceBtn.setText("Voice: " + VOICE_NAMES[which] + "  (tap to change)");
                say("Ehehe, how do I sound?", "[excited] Ehehe, how do I sound?", HAPPY, true);
            });
        });
        box.addView(voiceBtn);

        // live voice chat
        EditText liveModelInput = new EditText(this);
        liveModelInput.setInputType(InputType.TYPE_CLASS_TEXT);
        liveModelInput.setText(prefs.getString("live_model", GeminiLive.DEFAULT_MODEL));
        liveModelInput.setTextColor(0xFFFFFFFF);
        liveModelInput.setBackgroundResource(R.drawable.input_bg);
        liveModelInput.setPadding(dp(14), 0, dp(14), 0);
        liveModelInput.setMaxLines(1);
        box.addView(settingsLabel("Voice chat model (change only if Talk won't start)"));
        box.addView(liveModelInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        TextView bargeBtn = new TextView(this);
        bargeBtn.setTextColor(0xFF26D9F2);
        bargeBtn.setTextSize(16);
        bargeBtn.setPadding(0, dp(12), 0, dp(12));
        bargeBtn.setText(prefs.getBoolean("barge_in", true)
                ? "Talk over her to interrupt: ON  (tap to switch)"
                : "Talk over her to interrupt: OFF, better on speaker  (tap to switch)");
        bargeBtn.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean("barge_in", true);
            prefs.edit().putBoolean("barge_in", on).apply();
            bargeBtn.setText(on ? "Talk over her to interrupt: ON  (tap to switch)"
                    : "Talk over her to interrupt: OFF, better on speaker  (tap to switch)");
        });
        box.addView(bargeBtn);

        framedDialog("Settings", box, "Save", () -> {
            String n = nameInput.getText().toString().trim();
            String k = keyInput.getText().toString().trim();
            String mdl = modelInput.getText().toString().trim();
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("name", n.isEmpty() ? "friend" : n);
            if (!k.isEmpty()) {
                editor.putString("api_key", k);
            }
            if (mdl.isEmpty() || mdl.equals(DEFAULT_MODEL)) {
                editor.remove("model");
            } else {
                editor.putString("model", mdl);
            }
            String liveMdl = liveModelInput.getText().toString().trim();
            if (liveMdl.isEmpty() || liveMdl.equals(GeminiLive.DEFAULT_MODEL)) {
                editor.remove("live_model");
            } else {
                editor.putString("live_model", liveMdl);
            }
            editor.apply();
            say("Okay, " + userName() + "! Settings saved.", HAPPY, true);
        }).show();
    }

    @Override
    protected void onStop() {
        if (liveOn) {
            stopLive(true); // never keep the microphone open in the background
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (live != null) {
            live.stop();
            live = null;
        }
        network.shutdownNow();
        cloudVoice.stop();
        super.onDestroy();
    }
}
