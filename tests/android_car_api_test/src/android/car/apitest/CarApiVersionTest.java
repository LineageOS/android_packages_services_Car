/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.car.apitest;

import static android.car.CarApiVersion.TIRAMISU_0;
import static android.car.CarApiVersion.TIRAMISU_1;
import static android.os.Build.VERSION_CODES.TIRAMISU;

import android.car.test.AbstractExpectableTestCase;

import org.junit.Test;

public final class CarApiVersionTest extends AbstractExpectableTestCase {

    @Test
    public void testTiramisu_0() {
        expectWithMessage("TIRAMISU_0").that(TIRAMISU_1).isNotNull();
        expectWithMessage("TIRAMISU_0.major").that(TIRAMISU_0.getMajorVersion())
                .isEqualTo(TIRAMISU);
        expectWithMessage("TIRAMISU_0.minor").that(TIRAMISU_0.getMinorVersion())
                .isEqualTo(0);
    }

    @Test
    public void testTiramisu_1() {
        expectWithMessage("TIRAMISU_1").that(TIRAMISU_1).isNotNull();
        expectWithMessage("TIRAMISU_1.major").that(TIRAMISU_1.getMajorVersion())
                .isEqualTo(TIRAMISU);
        expectWithMessage("TIRAMISU_1.minor").that(TIRAMISU_1.getMinorVersion())
                .isEqualTo(1);
    }
}
