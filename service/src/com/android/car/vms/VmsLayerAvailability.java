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

import android.car.builtin.util.Slogf;
import android.car.vms.VmsAssociatedLayer;
import android.car.vms.VmsAvailableLayers;
import android.car.vms.VmsLayer;
import android.car.vms.VmsLayerDependency;
import android.car.vms.VmsLayersOffering;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.car.CarLog;
import com.android.internal.annotations.GuardedBy;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Manages VMS availability for layers.
 *
 * Each VMS publisher sets its layers offering which are a list of layers the publisher claims
 * it might publish. VmsLayersAvailability calculates from all the offering what are the
 * available layers.
 */

class VmsLayerAvailability {
    private static final String TAG = CarLog.tagFor(VmsLayerAvailability.class);
    private static final boolean DBG = Slogf.isLoggable(TAG, Log.DEBUG);

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Map<VmsLayer, Set<Set<VmsLayer>>> mPotentialLayersAndDependencies =
            new ArrayMap<>();
    @GuardedBy("mLock")
    private Set<VmsAssociatedLayer> mAvailableAssociatedLayers = Collections.emptySet();
    @GuardedBy("mLock")
    private Map<VmsLayer, Set<Integer>> mPotentialLayersAndPublishers = new ArrayMap<>();
    @GuardedBy("mLock")
    private int mSeq = 0;

    /**
     * Setting the current layers offerings as reported by publishers.
     */
    void setPublishersOffering(Collection<VmsLayersOffering> publishersLayersOfferings) {
        synchronized (mLock) {
            reset();

            for (VmsLayersOffering offering : publishersLayersOfferings) {
                for (VmsLayerDependency dependency : offering.getDependencies()) {
                    VmsLayer layer = dependency.getLayer();

                    // Associate publishers with layers.
                    mPotentialLayersAndPublishers.computeIfAbsent(layer, k -> new ArraySet<>())
                            .add(offering.getPublisherId());

                    // Add dependencies for availability calculation.
                    mPotentialLayersAndDependencies.computeIfAbsent(layer, k -> new ArraySet<>())
                            .add(dependency.getDependencies());
                }
            }
            calculateLayers();
        }
    }

    /**
     * Returns a collection of all the layers which may be published.
     */
    VmsAvailableLayers getAvailableLayers() {
        synchronized (mLock) {
            return new VmsAvailableLayers(mAvailableAssociatedLayers, mSeq);
        }
    }

    private void reset() {
        synchronized (mLock) {
            mPotentialLayersAndDependencies.clear();
            mPotentialLayersAndPublishers.clear();
            mAvailableAssociatedLayers = Collections.emptySet();
            mSeq += 1;
        }
    }

    private void calculateLayers() {
        synchronized (mLock) {
            ArraySet<VmsLayer> availableLayersSet = new ArraySet<>();
            ArraySet<VmsLayer> cyclicAvoidanceAuxiliarySet = new ArraySet<>();

            for (VmsLayer layer : mPotentialLayersAndDependencies.keySet()) {
                addLayerToAvailabilityCalculationLocked(layer,
                        availableLayersSet,
                        cyclicAvoidanceAuxiliarySet);
            }

            ArraySet<VmsAssociatedLayer> associatedLayerSet = new ArraySet<>();
            for (int index = 0; index < availableLayersSet.size(); index++) {
                VmsLayer availableLayer = availableLayersSet.valueAt(index);
                VmsAssociatedLayer associatedLayer = new VmsAssociatedLayer(availableLayer,
                        mPotentialLayersAndPublishers.get(availableLayer));
                associatedLayerSet.add(associatedLayer);
            }
            mAvailableAssociatedLayers = Collections.unmodifiableSet(associatedLayerSet);
        }
    }

    @GuardedBy("mLock")
    private void addLayerToAvailabilityCalculationLocked(VmsLayer layer,
                                                         Set<VmsLayer> currentAvailableLayers,
                                                         Set<VmsLayer> cyclicAvoidanceSet) {
        if (DBG) {
            Slogf.d(TAG, "addLayerToAvailabilityCalculationLocked: checking layer: " + layer);
        }
        // If we already know that this layer is supported then we are done.
        if (currentAvailableLayers.contains(layer)) {
            return;
        }
        // If there is no offering for this layer we're done.
        if (!mPotentialLayersAndDependencies.containsKey(layer)) {
            return;
        }
        // Avoid cyclic dependency.
        if (cyclicAvoidanceSet.contains(layer)) {
            Slogf.e(TAG, "Detected a cyclic dependency: " + cyclicAvoidanceSet + " -> " + layer);
            return;
        }
        // A layer may have multiple dependency sets. The layer is available if any dependency
        // set is satisfied
        for (Set<VmsLayer> dependencies : mPotentialLayersAndDependencies.get(layer)) {
            // If layer does not have any dependencies then add to supported.
            if (dependencies == null || dependencies.isEmpty()) {
                currentAvailableLayers.add(layer);
                return;
            }
            // Add the layer to cyclic avoidance set
            cyclicAvoidanceSet.add(layer);

            boolean isSupported = true;
            for (VmsLayer dependency : dependencies) {
                addLayerToAvailabilityCalculationLocked(dependency,
                        currentAvailableLayers,
                        cyclicAvoidanceSet);

                if (!currentAvailableLayers.contains(dependency)) {
                    isSupported = false;
                    break;
                }
            }
            cyclicAvoidanceSet.remove(layer);

            if (isSupported) {
                currentAvailableLayers.add(layer);
                return;
            }
        }
    }
}
