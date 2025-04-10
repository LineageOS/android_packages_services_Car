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

import android.annotation.AnyThread;
import android.annotation.Nullable;
import android.util.SparseArray;

/**
 * An immutable version of {@link SparseArray}.
 *
 * This only exposes getter methods and is safe to use across threads.
 *
 * @param <E> Type of the element
 */
public final class ImmutableSparseArray<E> {
    private final SparseArray<E> mArray;

    /**
     * Caller must not modify the array after calling this.
     *
     * It is safer for caller to drop the reference to the array after calling this.
     *
     * @param array The sparse array that should be immutable.
     */
    public ImmutableSparseArray(SparseArray<E> array) {
        mArray = array;
    }

    /** Creates a clone. */
    @Override
    public ImmutableSparseArray<E> clone() {
        return new ImmutableSparseArray<E>(mArray.clone());
    }

    /**
     * @return the size of this array
     */
    @AnyThread
    public int size() {
        return mArray.size();
    }

    /**
     * Returns the key of the specified index.
     *
     * @return the key of the specified index
     * @throws ArrayIndexOutOfBoundsException when the index is out of range
     */
    @AnyThread
    public int keyAt(int index) {
        return mArray.keyAt(index);
    }

    /**
     * Returns the value of the specified index.
     *
     * @return the value of the specified index
     * @throws ArrayIndexOutOfBoundsException when the index is out of range
     */
    @AnyThread
    @Nullable
    public E valueAt(int index) {
        return mArray.valueAt(index);
    }

    /**
     * Returns the index of the specified key.
     *
     * @return the index of the specified key if exists. Otherwise {@code -1}
     */
    @AnyThread
    public int indexOfKey(int key) {
        return mArray.indexOfKey(key);
    }

    /**
     * Returns {@code true} if the given {@code key} exists.
     *
     * @param key the key to be queried
     * @return    {@code true} if the given {@code key} exists
     */
    @AnyThread
    public boolean contains(int key) {
        return mArray.contains(key);
    }

    /**
     * Returns the value associated with the {@code key}.
     *
     * @param key the key to be queried
     * @return    the value associated with the {@code key} if exists. Otherwise {@code null}
     */
    @AnyThread
    @Nullable
    public E get(int key) {
        return mArray.get(key);
    }
}
