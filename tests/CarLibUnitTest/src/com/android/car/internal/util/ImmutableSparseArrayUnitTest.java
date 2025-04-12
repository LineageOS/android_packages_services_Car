/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.car.internal.util;

import android.car.test.AbstractExpectableTestCase;
import android.util.SparseArray;

import org.junit.Test;

public class ImmutableSparseArrayUnitTest extends AbstractExpectableTestCase {

    @Test
    public void testImmutableSparseArray() {
        var sparseArray = new SparseArray<Integer>();
        sparseArray.put(1, 2);
        sparseArray.put(3, 4);

        var immutableArray = new ImmutableSparseArray(sparseArray);

        expectThat(immutableArray.size()).isEqualTo(2);
        expectThat(immutableArray.keyAt(0)).isEqualTo(1);
        expectThat(immutableArray.valueAt(0)).isEqualTo(2);
        expectThat(immutableArray.indexOfKey(3)).isEqualTo(1);
        expectThat(immutableArray.contains(3)).isTrue();
        expectThat(immutableArray.contains(4)).isFalse();
        expectThat(immutableArray.get(1)).isEqualTo(2);
        expectThat(immutableArray.get(3)).isEqualTo(4);
    }

    @Test
    public void testImmutableSparseArray_clone() {
        var sparseArray = new SparseArray<Integer>();
        sparseArray.put(1, 2);
        sparseArray.put(3, 4);

        var immutableArray = new ImmutableSparseArray(sparseArray);
        var clone = immutableArray.clone();

        expectThat(clone.size()).isEqualTo(2);
        expectThat(clone.keyAt(0)).isEqualTo(1);
        expectThat(clone.valueAt(0)).isEqualTo(2);
        expectThat(clone.indexOfKey(3)).isEqualTo(1);
        expectThat(clone.contains(3)).isTrue();
        expectThat(clone.contains(4)).isFalse();
        expectThat(clone.get(1)).isEqualTo(2);
        expectThat(clone.get(3)).isEqualTo(4);
    }
}
