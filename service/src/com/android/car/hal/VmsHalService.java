/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.car.hal;

import static com.android.car.CarServiceUtils.toByteArray;
import static java.lang.Integer.toHexString;

import android.car.VehicleAreaType;
import android.car.annotation.FutureFeature;
import android.car.vms.IVmsSubscriberClient;
import android.car.vms.VmsLayer;
import android.car.vms.VmsLayerDependency;
import android.car.vms.VmsLayersOffering;
import android.car.vms.VmsSubscriptionState;
import android.hardware.automotive.vehicle.V2_0.VehiclePropConfig;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;
import android.hardware.automotive.vehicle.V2_1.VehicleProperty;
import android.hardware.automotive.vehicle.V2_1.VmsBaseMessageIntegerValuesIndex;
import android.hardware.automotive.vehicle.V2_1.VmsMessageType;
import android.hardware.automotive.vehicle.V2_1.VmsOfferingMessageIntegerValuesIndex;
import android.hardware.automotive.vehicle.V2_1.VmsSimpleMessageIntegerValuesIndex;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import com.android.car.CarLog;
import com.android.car.VmsLayersAvailability;
import com.android.car.VmsRouting;
import com.android.internal.annotations.GuardedBy;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * This is a glue layer between the VehicleHal and the VmsService. It sends VMS properties back and
 * forth.
 */
@FutureFeature
public class VmsHalService extends HalServiceBase {

    private static final boolean DBG = true;
    private static final int HAL_PROPERTY_ID = VehicleProperty.VEHICLE_MAP_SERVICE;
    private static final String TAG = "VmsHalService";

    private boolean mIsSupported = false;
    private CopyOnWriteArrayList<VmsHalPublisherListener> mPublisherListeners =
        new CopyOnWriteArrayList<>();
    private CopyOnWriteArrayList<VmsHalSubscriberListener> mSubscriberListeners =
        new CopyOnWriteArrayList<>();

    private final IBinder mHalPublisherToken = new Binder();
    private final VehicleHal mVehicleHal;

    private final Object mRoutingLock = new Object();
    private final VmsRouting mRouting = new VmsRouting();
    private final Object mAvailabilityLock = new Object();
    @GuardedBy("mAvailabilityLock")
    private final Map<IBinder, VmsLayersOffering> mOfferings = new HashMap<>();
    @GuardedBy("mAvailabilityLock")
    private final VmsLayersAvailability mAvailableLayers = new VmsLayersAvailability();

    /**
     * The VmsPublisherService implements this interface to receive data from the HAL.
     */
    public interface VmsHalPublisherListener {
        void onChange(VmsSubscriptionState subscriptionState);
    }

    /**
     * The VmsSubscriberService implements this interface to receive data from the HAL.
     */
    public interface VmsHalSubscriberListener {
        // Notify listener on a data Message.
        void onDataMessage(VmsLayer layer, byte[] payload);

        // Notify listener on a change in available layers.
        void onLayersAvaiabilityChange(List<VmsLayer> availableLayers);
    }

    /**
     * The VmsService implements this interface to receive data from the HAL.
     */
    protected VmsHalService(VehicleHal vehicleHal) {
        mVehicleHal = vehicleHal;
        if (DBG) {
            Log.d(TAG, "started VmsHalService!");
        }
    }

    public void addPublisherListener(VmsHalPublisherListener listener) {
        mPublisherListeners.add(listener);
    }

    public void addSubscriberListener(VmsHalSubscriberListener listener) {
        mSubscriberListeners.add(listener);
    }

    public void removePublisherListener(VmsHalPublisherListener listener) {
        mPublisherListeners.remove(listener);
    }

    public void removeSubscriberListener(VmsHalSubscriberListener listener) {
        mSubscriberListeners.remove(listener);
    }

    public void addSubscription(IVmsSubscriberClient listener, VmsLayer layer) {
        boolean firstSubscriptionForLayer = false;
        synchronized (mRoutingLock) {
            // Check if publishers need to be notified about this change in subscriptions.
            firstSubscriptionForLayer = !mRouting.hasLayerSubscriptions(layer);

            // Add the listeners subscription to the layer
            mRouting.addSubscription(listener, layer);
        }
        if (firstSubscriptionForLayer) {
            notifyPublishers(layer, true);
        }
    }

