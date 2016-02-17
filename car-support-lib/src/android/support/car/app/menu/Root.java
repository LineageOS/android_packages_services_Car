/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.support.car.app.menu;

import android.os.Bundle;

/**
 * Stores the Root id for the menu. The Root is the main menu.
 * Also allows passing hints through bundles. Hints allow the
 * the recipient to alter its behavior based on the hints.
 */
public class Root {
    private final Bundle mBundle;

    /**
     * Create a root with no extra hints.
     *
     * @param id Root id
     */
    public Root(String id) {
        this(id, null);
    }

    /**
     * Create a root with hints
     *
     * @param id Root id
     * @param extras Hints to pass along
     */
    public Root(String id, Bundle extras) {
        mBundle = new Bundle();
        mBundle.putString(Constants.CarMenuConstants.KEY_ID, id);
        if (extras != null) {
            mBundle.putAll(extras);
        }
    }

    /**
     * Get the root id
     *
     * @return The root id
     */
    public String getId() {
        return mBundle.getString(Constants.CarMenuConstants.KEY_ID);
    }

    /**
     * Get any hints
     *
     * @return A bundle if there are hints; null otherwise.
     */
    public Bundle getBundle() {
        return new Bundle(mBundle);
    }
}
