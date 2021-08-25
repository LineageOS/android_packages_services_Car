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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.car.hardware.CarPropertyConfig;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SystemClock;

import com.android.car.CarPropertyService;
import com.android.car.telemetry.ResultStore;
import com.android.car.telemetry.TelemetryProto;
import com.android.car.telemetry.publisher.PublisherFactory;
import com.android.car.telemetry.publisher.StatsManagerProxy;
import com.android.car.telemetry.scriptexecutorinterface.IScriptExecutor;
import com.android.car.telemetry.scriptexecutorinterface.IScriptExecutorListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;

@RunWith(MockitoJUnitRunner.class)
public class DataBrokerTest {
    private static final int PROP_ID = 100;
    private static final int PROP_AREA = 200;
    private static final int PRIORITY_HIGH = 1;
    private static final int PRIORITY_LOW = 100;
    private static final long TIMEOUT_MS = 5_000L;
    private static final CarPropertyConfig<Integer> PROP_CONFIG =
            CarPropertyConfig.newBuilder(Integer.class, PROP_ID, PROP_AREA).setAccess(
                    CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ).build();
    private static final PersistableBundle DATA = new PersistableBundle();
    private static final TelemetryProto.VehiclePropertyPublisher
            VEHICLE_PROPERTY_PUBLISHER_CONFIGURATION =
            TelemetryProto.VehiclePropertyPublisher.newBuilder().setReadRate(
                    1).setVehiclePropertyId(PROP_ID).build();
    private static final TelemetryProto.Publisher PUBLISHER_CONFIGURATION =
            TelemetryProto.Publisher.newBuilder().setVehicleProperty(
                    VEHICLE_PROPERTY_PUBLISHER_CONFIGURATION).build();
    private static final TelemetryProto.Subscriber SUBSCRIBER_FOO =
            TelemetryProto.Subscriber.newBuilder().setHandler("function_name_foo").setPublisher(
                    PUBLISHER_CONFIGURATION).setPriority(PRIORITY_HIGH).build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_FOO =
            TelemetryProto.MetricsConfig.newBuilder().setName("Foo").setVersion(
                    1).addSubscribers(SUBSCRIBER_FOO).build();
    private static final TelemetryProto.Subscriber SUBSCRIBER_BAR =
            TelemetryProto.Subscriber.newBuilder().setHandler("function_name_bar").setPublisher(
                    PUBLISHER_CONFIGURATION).setPriority(PRIORITY_LOW).build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_BAR =
            TelemetryProto.MetricsConfig.newBuilder().setName("Bar").setVersion(
                    1).addSubscribers(SUBSCRIBER_BAR).build();

    // when count reaches 0, all messages are handled, so assertions won't have race condition
    private CountDownLatch mIdleHandlerLatch = new CountDownLatch(1);
    private DataBrokerImpl mDataBroker;
    private FakeScriptExecutor mFakeScriptExecutor;
    private ScriptExecutionTask mHighPriorityTask;
    private ScriptExecutionTask mLowPriorityTask;

    @Mock
    private Context mMockContext;
    @Mock
    private CarPropertyService mMockCarPropertyService;
    @Mock
    private Handler mMockHandler;
    @Mock
    private StatsManagerProxy mMockStatsManager;
    @Mock
    private SharedPreferences mMockSharedPreferences;
    @Mock
    private IBinder mMockScriptExecutorBinder;
    @Mock
    private ResultStore mMockResultStore;

