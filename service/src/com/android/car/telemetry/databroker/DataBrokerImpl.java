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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.car.CarLog;
import com.android.car.CarServiceUtils;
import com.android.car.telemetry.CarTelemetryService;
import com.android.car.telemetry.ResultStore;
import com.android.car.telemetry.TelemetryProto;
import com.android.car.telemetry.TelemetryProto.MetricsConfig;
import com.android.car.telemetry.publisher.AbstractPublisher;
import com.android.car.telemetry.publisher.PublisherFactory;
import com.android.car.telemetry.scriptexecutorinterface.IScriptExecutor;
import com.android.car.telemetry.scriptexecutorinterface.IScriptExecutorListener;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implementation of the data path component of CarTelemetryService. Forwards the published data
 * from publishers to consumers subject to the Controller's decision.
 * TODO(b/187743369): Handle thread-safety of member variables.
 */
public class DataBrokerImpl implements DataBroker {

    private static final int MSG_HANDLE_TASK = 1;
    @VisibleForTesting
    static final int MSG_BIND_TO_SCRIPT_EXECUTOR = 2;

    private static final String SCRIPT_EXECUTOR_PACKAGE = "com.android.car.scriptexecutor";
    private static final String SCRIPT_EXECUTOR_CLASS =
            "com.android.car.scriptexecutor.ScriptExecutor";

    private final Context mContext;
    private final PublisherFactory mPublisherFactory;
    private final ResultStore mResultStore;
    private final ScriptExecutorListener mScriptExecutorListener;
    private final Object mLock = new Object();
    private final HandlerThread mWorkerThread = CarServiceUtils.getHandlerThread(
            CarTelemetryService.class.getSimpleName());
    private final Handler mWorkerHandler = new TaskHandler(mWorkerThread.getLooper());

    /** Thread-safe int to determine which data can be processed. */
    private final AtomicInteger mPriority = new AtomicInteger(1);

    /**
     * Name of the script that's currently running. If no script is running, value is null.
     * A non-null script name indicates a script is running, which means DataBroker should not
     * make another ScriptExecutor binder call.
     */
    private final AtomicReference<String> mCurrentScriptName = new AtomicReference<>(null);

    /**
     * If something irrecoverable happened, DataBroker should enter into a disabled state to prevent
     * doing futile work.
     */
    private final AtomicBoolean mDisabled = new AtomicBoolean(false);

    /** Thread-safe priority queue for scheduling tasks. */
    private final PriorityBlockingQueue<ScriptExecutionTask> mTaskQueue =
            new PriorityBlockingQueue<>();

