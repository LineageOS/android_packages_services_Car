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

package com.android.car.bugreport;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.TextView;

import com.google.common.base.Preconditions;

import java.util.Locale;

public class ChoiceDialogActivity extends Activity {

    private static final String TAG = ChoiceDialogActivity.class.getSimpleName();

    /** This is used by other apps. */
    private static final String ACTION_START_BUG_REPORT =
            "com.android.car.bugreport.action.START_BUG_REPORT";

    private static final int DIALOG_MAX_DURATION_MILLIS = 5 * 1000;

    private boolean mBound;
    private TextView mTitleText;
    private BugReportService mService;
    private CountDownTimer mCountDownTimer;

    /** Defines callbacks for service binding, passed to bindService() */
    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            BugReportService.ServiceBinder binder = (BugReportService.ServiceBinder) service;
            mService = binder.getService();
            mBound = true;
            startActivityWithService();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            // called when service connection breaks unexpectedly.
            mBound = false;
        }
    };


    @Override
    public void onCreate(Bundle savedInstanceState) {
        Preconditions.checkState(Config.isBugReportEnabled(), "BugReport is disabled.");

        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Bind to BugReportService.
        Intent intent = new Intent(this, BugReportService.class);
        bindService(intent, mConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (BugReportActivity.isOnActivityStarted()) {
            setResult(Activity.RESULT_CANCELED);
            finish();
        }

        if (mBound) {
            startActivityWithService();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mCountDownTimer != null) {
            mCountDownTimer.cancel();
        }
    }

    @Override
    public void onDestroy() {
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
        super.onDestroy();
    }

    private void startActivityWithService() {
        if (mService.isCollectingBugReport()) {
            startNextAction(/* isAudioFirst= */ true);
            return;
        }

        if (ACTION_START_BUG_REPORT.equals(getIntent().getAction())) {
            prepareUi();
            mCountDownTimer = createCountDownTimer();
            mCountDownTimer.start();
        } else {
            Log.w(TAG, "Unsupported intent action provided: " + getIntent().getAction());
            finish();
        }
    }

    private void prepareUi() {
        setContentView(R.layout.choice_dialog_activity);

        mTitleText = findViewById(R.id.choice_dialog_title);

        findViewById(R.id.button_speak_later).setOnClickListener(this::onClickSpeakLaterButton);
        findViewById(R.id.button_begin_now).setOnClickListener(this::onClickBeginNowButton);
    }

    private String getTimerTextMessage(long millisUntilFinished) {
        long secondsRemaining = millisUntilFinished / 1000;
        String secondText = secondsRemaining > 1 ? "secs" : "sec";
        return String.format(Locale.US, "%s in %d %s",
                getString(R.string.choice_dialog_title), secondsRemaining, secondText);
    }

    private CountDownTimer createCountDownTimer() {
        return new CountDownTimer(DIALOG_MAX_DURATION_MILLIS, /* countDownInterval= */ 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                mTitleText.setText(getTimerTextMessage(millisUntilFinished));
            }

            @Override
            public void onFinish() {
                startNextAction(/* isAudioFirst= */ true);
            }
        };
    }

    private void startNextAction(boolean isAudioFirst) {
        if (mCountDownTimer != null) {
            mCountDownTimer.cancel();
        }

        if (isAudioFirst) {
            startBugReportWithAudioFirst();
        } else {
            startBugReportWithAudioLater();
        }

        setResult(Activity.RESULT_OK);
        finish();
    }

    private void startBugReportWithAudioFirst() {
        Log.i(TAG, "Start audio recording first.");
        Intent intent = BugReportActivity.buildStartBugReportIntent(this);
        startActivity(intent);
    }

    private void startBugReportWithAudioLater() {
        Log.i(TAG, "Start collecting a bug report first.");
        Intent intent = BugReportService.buildStartBugReportIntent(this);
        startService(intent);
    }

    private void onClickBeginNowButton(View view) {
        startNextAction(/* isAudioFirst= */ true);
    }

    private void onClickSpeakLaterButton(View view) {
        startNextAction(/* isAudioFirst= */ false);
    }
}
