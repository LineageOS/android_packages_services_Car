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
package com.google.android.car.kitchensink.widgets;

import android.annotation.Nullable;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetProviderInfo;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.R;

public class AppWidgetHostViewFragment extends Fragment {
    private final AppWidgetHost mWidgetHost;
    private final int mAppWidgetId;
    private final AppWidgetProviderInfo mAppWidgetProviderInfo;

    AppWidgetHostViewFragment(int appWidgetId, AppWidgetHost widgetHost,
            AppWidgetProviderInfo appWidgetProviderInfo) {
        mWidgetHost = widgetHost;
        mAppWidgetId = appWidgetId;
        mAppWidgetProviderInfo = appWidgetProviderInfo;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        FrameLayout view = (FrameLayout) inflater.inflate(R.layout.widget_host_fragment, container,
                false);
        view.setOnClickListener(item -> getParentFragmentManager().popBackStack());
        view.addView(mWidgetHost.createView(getContext(), mAppWidgetId, mAppWidgetProviderInfo));
        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        mWidgetHost.startListening();
    }

    @Override
    public void onStop() {
        super.onStop();
        mWidgetHost.stopListening();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mWidgetHost.deleteAppWidgetId(mAppWidgetId);
    }
}
