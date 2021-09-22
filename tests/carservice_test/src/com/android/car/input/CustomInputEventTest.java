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

package com.android.car.input;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.car.CarOccupantZoneManager;
import android.car.input.CustomInputEvent;
import android.os.Parcel;

import org.junit.Test;

public final class CustomInputEventTest {

    private static final int INVALID_INPUT_CODE = -1;

    @Test
    public void testCustomInputEventWriteAndReadParcel() {
        CustomInputEvent original = new CustomInputEvent(
                /* inputCode= */ CustomInputEvent.INPUT_CODE_F1,
                /* targetDisplayType= */ CarOccupantZoneManager.DISPLAY_TYPE_INSTRUMENT_CLUSTER,
                /* repeatCounter= */ 3);

        Parcel parcel = Parcel.obtain();
        original.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        CustomInputEvent actual = CustomInputEvent.CREATOR.createFromParcel(parcel);
        assertThat(actual).isEqualTo(original);

        // Check generated getters
        assertThat(actual.getInputCode()).isEqualTo(CustomInputEvent.INPUT_CODE_F1);
        assertThat(actual.getRepeatCounter()).isEqualTo(3);
        assertThat(actual.getTargetDisplayType()).isEqualTo(
                CarOccupantZoneManager.DISPLAY_TYPE_INSTRUMENT_CLUSTER);
    }

    @Test
    public void testCustomInputEventParcelNewArray() {
        CustomInputEvent[] eventArray = CustomInputEvent.CREATOR.newArray(10);
        assertThat(eventArray).hasLength(10);
    }


    @Test
    public void testCannotCreateCustomInputEventWithInvalidInputCode() {
        assertThrows(IllegalArgumentException.class, () -> new CustomInputEvent(
                /* inputCode= */ INVALID_INPUT_CODE,
                /* targetDisplayType= */ CarOccupantZoneManager.DISPLAY_TYPE_INSTRUMENT_CLUSTER,
                /* repeatCounter= */ 3));
    }

    @Test
    public void testInputCodeToString() {
        assertThrows(IllegalArgumentException.class,
                () -> CustomInputEvent.inputCodeToString(INVALID_INPUT_CODE));
        assertThat(CustomInputEvent.inputCodeToString(CustomInputEvent.INPUT_CODE_F1)).isEqualTo(
                "INPUT_CODE_F1");
        assertThat(CustomInputEvent.inputCodeToString(CustomInputEvent.INPUT_CODE_F2)).isEqualTo(
                "INPUT_CODE_F2");
        assertThat(CustomInputEvent.inputCodeToString(CustomInputEvent.INPUT_CODE_F3)).isEqualTo(
                "INPUT_CODE_F3");
        assertThat(CustomInputEvent.inputCodeToString(CustomInputEvent.INPUT_CODE_F4)).isEqualTo(
                "INPUT_CODE_F4");
        assertThat(CustomInputEvent.inputCodeToString(CustomInputEvent.INPUT_CODE_F5)).isEqualTo(
                "INPUT_CODE_F5");
        assertThat(CustomInputEvent.inputCodeToString(CustomInputEvent.INPUT_CODE_F6)).isEqualTo(
                "INPUT_CODE_F6");
        assertThat(CustomInputEvent.inputCodeToString(CustomInputEvent.INPUT_CODE_F7)).isEqualTo(
                "INPUT_CODE_F7");
        assertThat(CustomInputEvent.inputCodeToString(CustomInputEvent.INPUT_CODE_F8)).isEqualTo(
                "INPUT_CODE_F8");
        assertThat(CustomInputEvent.inputCodeToString(CustomInputEvent.INPUT_CODE_F9)).isEqualTo(
                "INPUT_CODE_F9");
        assertThat(CustomInputEvent.inputCodeToString(CustomInputEvent.INPUT_CODE_F10)).isEqualTo(
                "INPUT_CODE_F10");
    }
}
