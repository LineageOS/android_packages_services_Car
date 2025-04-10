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
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.R;
import com.google.android.car.kitchensink.widgets.AppWidgetListAdapter.AppWidgetInfoItem;

import java.util.ArrayList;
import java.util.List;

public class AppWidgetPickerFragment extends Fragment {
    private static final String TAG = "AppWidgetPickerFragment";

    private ListView mWidgetListView;
    private TextView mTextView;
    private AppWidgetManager mAppWidgetManager;
    private PackageManager mPackageManager;
    private Context mContext;

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.widget_picker_fragment, container, false);

        mContext = container.getContext();
        mWidgetListView = view.findViewById(R.id.widget_list_view);
        mTextView = view.findViewById(R.id.empty_text_view);
        mAppWidgetManager = AppWidgetManager.getInstance(mContext);
        mPackageManager = mContext.getPackageManager();

        loadConfiguredWidgets();

        return view;
    }

    private void loadConfiguredWidgets() {
        List<AppWidgetInfoItem> widgetListItems = new ArrayList<>();
        List<AppWidgetProviderInfo> appWidgetProviderInfos =
                mAppWidgetManager.getInstalledProviders();

        if (!appWidgetProviderInfos.isEmpty()) {
            for (AppWidgetProviderInfo providerInfo : appWidgetProviderInfos) {
                if (providerInfo != null) {
                    try {
                        String packageName = providerInfo.provider.getPackageName();
                        String appName = mPackageManager.getApplicationLabel(
                                mPackageManager.getApplicationInfo(packageName, 0)).toString();
                        widgetListItems.add(new AppWidgetInfoItem(appName, providerInfo));
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.e(TAG, "PackageManager NameNotFoundException: " + e);
                    }
                }
            }

            if (!widgetListItems.isEmpty()) {
                AppWidgetListAdapter adapter = new AppWidgetListAdapter(mContext, widgetListItems,
                        getParentFragmentManager());
                mWidgetListView.setAdapter(adapter);
                mTextView.setVisibility(View.GONE);
                mWidgetListView.setVisibility(View.VISIBLE);
            } else {
                mWidgetListView.setVisibility(View.GONE);
                mTextView.setVisibility(View.VISIBLE);
            }
        } else {
            mWidgetListView.setVisibility(View.GONE);
            mTextView.setVisibility(View.VISIBLE);
        }
    }
}
