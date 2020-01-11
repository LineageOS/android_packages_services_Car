/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.vms;

import android.car.vms.IVmsClientCallback;
import android.car.vms.VmsAssociatedLayer;
import android.car.vms.VmsLayer;
import android.car.vms.VmsLayerDependency;
import android.car.vms.VmsLayersOffering;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Class for tracking Vehicle Map Service client information, offerings, and subscriptions.
 */
final class VmsClientInfo {
    private final int mUid;
    private final String mPackageName;
    private final IVmsClientCallback mCallback;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final SparseBooleanArray mProviderIds = new SparseBooleanArray();
    @GuardedBy("mLock")
    private final SparseArray<Set<VmsLayerDependency>> mOfferings = new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseArray<Set<VmsLayer>> mPotentialOfferings = new SparseArray<>();
    @GuardedBy("mLock")
    private Set<VmsLayer> mLayerSubscriptions = Collections.emptySet();
    @GuardedBy("mLock")
    private Map<VmsLayer, Set<Integer>> mLayerAndProviderSubscriptions = Collections.emptyMap();
    @GuardedBy("mLock")
    private boolean mMonitoringEnabled;

    VmsClientInfo(int uid, String packageName, IVmsClientCallback callback) {
        mUid = uid;
        mPackageName = packageName;
        mCallback = callback;
    }

    int getUid() {
        return mUid;
    }

    String getPackageName() {
        return mPackageName;
    }

    IVmsClientCallback getCallback() {
        return mCallback;
    }

    void addProviderId(int providerId) {
        synchronized (mLock) {
            mProviderIds.put(providerId, true);
        }
    }

    boolean hasProviderId(int providerId) {
        synchronized (mLock) {
            return mProviderIds.get(providerId);
        }
    }

    boolean setProviderOfferings(int providerId, Collection<VmsLayerDependency> offerings) {
        synchronized (mLock) {
            Set<VmsLayerDependency> providerOfferings = mOfferings.get(providerId);

            // If the offerings are unchanged, do nothing
            if (providerOfferings != null
                    && providerOfferings.size() == offerings.size()
                    && providerOfferings.containsAll(offerings)) {
                return false;
            }

            // Otherwise, update the offerings and return true
            mOfferings.put(providerId, new ArraySet<>(offerings));
            mPotentialOfferings.put(providerId, offerings.stream()
                    .map(VmsLayerDependency::getLayer)
                    .collect(Collectors.toSet()));
            return true;
        }
    }

    Collection<VmsLayersOffering> getAllOfferings() {
        List<VmsLayersOffering> result = new ArrayList<>(mOfferings.size());
        synchronized (mLock) {
            for (int i = 0; i < mOfferings.size(); i++) {
                int providerId = mOfferings.keyAt(i);
                Set<VmsLayerDependency> providerOfferings = mOfferings.valueAt(i);
                result.add(new VmsLayersOffering(new ArraySet<>(providerOfferings), providerId));
            }
        }
        return result;
    }

    boolean hasOffering(int providerId, VmsLayer layer) {
        synchronized (mLock) {
            return mPotentialOfferings.get(providerId, Collections.emptySet()).contains(layer);
        }
    }

    void setSubscriptions(List<VmsAssociatedLayer> layers) {
        synchronized (mLock) {
            mLayerSubscriptions = layers.stream()
                    .filter(associatedLayer -> associatedLayer.getProviderIds().isEmpty())
                    .map(VmsAssociatedLayer::getVmsLayer)
                    .collect(Collectors.toSet());
            mLayerAndProviderSubscriptions = layers.stream()
                    .filter(associatedLayer -> !associatedLayer.getProviderIds().isEmpty())
                    .collect(Collectors.toMap(
                            VmsAssociatedLayer::getVmsLayer,
                            associatedLayer -> new ArraySet<>(associatedLayer.getProviderIds())));
        }
    }

    Set<VmsLayer> getLayerSubscriptions() {
        synchronized (mLock) {
            return new ArraySet<>(mLayerSubscriptions);
        }
    }

    Map<VmsLayer, Set<Integer>> getLayerAndProviderSubscriptions() {
        synchronized (mLock) {
            return deepCopy(mLayerAndProviderSubscriptions);
        }
    }

    void setMonitoringEnabled(boolean enabled) {
        synchronized (mLock) {
            mMonitoringEnabled = enabled;
        }
    }

    boolean isSubscribed(int providerId, VmsLayer layer) {
        synchronized (mLock) {
            return mMonitoringEnabled
                    || mLayerSubscriptions.contains(layer)
                    || mLayerAndProviderSubscriptions.getOrDefault(layer, Collections.emptySet())
                            .contains(providerId);
        }
    }

    private static <K, V> Map<K, Set<V>> deepCopy(Map<K, Set<V>> original) {
        return original.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> new ArraySet<>(entry.getValue())));
    }
}
