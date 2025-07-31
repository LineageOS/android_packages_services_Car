/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.displaycompat.app;

import static android.view.Display.DEFAULT_DISPLAY;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Environment;
import android.util.AtomicFile;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseArray;
import android.util.Xml;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowMetrics;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class MainActivity extends Activity {
    private static final String TAG = "DisplayCompatTestApp";
    private static final String CONFIG_PATH = "etc/display_compat_config.xml";
    private Config mConfig = new Config();
    private boolean mIsImmersive = false;
    private WindowInsetsControllerCompat mWindowInsetsController = null;
    private View mContainer = null;
    private Button mNextButton = null;
    private Button mImmersiveButton = null;
    private Button mNonDcActivity = null;
    private Button mLetterboxButton = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        mContainer = findViewById(R.id.test_container);
        mNextButton = findViewById(R.id.next);
        mImmersiveButton = findViewById(R.id.immersive);
        mNonDcActivity = findViewById(R.id.non_dc_activity);
        mLetterboxButton = findViewById(R.id.letterbox);

        mNextButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, IgnoreDecorActivity.class);
            startActivity(intent);
        });
        mNonDcActivity.setOnClickListener(v -> {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(/* pkg= */ "com.android.car.displaycompat.intent",
                    /* cls= */ "com.android.car.displaycompat.intent.MainActivity"));
            startActivity(intent);
        });

        listenForImmersive();

        getActionBar().setTitle("First Page");
        try (FileInputStream in = getConfigFile().openRead();) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(in, null);
            parser.nextTag();
            mConfig.readConfig(parser);
        } catch (XmlPullParserException | IOException | SecurityException e) {
            Log.e(TAG, "read config failed", e);
        }

        float scale = mConfig.get(DEFAULT_DISPLAY, 1.0f);

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        WindowMetrics windowMetrics = getWindowManager().getCurrentWindowMetrics();
        WindowInsets windowInsets = windowMetrics.getWindowInsets();
        final Insets insets = windowInsets.getInsets(0);
        final Rect bounds = new Rect(windowMetrics.getBounds());
        bounds.inset(insets);

        boolean hasDisplayCompat = readDisplayCompatState();

        if (hasDisplayCompat) {
            TextView originalDpi = findViewById(R.id.original_dpi);
            originalDpi.setText("Original DPI " + Math.round(metrics.densityDpi * scale));
            originalDpi.setVisibility(View.VISIBLE);
        }

        TextView mainScreenDpi = findViewById(R.id.main_screen_dpi);
        mainScreenDpi.setText("DPI " + metrics.densityDpi);

        TextView width = findViewById(R.id.main_screen_width);
        width.setText("Width " + bounds.width());

        TextView height = findViewById(R.id.main_screen_height);
        height.setText("Height " + bounds.height());

        // Setup letterbox
        mLetterboxButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, LetterboxActivity.class);
            intent.setAction(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            startActivity(intent);
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK && mIsImmersive) {
            exitImmersive();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean readDisplayCompatState() {
        InputStream is;
        try {
            is = new ProcessBuilder("/bin/device_config",
                    "get",
                    "car_framework",
                    "android.car.feature.display_compatibility")
                    .start()
                    .getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.strip();
                if (line.equals("true")) {
                    return true;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return false;
    }

    private static AtomicFile getConfigFile() {
        //getProductDirectory() is a system API
        File configFile = new File(Environment.getProductDirectory(), CONFIG_PATH);
        return new AtomicFile(configFile);
    }

    private void listenForImmersive() {
        mWindowInsetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());

        mWindowInsetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        );

        ViewCompat.setOnApplyWindowInsetsListener(
                getWindow().getDecorView(),
                (view, windowInsets) -> {
                    if (!windowInsets.isVisible(WindowInsetsCompat.Type.statusBars())
                            || !windowInsets.isVisible(WindowInsetsCompat.Type.navigationBars())) {
                        mIsImmersive = true;
                        mImmersiveButton.setText("Exit fullscreen");
                        mContainer.setBackgroundColor(getResources().getColor(R.color.green));
                        mImmersiveButton.setOnClickListener(v -> {
                            exitImmersive();
                        });
                    } else {
                        mIsImmersive = false;
                        mImmersiveButton.setText("Fullscreen");
                        mContainer.setBackgroundColor(getResources().getColor(R.color.purple));
                        mImmersiveButton.setOnClickListener(v -> {
                            goImmersive();
                        });
                    }
                    return ViewCompat.onApplyWindowInsets(view, windowInsets);
                });
    }

    private void goImmersive() {
        mWindowInsetsController.hide(WindowInsetsCompat.Type.systemBars());
    }

    private void exitImmersive() {
        mWindowInsetsController.show(WindowInsetsCompat.Type.systemBars());
    }

    private static class Scale {
        public final int display;
        public final float scale;

        private Scale(int display, float scale) {
            this.display = display;
            this.scale = scale;
        }
    }

    private static class Config {
        private static final String CONFIG = "config";
        private static final String SCALE = "scale";
        private static final String DISPLAY = "display";

        private SparseArray<Float> mScales = new SparseArray<>();

        public int size() {
            return mScales.size();
        }

        public float get(int index, float defaultValue) {
            return mScales.get(index, defaultValue);
        }

        public void readConfig(XmlPullParser parser) throws XmlPullParserException,
                IOException {
            parser.require(XmlPullParser.START_TAG, null, CONFIG);
            while (parser.next() != XmlPullParser.END_TAG) {
                if (parser.getEventType() != XmlPullParser.START_TAG) {
                    continue;
                }
                String name = parser.getName();
                if (SCALE.equals(name)) {
                    Scale scale = readScale(parser);
                    mScales.put(scale.display, scale.scale);
                } else {
                    skip(parser);
                }
            }
        }

        private Scale readScale(XmlPullParser parser) throws XmlPullParserException,
                IOException {
            parser.require(XmlPullParser.START_TAG, null, SCALE);
            int display = DEFAULT_DISPLAY;
            try {
                display = Integer.parseInt(parser.getAttributeValue(null, DISPLAY));
            } catch (NullPointerException | NumberFormatException e) {
                Log.e(TAG, "parse failed: " + parser.getAttributeValue(null, DISPLAY), e);
            }
            float value = 1f;
            if (parser.next() == XmlPullParser.TEXT) {
                try {
                    value = Float.parseFloat(parser.getText());
                } catch (NullPointerException | NumberFormatException e) {
                    Log.e(TAG, "parse failed: " + parser.getText(), e);
                }
                parser.nextTag();
            }
            parser.require(XmlPullParser.END_TAG, null, SCALE);
            return new Scale(display, value);
        }

        private void skip(XmlPullParser parser) throws XmlPullParserException, IOException {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                throw new IllegalStateException();
            }
            int depth = 1;
            while (depth != 0) {
                switch (parser.next()) {
                    case XmlPullParser.END_TAG:
                        depth--;
                        break;
                    case XmlPullParser.START_TAG:
                        depth++;
                        break;
                }
            }
        }
    }
}
