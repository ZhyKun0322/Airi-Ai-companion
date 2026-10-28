package com.zhykun.airi;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
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

    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final List<String[]> history = new ArrayList<>();
    private boolean waiting = false;

    private static class ApiException extends Exception {
        final int code;

        ApiException(int code, String body) {
            super(body);
            this.code = code;
        }
    }

    private TextToSpeech tts;
    private GeminiVoice cloudVoice;
    private boolean ttsReady = false;
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
        setupVoice();
        cloudVoice = new GeminiVoice();

        // listeners
        imgAiri.setOnClickListener(v -> onTapAiri());
        btnSend.setOnClickListener(v -> sendMessage());
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

    private void setupVoice() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                applyVoiceSettings();
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {
                        runOnUiThread(() -> startTalking());
                    }

                    @Override
                    public void onDone(String id) {
                        runOnUiThread(() -> stopTalking());
                    }

                    @Override
                    public void onError(String id) {
                        runOnUiThread(() -> stopTalking());
                    }
                });
                ttsReady = true;
            }
        });
    }

    private void applyVoiceSettings() {
        tts.setPitch(prefs.getFloat("pitch", 1.3f));
        tts.setSpeechRate(prefs.getFloat("rate", 1.0f));
    }

    private void say(String line, int face, boolean voice) {
        say(line, line, face, voice);
    }

    private void say(String shown, String spoken, int face, boolean voice) {
        txtBubble.setText(shown);
        imgAiri.removeCallbacks(resetFace);
        cloudVoice.stop();
        stopTalking();
        String key = prefs.getString("api_key", "");
        if (voice && !key.isEmpty() && prefs.getBoolean("cloud_voice", true)) {
            talkFace = face;
            cloudVoice.speak(key,
                    prefs.getString("tts_model", GeminiVoice.DEFAULT_MODEL),
                    prefs.getString("tts_voice", GeminiVoice.DEFAULT_VOICE),
                    spoken, 3000, new GeminiVoice.Callback() {
                        @Override
                        public void onStart() {
                            startTalking();
                        }

                        @Override
                        public void onDone() {
                            stopTalking();
                        }

                        @Override
                        public void onError() {
                            speakLocal(shown, face);
                        }
                    });
        } else if (voice) {
            speakLocal(shown, face);
        } else {
            imgAiri.setImageResource(face);
            imgAiri.postDelayed(resetFace, 2500);
        }
    }

    private void speakLocal(String line, int face) {
        if (ttsReady) {
            talkFace = face;
            tts.speak(line, TextToSpeech.QUEUE_FLUSH, null, "airi_line");
        } else {
            imgAiri.setImageResource(face);
            imgAiri.postDelayed(resetFace, 2500);
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
        network.execute(() -> {
            String reply = null;
            String problem = null;
            try {
                reply = callApi(key, system, snapshot);
            } catch (Exception e) {
                problem = friendlyError(e);
            }
            final String finalReply = reply;
            final String finalProblem = problem;
            runOnUiThread(() -> onAiResult(finalReply, finalProblem));
        });
    }

    private String callApi(String key, String system, List<String[]> messages) throws Exception {
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
            boolean ok = code >= 200 && code < 300;
            String text = readAll(ok ? conn.getInputStream() : conn.getErrorStream());
            if (!ok) {
                throw new ApiException(code, text);
            }
            JSONArray candidates = new JSONObject(text).optJSONArray("candidates");
            StringBuilder sb = new StringBuilder();
            if (candidates != null && candidates.length() > 0) {
                JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
                JSONArray parts = content == null ? null : content.optJSONArray("parts");
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i++) {
                        sb.append(parts.getJSONObject(i).optString("text"));
                    }
                }
            }
            String reply = sb.toString().trim();
            if (reply.isEmpty()) {
                return "[confused] Hmm, I couldn't think of a reply. Could you say that another way?";
            }
            return reply;
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

    private void onAiResult(String text, String problem) {
        waiting = false;
        if (problem != null) {
            if (!history.isEmpty() && history.get(history.size() - 1)[0].equals("user")) {
                history.remove(history.size() - 1);
            }
            say(problem, CONFUSED, false);
            return;
        }
        int face = HAPPY;
        String line = text.replace("*", "").trim();
        Matcher m = MOOD_TAG.matcher(line);
        if (m.matches()) {
            face = moodFace(m.group(1));
            line = m.group(2).trim();
        }
        if (line.isEmpty()) {
            line = "...";
        }
        String shown = line.replaceAll("\\[[^\\]]*\\]", "").replaceAll("\\s+", " ").trim();
        if (shown.isEmpty()) {
            shown = "...";
        }
        history.add(new String[]{"assistant", shown});
        say(shown, line, face, true);
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

    private SeekBar settingsBar(float value) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(150);
        bar.setProgress((int) ((value - 0.5f) * 100));
        bar.setProgressTintList(ColorStateList.valueOf(0xFF26D9F2));
        bar.setThumbTintList(ColorStateList.valueOf(0xFF26D9F2));
        return bar;
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

        // pitch and speed
        SeekBar pitchBar = settingsBar(prefs.getFloat("pitch", 1.3f));
        SeekBar rateBar = settingsBar(prefs.getFloat("rate", 1.0f));

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
        box.addView(settingsLabel("Voice pitch"));
        box.addView(pitchBar);
        box.addView(settingsLabel("Speaking speed"));
        box.addView(rateBar);
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

        TextView modeBtn = new TextView(this);
        modeBtn.setTextColor(0xFF26D9F2);
        modeBtn.setTextSize(16);
        modeBtn.setPadding(0, dp(12), 0, dp(12));
        modeBtn.setText(prefs.getBoolean("cloud_voice", true)
                ? "Natural voice: ON  (tap to switch)" : "Natural voice: OFF, fast phone voice  (tap to switch)");
        modeBtn.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean("cloud_voice", true);
            prefs.edit().putBoolean("cloud_voice", on).apply();
            modeBtn.setText(on ? "Natural voice: ON  (tap to switch)"
                    : "Natural voice: OFF, fast phone voice  (tap to switch)");
        });
        box.addView(modeBtn);

        framedDialog("Settings", box, "Save", () -> {
            String n = nameInput.getText().toString().trim();
            String k = keyInput.getText().toString().trim();
            String mdl = modelInput.getText().toString().trim();
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("name", n.isEmpty() ? "friend" : n);
            editor.putFloat("pitch", 0.5f + pitchBar.getProgress() / 100f);
            editor.putFloat("rate", 0.5f + rateBar.getProgress() / 100f);
            if (!k.isEmpty()) {
                editor.putString("api_key", k);
            }
            if (mdl.isEmpty() || mdl.equals(DEFAULT_MODEL)) {
                editor.remove("model");
            } else {
                editor.putString("model", mdl);
            }
            editor.apply();
            if (ttsReady) {
                applyVoiceSettings();
            }
            say("Okay, " + userName() + "! Settings saved.", HAPPY, true);
        }).show();
    }

    @Override
    protected void onDestroy() {
        network.shutdownNow();
        cloudVoice.stop();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
