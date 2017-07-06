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

package com.android.car;

import android.car.annotation.FutureFeature;
import android.car.vms.IVmsSubscriberClient;
import android.car.vms.VmsAssociatedLayer;
import android.car.vms.VmsLayer;
import android.car.vms.VmsOperationRecorder;
import android.car.vms.VmsSubscriptionState;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manages all the VMS subscriptions:
 * + Subscriptions to data messages of individual layer + version.
 * + Subscriptions to all data messages.
 * + HAL subscriptions to layer + version.
 */
@FutureFeature
public class VmsRouting {
    private final Object mLock = new Object();
    // A map of Layer + Version to listeners.
    @GuardedBy("mLock")
    private Map<VmsLayer, Set<IVmsSubscriberClient>> mLayerSubscriptions = new HashMap<>();

    @GuardedBy("mLock")
    private Map<VmsLayer, Map<Integer, Set<IVmsSubscriberClient>>> mLayerSubscriptionsToPublishers =
            new HashMap<>();
    // A set of listeners that are interested in any layer + version.
    @GuardedBy("mLock")
    private Set<IVmsSubscriberClient> mPromiscuousSubscribers = new HashSet<>();

    // A set of all the layers + versions the HAL is subscribed to.
    @GuardedBy("mLock")
    private Set<VmsLayer> mHalSubscriptions = new HashSet<>();

    @GuardedBy("mLock")
    private Map<VmsLayer, Set<Integer>> mHalSubscriptionsToPublishers = new HashMap<>();
    // A sequence number that is increased every time the subscription state is modified. Note that
    // modifying the list of promiscuous subscribers does not affect the subscription state.
    @GuardedBy("mLock")
    private int mSequenceNumber = 0;

    /**
     * Add a listener subscription to data messages from a VMS layer.
     *
     * @param listener a VMS subscriber.
     * @param layer    the layer subscribing to.
     */
    public void addSubscription(IVmsSubscriberClient listener, VmsLayer layer) {
        //TODO(b/36902947): revise if need to sync, and return value.
        synchronized (mLock) {
            ++mSequenceNumber;
            // Get or create the list of listeners for layer and version.
            Set<IVmsSubscriberClient> listeners = mLayerSubscriptions.get(layer);

            if (listeners == null) {
                listeners = new HashSet<>();
                mLayerSubscriptions.put(layer, listeners);
            }
            // Add the listener to the list.
            listeners.add(listener);
            VmsOperationRecorder.get().addSubscription(mSequenceNumber, layer);
        }
    }

    /**
     * Add a listener subscription to all data messages.
     *
     * @param listener a VMS subscriber.
     */
    public void addSubscription(IVmsSubscriberClient listener) {
        synchronized (mLock) {
            ++mSequenceNumber;
            mPromiscuousSubscribers.add(listener);
            VmsOperationRecorder.get().addPromiscuousSubscription(mSequenceNumber);
        }
    }

    /**
     * Add a listener subscription to data messages from a VMS layer from a specific publisher.
     *
     * @param listener    a VMS subscriber.
     * @param layer       the layer to subscribing to.
     * @param publisherId the publisher ID.
     */
    public void addSubscription(IVmsSubscriberClient listener, VmsLayer layer, int publisherId) {
        synchronized (mLock) {
            ++mSequenceNumber;

            Map<Integer, Set<IVmsSubscriberClient>> publisherIdsToListenersForLayer =
                    mLayerSubscriptionsToPublishers.get(layer);

            if (publisherIdsToListenersForLayer == null) {
                publisherIdsToListenersForLayer = new HashMap<>();
                mLayerSubscriptionsToPublishers.put(layer, publisherIdsToListenersForLayer);
            }

            Set<IVmsSubscriberClient> listenersForPublisher =
                    publisherIdsToListenersForLayer.get(publisherId);

            if (listenersForPublisher == null) {
                listenersForPublisher = new HashSet<>();
                publisherIdsToListenersForLayer.put(publisherId, listenersForPublisher);
            }

            // Add the listener to the list.
            listenersForPublisher.add(listener);
        }
    }

