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

import static com.android.car.telemetry.databroker.DataBrokerImpl.MSG_HANDLE_TASK;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.when;

import android.car.hardware.CarPropertyConfig;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;

import com.android.car.CarPropertyService;
import com.android.car.telemetry.TelemetryProto;
import com.android.car.telemetry.publisher.PublisherFactory;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.concurrent.PriorityBlockingQueue;

@RunWith(MockitoJUnitRunner.class)
public class DataBrokerUnitTest {
    private static final int PROP_ID = 100;
    private static final int PROP_AREA = 200;
    private static final int PRIORITY_HIGH = 1;
    private static final int PRIORITY_LOW = 10;
    private static final long TIMEOUT_MS = 5_000L;
    private static final CarPropertyConfig<Integer> PROP_CONFIG =
            CarPropertyConfig.newBuilder(Integer.class, PROP_ID, PROP_AREA).setAccess(
                    CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ).build();
    private static final Bundle DATA = new Bundle();
    private static final TelemetryProto.VehiclePropertyPublisher
            VEHICLE_PROPERTY_PUBLISHER_CONFIGURATION =
            TelemetryProto.VehiclePropertyPublisher.newBuilder().setReadRate(
                    1).setVehiclePropertyId(PROP_ID).build();
    private static final TelemetryProto.Publisher PUBLISHER_CONFIGURATION =
            TelemetryProto.Publisher.newBuilder().setVehicleProperty(
                    VEHICLE_PROPERTY_PUBLISHER_CONFIGURATION).build();
    private static final TelemetryProto.Subscriber SUBSCRIBER_FOO =
            TelemetryProto.Subscriber.newBuilder().setHandler("function_name_foo").setPublisher(
                    PUBLISHER_CONFIGURATION).build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_FOO =
            TelemetryProto.MetricsConfig.newBuilder().setName("Foo").setVersion(
                    1).addSubscribers(SUBSCRIBER_FOO).build();
    private static final TelemetryProto.Subscriber SUBSCRIBER_BAR =
            TelemetryProto.Subscriber.newBuilder().setHandler("function_name_bar").setPublisher(
                    PUBLISHER_CONFIGURATION).build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_BAR =
            TelemetryProto.MetricsConfig.newBuilder().setName("Bar").setVersion(
                    1).addSubscribers(SUBSCRIBER_BAR).build();

    @Mock
    private CarPropertyService mMockCarPropertyService;

    private DataBrokerImpl mDataBroker;
    private Handler mHandler;
    private ScriptExecutionTask mHighPriorityTask;
    private ScriptExecutionTask mLowPriorityTask;

    @Before
    public void setUp() {
        when(mMockCarPropertyService.getPropertyList())
                .thenReturn(Collections.singletonList(PROP_CONFIG));
        PublisherFactory factory = new PublisherFactory(mMockCarPropertyService);
        mDataBroker = new DataBrokerImpl(factory);
        mHandler = mDataBroker.getWorkerHandler();
        mHighPriorityTask = new ScriptExecutionTask(
                new DataSubscriber(mDataBroker, METRICS_CONFIG_FOO, SUBSCRIBER_FOO, PRIORITY_HIGH),
                DATA,
                SystemClock.elapsedRealtime());
        mLowPriorityTask = new ScriptExecutionTask(
                new DataSubscriber(mDataBroker, METRICS_CONFIG_FOO, SUBSCRIBER_FOO, PRIORITY_LOW),
                DATA,
                SystemClock.elapsedRealtime());
    }

    @Test
    public void testSetTaskExecutionPriority_whenNoTask_shouldNotScheduleTask() {
        mDataBroker.setTaskExecutionPriority(PRIORITY_HIGH);

        assertThat(mHandler.hasMessages(MSG_HANDLE_TASK)).isFalse();
    }

    @Test
    public void testSetTaskExecutionPriority_whenNextTaskPriorityLow_shouldNotPollTask() {
        mDataBroker.getTaskQueue().add(mLowPriorityTask);

        mDataBroker.setTaskExecutionPriority(PRIORITY_HIGH);

        waitForHandlerThreadToFinish();
        // task is not polled
        assertThat(mDataBroker.getTaskQueue().peek()).isEqualTo(mLowPriorityTask);
    }

    @Test
    public void testSetTaskExecutionPriority_whenNextTaskPriorityHigh_shouldPollTask() {
        mDataBroker.getTaskQueue().add(mHighPriorityTask);

        mDataBroker.setTaskExecutionPriority(PRIORITY_HIGH);

        waitForHandlerThreadToFinish();
        // task is polled and run
        assertThat(mDataBroker.getTaskQueue().peek()).isNull();
    }

