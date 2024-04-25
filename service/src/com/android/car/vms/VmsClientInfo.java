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

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.car.vms.IVmsClientCallback;
import android.car.vms.VmsAssociatedLayer;
import android.car.vms.VmsLayer;
import android.car.vms.VmsLayerDependency;
import android.car.vms.VmsLayersOffering;
import android.os.IBinder;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Class for tracking Vehicle Map Service client information, offerings, and subscriptions.
 */
final class VmsClientInfo {
    private final int mUid;
    private final String mPackageName;
    private final IVmsClientCallback mCallback;
    private final boolean mLegacyClient;
    private final IBinder.DeathRecipient mDeathRecipient;

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
    private ArrayMap<VmsLayer, ArraySet<Integer>> mLayerAndProviderSubscriptions = new ArrayMap<>();
    @GuardedBy("mLock")
    private boolean mMonitoringEnabled;

    VmsClientInfo(int uid, String packageName, IVmsClientCallback callback, boolean legacyClient,
            IBinder.DeathRecipient deathRecipient) {
        mUid = uid;
        mPackageName = packageName;
        mCallback = callback;
        mLegacyClient = legacyClient;
        mDeathRecipient = deathRecipient;
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

    boolean isLegacyClient() {
        return mLegacyClient;
    }

    IBinder.DeathRecipient getDeathRecipient() {
        return mDeathRecipient;
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

    boolean setProviderOfferings(int providerId, List<VmsLayerDependency> offerings) {
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
            ArraySet<VmsLayer> layerSet = new ArraySet<>(offerings.size());
            for (int i = 0; i < offerings.size(); i++) {
                layerSet.add(offerings.get(i).getLayer());
            }
            mPotentialOfferings.put(providerId, layerSet);
            return true;
        }
    }

    Collection<VmsLayersOffering> getAllOfferings() {
        synchronized (mLock) {
            return getAllOfferingsLcoked();
        }
    }

    @GuardedBy("mLock")
    Collection<VmsLayersOffering> getAllOfferingsLcoked() {
        List<VmsLayersOffering> result = new ArrayList<>(mOfferings.size());
        for (int i = 0; i < mOfferings.size(); i++) {
            int providerId = mOfferings.keyAt(i);
            Set<VmsLayerDependency> providerOfferings = mOfferings.valueAt(i);
            result.add(new VmsLayersOffering(new ArraySet<>(providerOfferings), providerId));
        }
        return result;
    }

    boolean hasOffering(int providerId, VmsLayer layer) {
        synchronized (mLock) {
            return mPotentialOfferings.get(providerId, Collections.emptySet()).contains(layer);
        }
    }

    void setSubscriptions(List<VmsAssociatedLayer> layers) {
        ArraySet<VmsLayer> layerSubscriptions = new ArraySet<>();
        ArrayMap<VmsLayer, ArraySet<Integer>> layerAndProviderSubscriptions = new ArrayMap<>();
        for (int index = 0; index < layers.size(); index++) {
            VmsAssociatedLayer associatedLayer = layers.get(index);
            if (associatedLayer.getProviderIds().isEmpty()) {
                layerSubscriptions.add(associatedLayer.getVmsLayer());
            } else {
                layerAndProviderSubscriptions.put(associatedLayer.getVmsLayer(),
                        new ArraySet<>(associatedLayer.getProviderIds()));
            }
        }
        synchronized (mLock) {
            mLayerSubscriptions = layerSubscriptions;
            mLayerAndProviderSubscriptions = layerAndProviderSubscriptions;
        }
    }

    Set<VmsLayer> getLayerSubscriptions() {
        synchronized (mLock) {
            return new ArraySet<>(mLayerSubscriptions);
        }
    }

    ArrayMap<VmsLayer, ArraySet<Integer>> getLayerAndProviderSubscriptions() {
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
                    || mLayerAndProviderSubscriptions.getOrDefault(layer, new ArraySet<>())
                            .contains(providerId);
        }
    }

    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    void dump(PrintWriter writer, String indent) {
        synchronized (mLock) {
            String prefix = indent;
            writer.println(prefix + "VmsClient [" + mPackageName + "]");

            prefix = indent + "  ";
            writer.println(prefix + "UID: " + mUid);
            writer.println(prefix + "Legacy Client: " + mLegacyClient);
            writer.println(prefix + "Monitoring: " + mMonitoringEnabled);

            if (mProviderIds.size() > 0) {
                writer.println(prefix + "Offerings:");
                for (int i = 0; i < mProviderIds.size(); i++) {
                    prefix = indent + "    ";
                    int providerId = mProviderIds.keyAt(i);
                    writer.println(prefix + "Provider [" + providerId + "]");

                    for (VmsLayerDependency layerOffering : mOfferings.get(
                            providerId, Collections.emptySet())) {
                        prefix = indent + "      ";
                        writer.println(prefix + layerOffering.getLayer());
                        if (!layerOffering.getDependencies().isEmpty()) {
                            prefix = indent + "        ";
                            writer.println(prefix + "Dependencies: "
                                    + layerOffering.getDependencies());
                        }
                    }
                }
            }

            if (!mLayerSubscriptions.isEmpty() || !mLayerAndProviderSubscriptions.isEmpty()) {
                prefix = indent + "  ";
                writer.println(prefix + "Subscriptions:");

                prefix = indent + "    ";
                for (VmsLayer layer : mLayerSubscriptions) {
                    writer.println(prefix + layer);
                }
                for (int i = 0; i < mLayerAndProviderSubscriptions.size(); i++) {
                    writer.println(prefix + mLayerAndProviderSubscriptions.keyAt(i) + ": "
                            + mLayerAndProviderSubscriptions.valueAt(i));
                }
            }
        }
    }

    private static <K, V> ArrayMap<K, ArraySet<V>> deepCopy(ArrayMap<K, ArraySet<V>> original) {
        ArrayMap<K, ArraySet<V>> newMap = new ArrayMap<>(original.size());
        for (int i = 0; i < original.size(); i++) {
            newMap.put(original.keyAt(i), new ArraySet<>(original.valueAt(i)));
        }
        return newMap;
    }
}