    public void removeSubscription(IVmsSubscriberClient listener, VmsLayer layer) {
        boolean layerHasSubscribers = true;
        synchronized (mRoutingLock) {
            if (!mRouting.hasLayerSubscriptions(layer)) {
                Log.i(TAG, "Trying to remove a layer with no subscription: " + layer);
                return;
            }

            // Remove the listeners subscription to the layer
            mRouting.removeSubscription(listener, layer);

            // Check if publishers need to be notified about this change in subscriptions.
            layerHasSubscribers = mRouting.hasLayerSubscriptions(layer);
        }
        if (!layerHasSubscribers) {
            notifyPublishers(layer, false);
        }
    }

    public void addSubscription(IVmsSubscriberClient listener) {
        synchronized (mRoutingLock) {
            mRouting.addSubscription(listener);
        }
    }

    public void removeSubscription(IVmsSubscriberClient listener) {
        synchronized (mRoutingLock) {
            mRouting.removeSubscription(listener);
        }
    }

    public void removeDeadListener(IVmsSubscriberClient listener) {
        synchronized (mRoutingLock) {
            mRouting.removeDeadListener(listener);
        }
    }

    public Set<IVmsSubscriberClient> getListeners(VmsLayer layer) {
        synchronized (mRoutingLock) {
            return mRouting.getListeners(layer);
        }
    }

    public Set<IVmsSubscriberClient> getAllListeners() {
        synchronized (mRoutingLock) {
            return mRouting.getAllListeners();
        }
    }

    public boolean isHalSubscribed(VmsLayer layer) {
        synchronized (mRoutingLock) {
            return mRouting.isHalSubscribed(layer);
        }
    }

    public VmsSubscriptionState getSubscriptionState() {
        synchronized (mRoutingLock) {
            return mRouting.getSubscriptionState();
        }
    }

    public void addHalSubscription(VmsLayer layer) {
        boolean firstSubscriptionForLayer = true;
        synchronized (mRoutingLock) {
            // Check if publishers need to be notified about this change in subscriptions.
            firstSubscriptionForLayer = !mRouting.hasLayerSubscriptions(layer);

            // Add the listeners subscription to the layer
            mRouting.addHalSubscription(layer);
        }
        if (firstSubscriptionForLayer) {
            notifyPublishers(layer, true);
        }
    }

    public void removeHalSubscription(VmsLayer layer) {
        boolean layerHasSubscribers = true;
        synchronized (mRoutingLock) {
            if (!mRouting.hasLayerSubscriptions(layer)) {
                Log.i(TAG, "Trying to remove a layer with no subscription: " + layer);
                return;
            }

            // Remove the listeners subscription to the layer
            mRouting.removeHalSubscription(layer);

            // Check if publishers need to be notified about this change in subscriptions.
            layerHasSubscribers = mRouting.hasLayerSubscriptions(layer);
        }
        if (!layerHasSubscribers) {
            notifyPublishers(layer, false);
        }
    }

    public boolean containsListener(IVmsSubscriberClient listener) {
        synchronized (mRoutingLock) {
            return mRouting.containsListener(listener);
        }
    }

    public void setPublisherLayersOffering(IBinder publisherToken, VmsLayersOffering offering){
        Set<VmsLayer> availableLayers = Collections.EMPTY_SET;
        synchronized (mAvailabilityLock) {
            updateOffering(publisherToken, offering);
            availableLayers = mAvailableLayers.getAvailableLayers();
        }
        notifySubscribers(availableLayers);
    }

    public Set<VmsLayer> getAvailableLayers() {
        //TODO(b/36872877): wrap available layers in VmsAvailabilityState similar to VmsSubscriptionState.
        synchronized (mAvailabilityLock) {
            return mAvailableLayers.getAvailableLayers();
        }
    }

    /**
     * Notify all the publishers and the HAL on subscription changes regardless of who triggered
     * the change.
     *
     * @param layer          layer which is being subscribed to or unsubscribed from.
     * @param hasSubscribers indicates if the notification is for subscription or unsubscription.
     */
    private void notifyPublishers(VmsLayer layer, boolean hasSubscribers) {
        // notify the HAL
        setSubscriptionRequest(layer, hasSubscribers);

        // Notify the App publishers
        for (VmsHalPublisherListener listener : mPublisherListeners) {
            // Besides the list of layers, also a timestamp is provided to the clients.
            // They should ignore any notification with a timestamp that is older than the most
            // recent timestamp they have seen.
            listener.onChange(getSubscriptionState());
        }
    }

