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
import android.support.car.app.menu.ISubscriptionCallbacks;

/** {@CompatibilityApi} */
interface ICarMenuCallbacks {
    int getVersion() = 0;
    Bundle getRoot() = 1;
    void subscribe(String parentId, ISubscriptionCallbacks callbacks) = 2;
    void unsubscribe(String parentId, ISubscriptionCallbacks callbacks) = 3;
    void onCarMenuOpened() = 4;
    void onCarMenuClosed() = 5;
    void onItemClicked(String id) = 6;
    boolean onItemLongClicked(String id) = 7;
    void onStateChanged(int newState) = 8;
    boolean onMenuClicked() = 9;
    void onCarMenuOpening() = 10;
    void onCarMenuClosing() = 11;
}
