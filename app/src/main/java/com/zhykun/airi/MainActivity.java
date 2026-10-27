package com.zhykun.airi;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.widget.Button;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private TextToSpeech tts;
    private ImageView imgAiri;
    private ObjectAnimator talkAnim;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // get views
        imgAiri = findViewById(R.id.imgAiri);
        Button btn = findViewById(R.id.btnGetStarted);
        imgAiri.setImageResource(R.drawable.airi_neutral);

        // talking animation (pulse)
        talkAnim = ObjectAnimator.ofPropertyValuesHolder(imgAiri,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.04f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 1.04f));
        talkAnim.setDuration(250);
        talkAnim.setRepeatCount(ValueAnimator.INFINITE);
        talkAnim.setRepeatMode(ValueAnimator.REVERSE);

        // voice setup
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                tts.setPitch(1.3f);
                tts.setSpeechRate(1.0f);
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
            }
        });

        // speak on tap
        btn.setOnClickListener(v ->
                tts.speak("Hi there! I'm Airi. Nice to meet you!",
                        TextToSpeech.QUEUE_FLUSH, null, "airi_intro"));
    }

    private void startTalking() {
        imgAiri.setImageResource(R.drawable.airi_happy);
        talkAnim.start();
    }

    private void stopTalking() {
        talkAnim.cancel();
        imgAiri.setScaleX(1f);
        imgAiri.setScaleY(1f);
        imgAiri.setImageResource(R.drawable.airi_neutral);
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