    /**
     * Notify all the subscribers and the HAL on layers availability change.
     *
     * @param availableLayers the layers which publishers claim they made publish.
     */
    private void notifySubscribers(Set<VmsLayer> availableLayers) {
        // notify the HAL
        setAvailableLayers(availableLayers);

        // Notify the App subscribers
        for (VmsHalSubscriberListener listener : mSubscriberListeners) {
            listener.onLayersAvaiabilityChange(new ArrayList<>(availableLayers));
        }
    }

    @Override
    public void init() {
        if (DBG) {
            Log.d(TAG, "init()");
        }
        if (mIsSupported) {
            mVehicleHal.subscribeProperty(this, HAL_PROPERTY_ID);
        }
    }

    @Override
    public void release() {
        if (DBG) {
            Log.d(TAG, "release()");
        }
        if (mIsSupported) {
            mVehicleHal.unsubscribeProperty(this, HAL_PROPERTY_ID);
        }
        mPublisherListeners.clear();
        mSubscriberListeners.clear();
    }

    @Override
    public Collection<VehiclePropConfig> takeSupportedProperties(
            Collection<VehiclePropConfig> allProperties) {
        List<VehiclePropConfig> taken = new LinkedList<>();
        for (VehiclePropConfig p : allProperties) {
            if (p.prop == HAL_PROPERTY_ID) {
                taken.add(p);
                mIsSupported = true;
                if (DBG) {
                    Log.d(TAG, "takeSupportedProperties: " + toHexString(p.prop));
                }
                break;
            }
        }
        return taken;
    }

    /**
     * Consumes/produces HAL messages. The format of these messages is defined in:
     * hardware/interfaces/automotive/vehicle/2.1/types.hal
     */
    @Override
    public void handleHalEvents(List<VehiclePropValue> values) {
        if (DBG) {
            Log.d(TAG, "Handling a VMS property change");
        }
        for (VehiclePropValue v : values) {
            ArrayList<Integer> vec = v.value.int32Values;
            int messageType = vec.get(VmsBaseMessageIntegerValuesIndex.VMS_MESSAGE_TYPE);

            if (DBG) {
                Log.d(TAG, "Handling VMS message type: " + messageType);
            }
            switch(messageType) {
                case VmsMessageType.DATA:
                    handleDataEvent(vec, toByteArray(v.value.bytes));
                    break;
                case VmsMessageType.SUBSCRIBE:
                    handleSubscribeEvent(vec);
                    break;
                case VmsMessageType.UNSUBSCRIBE:
                    handleUnsubscribeEvent(vec);
                    break;
                case VmsMessageType.OFFERING:
                    handleOfferingEvent(vec);
                    break;
                case VmsMessageType.AVAILABILITY_REQUEST:
                    handleAvailabilityEvent();
                    break;
                case VmsMessageType.SUBSCRIPTION_REQUEST:
                    handleSubscriptionRequestEvent();
                    break;
                default:
                    throw new IllegalArgumentException("Unexpected message type: " + messageType);
            }
        }
    }

    /**
     * Data message format:
     * <ul>
     * <li>Message type.
     * <li>Layer id.
     * <li>Layer version.
     * <li>Payload.
     * </ul>
     */
    private void handleDataEvent(List<Integer> integerValues, byte[] payload) {
        int layerId = integerValues.get(VmsSimpleMessageIntegerValuesIndex.VMS_LAYER_ID);
        int layerVersion = integerValues.get(VmsSimpleMessageIntegerValuesIndex.VMS_LAYER_VERSION);
        if (DBG) {
            Log.d(TAG,
                "Handling a data event for Layer Id: " + layerId +
                    " Version: " + layerVersion);
        }

        // Send the message.
        for (VmsHalSubscriberListener listener : mSubscriberListeners) {
            listener.onDataMessage(new VmsLayer(layerId, layerVersion), payload);
        }
    }

    /**
     * Subscribe message format:
     * <ul>
     * <li>Message type.
     * <li>Layer id.
     * <li>Layer version.
     * </ul>
     */
    private void handleSubscribeEvent(List<Integer> integerValues) {
        int layerId = integerValues.get(VmsSimpleMessageIntegerValuesIndex.VMS_LAYER_ID);
        int layerVersion = integerValues.get(VmsSimpleMessageIntegerValuesIndex.VMS_LAYER_VERSION);
        if (DBG) {
            Log.d(TAG,
                "Handling a subscribe event for Layer Id: " + layerId +
                    " Version: " + layerVersion);
        }
        addHalSubscription(new VmsLayer(layerId, layerVersion));
    }

