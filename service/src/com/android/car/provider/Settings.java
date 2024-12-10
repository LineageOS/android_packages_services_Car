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

package com.android.car.provider;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.Settings.SettingNotFoundException;

/**
 * An interface to stub methods from {@link android.provider.Settings}.
 */
public interface Settings {
    /**
     * @see android.provider.Settings.System#getInt
     */
    int getIntSystem(ContentResolver cr, String name) throws SettingNotFoundException;

    /**
     * @see android.provider.Settings.System#putInt
     */
    void putIntSystem(ContentResolver cr, String name, int value);

    /**
     * @see android.provider.Settings.System#getUriFor
     */
    Uri getUriForSystem(String name);

    /**
     * @see android.provider.Settings.System#putString
     */
    boolean putStringSystem(ContentResolver resolver, String name, String value);

    /**
     * @see android.provider.Settings.Global.getString
     */
    String getStringGlobal(ContentResolver resolver, String name);

    /**
     * @see android.provider.Settings.Global.getInt
     */
    int getIntGlobal(ContentResolver cr, String name, int def);

    /**
     * @see android.provider.Settings.Global.putInt
     */
    boolean putIntGlobal(ContentResolver cr, String name, int value);

    /**
     * The default real implementation.
     */
    class DefaultImpl implements Settings {
        @Override
        public int getIntSystem(ContentResolver cr, String name) throws SettingNotFoundException {
            return android.provider.Settings.System.getInt(cr, name);
        }

        @Override
        public void putIntSystem(ContentResolver cr, String name, int value) {
            android.provider.Settings.System.putInt(cr, name, value);
        }

        @Override
        public Uri getUriForSystem(String name) {
            return android.provider.Settings.System.getUriFor(name);
        }

        @Override
        public boolean putStringSystem(ContentResolver resolver, String name, String value) {
            return android.provider.Settings.System.putString(resolver, name, value);
        }

        @Override
        public String getStringGlobal(ContentResolver resolver, String name) {
            return android.provider.Settings.Global.getString(resolver, name);
        }

        @Override
        public int getIntGlobal(ContentResolver cr, String name, int def) {
            return android.provider.Settings.Global.getInt(cr, name, def);
        }

        @Override
        public boolean putIntGlobal(ContentResolver cr, String name, int value) {
            return android.provider.Settings.Global.putInt(cr, name, value);
        }
    }
}
