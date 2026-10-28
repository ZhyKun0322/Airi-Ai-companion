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

import java.util.Locale;
import java.util.Random;
import java.util.function.IntConsumer;

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
            "Eh? Did you need something?",
            "Hehe, that tickles!",
            "H-hey, don't poke me so suddenly!",
            "I'm right here with you.",
            "Hmph! Stop poking me!",
            "Let's do our best today!",
            "Oh! Is something exciting happening?"
    };
    private static final int[] TAP_FACES = {SURPRISED, PLAYFUL, EMBARRASSED, HAPPY, ANGRY, DETERMINED, EXCITED};

    private TextToSpeech tts;
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
        txtBubble.setText(line);
        imgAiri.removeCallbacks(resetFace);
        if (voice && ttsReady) {
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
        if (input.isEmpty()) {
            return;
        }
        etMessage.setText("");
        reply(input);
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

        // pitch and speed
        SeekBar pitchBar = settingsBar(prefs.getFloat("pitch", 1.3f));
        SeekBar rateBar = settingsBar(prefs.getFloat("rate", 1.0f));

        box.addView(settingsLabel("What should Airi call you?"));
        box.addView(nameInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        box.addView(settingsLabel("Voice pitch"));
        box.addView(pitchBar);
        box.addView(settingsLabel("Speaking speed"));
        box.addView(rateBar);

        framedDialog("Settings", box, "Save", () -> {
            String n = nameInput.getText().toString().trim();
            prefs.edit()
                    .putString("name", n.isEmpty() ? "friend" : n)
                    .putFloat("pitch", 0.5f + pitchBar.getProgress() / 100f)
                    .putFloat("rate", 0.5f + rateBar.getProgress() / 100f)
                    .apply();
            if (ttsReady) {
                applyVoiceSettings();
            }
            say("Okay, " + userName() + "! Settings saved.", HAPPY, true);
        }).show();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
