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

package com.android.car.internal.property;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;
import static com.android.car.internal.property.CarPropertyHelper.propertyIdsToString;
import static com.android.car.internal.util.ArrayUtils.convertToIntArray;

import android.annotation.Nullable;
import android.car.VehiclePropertyIds;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.car.internal.util.PairSparseArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * This class manages [{propertyId, areaId} -> RateInfoForClients] map and maintains two states:
 * a current state and a staged state. The staged state represents the proposed changes. After
 * the changes are applied to the lower layer, caller either uses {@link #commit} to replace
 * the curren state with the staged state, or uses {@link #dropCommit} to drop the staged state.
 *
 * A common pattern is
 *
 * ```
 * synchronized (mLock) {
 *   mSubscriptionManager.stageNewOptions(...);
 *
 *   mSubscriptionManager.diffBetweenCurrentAndStage(...);
 *   try {
 *     // Apply the diff.
 *   } catch (Exception e) {
 *     mSubscriptionManager.dropCommit();
 *     throw e;
 *   }
 *   mSubscriptionManager.commit();
 * }
 * ```
 *
 * This class is not thread-safe.
 *
 * @param <ClientType> A class representing a client.
 *
 * @hide
 */
public final class SubscriptionManager<ClientType> {
    private static final String TAG = SubscriptionManager.class.getSimpleName();
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    /**
     * This class provides an abstraction for all the clients and their subscribed rate for a
     * specific {propertyId, areaId} pair.
     */
    private static final class RateInfoForClients<ClientType> {
        private final ArrayMap<ClientType, Float> mUpdateRateHzByClient;

        RateInfoForClients() {
            mUpdateRateHzByClient = new ArrayMap<>();
        }

        RateInfoForClients(RateInfoForClients other) {
            mUpdateRateHzByClient = new ArrayMap<>(other.mUpdateRateHzByClient);
        }

        /**
         * Gets the max update rate for this {propertyId, areaId}.
         */
        float getMaxUpdateRateHz() {
            float maxUpdateRateHz = 0;
            for (int i = 0; i < mUpdateRateHzByClient.size(); i++) {
                if (mUpdateRateHzByClient.valueAt(i) > maxUpdateRateHz) {
                    maxUpdateRateHz = mUpdateRateHzByClient.valueAt(i);
                }
            }
            return maxUpdateRateHz;
        }

        Set<ClientType> getClients() {
            return mUpdateRateHzByClient.keySet();
        }

        float getUpdateRateHz(ClientType client) {
            return mUpdateRateHzByClient.get(client);
        }

        /**
         * Adds a new client for this {propertyId, areaId}.
         */
        void add(ClientType client, float updateRateHz) {
            mUpdateRateHzByClient.put(client, updateRateHz);
        }

        void remove(ClientType client) {
            mUpdateRateHzByClient.remove(client);
        }

        boolean isEmpty() {
            return mUpdateRateHzByClient.isEmpty();
        }
    }

    PairSparseArray<RateInfoForClients<ClientType>> mCurrentUpdateRateHzByClientByPropIdAreaId =
            new PairSparseArray<>();
    PairSparseArray<RateInfoForClients<ClientType>> mStagedUpdateRateHzByClientByPropIdAreaId =
            new PairSparseArray<>();
    boolean mHasStagedChanges;

    /**
     * Prepares new subscriptions.
     *
     * This apply the new subscribe options in the staging area without actually committing them.
     * Client should call {@link #diffBetweenCurrentAndStage} to get the difference between current
     * and the staging state. Apply them to the lower layer, and either commit the change after
     * the operation succeeds or drop the change after the operation failed.
     */
    public void stageNewOptions(ClientType client, List<CarSubscribeOption> options) {
        if (DBG) {
            Log.d(TAG, "stageNewOptions: options: " + options);
        }

        cloneCurrentToStage();
        mHasStagedChanges = true;

        for (int i = 0; i < options.size(); i++) {
            CarSubscribeOption option = options.get(i);
            int propertyId = option.propertyId;
            for (int areaId : option.areaIds) {
                if (mStagedUpdateRateHzByClientByPropIdAreaId.get(propertyId, areaId) == null) {
                    mStagedUpdateRateHzByClientByPropIdAreaId.put(propertyId, areaId,
                            new RateInfoForClients<>());
                }
                mStagedUpdateRateHzByClientByPropIdAreaId.get(propertyId, areaId).add(
                        client, option.updateRateHz);
            }
        }
    }

    /**
     * Prepares unregistration for list of property IDs.
     *
     * This apply the unregistration in the staging area without actually committing them.
     */
    public void stageUnregister(ClientType client, ArraySet<Integer> propertyIdsToUnregister) {
        if (DBG) {
            Log.d(TAG, "stageUnregister: propertyIdsToUnregister: " + propertyIdsToString(
                    propertyIdsToUnregister));
        }

        cloneCurrentToStage();
        mHasStagedChanges = true;

        // TODO (b/302574617): Optimize this once PairSparseArray supports getting all areaIds for
        // one property Id.
        List<int[]> propIdAreaIdsToUnsubscribe = new ArrayList<>();
        for (int i = 0; i < mStagedUpdateRateHzByClientByPropIdAreaId.size(); i++) {
            int[] propIdAreaId = mStagedUpdateRateHzByClientByPropIdAreaId.keyPairAt(i);
            int propertyId = propIdAreaId[0];
            if (!propertyIdsToUnregister.contains(propertyId)) {
                continue;
            }
            int areaId = propIdAreaId[1];
            RateInfoForClients<ClientType> rateInfoForClients =
                    mStagedUpdateRateHzByClientByPropIdAreaId.get(propertyId, areaId);
            if (rateInfoForClients == null) {
                Log.e(TAG, "The property: " + VehiclePropertyIds.toString(propertyId)
                        + ", area ID: " + areaId + " was not registered, do nothing");
                continue;
            }
            rateInfoForClients.remove(client);
            if (rateInfoForClients.isEmpty()) {
                propIdAreaIdsToUnsubscribe.add(new int[]{propertyId, areaId});
            }
        }

        for (int i = 0; i < propIdAreaIdsToUnsubscribe.size(); i++) {
            int[] propIdAreaIdToUnsubscribe = propIdAreaIdsToUnsubscribe.get(i);
            mStagedUpdateRateHzByClientByPropIdAreaId.remove(propIdAreaIdToUnsubscribe[0],
                    propIdAreaIdToUnsubscribe[1]);
        }
    }

    /**
     * Commit the staged changes.
     *
     * This will replace the current state with the staged state. This should be called after the
     * changes are applied successfully to the lower layer.
     */
    public void commit() {
        if (!mHasStagedChanges) {
            Log.w(TAG, "No changes has been staged, nothing to commit");
            return;
        }
        // Drop the current state.
        mCurrentUpdateRateHzByClientByPropIdAreaId = mStagedUpdateRateHzByClientByPropIdAreaId;
        mHasStagedChanges = false;
    }

    /**
     * Drop the staged changes.
     *
     * This should be called after the changes failed to apply to the lower layer.
     */
    public void dropCommit() {
        if (!mHasStagedChanges) {
            Log.w(TAG, "No changes has been staged, nothing to drop");
            return;
        }
        // Drop the staged state.
        mStagedUpdateRateHzByClientByPropIdAreaId = mCurrentUpdateRateHzByClientByPropIdAreaId;
        mHasStagedChanges = false;
    }

    /**
     * Clear both the current state and staged state.
     */
    public void clear() {
        mStagedUpdateRateHzByClientByPropIdAreaId.clear();
        mCurrentUpdateRateHzByClientByPropIdAreaId.clear();
        mHasStagedChanges = false;
    }

    /**
     * Gets all the subscription clients for the given propertyID, area ID pair.
     *
     * This uses the current state.
     */
    public @Nullable Set<ClientType> getClients(int propertyId, int areaId) {
        if (!mCurrentUpdateRateHzByClientByPropIdAreaId.contains(propertyId, areaId)) {
            return null;
        }
        return mCurrentUpdateRateHzByClientByPropIdAreaId.get(propertyId, areaId).getClients();
    }

    /**
     * Dumps the state.
     */
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(IndentingPrintWriter writer) {
        writer.println("Current subscription states:");
        dumpStates(writer, mCurrentUpdateRateHzByClientByPropIdAreaId);
        writer.println("Staged subscription states:");
        dumpStates(writer, mStagedUpdateRateHzByClientByPropIdAreaId);
    }

    /**
     * Calculates the difference between the staged state and current state.
     *
     * @param outDiffSubscribeOptions The output subscribe options that has changed. This includes
     *      both new subscriptions and updated subscriptions with a new update rate.
     * @param outPropertyIdsToUnsubscribe The output property IDs that need to be unsubscribed.
     */
    public void diffBetweenCurrentAndStage(List<CarSubscribeOption> outDiffSubscribeOptions,
            List<Integer> outPropertyIdsToUnsubscribe) {
        if (!mHasStagedChanges) {
            Log.w(TAG, "No changes has been staged, no diff");
            return;
        }
        // TODO(b/302574617): Optimize this, loop only through the updated propId, area Ids.
        ArraySet<int[]> combinedPropIdAreaIds = new ArraySet<>();
        for (int i = 0; i < mCurrentUpdateRateHzByClientByPropIdAreaId.size(); i++) {
            combinedPropIdAreaIds.add(mCurrentUpdateRateHzByClientByPropIdAreaId.keyPairAt(i));
        }
        for (int i = 0; i < mStagedUpdateRateHzByClientByPropIdAreaId.size(); i++) {
            combinedPropIdAreaIds.add(mStagedUpdateRateHzByClientByPropIdAreaId.keyPairAt(i));
        }
        ArraySet<Integer> possiblePropIdsToUnsubscribe = new ArraySet<>();
        SparseArray<SparseArray<Float>> diffUpdateRateHzByAreaIdByPropId = new SparseArray<>();
        for (int i = 0; i < combinedPropIdAreaIds.size(); i++) {
            int[] propIdAreaId = combinedPropIdAreaIds.valueAt(i);
            int propertyId = propIdAreaId[0];
            int areaId = propIdAreaId[1];

            if (!mStagedUpdateRateHzByClientByPropIdAreaId.contains(propertyId, areaId)) {
                // The [PropertyId, areaId] is no longer subscribed.
                if (DBG) {
                    Log.d(TAG, String.format("The property: %s, areaId: %d is no longer subscribed",
                            VehiclePropertyIds.toString(propertyId), areaId));
                }
                possiblePropIdsToUnsubscribe.add(propertyId);
                continue;
            }

            float newMaxUpdateRate = mStagedUpdateRateHzByClientByPropIdAreaId
                    .get(propertyId, areaId).getMaxUpdateRateHz();
            if (!mCurrentUpdateRateHzByClientByPropIdAreaId.contains(propertyId, areaId)) {
                if (DBG) {
                    Log.d(TAG, String.format(
                            "The property: %s, areaId: %d is newly subscribed at rate: %f hz",
                            VehiclePropertyIds.toString(propertyId), areaId, newMaxUpdateRate));
                }
                storeNewUpdateRateHz(propertyId, areaId, newMaxUpdateRate,
                        diffUpdateRateHzByAreaIdByPropId);
                continue;
            }
            if (mCurrentUpdateRateHzByClientByPropIdAreaId.get(propertyId, areaId)
                    .getMaxUpdateRateHz() != newMaxUpdateRate) {
                if (DBG) {
                    Log.d(TAG, String.format(
                            "The property: %s, areaId: %d subscribes at new rate: %f hz",
                            VehiclePropertyIds.toString(propertyId), areaId, newMaxUpdateRate));
                }
                storeNewUpdateRateHz(propertyId, areaId, newMaxUpdateRate,
                        diffUpdateRateHzByAreaIdByPropId);
                continue;
            }
        }
        for (int i = 0; i < diffUpdateRateHzByAreaIdByPropId.size(); i++) {
            int propertyId = diffUpdateRateHzByAreaIdByPropId.keyAt(i);
            outDiffSubscribeOptions.addAll(getCarSubscribeOptionForProperty(
                    propertyId, diffUpdateRateHzByAreaIdByPropId.valueAt(i)));
        }
        // TODO(b/302574617): Optimize this once PairSparseArray supports getting all area Ids for
        // one property Id.
        ArraySet<Integer> subscribedPropIdsInStage = new ArraySet<>();
        for (int i = 0; i < mStagedUpdateRateHzByClientByPropIdAreaId.size(); i++) {
            subscribedPropIdsInStage.add(
                    (mStagedUpdateRateHzByClientByPropIdAreaId.keyPairAt(i))[0]);
        }
        for (int i = 0; i < possiblePropIdsToUnsubscribe.size(); i++) {
            int possiblePropIdToUnsubscribe = possiblePropIdsToUnsubscribe.valueAt(i);
            if (!subscribedPropIdsInStage.contains(possiblePropIdToUnsubscribe)) {
                // We should only unsubscribe the property if all area IDs are unsubscribed.
                if (DBG) {
                    Log.d(TAG, String.format(
                            "All areas for the property: %s are no longer subscribed, "
                            + "unsubscribe it", VehiclePropertyIds.toString(
                                    possiblePropIdToUnsubscribe)));
                }
                outPropertyIdsToUnsubscribe.add(possiblePropIdToUnsubscribe);
            }
        }
    }

    private static void storeNewUpdateRateHz(int propertyId, int areaId, float updateRateHz,
            SparseArray<SparseArray<Float>> updateRateHzByAreaIdByPropId) {
        if (!updateRateHzByAreaIdByPropId.contains(propertyId)) {
            updateRateHzByAreaIdByPropId.put(propertyId, new SparseArray<>());
        }
        updateRateHzByAreaIdByPropId.get(propertyId).put(areaId, updateRateHz);
    }

    /**
     * Generates the {@code CarSubscribeOption} instances for the specific property.
     *
     * Converts [areaId -> updateRateHz] map to [updateRateHz -> list of areaIds] and then generates
     * subscribe option for each updateRateHz.
     *
     * @param propertyId The property Id.
     * @param updateRateHzByAreaId The update rate hz by each area that needs to be updated.
     */
    private static List<CarSubscribeOption> getCarSubscribeOptionForProperty(
            int propertyId, SparseArray<Float> updateRateHzByAreaId) {
        // Group the areaIds by update rate.
        List<CarSubscribeOption> carSubscribeOptions = new ArrayList<>();
        ArrayMap<Float, List<Integer>> areaIdsByUpdateRateHz = new ArrayMap<>();
        for (int i = 0; i < updateRateHzByAreaId.size(); i++) {
            int areaId = updateRateHzByAreaId.keyAt(i);
            float updateRateHz = updateRateHzByAreaId.valueAt(i);
            if (!areaIdsByUpdateRateHz.containsKey(updateRateHz)) {
                areaIdsByUpdateRateHz.put(updateRateHz, new ArrayList<>());
            }
            areaIdsByUpdateRateHz.get(updateRateHz).add(areaId);
        }

        // Convert each update rate to a new CarSubscribeOption.
        for (int i = 0; i < areaIdsByUpdateRateHz.size(); i++) {
            CarSubscribeOption option = new CarSubscribeOption();
            option.propertyId = propertyId;
            option.areaIds = convertToIntArray(areaIdsByUpdateRateHz.valueAt(i));
            option.updateRateHz = areaIdsByUpdateRateHz.keyAt(i);
            carSubscribeOptions.add(option);
        }

        return carSubscribeOptions;
    }

    private void cloneCurrentToStage() {
        if (mHasStagedChanges) {
            throw new IllegalStateException(
                    "Must either commit or dropCommit before staging new changes");
        }

        mStagedUpdateRateHzByClientByPropIdAreaId = new PairSparseArray<>();
        for (int i = 0; i < mCurrentUpdateRateHzByClientByPropIdAreaId.size(); i++) {
            int[] keyPair = mCurrentUpdateRateHzByClientByPropIdAreaId.keyPairAt(i);
            mStagedUpdateRateHzByClientByPropIdAreaId.put(keyPair[0], keyPair[1],
                    new RateInfoForClients<>(
                            mCurrentUpdateRateHzByClientByPropIdAreaId.valueAt(i)));
        }
    }

    private static <ClientType> void dumpStates(IndentingPrintWriter writer,
            PairSparseArray<RateInfoForClients<ClientType>> states) {
        for (int i = 0; i < states.size(); i++) {
            int[] propIdAreaId = states.keyPairAt(i);
            RateInfoForClients<ClientType> rateInfoForClients = states.valueAt(i);
            int propertyId = propIdAreaId[0];
            int areaId = propIdAreaId[1];
            Set<ClientType> clients = states.get(propertyId, areaId).getClients();
            writer.println("property: " + VehiclePropertyIds.toString(propertyId)
                    + ", area ID: " + areaId + " is registered by " + clients.size()
                    + " client(s).");
            writer.increaseIndent();
            for (ClientType client : clients) {
                writer.println("Client " + client + ": Subscribed at "
                        + rateInfoForClients.getUpdateRateHz(client) + " hz");
            }
            writer.decreaseIndent();
        }
    }
}