    @Before
    public void setUp() {
        when(mMockCarPropertyService.getPropertyList())
                .thenReturn(Collections.singletonList(PROP_CONFIG));
        // bind service should return true, otherwise broker is disabled
        when(mMockContext.bindServiceAsUser(any(), any(), anyInt(), any())).thenReturn(true);
        PublisherFactory factory = new PublisherFactory(
                mMockCarPropertyService, mMockHandler, mMockStatsManager, mMockSharedPreferences);
        mDataBroker = new DataBrokerImpl(mMockContext, factory, mMockResultStore);
        // add IdleHandler to get notified when all messages and posts are handled
        mDataBroker.getTelemetryHandler().getLooper().getQueue().addIdleHandler(() -> {
            mIdleHandlerLatch.countDown();
            return true;
        });

        mFakeScriptExecutor = new FakeScriptExecutor();
        when(mMockScriptExecutorBinder.queryLocalInterface(anyString()))
                .thenReturn(mFakeScriptExecutor);
        when(mMockContext.bindServiceAsUser(any(), any(), anyInt(), any())).thenAnswer(i -> {
            ServiceConnection conn = i.getArgument(1);
            conn.onServiceConnected(null, mMockScriptExecutorBinder);
            return true;
        });

        mHighPriorityTask = new ScriptExecutionTask(
                new DataSubscriber(mDataBroker, METRICS_CONFIG_FOO, SUBSCRIBER_FOO),
                DATA,
                SystemClock.elapsedRealtime());
        mLowPriorityTask = new ScriptExecutionTask(
                new DataSubscriber(mDataBroker, METRICS_CONFIG_BAR, SUBSCRIBER_BAR),
                DATA,
                SystemClock.elapsedRealtime());
    }

    @Test
    public void testSetTaskExecutionPriority_whenNoTask_shouldNotInvokeScriptExecutor()
            throws Exception {
        mDataBroker.setTaskExecutionPriority(PRIORITY_HIGH);

        waitForHandlerThreadToFinish();
        assertThat(mFakeScriptExecutor.getApiInvocationCount()).isEqualTo(0);
    }

    @Test
    public void testSetTaskExecutionPriority_whenNextTaskPriorityLow_shouldNotRunTask()
            throws Exception {
        mDataBroker.getTaskQueue().add(mLowPriorityTask);

        mDataBroker.setTaskExecutionPriority(PRIORITY_HIGH);

        waitForHandlerThreadToFinish();
        // task is not polled
        assertThat(mDataBroker.getTaskQueue().peek()).isEqualTo(mLowPriorityTask);
        assertThat(mFakeScriptExecutor.getApiInvocationCount()).isEqualTo(0);
    }

    @Test
    public void testSetTaskExecutionPriority_whenNextTaskPriorityHigh_shouldInvokeScriptExecutor()
            throws Exception {
        mDataBroker.getTaskQueue().add(mHighPriorityTask);

        mDataBroker.setTaskExecutionPriority(PRIORITY_HIGH);

        waitForHandlerThreadToFinish();
        // task is polled and run
        assertThat(mDataBroker.getTaskQueue().peek()).isNull();
        assertThat(mFakeScriptExecutor.getApiInvocationCount()).isEqualTo(1);
    }

    @Test
    public void testScheduleNextTask_whenNoTask_shouldNotInvokeScriptExecutor() throws Exception {
        mDataBroker.scheduleNextTask();

        waitForHandlerThreadToFinish();
        assertThat(mFakeScriptExecutor.getApiInvocationCount()).isEqualTo(0);
    }

    @Test
    public void testScheduleNextTask_whenTaskInProgress_shouldNotInvokeScriptExecutorAgain()
            throws Exception {
        PriorityBlockingQueue<ScriptExecutionTask> taskQueue = mDataBroker.getTaskQueue();
        taskQueue.add(mHighPriorityTask);
        mDataBroker.scheduleNextTask(); // start a task
        waitForHandlerThreadToFinish();
        assertThat(taskQueue.peek()).isNull(); // assert that task is polled and running
        taskQueue.add(mHighPriorityTask); // add another task into the queue

        mDataBroker.scheduleNextTask(); // schedule next task while the last task is in progress

        // verify task is not polled
        assertThat(taskQueue.peek()).isEqualTo(mHighPriorityTask);
        // expect one invocation for the task that is running
        assertThat(mFakeScriptExecutor.getApiInvocationCount()).isEqualTo(1);
    }

