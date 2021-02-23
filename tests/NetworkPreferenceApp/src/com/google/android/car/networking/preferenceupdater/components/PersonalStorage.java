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
package com.google.android.car.networking.preferenceupdater.components;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** This class will abstragate storage where OEM network policies are persisted */
public final class PersonalStorage {
    private static final String KEY_PREFERENCE_APP = "key_preference_app";
    private static final String KEY_OEM_PAID_APPS_LIST = "key_oem_paid_apps_list";
    private static final String KEY_OEM_PRIVATE_APPS_LIST = "key_oem_private_apps_list";

    private SharedPreferences mSharedPrefs;

    public PersonalStorage(Context ctx) {
        mSharedPrefs = ctx.getSharedPreferences(KEY_PREFERENCE_APP, Context.MODE_PRIVATE);
    }

    public Set<String> getOEMPaidAppSet() {
        return mSharedPrefs.getStringSet(KEY_OEM_PAID_APPS_LIST, new HashSet<String>());
    }

    public Set<String> getOEMPrivateAppSet() {
        return mSharedPrefs.getStringSet(KEY_OEM_PRIVATE_APPS_LIST, new HashSet<String>());
    }

    public void saveOEMPaidAppSet(Set<String> st) {
        SharedPreferences.Editor editor = mSharedPrefs.edit();
        editor.putStringSet(KEY_OEM_PAID_APPS_LIST, st);
        editor.apply();
    }

    public void saveOEMPrivateAppSet(Set<String> st) {
        SharedPreferences.Editor editor = mSharedPrefs.edit();
        editor.putStringSet(KEY_OEM_PRIVATE_APPS_LIST, st);
        editor.apply();
    }
}
