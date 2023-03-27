/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.car.tool.apibuilder;

import com.android.car.tool.data.ParsedData;

import java.util.ArrayList;
import java.util.List;

public class ParsedDataHelper {

    // TODO: add tests for this class
    public static List<String> getClassNamesOnly(ParsedData parsedData) {
        List<String> classes = new ArrayList<>();
        parsedData.packages.values().forEach((packageData) -> packageData.classes.values()
                .forEach((classData) -> classes.add(classData.useableClassName)));
        return classes;
    }
}