    /**
     * Unsubscribe message format:
     * <ul>
     * <li>Message type.
     * <li>Layer id.
     * <li>Layer version.
     * </ul>
     */
    private void handleUnsubscribeEvent(List<Integer> integerValues) {
        int layerId = integerValues.get(VmsSimpleMessageIntegerValuesIndex.VMS_LAYER_ID);
        int layerVersion = integerValues.get(VmsSimpleMessageIntegerValuesIndex.VMS_LAYER_VERSION);
        if (DBG) {
            Log.d(TAG,
                "Handling an unsubscribe event for Layer Id: " + layerId +
                    " Version: " + layerVersion);
        }
        removeHalSubscription(new VmsLayer(layerId, layerVersion));
    }

    /**
     * Offering message format:
     * <ul>
     * <li>Message type.
     * <li>Number of offerings.
     * <li>Each offering consists of:
     *   <ul>
     *   <li>Layer id.
     *   <li>Layer version.
     *   <li>Number of layer dependencies.
     *   <li>Layer type/version pairs.
     *   </ul>
     * </ul>
     */
    private void handleOfferingEvent(List<Integer> integerValues) {
        int numLayersDependencies =
            integerValues.get(VmsOfferingMessageIntegerValuesIndex.VMS_NUMBER_OF_LAYERS_DEPENDENCIES);
        int idx = VmsOfferingMessageIntegerValuesIndex.FIRST_DEPENDENCIES_INDEX;

        List<VmsLayerDependency> offeredLayers = new ArrayList<>();

        // An offering is layerId, LayerVersion, NumDeps, <LayerId, LayerVersion> X NumDeps.
        for (int i = 0; i < numLayersDependencies; i++) {
            int layerId = integerValues.get(idx++);
            int layerVersion = integerValues.get(idx++);
            VmsLayer offeredLayer = new VmsLayer(layerId, layerVersion);

            int numDependenciesForLayer = integerValues.get(idx++);
            if (numDependenciesForLayer == 0) {
                offeredLayers.add(new VmsLayerDependency(offeredLayer));
            } else {
                Set<VmsLayer> dependencies = new HashSet<>();

                for (int j = 0; j < numDependenciesForLayer; j++) {
                    int dependantLayerId = integerValues.get(idx++);
                    int dependantLayerVersion = integerValues.get(idx++);

                    VmsLayer dependantLayer = new VmsLayer(dependantLayerId, dependantLayerVersion);
                    dependencies.add(dependantLayer);
                }
                offeredLayers.add(new VmsLayerDependency(offeredLayer, dependencies));
            }
        }
        // Store the HAL offering.
        VmsLayersOffering offering = new VmsLayersOffering(offeredLayers);
        synchronized (mAvailabilityLock) {
            updateOffering(mHalPublisherToken, offering);
        }
    }

    /**
     * Availability message format:
     * <ul>
     * <li>Message type.
     * <li>Number of layers.
     * <li>Layer type/version pairs.
     * </ul>
     */
    private void handleAvailabilityEvent() {
        synchronized (mAvailabilityLock) {
            Collection<VmsLayer> availableLayers = mAvailableLayers.getAvailableLayers();
            VehiclePropValue vehiclePropertyValue = toVehiclePropValue(
                VmsMessageType.AVAILABILITY_RESPONSE, availableLayers);
            setPropertyValue(vehiclePropertyValue);
        }
    }

    /**
     * VmsSubscriptionRequestFormat:
     * <ul>
     * <li>Message type.
     * </ul>
     *
     * VmsSubscriptionResponseFormat:
     * <ul>
     * <li>Message type.
     * <li>Sequence number.
     * <li>Number of layers.
     * <li>Layer type/version pairs.
     * </ul>
     */
    private void handleSubscriptionRequestEvent() {
        VmsSubscriptionState subscription = getSubscriptionState();
        VehiclePropValue vehicleProp = toVehiclePropValue(VmsMessageType.SUBSCRIPTION_RESPONSE);
        VehiclePropValue.RawValue v = vehicleProp.value;
        v.int32Values.add(subscription.getSequenceNumber());
        List<VmsLayer> layers = subscription.getLayers();
        v.int32Values.add(layers.size());
        for (VmsLayer layer : layers) {
            v.int32Values.add(layer.getId());
            v.int32Values.add(layer.getVersion());
        }
        setPropertyValue(vehicleProp);
    }