    @Test
    public void testScheduleNextTask_whenNoTask_shouldNotSendMessageToHandler() {
        mDataBroker.scheduleNextTask();

        assertThat(mHandler.hasMessages(MSG_HANDLE_TASK)).isFalse();
    }

    @Test
    public void testScheduleNextTask_whenTaskInProgress_shouldNotSendMessageToHandler() {
        PriorityBlockingQueue<ScriptExecutionTask> taskQueue = mDataBroker.getTaskQueue();
        taskQueue.add(mHighPriorityTask);
        mDataBroker.scheduleNextTask(); // start a task
        waitForHandlerThreadToFinish();
        assertThat(taskQueue.peek()).isNull(); // assert that task is polled and running
        taskQueue.add(mHighPriorityTask); // add another task into the queue

        mDataBroker.scheduleNextTask(); // schedule next task while the last task is in progress

        // verify no message is sent to handler and no task is polled
        assertThat(mHandler.hasMessages(MSG_HANDLE_TASK)).isFalse();
        assertThat(taskQueue.peek()).isEqualTo(mHighPriorityTask);
    }

    @Test
    public void testAddTaskToQueue_shouldScheduleNextTask() {
        mDataBroker.addTaskToQueue(mHighPriorityTask);

        assertThat(mHandler.hasMessages(MSG_HANDLE_TASK)).isTrue();
    }

    @Test
    public void testAddMetricsConfiguration_newMetricsConfig() {
        mDataBroker.addMetricsConfiguration(METRICS_CONFIG_BAR);

        waitForHandlerThreadToFinish();
        assertThat(mDataBroker.getSubscriptionMap()).hasSize(1);
        assertThat(mDataBroker.getSubscriptionMap()).containsKey(METRICS_CONFIG_BAR.getName());
        // there should be one data subscriber in the subscription list of METRICS_CONFIG_BAR
        assertThat(mDataBroker.getSubscriptionMap().get(METRICS_CONFIG_BAR.getName())).hasSize(1);
    }


    @Test
    public void testAddMetricsConfiguration_duplicateMetricsConfig_shouldDoNothing() {
        // this metrics config has already been added in setUp()
        mDataBroker.addMetricsConfiguration(METRICS_CONFIG_FOO);

        waitForHandlerThreadToFinish();
        assertThat(mDataBroker.getSubscriptionMap()).hasSize(1);
        assertThat(mDataBroker.getSubscriptionMap()).containsKey(METRICS_CONFIG_FOO.getName());
        assertThat(mDataBroker.getSubscriptionMap().get(METRICS_CONFIG_FOO.getName())).hasSize(1);
    }

    @Test
    public void testRemoveMetricsConfiguration_shouldRemoveAllAssociatedTasks() {
        mDataBroker.addMetricsConfiguration(METRICS_CONFIG_FOO);
        mDataBroker.addMetricsConfiguration(METRICS_CONFIG_BAR);
        ScriptExecutionTask taskWithMetricsConfigBar = new ScriptExecutionTask(
                new DataSubscriber(mDataBroker, METRICS_CONFIG_BAR, SUBSCRIBER_BAR, PRIORITY_HIGH),
                new Bundle(),
                SystemClock.elapsedRealtime());
        PriorityBlockingQueue<ScriptExecutionTask> taskQueue = mDataBroker.getTaskQueue();
        taskQueue.add(mHighPriorityTask); // associated with METRICS_CONFIG_FOO
        taskQueue.add(mLowPriorityTask); // associated with METRICS_CONFIG_FOO
        taskQueue.add(taskWithMetricsConfigBar); // associated with METRICS_CONFIG_BAR
        assertThat(taskQueue).hasSize(3);

        mDataBroker.removeMetricsConfiguration(METRICS_CONFIG_FOO);

        waitForHandlerThreadToFinish();
        assertThat(taskQueue).hasSize(1);
        assertThat(taskQueue.poll()).isEqualTo(taskWithMetricsConfigBar);
    }

    @Test
    public void testRemoveMetricsConfiguration_whenMetricsConfigNonExistent_shouldDoNothing() {
        mDataBroker.removeMetricsConfiguration(METRICS_CONFIG_BAR);

        waitForHandlerThreadToFinish();
        assertThat(mDataBroker.getSubscriptionMap()).hasSize(0);
    }

    private void waitForHandlerThreadToFinish() {
        assertWithMessage("handler not idle in %sms", TIMEOUT_MS)
                .that(mHandler.runWithScissors(() -> {}, TIMEOUT_MS)).isTrue();
    }
}