    /**
     * Remove a subscription for a layer + version and make sure to remove the key if there are no
     * more subscribers.
     *
     * @param listener to remove.
     * @param layer    of the subscription.
     */
    public void removeSubscription(IVmsSubscriberClient listener, VmsLayer layer) {
        synchronized (mLock) {
            ++mSequenceNumber;
            Set<IVmsSubscriberClient> listeners = mLayerSubscriptions.get(layer);

            // If there are no listeners we are done.
            if (listeners == null) {
                return;
            }
            listeners.remove(listener);
            VmsOperationRecorder.get().removeSubscription(mSequenceNumber, layer);

            // If there are no more listeners then remove the list.
            if (listeners.isEmpty()) {
                mLayerSubscriptions.remove(layer);
            }
        }
    }

    /**
     * Remove a listener subscription to all data messages.
     *
     * @param listener a VMS subscriber.
     */
    public void removeSubscription(IVmsSubscriberClient listener) {
        synchronized (mLock) {
            ++mSequenceNumber;
            mPromiscuousSubscribers.remove(listener);
            VmsOperationRecorder.get().removePromiscuousSubscription(mSequenceNumber);
        }
    }

    /**
     * Remove a subscription to data messages from a VMS layer from a specific publisher.
     *
     * @param listener    a VMS subscriber.
     * @param layer       the layer to unsubscribing from.
     * @param publisherId the publisher ID.
     */
    public void removeSubscription(IVmsSubscriberClient listener, VmsLayer layer, int publisherId) {
        synchronized (mLock) {
            ++mSequenceNumber;

            Map<Integer, Set<IVmsSubscriberClient>> listenersToPublishers =
                    mLayerSubscriptionsToPublishers.get(layer);

            if (listenersToPublishers == null) {
                return;
            }

            Set<IVmsSubscriberClient> listeners = listenersToPublishers.get(publisherId);

            if (listeners == null) {
                return;
            }
            listeners.remove(listener);

            if (listeners.isEmpty()) {
                listenersToPublishers.remove(publisherId);
            }

            if (listenersToPublishers.isEmpty()) {
                mLayerSubscriptionsToPublishers.remove(layer);
            }
        }
    }

    /**
     * Remove a subscriber from all routes (optional operation).
     *
     * @param listener a VMS subscriber.
     */
    public void removeDeadListener(IVmsSubscriberClient listener) {
        synchronized (mLock) {
            // Remove the listener from all the routes.
            for (VmsLayer layer : mLayerSubscriptions.keySet()) {
                removeSubscription(listener, layer);
            }
            // Remove the listener from the loggers.
            removeSubscription(listener);
        }
    }

    /**
     * Returns a list with all the listeners for a layer and version. This include the subscribers
     * which explicitly subscribed to this layer and version and the promiscuous subscribers.
     *
     * @param layer to get listeners to.
     * @return a list of the listeners.
     */
    public Set<IVmsSubscriberClient> getListeners(VmsLayer layer) {
        Set<IVmsSubscriberClient> listeners = new HashSet<>();
        synchronized (mLock) {
            // Add the subscribers which explicitly subscribed to this layer and version
            if (mLayerSubscriptions.containsKey(layer)) {
                listeners.addAll(mLayerSubscriptions.get(layer));
            }
            // Add the promiscuous subscribers.
            listeners.addAll(mPromiscuousSubscribers);
        }
        return listeners;
    }

    /**
     * Returns a list with all the listeners.
     */
    public Set<IVmsSubscriberClient> getAllListeners() {
        Set<IVmsSubscriberClient> listeners = new HashSet<>();
        synchronized (mLock) {
            for (VmsLayer layer : mLayerSubscriptions.keySet()) {
                listeners.addAll(mLayerSubscriptions.get(layer));
            }
            // Add the promiscuous subscribers.
            listeners.addAll(mPromiscuousSubscribers);
        }
        return listeners;
    }

    /**
     * Checks if a listener is subscribed to any messages.
     *
     * @param listener that may have subscription.
     * @return true if the listener uis subscribed to messages.
     */
    public boolean containsListener(IVmsSubscriberClient listener) {
        synchronized (mLock) {
            // Check if listener is subscribed to a layer.
            for (Set<IVmsSubscriberClient> layerListeners : mLayerSubscriptions.values()) {
                if (layerListeners.contains(listener)) {
                    return true;
                }
            }
            // Check is listener is subscribed to all data messages.
            return mPromiscuousSubscribers.contains(listener);
        }
    }

