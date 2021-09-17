/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.developeroptions;

import android.content.Context;
import android.os.SystemProperties;

import androidx.preference.Preference;
import androidx.preference.SwitchPreference;

/**
 * Preference controller for Car UI library plugin enablement.
 */
public class CarDevelopmentCarUiLibController extends CarDevelopmentPreferenceController {
    private static final String CAR_UI_PLUGIN_ENABLED_KEY = "car_ui_plugin_enabled";
    static final String CAR_UI_PLUGIN_ENABLED_PROPERTY =
            "persist.sys.automotive.car.ui.plugin.enabled";


    public CarDevelopmentCarUiLibController(Context context) {
        super(context);
    }

    @Override
    public String getPreferenceKey() {
        return CAR_UI_PLUGIN_ENABLED_KEY;
    }

    @Override
    public String getPreferenceTitle() {
        return mContext.getString(R.string.car_ui_plugin_enabled_pref_title);
    }

    @Override
    String getPreferenceSummary() {
        return null;
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        SystemProperties.set(CAR_UI_PLUGIN_ENABLED_PROPERTY, String.valueOf(newValue));
        return true;
    }

    @Override
    public void updateState(Preference preference) {
        final boolean pluginEnabled = SystemProperties.getBoolean(
                CAR_UI_PLUGIN_ENABLED_PROPERTY, false /* default */);
        ((SwitchPreference) mPreference).setChecked(pluginEnabled);
    }
}
