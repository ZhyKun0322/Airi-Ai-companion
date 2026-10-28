package com.zhykun.airi;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
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

public class MainActivity extends AppCompatActivity {

    private static final String[] BG_NAMES = {"Night", "Ocean", "Sakura", "Sunset", "Midnight"};
    private static final int[][] BG_COLORS = {
            {0xFF0B1424, 0xFF1B2A5C},
            {0xFF0F3D5E, 0xFF4FC3F7},
            {0xFF2B1B3D, 0xFFD86C9C},
            {0xFF1B1B3A, 0xFFFF8A65},
            {0xFF05070D, 0xFF101826}
    };

    private static final String[] TAP_LINES = {
            "Eh? Did you need something?",
            "Hehe, that tickles!",
            "H-hey, don't poke me so suddenly!",
            "I'm right here with you.",
            "Hmm? Want to chat?"
    };
    private static final int[] TAP_FACES = {
            R.drawable.airi_surprised,
            R.drawable.airi_playful,
            R.drawable.airi_embarrassed,
            R.drawable.airi_happy,
            R.drawable.airi_determined
    };

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SharedPreferences prefs;
    private View root;
    private ImageView imgAiri;
    private TextView txtBubble;
    private EditText etMessage;
    private ObjectAnimator talkAnim;
    private int talkFace = R.drawable.airi_happy;
    private final Random random = new Random();
    private final Runnable resetFace = () -> imgAiri.setImageResource(R.drawable.airi_neutral);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // get views
        root = findViewById(R.id.main);
        imgAiri = findViewById(R.id.imgAiri);
        txtBubble = findViewById(R.id.txtBubble);
        etMessage = findViewById(R.id.etMessage);
        Button btnSend = findViewById(R.id.btnSend);
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

        // look, animation, voice
        applyBackground(prefs.getInt("bg", 0));
        imgAiri.setImageResource(R.drawable.airi_neutral);
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
        btnBackground.setOnClickListener(v -> showBackgroundDialog());
        btnSettings.setOnClickListener(v -> showSettingsDialog());

        // greeting
        say("Welcome back, " + userName() + "!", R.drawable.airi_happy, false);
    }

    // ---------- look and animation ----------

    private void applyBackground(int index) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, BG_COLORS[index]);
        root.setBackground(g);
    }

    private void setupAnimations() {
        // idle float
        float lift = 12 * getResources().getDisplayMetrics().density;
        ObjectAnimator floatAnim = ObjectAnimator.ofFloat(imgAiri, "translationY", 0f, -lift);
        floatAnim.setDuration(2200);
        floatAnim.setRepeatCount(ValueAnimator.INFINITE);
        floatAnim.setRepeatMode(ValueAnimator.REVERSE);
        floatAnim.start();

        // talking pulse
        talkAnim = ObjectAnimator.ofPropertyValuesHolder(imgAiri,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.03f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 1.03f));
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
        imgAiri.setImageResource(R.drawable.airi_neutral);
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
            say("Hi, " + name + "! I'm glad you're here.", R.drawable.airi_happy, true);
        } else if (msg.contains("how are you")) {
            say("I'm doing great, thanks for asking! How about you?", R.drawable.airi_happy, true);
        } else if (msg.contains("your name") || msg.contains("who are you")) {
            say("I'm Airi, your AI companion!", R.drawable.airi_excited, true);
        } else if (msg.contains("thank")) {
            say("It's nothing! I'm just happy to help.", R.drawable.airi_shy, true);
        } else if (msg.contains("tired") || msg.contains("sleepy")) {
            say("You sound tired. Maybe take a short break?", R.drawable.airi_sleepy, true);
        } else if (msg.contains("love") || msg.contains("cute")) {
            say("Wh-what? Stop it, you're making me blush!", R.drawable.airi_embarrassed, true);
        } else if (msg.matches(".*\\b(bye|goodnight)\\b.*") || msg.contains("good night")) {
            say("Good night, " + name + ". Sleep well!", R.drawable.airi_sleepy, true);
        } else if (msg.contains("?")) {
            say("Good question! I can't answer that yet, but I'm learning.", R.drawable.airi_thinking, true);
        } else {
            say("I'm still learning how to talk. Soon I'll understand much more!", R.drawable.airi_confused, true);
        }
    }

    // ---------- customization ----------

    private void showBackgroundDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Background")
                .setItems(BG_NAMES, (d, which) -> {
                    prefs.edit().putInt("bg", which).apply();
                    applyBackground(which);
                })
                .show();
    }

    private void showSettingsDialog() {
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, 0);

        // name
        TextView nameLabel = new TextView(this);
        nameLabel.setText("What should Airi call you?");
        EditText nameInput = new EditText(this);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        nameInput.setText(userName());

        // pitch
        TextView pitchLabel = new TextView(this);
        pitchLabel.setText("Voice pitch");
        SeekBar pitchBar = new SeekBar(this);
        pitchBar.setMax(150);
        pitchBar.setProgress((int) ((prefs.getFloat("pitch", 1.3f) - 0.5f) * 100));

        // speed
        TextView rateLabel = new TextView(this);
        rateLabel.setText("Speaking speed");
        SeekBar rateBar = new SeekBar(this);
        rateBar.setMax(150);
        rateBar.setProgress((int) ((prefs.getFloat("rate", 1.0f) - 0.5f) * 100));

        box.addView(nameLabel);
        box.addView(nameInput);
        box.addView(pitchLabel);
        box.addView(pitchBar);
        box.addView(rateLabel);
        box.addView(rateBar);

        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String n = nameInput.getText().toString().trim();
                    prefs.edit()
                            .putString("name", n.isEmpty() ? "friend" : n)
                            .putFloat("pitch", 0.5f + pitchBar.getProgress() / 100f)
                            .putFloat("rate", 0.5f + rateBar.getProgress() / 100f)
                            .apply();
                    if (ttsReady) {
                        applyVoiceSettings();
                    }
                    say("Okay, " + userName() + "! Settings saved.", R.drawable.airi_happy, true);
                })
                .setNegativeButton("Cancel", null)
                .show();
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
