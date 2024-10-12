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

package com.android.car.internal.property;

import static com.google.common.truth.Truth.assertThat;

import android.car.test.AbstractExpectableTestCase;
import android.os.Parcel;

import org.junit.Test;

public final class RawPropertyValueUnitTest extends AbstractExpectableTestCase {

    @Test
    public void testGetTypedValue() {
        Integer[] typedValue = new Integer[]{1, 2};

        var value = new RawPropertyValue(typedValue);

        assertThat(value.getTypedValue()).isEqualTo(typedValue);
    }

    @Test
    public void testEquals() {
        expectThat(new RawPropertyValue(1).equals(new RawPropertyValue(1))).isTrue();
        expectThat(new RawPropertyValue(1).equals(new RawPropertyValue(2))).isFalse();
        expectThat(new RawPropertyValue(1.1f)
                .equals(new RawPropertyValue(1.1f))).isTrue();
        expectThat(new RawPropertyValue(1.1f)
                .equals(new RawPropertyValue(1.2f))).isFalse();
        expectThat(new RawPropertyValue(new Integer[]{1, 2, 3})
                .equals(new RawPropertyValue(new Integer[]{1, 2, 3}))).isTrue();
        expectThat(new RawPropertyValue(new Integer[]{1, 2, 3})
                .equals(new RawPropertyValue(new Integer[]{3, 2, 3}))).isFalse();
        expectThat(new RawPropertyValue(new Object[]{1, "abc", 2.3f})
                .equals(new RawPropertyValue(new Object[]{1, "abc", 2.3f}))).isTrue();
        expectThat(new RawPropertyValue(new Object[]{1, "abc", 2.3f})
                .equals(new RawPropertyValue(new Object[]{1, "bcd", 2.3f}))).isFalse();
    }

    @Test
    public void testHashCode() {
        int hashCode = new RawPropertyValue(new Object[]{1, "abc", 2.3f}).hashCode();

        expectThat(hashCode == new RawPropertyValue(new Object[]{1, "abc", 2.3f}).hashCode())
                .isTrue();
        expectThat(hashCode == new RawPropertyValue(new Object[]{1, "bcd", 2.3f}).hashCode())
                .isFalse();
    }

    @Test
    public void testToString() {
        expectThat(new RawPropertyValue(new Object[]{1, "abc", 2.3f}).toString())
                .contains("1");
        expectThat(new RawPropertyValue(new Object[]{1, "abc", 2.3f}).toString())
                .contains("abc");
        expectThat(new RawPropertyValue(new Object[]{1, "abc", 2.3f}).toString())
                .contains("2.3");
    }

    @Test
    public void toParcelFromParcel() {
        var value = new RawPropertyValue(new Object[]{1, "abc", 2.3f, false});

        Parcel parcel = Parcel.obtain();

        value.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);

        expectThat(new RawPropertyValue(parcel)).isEqualTo(value);

        parcel.recycle();
    }
}
