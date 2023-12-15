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

package com.android.systemui.car.distantdisplay.common;

import java.util.LinkedHashSet;

/**
 * Custom implementation for LinkedHashSet such that when a duplicate is added the duplicated
 * element stays towards the end of te list. For example,
 *
 *      DistantDisplayLinkedHashSet<> set = new DistantDisplayLinkedHashSet<>();
 *      set.add(1);
 *      set.add(2);
 *      set.add(3);
 *      set.add(4);
 *      // set => 1, 2, 3, 4
 *
 *      set.add(2);
 *      // set => 1, 3, 4, 2
 */
class OrderedHashSet<E> {

    LinkedHashSet<E> mData;

    OrderedHashSet() {
        mData = new LinkedHashSet<>();
    }

    void add(E e) {
        // Get rid of old one.
        mData.remove(e);
        // Add it.
        mData.add(e);
    }

    E getLast() {
        E last = null;
        for (E e : mData) last = e;
        return last;
    }

    boolean remove(E e) {
        return mData.remove(e);
    }

    boolean isEmpty() {
        return mData.isEmpty();
    }
}