    @Test
    public void testScheduleNextTask_whenTaskCompletes_shouldAutomaticallyScheduleNextTask()
            throws Exception {
        PriorityBlockingQueue<ScriptExecutionTask> taskQueue = mDataBroker.getTaskQueue();
        // add two tasks into the queue for execution
        taskQueue.add(mHighPriorityTask);
        taskQueue.add(mHighPriorityTask);

        mDataBroker.scheduleNextTask(); // start a task
        waitForHandlerThreadToFinish();
        mIdleHandlerLatch = new CountDownLatch(1); // reset handler idle condition
        // end a task, should automatically schedule the next task
        mFakeScriptExecutor.notifyScriptSuccess(DATA); // posts to handler

        waitForHandlerThreadToFinish();
        // verify queue is empty, both tasks are polled and executed
        assertThat(taskQueue.peek()).isNull();
        assertThat(mFakeScriptExecutor.getApiInvocationCount()).isEqualTo(2);
    }

    @Test
    public void testScheduleNextTask_onScriptSuccess_shouldStoreInterimResult() throws Exception {
        mDataBroker.getTaskQueue().add(mHighPriorityTask);
        ArgumentCaptor<PersistableBundle> persistableBundleCaptor =
                ArgumentCaptor.forClass(PersistableBundle.class);

        mDataBroker.scheduleNextTask();
        waitForHandlerThreadToFinish();
        mFakeScriptExecutor.notifyScriptSuccess(DATA);

        assertThat(mFakeScriptExecutor.getApiInvocationCount()).isEqualTo(1);
        verify(mMockResultStore).putInterimResult(eq(METRICS_CONFIG_FOO.getName()),
                persistableBundleCaptor.capture());
        assertThat(persistableBundleCaptor.getValue().toString()).isEqualTo(
                DATA.toString()); // expect same persistable bundle
    }

    @Test
    public void testScheduleNextTask_whenBindScriptExecutorFailed_shouldDisableBroker()
            throws Exception {
        // fail all future attempts to bind to it
        Mockito.reset(mMockContext);
        when(mMockContext.bindServiceAsUser(any(), any(), anyInt(), any())).thenReturn(false);
        mDataBroker.addMetricsConfiguration(METRICS_CONFIG_FOO);
        PriorityBlockingQueue<ScriptExecutionTask> taskQueue = mDataBroker.getTaskQueue();
        taskQueue.add(mHighPriorityTask);

        // will rebind to ScriptExecutor if it is null
        mDataBroker.scheduleNextTask();

        waitForHandlerThreadToFinish();
        // all subscribers should have been removed
        assertThat(mDataBroker.getSubscriptionMap()).hasSize(0);
        assertThat(mFakeScriptExecutor.getApiInvocationCount()).isEqualTo(0);
    }

    @Test
    public void testScheduleNextTask_whenScriptExecutorThrowsException_shouldRequeueTask()
            throws Exception {
        PriorityBlockingQueue<ScriptExecutionTask> taskQueue = mDataBroker.getTaskQueue();
        taskQueue.add(mHighPriorityTask);
        mFakeScriptExecutor.failNextApiCalls(1); // fail the next invokeScript() call

        mDataBroker.scheduleNextTask();

        waitForHandlerThreadToFinish();
        // expect invokeScript() to be called and failed, causing the same task to be re-queued
        assertThat(mFakeScriptExecutor.getApiInvocationCount()).isEqualTo(1);
        assertThat(taskQueue.peek()).isEqualTo(mHighPriorityTask);
    }

    @Test
    public void testAddTaskToQueue_shouldInvokeScriptExecutor() throws Exception {
        mDataBroker.addTaskToQueue(mHighPriorityTask);

        waitForHandlerThreadToFinish();
        assertThat(mFakeScriptExecutor.getApiInvocationCount()).isEqualTo(1);
    }