    /**
     * Maps MetricsConfig's name to its subscriptions. This map is useful when removing a
     * MetricsConfig.
     */
    @GuardedBy("mLock")
    private final Map<String, List<DataSubscriber>> mSubscriptionMap = new ArrayMap<>();
    private final AtomicReference<IScriptExecutor> mScriptExecutor = new AtomicReference<>();
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mScriptExecutor.set(IScriptExecutor.Stub.asInterface(service));
            scheduleNextTask();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mScriptExecutor.set(null);
            cleanupBoundService();
        }
    };

    private ScriptFinishedCallback mScriptFinishedCallback;

    public DataBrokerImpl(
            Context context, PublisherFactory publisherFactory, ResultStore resultStore) {
        mContext = context;
        mPublisherFactory = publisherFactory;
        mResultStore = resultStore;
        mScriptExecutorListener = new ScriptExecutorListener(this);
        bindScriptExecutor();
    }

    /** Binds to ScriptExecutor. */
    private void bindScriptExecutor() {
        // do not re-bind if broker is in a disabled state or script executor is nonnull
        if (mDisabled.get() || mScriptExecutor.get() != null) {
            return;
        }
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(SCRIPT_EXECUTOR_PACKAGE, SCRIPT_EXECUTOR_CLASS));
        boolean success = mContext.bindServiceAsUser(
                intent,
                mServiceConnection,
                Context.BIND_AUTO_CREATE,
                UserHandle.SYSTEM);
        if (!success) {
            Slog.w(CarLog.TAG_TELEMETRY, "failed to get valid connection to ScriptExecutor");
            unbindScriptExecutor();
            disableBroker();
        }
    }

    /** Unbinds {@link ScriptExecutor} to release the connection. */
    private void unbindScriptExecutor() {
        try {
            mContext.unbindService(mServiceConnection);
        } catch (IllegalArgumentException e) {
            // If ScriptExecutor is gone before unbinding, it will throw this exception
            Slog.w(CarLog.TAG_TELEMETRY, "Failed to unbind from ScriptExecutor", e);
        }
    }

    /**
     * Cleans up the state after ScriptExecutor is killed by the system & service disconnects.
     */
    private void cleanupBoundService() {
        // TODO(b/187743369): clean up the state after script executor disconnects
        unbindScriptExecutor();
        mCurrentScriptName.set(null);
    }

    /** Enters into a disabled state because something irrecoverable happened. */
    private void disableBroker() {
        mDisabled.set(true);
        // remove all MetricConfigs, disable all publishers, stop receiving data
        synchronized (mLock) {
            for (String metricsConfigName : mSubscriptionMap.keySet()) {
                // if no subscriber, remove key from map
                if (mSubscriptionMap.get(metricsConfigName).size() == 0) {
                    mSubscriptionMap.remove(metricsConfigName);
                } else {
                    // otherwise get the metrics config from the DataSubscriber and remove it
                    removeMetricsConfiguration(mSubscriptionMap.get(metricsConfigName).get(0)
                            .getMetricsConfig());
                }
            }
        }
    }

    @Override
    public void addMetricsConfiguration(MetricsConfig metricsConfig) {
        if (mDisabled.get()) {
            return;
        }
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
                    subscriber);
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
        }
        // Remove all the tasks associated with this metrics config. The underlying impl uses the
        // weakly consistent iterator, which is thread-safe but does not freeze the collection while
        // iterating, so it may or may not reflect any updates since the iterator was created.
        // But since adding & polling from queue should happen in the same thread, the task queue
        // should not be changed while tasks are being iterated and removed.
        mTaskQueue.removeIf(task -> task.isAssociatedWithMetricsConfig(metricsConfig));
    }

    @Override
    public void addTaskToQueue(ScriptExecutionTask task) {
        if (mDisabled.get()) {
            return;
        }
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
     * will also have different threading behavior. Update javadoc when the
     * behavior is decided.
     */
    @Override
    public void scheduleNextTask() {
        if (mDisabled.get() || mCurrentScriptName.get() != null || mTaskQueue.peek() == null) {
            return;
        }
        mWorkerHandler.sendMessage(mWorkerHandler.obtainMessage(MSG_HANDLE_TASK));
    }

    @Override
    public void setOnScriptFinishedCallback(ScriptFinishedCallback callback) {
        if (mDisabled.get()) {
            return;
        }
        mScriptFinishedCallback = callback;
    }

    @Override
    public void setTaskExecutionPriority(int priority) {
        if (mDisabled.get()) {
            return;
        }
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
        // all checks are thread-safe
        if (mDisabled.get()
                || mCurrentScriptName.get() != null
                || mTaskQueue.peek() == null
                || mTaskQueue.peek().getPriority() > mPriority.get()) {
            return;
        }
        IScriptExecutor scriptExecutor = mScriptExecutor.get();
        if (scriptExecutor == null) {
            Slog.w(CarLog.TAG_TELEMETRY, "script executor is null, cannot execute task");
            bindScriptExecutor();
            return;
        }
        ScriptExecutionTask task = mTaskQueue.poll();
        if (task == null) {
            return;
        }
        MetricsConfig metricsConfig = task.getMetricsConfig();
        mCurrentScriptName.set(metricsConfig.getName()); // signal the start of script execution
        try {
            scriptExecutor.invokeScript(
                    metricsConfig.getScript(),
                    task.getHandlerName(),
                    task.getData(),
                    null, // TODO(b/197027637): PersistableBundle cannot be converted into Bundle
                    mScriptExecutorListener);
        } catch (RemoteException e) {
            Slog.d(CarLog.TAG_TELEMETRY, "remote exception occurred invoking script", e);
            mTaskQueue.add(task); // will not trigger scheduleNextTask()
            mCurrentScriptName.set(null);
        }
    }

    /** Stores final metrics and schedules the next task. */
    private void onScriptFinished(byte[] result) {
        // TODO(b/197027637): update API to use PersistableBundle
        //                    mResultStore.putFinalResult(mCurrentScriptName.get(), result);
        mCurrentScriptName.set(null);
        scheduleNextTask();
    }

    /** Stores interim metrics and schedules the next task. */
    private void onScriptSuccess(PersistableBundle stateToPersist) {
        // TODO(b/197027637): update API to use PersistableBundle
        PersistableBundle persistableBundle = new PersistableBundle();
        for (String key : stateToPersist.keySet()) {
            Object value = stateToPersist.get(key);
            if (value instanceof Integer) {
                persistableBundle.putInt(key, (int) value);
            } else if (value instanceof Double) {
                persistableBundle.putDouble(key, (double) value);
            } else if (value instanceof Boolean) {
                persistableBundle.putBoolean(key, (boolean) value);
            } else if (value instanceof String) {
                persistableBundle.putString(key, (String) value);
            }
        }
        mResultStore.putInterimResult(mCurrentScriptName.get(), persistableBundle);
        mCurrentScriptName.set(null);
        scheduleNextTask();
    }

    /** Stores telemetry error and schedules the next task. */
    private void onScriptError(int errorType, String message, String stackTrace) {
        // TODO(b/197027637): create error object
        mCurrentScriptName.set(null);
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
            dataBroker.onScriptFinished(result);
        }

        @Override
        public void onSuccess(PersistableBundle stateToPersist) {
            DataBrokerImpl dataBroker = mWeakDataBroker.get();
            if (dataBroker == null) {
                return;
            }
            dataBroker.onScriptSuccess(stateToPersist);
        }

        @Override
        public void onError(int errorType, String message, String stackTrace) {
            DataBrokerImpl dataBroker = mWeakDataBroker.get();
            if (dataBroker == null) {
                return;
            }
            dataBroker.onScriptError(errorType, message, stackTrace);
        }
    }

    /** Callback handler to handle scheduling and rescheduling of {@link ScriptExecutionTask}s. */
    class TaskHandler extends Handler {
        TaskHandler(Looper looper) {
            super(looper);
        }

        /**
         * Handles a message depending on the message ID.
         * If the msg ID is MSG_HANDLE_TASK, it polls a task from the priority queue and executing a
         * {@link ScriptExecutionTask}. There are multiple places where this message is sent: when
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
