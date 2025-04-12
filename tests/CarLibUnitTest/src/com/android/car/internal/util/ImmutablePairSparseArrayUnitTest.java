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

import org.junit.Test;

public class ImmutablePairSparseArrayUnitTest extends AbstractExpectableTestCase {

    @Test
    public void testImmutablePairSparseArray() {
        var pairSparseArray = new PairSparseArray<Integer>();
        pairSparseArray.put(1, 2, 3);
        pairSparseArray.put(1, 4, 5);

        var immutableArray = new ImmutablePairSparseArray(pairSparseArray);

        expectThat(immutableArray.getSecondKeysForFirstKey(1)).containsExactly(2, 4);
        expectThat(immutableArray.getFirstKeys()).containsExactly(1);
        expectThat(immutableArray.get(1, 2)).isEqualTo(3);
        expectThat(immutableArray.get(1, 1234, /* valueIfKeyPairNotFound= */ 234))
                .isEqualTo(234);
        expectThat(immutableArray.indexOfKeyPair(1, 2)).isEqualTo(0);
        expectThat(immutableArray.indexOfValue(3)).isEqualTo(0);
        expectThat(immutableArray.keyPairAt(1)).isEqualTo(new int[]{1, 4});
        expectThat(immutableArray.size()).isEqualTo(2);
        expectThat(immutableArray.toString()).isEqualTo(pairSparseArray.toString());
        expectThat(immutableArray.valueAt(1)).isEqualTo(5);
    }

    @Test
    public void testImmutablePairSparseArray_clone() {
        var pairSparseArray = new PairSparseArray<Integer>();
        pairSparseArray.put(1, 2, 3);
        pairSparseArray.put(1, 4, 5);

        var immutableArray = new ImmutablePairSparseArray(pairSparseArray);
        var clone = immutableArray.clone();

        expectThat(clone.getSecondKeysForFirstKey(1)).containsExactly(2, 4);
        expectThat(clone.getFirstKeys()).containsExactly(1);
        expectThat(clone.get(1, 2)).isEqualTo(3);
        expectThat(clone.get(1, 1234, /* valueIfKeyPairNotFound= */ 234))
                .isEqualTo(234);
        expectThat(clone.indexOfKeyPair(1, 2)).isEqualTo(0);
        expectThat(clone.indexOfValue(3)).isEqualTo(0);
        expectThat(clone.keyPairAt(1)).isEqualTo(new int[]{1, 4});
        expectThat(clone.size()).isEqualTo(2);
        expectThat(clone.toString()).isEqualTo(pairSparseArray.toString());
        expectThat(clone.valueAt(1)).isEqualTo(5);
    }
}