    /**
     * Add a layer and version to the HAL subscriptions.
     *
     * @param layer the HAL subscribes to.
     */
    public void addHalSubscription(VmsLayer layer) {
        synchronized (mLock) {
            ++mSequenceNumber;
            mHalSubscriptions.add(layer);
            VmsOperationRecorder.get().addHalSubscription(mSequenceNumber, layer);
        }
    }

    public void addHalSubscriptionToPublisher(VmsLayer layer, int publisherId) {
        synchronized (mLock) {
            ++mSequenceNumber;

            Set<Integer> publisherIdsForLayer = mHalSubscriptionsToPublishers.get(layer);
            if (publisherIdsForLayer == null) {
                publisherIdsForLayer = new HashSet<>();
                mHalSubscriptionsToPublishers.put(layer, publisherIdsForLayer);
            }
            publisherIdsForLayer.add(publisherId);
        }
    }

    /**
     * remove a layer and version to the HAL subscriptions.
     *
     * @param layer the HAL unsubscribes from.
     */
    public void removeHalSubscription(VmsLayer layer) {
        synchronized (mLock) {
            ++mSequenceNumber;
            mHalSubscriptions.remove(layer);
            VmsOperationRecorder.get().removeHalSubscription(mSequenceNumber, layer);
        }
    }

    public void removeHalSubscriptionToPublisher(VmsLayer layer, int publisherId) {
        synchronized (mLock) {
            ++mSequenceNumber;

            Set<Integer> publisherIdsForLayer = mHalSubscriptionsToPublishers.get(layer);
            if (publisherIdsForLayer == null) {
                return;
            }
            publisherIdsForLayer.remove(publisherId);

            if (publisherIdsForLayer.isEmpty()) {
                mHalSubscriptionsToPublishers.remove(layer);
            }
        }
    }

    /**
     * checks if the HAL is subscribed to a layer.
     *
     * @param layer
     * @return true if the HAL is subscribed to layer.
     */
    public boolean isHalSubscribed(VmsLayer layer) {
        synchronized (mLock) {
            return mHalSubscriptions.contains(layer);
        }
    }

    /**
     * checks if there are subscribers to a layer.
     *
     * @param layer
     * @return true if there are subscribers to layer.
     */
    public boolean hasLayerSubscriptions(VmsLayer layer) {
        synchronized (mLock) {
            return mLayerSubscriptions.containsKey(layer) || mHalSubscriptions.contains(layer);
        }
    }

    /**
     * returns true if there is already a subscription for the layer from publisherId.
     *
     * @param layer
     * @param publisherId
     * @return
     */
    public boolean hasLayerFromPublisherSubscriptions(VmsLayer layer, int publisherId) {
        synchronized (mLock) {
            boolean hasClientSubscription =
                    mLayerSubscriptionsToPublishers.containsKey(layer) &&
                            mLayerSubscriptionsToPublishers.get(layer).containsKey(publisherId);

            boolean hasHalSubscription = mHalSubscriptionsToPublishers.containsKey(layer) &&
                    mHalSubscriptionsToPublishers.get(layer).contains(publisherId);

            return hasClientSubscription || hasHalSubscription;
        }
    }

    /**
     * @return a Set of layers and versions which VMS clients are subscribed to.
     */
    public VmsSubscriptionState getSubscriptionState() {
        synchronized (mLock) {
            Set<VmsLayer> layers = new HashSet<>();
            layers.addAll(mLayerSubscriptions.keySet());
            layers.addAll(mHalSubscriptions);


            Set<VmsAssociatedLayer> layersFromPublishers = new HashSet<>();
            layersFromPublishers.addAll(mLayerSubscriptionsToPublishers.entrySet()
                    .stream()
                    .map(e -> new VmsAssociatedLayer(e.getKey(), e.getValue().keySet()))
                    .collect(Collectors.toSet()));
            layersFromPublishers.addAll(mHalSubscriptionsToPublishers.entrySet()
                    .stream()
                    .map(e -> new VmsAssociatedLayer(e.getKey(), e.getValue()))
                    .collect(Collectors.toSet()));

            return new VmsSubscriptionState(mSequenceNumber, layers, layersFromPublishers);
        }
    }
}