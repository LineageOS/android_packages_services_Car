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

package com.android.car.portraitlauncher.controlbar;

import static android.content.pm.ActivityInfo.CONFIG_UI_MODE;
import static android.window.DisplayAreaOrganizer.FEATURE_DEFAULT_TASK_CONTAINER;

import static com.android.car.caruiportrait.common.service.CarUiPortraitService.INTENT_EXTRA_COLLAPSE_APPLICATION_PANEL;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.INTENT_EXTRA_CONTROL_BAR_HEIGHT_CHANGE;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.INTENT_EXTRA_IS_APPLICATION_PANEL_OPEN;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.INTENT_EXTRA_TOP_TASK_IN_APPLICATION_PANEL;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.REQUEST_FROM_LAUNCHER;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.REQUEST_FROM_SYSTEM_UI;
import static com.android.car.portraitlauncher.panel.TaskViewPanelStateChangeReason.ON_INCALL_INTENT;
import static com.android.car.portraitlauncher.panel.TaskViewPanelStateChangeReason.ON_MEDIA_INTENT;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;

import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.android.car.carlauncher.homescreen.HomeCardModule;
import com.android.car.carlauncher.homescreen.audio.IntentHandler;
import com.android.car.carlauncher.homescreen.audio.dialer.InCallIntentRouter;
import com.android.car.carlauncher.homescreen.audio.media.MediaIntentRouter;
import com.android.car.portraitlauncher.R;
import com.android.car.portraitlauncher.homeactivities.TaskCategoryManager;

import java.lang.reflect.InvocationTargetException;
import java.util.Set;
import java.util.function.Function;

/**
 * Launcher activity that shows only the control bar fragment.
 */
public class ControlBarActivity extends FragmentActivity {
    private static final String TAG = ControlBarActivity.class.getSimpleName();
    private static final boolean DBG = Build.IS_DEBUGGABLE;

    private final Configuration mConfiguration = new Configuration();
    private final InCallTaskStateRouter mInCallTaskStateRouter =
            InCallTaskStateRouter.getInstance();

    private boolean mIsApplicationPanelOpen;
    private ActivityManager.RunningTaskInfo mCurrentTaskInApplicationPanel;
    private LinearLayout mControlBarArea;
    private int mHeight = 0;
    private TaskCategoryManager mTaskCategoryManager;

    private final View.OnLayoutChangeListener mControlBarAreaSizeChangeListener =
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                int newHeight = bottom - top;
                if (newHeight == mHeight) {
                    return;
                }
                mHeight = newHeight;
                requestControlBarHeightChange();
            };

    private final BroadcastReceiver mSysUiRequestsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra(INTENT_EXTRA_IS_APPLICATION_PANEL_OPEN)) {
                mIsApplicationPanelOpen = intent.getBooleanExtra(
                        INTENT_EXTRA_IS_APPLICATION_PANEL_OPEN, /* defaultValue= */ false);
            }
            if (intent.hasExtra(INTENT_EXTRA_TOP_TASK_IN_APPLICATION_PANEL)) {
                mCurrentTaskInApplicationPanel = intent.getParcelableExtra(
                        INTENT_EXTRA_TOP_TASK_IN_APPLICATION_PANEL,
                        ActivityManager.RunningTaskInfo.class);
                mInCallTaskStateRouter.handleInCallTaskState(
                        mTaskCategoryManager.isInCallActivity(mCurrentTaskInApplicationPanel));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.control_bar_activity);

        mControlBarArea = findViewById(R.id.control_bar_area);
        mTaskCategoryManager = new TaskCategoryManager(getApplicationContext());

        initializeCards();

        IntentHandler mediaIntentHandler = new ControlBarActivityIntentHandler(
                mTaskCategoryManager::isMediaApp, ON_MEDIA_INTENT);
        IntentHandler inCallIntentHandler = new ControlBarActivityIntentHandler(
                mTaskCategoryManager::isInCallActivity, ON_INCALL_INTENT);

        MediaIntentRouter.getInstance().registerMediaIntentHandler(mediaIntentHandler);
        InCallIntentRouter.getInstance().registerInCallIntentHandler(inCallIntentHandler);

        registerSystemUIListener();

        mControlBarArea.addOnLayoutChangeListener(mControlBarAreaSizeChangeListener);
    }

    private void registerSystemUIListener() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(REQUEST_FROM_SYSTEM_UI);
        registerReceiver(mSysUiRequestsReceiver, filter, RECEIVER_EXPORTED);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int diff = mConfiguration.updateFrom(newConfig);
        if (DBG) {
            Log.d(TAG, "onConfigurationChanged with diff =" + diff);
        }
        if ((diff & CONFIG_UI_MODE) == 0) {
            return;
        }

        initializeCards();

        Drawable background = getResources().getDrawable(R.drawable.control_bar_background,
                getTheme());
        mControlBarArea.setBackground(background);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mSysUiRequestsReceiver);
    }

    private void initializeCards() {
        Set<HomeCardModule> homeCardModules = new androidx.collection.ArraySet<>();
        for (String providerClassName : getResources().getStringArray(
                R.array.config_homeCardModuleClasses)) {
            try {
                HomeCardModule cardModule = Class.forName(providerClassName).asSubclass(
                        HomeCardModule.class).getDeclaredConstructor().newInstance();
                cardModule.setViewModelProvider(new ViewModelProvider(/* owner= */ this));
                homeCardModules.add(cardModule);
            } catch (IllegalAccessException | InstantiationException | ClassNotFoundException
                     | InvocationTargetException | NoSuchMethodException e) {
                Log.w(TAG, "Unable to create HomeCardProvider class " + providerClassName, e);
            }
        }
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        for (HomeCardModule cardModule : homeCardModules) {
            transaction.replace(cardModule.getCardResId(), cardModule.getCardView().getFragment());
        }
        transaction.commitNow();
    }

    private void requestControlBarHeightChange() {
        Intent intent = new Intent(REQUEST_FROM_LAUNCHER);
        intent.putExtra(INTENT_EXTRA_CONTROL_BAR_HEIGHT_CHANGE, mHeight);
        sendBroadcast(intent);
    }

    private void requestPanelCollapse(String reason) {
        Intent intent = new Intent(REQUEST_FROM_LAUNCHER);
        intent.putExtra(INTENT_EXTRA_COLLAPSE_APPLICATION_PANEL, reason);
        sendBroadcast(intent);
    }

    private class ControlBarActivityIntentHandler implements IntentHandler {
        private final Function<ActivityManager.RunningTaskInfo, Boolean> mTaskChecker;
        private final String mReason;

        ControlBarActivityIntentHandler(
                Function<ActivityManager.RunningTaskInfo, Boolean> taskChecker,
                String reason) {
            mTaskChecker = taskChecker;
            mReason = reason;
        }

        @Override
        public void handleIntent(@Nullable Intent intent) {
            if (DBG) {
                Log.d(TAG, "handleIntent mCurrentTaskInApplicationPanel: "
                        + mCurrentTaskInApplicationPanel
                        + ", incoming intent = "
                        + intent);
            }

            if (mTaskChecker.apply(mCurrentTaskInApplicationPanel) && mIsApplicationPanelOpen) {
                requestPanelCollapse(mReason);
                return;
            }

            if (intent != null) {
                intent.setFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchTaskDisplayAreaFeatureId(FEATURE_DEFAULT_TASK_CONTAINER);
                startActivity(intent, options.toBundle());
            }
        }
    }
}
