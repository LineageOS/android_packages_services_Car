/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.google.android.car.networking.preferenceupdater;

import android.text.TextUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/** Utilities */
final class Utils {

    /** Convert list of strings to string */
    public static String toString(List<String> st) {
        return TextUtils.join(",", st);
    }

    /** Converts comma separated string to set of strings */
    public static List<String> toList(String st) {
        return Arrays.asList(TextUtils.split(",", st));
    }

    /** Converts Set to List */
    public static List<String> toList(Set<String> st) {
        List<String> lst = new LinkedList<String>();
        lst.addAll(st);
        return lst;
    }

    /** Converts List to Set */
    public static Set<String> toSet(List<String> lst) {
        Set<String> st = new HashSet<String>();
        st.addAll(lst);
        return st;
    }
}
