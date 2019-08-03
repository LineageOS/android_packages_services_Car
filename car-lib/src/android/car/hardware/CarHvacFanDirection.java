/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.car.hardware;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * CarHvacFanDirection is an abstraction for the fan directions.It exists to isolate the java APIs
 * from the VHAL definitions.
 * @hide
 */
public final class CarHvacFanDirection {
    public static final int UNKNOWN = 0x0;
    public static final int FACE = 0x01;
    public static final int FLOOR = 0x02;
    /**
     * FACE_AND_FLOOR = FACE | FLOOR
     */
    public static final int FACE_AND_FLOOR = 0x03;
    public static final int DEFROST = 0x04;
    /**
     * DEFROST_AND_FLOOR = DEFROST | FLOOR
     */
    public static final int DEFROST_AND_FLOOR = 0x06;

    /**@hide*/
    @IntDef(value = {
            UNKNOWN,
            FACE,
            FLOOR,
            FACE_AND_FLOOR,
            DEFROST,
            DEFROST_AND_FLOOR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Enum {}
    private CarHvacFanDirection() {}

    /**
     * @param direction
     * @return String of fan directions
     */
    public static String toString(int direction) {
        if (direction == UNKNOWN) {
            return "UNKNOWN";
        }
        if (direction == FACE) {
            return "FACE";
        }
        if (direction == FLOOR) {
            return "FLOOR";
        }
        if (direction == FACE_AND_FLOOR) {
            return "FACE_AND_FLOOR";
        }
        if (direction == DEFROST) {
            return "DEFROST";
        }
        if (direction == DEFROST_AND_FLOOR) {
            return "DEFROST_AND_FLOOR";
        }
        return "0x" + Integer.toHexString(direction);
    }
}
