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

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.fragment.app.FragmentManager;

import com.google.android.car.kitchensink.R;

import java.util.List;

public class AppWidgetListAdapter extends ArrayAdapter<AppWidgetListAdapter.AppWidgetInfoItem> {
    private static final String TAG = "AppWidgetListAdapter";

    private final AppWidgetManager mAppWidgetManager;
    private final AppWidgetHost mWidgetHost;
    private final FragmentManager mFragmentManager;

    public AppWidgetListAdapter(Context context, List<AppWidgetInfoItem> widgets,
                                FragmentManager fragmentManager) {
        super(context, 0, widgets);
        mAppWidgetManager = AppWidgetManager.getInstance(context);
        mWidgetHost = new AppWidgetHost(context, 1234);
        mFragmentManager = fragmentManager;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.widget_list_item,
                    parent, false);
        }

        AppWidgetInfoItem currentWidget = getItem(position);

        TextView appNameTextView = convertView.findViewById(R.id.widget_app_name_text_view);
        TextView providerTextView = convertView.findViewById(R.id.widget_provider_text_view);
        convertView.setOnClickListener(item -> {
            int appWidgetId = mWidgetHost.allocateAppWidgetId();
            if (mAppWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId,
                    currentWidget.getComponentName())) {
                Log.d(TAG, "Bind succeeded.");
                AppWidgetHostViewFragment fragment = new AppWidgetHostViewFragment(appWidgetId,
                        mWidgetHost, currentWidget.getAppWidgetProviderInfo());
                mFragmentManager.beginTransaction().replace(R.id.kitchen_content,
                        fragment).commit();
            } else {
                Log.e(TAG, "Bind failed unexpectedly.");
            }
        });

        appNameTextView.setText(currentWidget.getAppName());
        providerTextView.setText(currentWidget.getProviderName());

        return convertView;
    }

    static class AppWidgetInfoItem {
        private final String mAppName;
        private final AppWidgetProviderInfo mAppWidgetProviderInfo;

        AppWidgetInfoItem(String appName, AppWidgetProviderInfo appWidgetProviderInfo) {
            mAppName = appName;
            mAppWidgetProviderInfo = appWidgetProviderInfo;
        }

        String getAppName() {
            return mAppName;
        }

        String getProviderName() {
            return mAppWidgetProviderInfo.provider.getClassName();
        }

        ComponentName getComponentName() {
            return mAppWidgetProviderInfo.provider;
        }

        AppWidgetProviderInfo getAppWidgetProviderInfo() {
            return mAppWidgetProviderInfo;
        }
    }
}
