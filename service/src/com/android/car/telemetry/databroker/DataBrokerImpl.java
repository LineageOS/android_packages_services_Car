/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.telemetry.databroker;

import android.car.telemetry.IScriptExecutorListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.car.CarLog;
import com.android.car.CarServiceUtils;
import com.android.car.telemetry.CarTelemetryService;
import com.android.car.telemetry.TelemetryProto;
import com.android.car.telemetry.TelemetryProto.MetricsConfig;
import com.android.car.telemetry.publisher.AbstractPublisher;
import com.android.car.telemetry.publisher.PublisherFactory;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implementation of the data path component of CarTelemetryService. Forwards the published data
 * from publishers to consumers subject to the Controller's decision.
 * TODO(b/187743369): Handle thread-safety of member variables.
 */
public class DataBrokerImpl implements DataBroker {

    @VisibleForTesting
    static final int MSG_HANDLE_TASK = 1;

    private final Object mLock = new Object();
    private final HandlerThread mWorkerThread = CarServiceUtils.getHandlerThread(
            CarTelemetryService.class.getSimpleName());
    private final Handler mWorkerHandler = new TaskHandler(mWorkerThread.getLooper());

    /** Thread-safe int to determine which data can be processed. */
    private final AtomicInteger mPriority = new AtomicInteger(1);

    /**
     * Thread-safe boolean to indicate whether a script is running, which can prevent DataBroker
     * from making multiple ScriptExecutor binder calls.
     * TODO(b/187743369): replace flag with current script name
     */
    private final AtomicBoolean mTaskRunning = new AtomicBoolean(false);

    /** Thread-safe priority queue for scheduling tasks. */
    private final PriorityBlockingQueue<ScriptExecutionTask> mTaskQueue =
            new PriorityBlockingQueue<>();

    /**
     * Maps MetricsConfig's name to its subscriptions. This map is useful when removing a
     * MetricsConfig.
     */
    @GuardedBy("mLock")
    private final Map<String, List<DataSubscriber>> mSubscriptionMap = new ArrayMap<>();
    private final PublisherFactory mPublisherFactory;
    private ScriptFinishedCallback mScriptFinishedCallback;

    public DataBrokerImpl(PublisherFactory publisherFactory) {
        mPublisherFactory = publisherFactory;
    }

    @Override
    public void addMetricsConfiguration(MetricsConfig metricsConfig) {
        // TODO(b/187743369): pass status back to caller
        mWorkerHandler.post(() -> addMetricsConfigurationOnHandlerThread(metricsConfig));
    }

    private void addMetricsConfigurationOnHandlerThread(MetricsConfig metricsConfig) {
        // this method can only be called from the thread that the handler is running at
        if (Looper.myLooper() != mWorkerHandler.getLooper()) {
            throw new RuntimeException(
                    "addMetricsConfigurationOnHandlerThread is not called from handler thread");
        }
        synchronized (mLock) {
            // if metricsConfig already exists, it should not be added again
            if (mSubscriptionMap.containsKey(metricsConfig.getName())) {
                return;
            }
        }
        // Create the subscribers for this metrics configuration
        List<DataSubscriber> dataSubscribers = new ArrayList<>(
                metricsConfig.getSubscribersList().size());
        for (TelemetryProto.Subscriber subscriber : metricsConfig.getSubscribersList()) {
            // protobuf publisher to a concrete Publisher
            AbstractPublisher publisher = mPublisherFactory.getPublisher(
                    subscriber.getPublisher().getPublisherCase());
            // create DataSubscriber from TelemetryProto.Subscriber
            DataSubscriber dataSubscriber = new DataSubscriber(
                    this,
                    metricsConfig,
                    subscriber,
                    /* priority= */ 1); // TODO(b/187743369): remove hardcoded priority
            dataSubscribers.add(dataSubscriber);

            try {
                // The publisher will start sending data to the subscriber.
                // TODO(b/191378559): handle bad configs
                publisher.addDataSubscriber(dataSubscriber);
            } catch (IllegalArgumentException e) {
                Slog.w(CarLog.TAG_TELEMETRY, "Invalid config", e);
                return;
            }
        }
        synchronized (mLock) {
            mSubscriptionMap.put(metricsConfig.getName(), dataSubscribers);
        }
    }

    @Override
    public void removeMetricsConfiguration(MetricsConfig metricsConfig) {
        // TODO(b/187743369): pass status back to caller
        mWorkerHandler.post(() -> removeMetricsConfigurationOnHandlerThread(metricsConfig));
    }

    private void removeMetricsConfigurationOnHandlerThread(MetricsConfig metricsConfig) {
        // this method can only be called from the thread that the handler is running at
        if (Looper.myLooper() != mWorkerHandler.getLooper()) {
            throw new RuntimeException(
                    "removeMetricsConfigurationOnHandlerThread is not called from handler thread");
        }
        synchronized (mLock) {
            if (!mSubscriptionMap.containsKey(metricsConfig.getName())) {
                return;
            }
        }
        // get the subscriptions associated with this MetricsConfig, remove it from the map
        List<DataSubscriber> dataSubscribers;
        synchronized (mLock) {
            dataSubscribers = mSubscriptionMap.remove(metricsConfig.getName());
        }
        // for each subscriber, remove it from publishers
        for (DataSubscriber subscriber : dataSubscribers) {
            AbstractPublisher publisher = mPublisherFactory.getPublisher(
                    subscriber.getPublisherParam().getPublisherCase());
            try {
                publisher.removeDataSubscriber(subscriber);
            } catch (IllegalArgumentException e) {
                // It shouldn't happen, but if happens, let's just log it.
                Slog.w(CarLog.TAG_TELEMETRY, "Failed to remove subscriber from publisher", e);
            }
            // TODO(b/187743369): remove related tasks from the queue
        }
    }

