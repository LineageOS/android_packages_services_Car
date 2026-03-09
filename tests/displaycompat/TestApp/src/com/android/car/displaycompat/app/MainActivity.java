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
import android.provider.DeviceConfig;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

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
    private boolean mHasStatusBar = true;
    private boolean mHasNavBar = true;
    private boolean mInsetsChecked = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        mContainer = findViewById(R.id.test_container);
        mNextButton = findViewById(R.id.next);
        mImmersiveButton = findViewById(R.id.immersive);
        mNonDcActivity = findViewById(R.id.non_dc_activity);
        mLetterboxButton = findViewById(R.id.letterbox);
        Button resizableActivityButton = findViewById(R.id.resizable);

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
        resizableActivityButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, ResizableActivity.class);
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
        return DeviceConfig.getBoolean(
                /* namespace= */ "car_framework",
                /* name= */ "android.car.feature.display_compatibility",
                /* defaultValue= */ false);
    }

    private static AtomicFile getConfigFile() {
        //getProductDirectory() is a system API
        File configFile = new File(Environment.getProductDirectory(), CONFIG_PATH);
        return new AtomicFile(configFile);
    }

    private void listenForImmersive() {
        mWindowInsetsController = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());

        mWindowInsetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        View decorView = getWindow().getDecorView();

        ViewCompat.setOnApplyWindowInsetsListener(decorView, (view, windowInsets) -> {
            // Detect supported bars once (position-independent)
            if (!mInsetsChecked) {
                androidx.core.graphics.Insets statusInsets
                        = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars());
                androidx.core.graphics.Insets navInsets
                        = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars());

                mHasStatusBar = hasBar(statusInsets);
                mHasNavBar = hasBar(navInsets);

                // Debug logging for verification
                Log.d("Immersive", "StatusBar: " + mHasStatusBar + ", NavBar: " + mHasNavBar);

                mInsetsChecked = true;
            }

            return ViewCompat.onApplyWindowInsets(view, windowInsets);
        });

        // Initialize button state
        setImmersiveMode(false);
    }

    /** Returns true if any side of the insets is non-zero, meaning the bar exists somewhere */
    private boolean hasBar(androidx.core.graphics.Insets insets) {
        return insets.top > 0 || insets.bottom > 0 || insets.left > 0 || insets.right > 0;
    }

    /** Returns a bitmask of supported system bars */
    private int getSupportedBars() {
        int bars = 0;
        if (mHasStatusBar) bars |= WindowInsetsCompat.Type.statusBars();
        if (mHasNavBar) bars |= WindowInsetsCompat.Type.navigationBars();
        return bars;
    }

    private void goImmersive() {
        int barsToHide = getSupportedBars();
        if (barsToHide != 0) {
            mWindowInsetsController.hide(barsToHide);
        }

        setImmersiveMode(true);
    }

    private void exitImmersive() {
        int barsToShow = getSupportedBars();
        if (barsToShow != 0) {
            mWindowInsetsController.show(barsToShow);
        }

        setImmersiveMode(false);
    }

    /** Updates UI and state for immersive mode */
    private void setImmersiveMode(boolean immersive) {
        mIsImmersive = immersive;
        if (immersive) {
            mImmersiveButton.setText("Exit fullscreen");
            mContainer.setBackgroundColor(getResources().getColor(R.color.green));
            mImmersiveButton.setOnClickListener(v -> exitImmersive());
        } else {
            mImmersiveButton.setText("Fullscreen");
            mContainer.setBackgroundColor(getResources().getColor(R.color.purple));
            mImmersiveButton.setOnClickListener(v -> goImmersive());
        }
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