    private void updateOffering(IBinder publisherToken, VmsLayersOffering offering) {
        Set<VmsLayer> availableLayers = Collections.EMPTY_SET;
        synchronized (mAvailabilityLock) {
            mOfferings.put(publisherToken, offering);

            // Update layers availability.
            mAvailableLayers.setPublishersOffering(mOfferings.values());

            availableLayers = mAvailableLayers.getAvailableLayers();
        }
        notifySubscribers(availableLayers);
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println(TAG);
        writer.println("VmsProperty " + (mIsSupported ? "" : "not") + " supported.");
    }

    /**
     * Updates the VMS HAL property with the given value.
     *
     * @param layer          layer data to update the hal property.
     * @param hasSubscribers if it is a subscribe or unsubscribe message.
     * @return true if the call to the HAL to update the property was successful.
     */
    public boolean setSubscriptionRequest(VmsLayer layer, boolean hasSubscribers) {
        VehiclePropValue vehiclePropertyValue = toVehiclePropValue(
            hasSubscribers ? VmsMessageType.SUBSCRIBE : VmsMessageType.UNSUBSCRIBE, layer);
        return setPropertyValue(vehiclePropertyValue);
    }

    public boolean setDataMessage(VmsLayer layer, byte[] payload) {
        VehiclePropValue vehiclePropertyValue = toVehiclePropValue(VmsMessageType.DATA,
            layer,
            payload);
        return setPropertyValue(vehiclePropertyValue);
    }

    public boolean setAvailableLayers(Collection<VmsLayer> availableLayers) {
        VehiclePropValue vehiclePropertyValue =
                toVehiclePropValue(VmsMessageType.AVAILABILITY_RESPONSE,
            availableLayers);

        return setPropertyValue(vehiclePropertyValue);
    }

    public boolean setPropertyValue(VehiclePropValue vehiclePropertyValue) {
        try {
            mVehicleHal.set(vehiclePropertyValue);
            return true;
        } catch (PropertyTimeoutException e) {
            Log.e(CarLog.TAG_PROPERTY, "set, property not ready 0x" + toHexString(HAL_PROPERTY_ID));
        }
        return false;
    }

    /** Creates a {@link VehiclePropValue} */
    private static VehiclePropValue toVehiclePropValue(int messageType) {
        VehiclePropValue vehicleProp = new VehiclePropValue();
        vehicleProp.prop = HAL_PROPERTY_ID;
        vehicleProp.areaId = VehicleAreaType.VEHICLE_AREA_TYPE_NONE;
        VehiclePropValue.RawValue v = vehicleProp.value;

        v.int32Values.add(messageType);
        return vehicleProp;
    }

    /** Creates a {@link VehiclePropValue} */
    private static VehiclePropValue toVehiclePropValue(int messageType, VmsLayer layer) {
        VehiclePropValue vehicleProp = toVehiclePropValue(messageType);
        VehiclePropValue.RawValue v = vehicleProp.value;
        v.int32Values.add(layer.getId());
        v.int32Values.add(layer.getVersion());
        return vehicleProp;
    }

    /** Creates a {@link VehiclePropValue} with payload */
    private static VehiclePropValue toVehiclePropValue(int messageType,
        VmsLayer layer,
        byte[] payload) {
        VehiclePropValue vehicleProp = toVehiclePropValue(messageType, layer);
        VehiclePropValue.RawValue v = vehicleProp.value;
        v.bytes.ensureCapacity(payload.length);
        for (byte b : payload) {
            v.bytes.add(b);
        }
        return vehicleProp;
    }

    /** Creates a {@link VehiclePropValue} with payload */
    private static VehiclePropValue toVehiclePropValue(int messageType,
        Collection<VmsLayer> layers) {
        VehiclePropValue vehicleProp = toVehiclePropValue(messageType);
        VehiclePropValue.RawValue v = vehicleProp.value;
        int numLayers = layers.size();
        v.int32Values.add(numLayers);
        for (VmsLayer layer : layers) {
            v.int32Values.add(layer.getId());
            v.int32Values.add(layer.getVersion());
        }
        return vehicleProp;
    }
}