    @Override
    public void addTaskToQueue(ScriptExecutionTask task) {
        mTaskQueue.add(task);
        scheduleNextTask();
    }

    /**
     * This method can be called from any thread. It is thread-safe because atomic values and the
     * blocking queue are thread-safe. It is possible for this method to be invoked from different
     * threads at the same time, but it is not possible to schedule the same task twice, because
     * the handler handles message in the order they come in, this means the task will be polled
     * sequentially instead of concurrently. Every task that is scheduled and run will be distinct.
     * TODO(b/187743369): If the threading behavior in DataSubscriber changes, ScriptExecutionTask
     *                    will also have different threading behavior. Update javadoc when the
     *                    behavior is decided.
     */
    @Override
    public void scheduleNextTask() {
        if (mTaskRunning.get() || mTaskQueue.peek() == null) {
            return;
        }
        mWorkerHandler.sendMessage(mWorkerHandler.obtainMessage(MSG_HANDLE_TASK));
    }

    @Override
    public void setOnScriptFinishedCallback(ScriptFinishedCallback callback) {
        // TODO(b/187743369): move the interface on databroker surface and pass it in constructor
        mScriptFinishedCallback = callback;
    }

    @Override
    public void setTaskExecutionPriority(int priority) {
        mPriority.set(priority);
        scheduleNextTask(); // when priority updates, schedule a task which checks task queue
    }

    @VisibleForTesting
    Map<String, List<DataSubscriber>> getSubscriptionMap() {
        synchronized (mLock) {
            return new ArrayMap<>((ArrayMap<String, List<DataSubscriber>>) mSubscriptionMap);
        }
    }

    @VisibleForTesting
    Handler getWorkerHandler() {
        return mWorkerHandler;
    }

    @VisibleForTesting
    PriorityBlockingQueue<ScriptExecutionTask> getTaskQueue() {
        return mTaskQueue;
    }

    /**
     * Polls and runs a task from the head of the priority queue if the queue is nonempty and the
     * head of the queue has priority higher than or equal to the current priority. A higher
     * priority is denoted by a lower priority number, so head of the queue should have equal or
     * lower priority number to be polled.
     */
    private void pollAndExecuteTask() {
        // this method can only be called from the thread that the handler is running at
        if (Looper.myLooper() != mWorkerHandler.getLooper()) {
            throw new RuntimeException("pollAndExecuteTask is not called from handler thread");
        }
        // atomic boolean, atomic int, blocking queue, and the task are thread-safe.
        if (mTaskRunning.get()
                || mTaskQueue.peek() == null
                || mTaskQueue.peek().getPriority() > mPriority.get()) {
            return;
        }
        ScriptExecutionTask task = mTaskQueue.poll();
        if (task == null) {
            return;
        }
        mTaskRunning.set(true); // signal the start of script execution
        // TODO(b/187743369): scriptExecutor.invokeScript(...);
    }

    /**
     * Signals the end of script execution and schedules the next task. This method is thread-safe.
     */
    private void scriptExecutionFinished() {
        mTaskRunning.set(false);
        scheduleNextTask();
    }

    /** Listens for script execution status. Methods are called on the binder thread. */
    private static final class ScriptExecutorListener extends IScriptExecutorListener.Stub {
        private final WeakReference<DataBrokerImpl> mWeakDataBroker;

        private ScriptExecutorListener(DataBrokerImpl dataBroker) {
            mWeakDataBroker = new WeakReference<>(dataBroker);
        }

        @Override
        public void onScriptFinished(byte[] result) {
            DataBrokerImpl dataBroker = mWeakDataBroker.get();
            if (dataBroker == null) {
                return;
            }
            dataBroker.scriptExecutionFinished();
            // TODO(b/187743369): implement update config store and push results
        }

        @Override
        public void onSuccess(Bundle stateToPersist) {
            DataBrokerImpl dataBroker = mWeakDataBroker.get();
            if (dataBroker == null) {
                return;
            }
            dataBroker.scriptExecutionFinished();
            // TODO(b/187743369): implement persist states
        }

        @Override
        public void onError(int errorType, String message, String stackTrace) {
            DataBrokerImpl dataBroker = mWeakDataBroker.get();
            if (dataBroker == null) {
                return;
            }
            dataBroker.scriptExecutionFinished();
            // TODO(b/187743369): implement push errors
        }
    }

    /** Callback handler to handle scheduling and rescheduling of {@link ScriptExecutionTask}s. */
    class TaskHandler extends Handler {
        TaskHandler(Looper looper) {
            super(looper);
        }

        /**
         * Handles a message by polling a task from the priority queue and executing a
         * {@link ScriptExecutionTask}. There are multiple places where a message is sent: when
         * priority updates, when a new task is added to the priority queue, and when a task
         * finishes running.
         */
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_HANDLE_TASK:
                    pollAndExecuteTask(); // run the next task
                    break;
                default:
                    Slog.w(CarLog.TAG_TELEMETRY, "TaskHandler received unknown message.");
            }
        }
    }
}
