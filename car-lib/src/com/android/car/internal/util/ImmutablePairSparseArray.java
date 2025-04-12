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
import android.util.ArraySet;

/**
 * An immutable version of {@link PairSparseArray}.
 *
 * This only exposes getter methods and is safe to use across threads.
 *
 * @param <E> Type of the element
 */
public final class ImmutablePairSparseArray<E> implements Cloneable {
    private final PairSparseArray<E> mArray;

    /**
     * Caller must not modify the array after calling this.
     *
     * It is safer for caller to drop the reference to the array after calling this.
     *
     * @param array The pair sparse array that should be immutable.
     */
    public ImmutablePairSparseArray(PairSparseArray<E> array) {
        mArray = array;
    }

    /** Creates a clone. */
    @Override
    public ImmutablePairSparseArray<E> clone() {
        return new ImmutablePairSparseArray<E>(mArray.clone());
    }

    /**
     * Gets all the second keys for the first key.
     */
    @AnyThread
    public ArraySet<Integer> getSecondKeysForFirstKey(int firstKey) {
        return mArray.getSecondKeysForFirstKey(firstKey);
    }

    /**
     * Gets all the first keys.
     */
    @AnyThread
    public ArraySet<Integer> getFirstKeys() {
        return mArray.getFirstKeys();
    }

    /**
     * Gets the Object mapped from the specified key, or {@code null}
     * if no such mapping has been made.
     *
     * @see #get(int, int, Object)
     */
    @AnyThread
    public E get(int firstKey, int secondKey) {
        return mArray.get(firstKey, secondKey);
    }

    /**
     * Gets the Object mapped from the specified key, or the specified Object
     * if no such mapping has been made.
     *
     * @param firstKey the integer key stored in the most significant bits
     * @param secondKey the integer key stored in the least significant bits
     * @param valueIfKeyPairNotFound the value to return if {@code firstKey} and {@code secondKey}
     * have not been mapped.
     *
     * @return the value mapped to {@code firstKey} and {@code secondKey}, or {@code
     * valueIfKeyPairNotFound} if keys have not been mapped.
     */
    @AnyThread
    public E get(int firstKey, int secondKey, E valueIfKeyPairNotFound) {
        return mArray.get(firstKey, secondKey, valueIfKeyPairNotFound);
    }

    /**
     * Returns the index for which {@link #keyPairAt} would return the
     * specified keys, or a negative number if the specified
     * keys are not mapped.
     */
    @AnyThread
    public int indexOfKeyPair(int firstKey, int secondKey) {
        return mArray.indexOfKeyPair(firstKey, secondKey);
    }

    /**
     * Returns an index for which {@link #valueAt} would return the
     * specified key, or a negative number if no keys map to the
     * specified value.
     *
     * @see LongSparseArray#indexOfValue(Object)
     */
    @AnyThread
    public int indexOfValue(E value) {
        return mArray.indexOfValue(value);
    }

    /**
     * Given an index in the range <code>0...size()-1</code>, returns
     * the key pair from the <code>index</code>th key-value mapping that this
     * PairSparseArray stores.
     *
     * @return int array of size 2 with the first and second key at indices 0 and 1 respectively.
     * @see LongSparseArray#keyAt(int)
     */
    @AnyThread
    public int[] keyPairAt(int index) {
        return mArray.keyPairAt(index);
    }

    /** Returns the number of key-value mappings that this PairSparseArray currently stores. */
    @AnyThread
    public int size() {
        return mArray.size();
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation composes a string by iterating over its mappings.
     */
    @Override
    @AnyThread
    public String toString() {
        return mArray.toString();
    }

    /**
     * Given an index in the range <code>0...size()-1</code>, returns
     * the value from the <code>index</code>th key-value mapping that this
     * PairSparseArray stores.
     *
     * @see LongSparseArray#valueAt(int)
     */
    @AnyThread
    public E valueAt(int index) {
        return mArray.valueAt(index);
    }
}
