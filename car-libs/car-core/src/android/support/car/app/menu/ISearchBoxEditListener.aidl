/*
 * Copyright (C) 2016 The Android Open Source Project
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

interface ISearchBoxEditListener {
    /**
     * The user hit enter on the keyboard.
     */
    void onSearch(in String text) = 0;

    /**
     * The user changed the text in the search box with the keyboard.
     */
    void onEdit(in String text) = 1;
}