    @Test
    public void testAddMetricsConfiguration_newMetricsConfig() {
        mDataBroker.addMetricsConfiguration(METRICS_CONFIG_BAR);

        assertThat(mDataBroker.getSubscriptionMap()).hasSize(1);
        assertThat(mDataBroker.getSubscriptionMap()).containsKey(METRICS_CONFIG_BAR.getName());
        // there should be one data subscriber in the subscription list of METRICS_CONFIG_BAR
        assertThat(mDataBroker.getSubscriptionMap().get(METRICS_CONFIG_BAR.getName())).hasSize(1);
    }


    @Test
    public void testAddMetricsConfiguration_duplicateMetricsConfig_shouldDoNothing() {
        // this metrics config has already been added in setUp()
        mDataBroker.addMetricsConfiguration(METRICS_CONFIG_FOO);

        assertThat(mDataBroker.getSubscriptionMap()).hasSize(1);
        assertThat(mDataBroker.getSubscriptionMap()).containsKey(METRICS_CONFIG_FOO.getName());
        assertThat(mDataBroker.getSubscriptionMap().get(METRICS_CONFIG_FOO.getName())).hasSize(1);
    }

    @Test
    public void testRemoveMetricsConfiguration_shouldRemoveAllAssociatedTasks() {
        mDataBroker.addMetricsConfiguration(METRICS_CONFIG_FOO);
        mDataBroker.addMetricsConfiguration(METRICS_CONFIG_BAR);
        ScriptExecutionTask taskWithMetricsConfigFoo = new ScriptExecutionTask(
                new DataSubscriber(mDataBroker, METRICS_CONFIG_FOO, SUBSCRIBER_FOO),
                DATA,
                SystemClock.elapsedRealtime());
        PriorityBlockingQueue<ScriptExecutionTask> taskQueue = mDataBroker.getTaskQueue();
        taskQueue.add(mHighPriorityTask); // associated with METRICS_CONFIG_FOO
        taskQueue.add(mLowPriorityTask); // associated with METRICS_CONFIG_BAR
        taskQueue.add(taskWithMetricsConfigFoo); // associated with METRICS_CONFIG_FOO
        assertThat(taskQueue).hasSize(3);

        mDataBroker.removeMetricsConfiguration(METRICS_CONFIG_FOO);

        assertThat(taskQueue).hasSize(1);
        assertThat(taskQueue.poll()).isEqualTo(mLowPriorityTask);
    }

    @Test
    public void testRemoveMetricsConfiguration_whenMetricsConfigNonExistent_shouldDoNothing() {
        mDataBroker.removeMetricsConfiguration(METRICS_CONFIG_BAR);

        assertThat(mDataBroker.getSubscriptionMap()).hasSize(0);
    }

    private void waitForHandlerThreadToFinish() throws Exception {
        assertWithMessage("handler not idle in %sms", TIMEOUT_MS)
                .that(mIdleHandlerLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    private static class FakeScriptExecutor implements IScriptExecutor {
        private IScriptExecutorListener mListener;
        private int mApiInvocationCount = 0;
        private int mFailApi = 0;

        @Override
        public void invokeScript(String scriptBody, String functionName,
                PersistableBundle publishedData, @Nullable PersistableBundle savedState,
                IScriptExecutorListener listener)
                throws RemoteException {
            mApiInvocationCount++;
            mListener = listener;
            if (mFailApi > 0) {
                mFailApi--;
                throw new RemoteException("Simulated failure");
            }
        }

        @Override
        public IBinder asBinder() {
            return null;
        }

        /** Mocks script temporary completion. */
        public void notifyScriptSuccess(PersistableBundle bundle) {
            try {
                mListener.onSuccess(bundle);
            } catch (RemoteException e) {
                // nothing to do
            }
        }

        /** Fails the next N invokeScript() call. */
        public void failNextApiCalls(int n) {
            mFailApi = n;
        }

        /** Returns number of times the ScriptExecutor API was invoked. */
        public int getApiInvocationCount() {
            return mApiInvocationCount;
        }
    }
}
