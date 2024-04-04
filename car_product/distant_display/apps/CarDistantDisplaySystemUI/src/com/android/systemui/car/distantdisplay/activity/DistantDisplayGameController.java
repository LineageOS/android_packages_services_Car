/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.car.distantdisplay.activity;

import android.annotation.Nullable;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Pair;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.systemui.R;

import javax.inject.Inject;

public class DistantDisplayGameController extends Activity {
    // Intent extra key to specify the activity the was moved to the distant display for which these
    // extended controls are associated with.
    private static final String EXTENDED_CONTROLS_ASSOCIATED_PACKAGE_NAME_KEY =
            "extended_controls_associated_package_name_key";

    private final Context mContext;

    /**
     * Create new intent for the DistantDisplayGameController with the moved package name
     * provided as an extra.
     **/
    public static Intent createIntent(Context context, String movedPackageName) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(context, DistantDisplayGameController.class));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (movedPackageName != null) {
            intent.putExtra(EXTENDED_CONTROLS_ASSOCIATED_PACKAGE_NAME_KEY, movedPackageName);
        }
        return intent;
    }

    @Inject
    public DistantDisplayGameController(Context context) {
        mContext = context;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!Process.myUserHandle().isSystem()) {
            Intent intent = createIntent(mContext, getPackageNameExtra());
            mContext.startActivityAsUser(intent, UserHandle.SYSTEM);
            finish();
            return;
        }

        setContentView(R.layout.car_distant_display_game_controller);

        TextView textView = findViewById(R.id.game_companion_message);
        if (textView != null) {
            textView.setText(mContext.getString(R.string.distant_display_game_companion_message,
                    getAppString()));
        }

        ImageView dPad = findViewById(R.id.game_dpad);
        if (dPad != null) {
            dPad.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() != MotionEvent.ACTION_DOWN) {
                    return false;
                }
                String direction = getDpadDirection(v.getWidth(), v.getHeight(), event.getX(),
                        event.getY());
                if (!TextUtils.isEmpty(direction)) {
                    Toast.makeText(mContext, direction, Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        ImageView gameButtons = findViewById(R.id.game_buttons);
        if (gameButtons != null) {
            gameButtons.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() != MotionEvent.ACTION_DOWN) {
                    return false;
                }
                String button = getGameButton(v.getWidth(), v.getHeight(), event.getX(),
                        event.getY());
                if (!TextUtils.isEmpty(button)) {
                    Toast.makeText(mContext, button, Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        Button pauseButton = findViewById(R.id.game_pause_button);
        if (pauseButton != null) {
            pauseButton.setOnClickListener(v -> {
                Toast.makeText(mContext, "Pressed Pause", Toast.LENGTH_SHORT).show();
            });
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        finish();
    }

    private String getAppString() {
        String packageString = getPackageNameExtra();
        if (TextUtils.isEmpty(packageString)) {
            return getDefaultAppString();
        }
        PackageManager pm = mContext.getPackageManager();
        ApplicationInfo info;
        try {
            info = pm.getApplicationInfo(packageString, /* flags= */ 0);
        } catch (PackageManager.NameNotFoundException e) {
            return getDefaultAppString();
        }
        return String.valueOf(pm.getApplicationLabel(info));
    }

    @Nullable
    private String getPackageNameExtra() {
        return getIntent().getStringExtra(EXTENDED_CONTROLS_ASSOCIATED_PACKAGE_NAME_KEY);
    }

    private String getDefaultAppString() {
        return mContext.getString(R.string.distant_display_companion_message_default_app);
    }

    // TODO(b/333732791): use individual button elements to avoid slicing single images this way
    private String getDpadDirection(int width, int height, float x, float y) {
        double startRatio = 0.168;
        double endRatio = 0.835;
        double midRatio1 = 0.385;
        double midRatio2 = 0.615;
        int leftBound = (int) (width * startRatio);
        int rightBound = (int) (width * endRatio);
        int topBound = (int) (height * startRatio);
        int bottomBound = (int) (height * endRatio);
        int midXBound1 = (int) (width * midRatio1);
        int midXBound2 = (int) (width * midRatio2);
        int midYBound1 = (int) (height * midRatio1);
        int midYBound2 = (int) (height * midRatio2);

        Rect leftArrow = new Rect(leftBound, midYBound1, midXBound1, midYBound2);
        Rect rightArrow = new Rect(midXBound2, midYBound1, rightBound, midYBound2);
        Rect topArrow = new Rect(midXBound1, topBound, midXBound2, midYBound1);
        Rect bottomArrow = new Rect(midXBound1, midYBound2, midXBound2, bottomBound);

        if (isPointWithinRect(leftArrow, x, y)) {
            return "Pressed LEFT";
        }
        if (isPointWithinRect(rightArrow, x, y)) {
            return "Pressed RIGHT";
        }
        if (isPointWithinRect(topArrow, x, y)) {
            return "Pressed UP";
        }
        if (isPointWithinRect(bottomArrow, x, y)) {
            return "Pressed DOWN";
        }
        return "";
    }

    private boolean isPointWithinRect(Rect rect, float x, float y) {
        return x > rect.left && x < rect.right && y > rect.top && y < rect.bottom;
    }

    // TODO(b/333732791): use individual button elements to avoid slicing single images this way
    private String getGameButton(int width, int height, float x, float y) {
        double centerRatio = 0.5;
        double firstQuarterRatio = 0.23;
        double lastQuarterRatio = 1 - firstQuarterRatio;
        int buttonRadius = (int) (0.113 * width); // assumes width == height

        Pair<Integer, Integer> xButtonCenter = Pair.create((int) (width * firstQuarterRatio),
                (int) (height * centerRatio));
        Pair<Integer, Integer> yButtonCenter = Pair.create((int) (width * centerRatio),
                (int) (height * firstQuarterRatio));
        Pair<Integer, Integer> bButtonCenter = Pair.create((int) (width * lastQuarterRatio),
                (int) (height * centerRatio));
        Pair<Integer, Integer> aButtonCenter = Pair.create((int) (width * centerRatio),
                (int) (height * lastQuarterRatio));

        if (isPointWithinCircle(xButtonCenter.first, xButtonCenter.second, buttonRadius, x, y)) {
            return "Pressed X";
        }
        if (isPointWithinCircle(yButtonCenter.first, yButtonCenter.second, buttonRadius, x, y)) {
            return "Pressed Y";
        }
        if (isPointWithinCircle(bButtonCenter.first, bButtonCenter.second, buttonRadius, x, y)) {
            return "Pressed B";
        }
        if (isPointWithinCircle(aButtonCenter.first, aButtonCenter.second, buttonRadius, x, y)) {
            return "Pressed A";
        }

        return "";
    }

    private boolean isPointWithinCircle(int centerX, int centerY, int radius, float x, float y) {
        return Math.pow((x - centerX), 2) + Math.pow((y - centerY), 2) < Math.pow(radius, 2);
    }
}
