/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.car.obd2.test;

import java.util.ArrayList;
import java.util.Arrays;

public class Utils {
    private Utils() {}

    static int[] stringsToIntArray(String... strings) {
        ArrayList<Integer> arrayList = new ArrayList<>();
        for (String string : strings) {
            string.chars().forEach(arrayList::add);
        }
        return arrayList.stream().mapToInt(Integer::intValue).toArray();
    }

    static int[] concatIntArrays(int[] array1, int[] array2) {
        int[] newArray = Arrays.copyOf(array1, array1.length + array2.length);
        System.arraycopy(array2, 0, newArray, array1.length, array2.length);
        return newArray;
    }
}
