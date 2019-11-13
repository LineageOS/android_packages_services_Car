/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.car.CarUxRestrictionsManagerService.CONFIG_FILENAME_PRODUCTION;
import static com.android.car.CarUxRestrictionsManagerService.CONFIG_FILENAME_STAGED;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.car.VehiclePropertyIds;
import android.car.drivingstate.CarDrivingStateEvent;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsConfiguration;
import android.car.drivingstate.CarUxRestrictionsConfiguration.Builder;
import android.car.drivingstate.ICarDrivingStateChangeListener;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.automotive.vehicle.V2_0.VehicleProperty;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.JsonReader;
import android.util.JsonWriter;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.car.systeminterface.SystemInterface;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class CarUxRestrictionsManagerServiceTest {
    private CarUxRestrictionsManagerService mService;

    @Mock
    private CarDrivingStateService mMockDrivingStateService;
    @Mock
    private CarPropertyService mMockCarPropertyService;
    @Mock
    private SystemInterface mMockSystemInterface;

    private Context mSpyContext;

    private File mTempSystemCarDir;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        // Spy context because service needs to access xml resource during init.
        mSpyContext = spy(InstrumentationRegistry.getTargetContext());

        CarLocalServices.removeServiceForTest(SystemInterface.class);
        CarLocalServices.addService(SystemInterface.class, mMockSystemInterface);

        mTempSystemCarDir = Files.createTempDirectory("uxr_test").toFile();
        when(mMockSystemInterface.getSystemCarDir()).thenReturn(mTempSystemCarDir);

        setUpMockParkedState();

        mService = new CarUxRestrictionsManagerService(mSpyContext,
                mMockDrivingStateService, mMockCarPropertyService);
    }

    @After
    public void tearDown() throws Exception {
        mService = null;
        CarLocalServices.removeAllServices();
    }

    @Test
    public void testSaveConfig_WriteStagedFile() throws Exception {
        File staged = setupMockFile(CONFIG_FILENAME_STAGED, null);
        CarUxRestrictionsConfiguration config = createEmptyConfig();

        assertTrue(mService.saveUxRestrictionsConfigurationForNextBoot(Arrays.asList(config)));
        assertTrue(readFile(staged).equals(config));
        // Verify prod config file was not created.
        assertFalse(new File(mTempSystemCarDir, CONFIG_FILENAME_PRODUCTION).exists());
    }

    @Test
    public void testSaveConfig_ReturnFalseOnException() throws Exception {
        CarUxRestrictionsConfiguration spyConfig = spy(createEmptyConfig());
        doThrow(new IOException()).when(spyConfig).writeJson(any(JsonWriter.class));

        assertFalse(mService.saveUxRestrictionsConfigurationForNextBoot(Arrays.asList(spyConfig)));
    }

    @Test
    public void testLoadConfig_UseDefaultConfigWhenNoSavedConfigFileNoXml() {
        // Prevent R.xml.car_ux_restrictions_map being returned.
        Resources spyResources = spy(mSpyContext.getResources());
        doReturn(spyResources).when(mSpyContext).getResources();
        doReturn(null).when(spyResources).getXml(anyInt());

        for (CarUxRestrictionsConfiguration config : mService.loadConfig()) {
            assertTrue(config.equals(mService.createDefaultConfig(config.getPhysicalPort())));
        }
    }

    @Test
    public void testLoadConfig_UseXml() throws IOException, XmlPullParserException {
        List<CarUxRestrictionsConfiguration> expected =
                CarUxRestrictionsConfigurationXmlParser.parse(
                        mSpyContext, R.xml.car_ux_restrictions_map);

        List<CarUxRestrictionsConfiguration> actual = mService.loadConfig();

        assertTrue(actual.equals(expected));
    }

    @Test
    public void testLoadConfig_UseProdConfig() throws IOException {
        CarUxRestrictionsConfiguration expected = createEmptyConfig();
        setupMockFile(CONFIG_FILENAME_PRODUCTION, expected);

        CarUxRestrictionsConfiguration actual = mService.loadConfig().get(0);

        assertTrue(actual.equals(expected));
    }

    @Test
    public void testLoadConfig_PromoteStagedFileWhenParked() throws Exception {
        CarUxRestrictionsConfiguration expected = createEmptyConfig();
        // Staged file contains actual config. Ignore prod since it should be overwritten by staged.
        File staged = setupMockFile(CONFIG_FILENAME_STAGED, expected);
        // Set up temp file for prod to avoid polluting other tests.
        setupMockFile(CONFIG_FILENAME_PRODUCTION, null);

        CarUxRestrictionsConfiguration actual = mService.loadConfig().get(0);

        // Staged file should be moved as production.
        assertFalse(staged.exists());
        assertTrue(actual.equals(expected));
    }

    @Test
    public void testLoadConfig_NoPromoteStagedFileWhenMoving() throws Exception {
        CarUxRestrictionsConfiguration expected = createEmptyConfig();
        File staged = setupMockFile(CONFIG_FILENAME_STAGED, null);
        // Prod file contains actual config. Ignore staged since it should not be promoted.
        setupMockFile(CONFIG_FILENAME_PRODUCTION, expected);

        setUpMockDrivingState();
        CarUxRestrictionsConfiguration actual = mService.loadConfig().get(0);

        // Staged file should be untouched.
        assertTrue(staged.exists());
        assertTrue(actual.equals(expected));
    }

    @Test
    public void testValidateConfigs_SingleConfigNotSetPort() throws Exception {
        CarUxRestrictionsConfiguration config = createEmptyConfig();
        mService.validateConfigs(Arrays.asList(config));
    }

    @Test
    public void testValidateConfigs_SingleConfigWithPort() throws Exception {
        CarUxRestrictionsConfiguration config = createEmptyConfig((byte) 0);
        mService.validateConfigs(Arrays.asList(config));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateConfigs_MultipleConfigsMustHavePort() throws Exception {
        mService.validateConfigs(Arrays.asList(createEmptyConfig(null), createEmptyConfig(null)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateConfigs_MultipleConfigsMustHaveUniquePort() throws Exception {
        mService.validateConfigs(Arrays.asList(
                createEmptyConfig((byte) 0), createEmptyConfig((byte) 0)));
    }

    @Test
    public void testGetCurrentUxRestrictions_UnknownDisplayId_ReturnsFullRestrictions()
            throws Exception {
        mService.init();
        CarUxRestrictions restrictions = mService.getCurrentUxRestrictions(/* displayId= */ 10);
        CarUxRestrictions expected = new CarUxRestrictions.Builder(
                /*reqOpt= */ true,
                CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED,
                SystemClock.elapsedRealtimeNanos()).build();
        assertTrue(restrictions.toString(), expected.isSameRestrictions(restrictions));
    }

    // This test only involves calling a few methods and should finish very quickly. If it doesn't
    // finish in 20s, we probably encountered a deadlock.
    @Test(timeout = 20000)
    public void testInitService_NoDeadlockWithCarDrivingStateService()
            throws Exception {

        CarDrivingStateService drivingStateService = new CarDrivingStateService(mSpyContext,
                mMockCarPropertyService);
        CarUxRestrictionsManagerService uxRestrictionsService = new CarUxRestrictionsManagerService(
                mSpyContext, drivingStateService, mMockCarPropertyService);

        CountDownLatch dispatchingStartedSignal = new CountDownLatch(1);
        CountDownLatch initCompleteSignal = new CountDownLatch(1);

        // A deadlock can exist when the dispatching of a listener is synchronized. For instance,
        // the CarUxRestrictionsManagerService#init() method registers a callback like this one. The
        // deadlock risk occurs if:
        // 1. CarUxRestrictionsManagerService has registered a listener with CarDrivingStateService
        // 2. A synchronized method of CarUxRestrictionsManagerService starts to run
        // 3. While the method from (2) is running, a property event occurs on a different thread
        //    that triggers a drive state event in CarDrivingStateService. If CarDrivingStateService
        //    handles the property event in a synchronized method, then CarDrivingStateService is
        //    locked. The listener from (1) will wait until the lock on
        //    CarUxRestrictionsManagerService is released.
        // 4. The synchronized method from (2) attempts to access CarDrivingStateService. For
        //    example, the implementation below attempts to read the restriction mode.
        //
        // In the above steps, both CarUxRestrictionsManagerService and CarDrivingStateService are
        // locked and waiting on each other, hence the deadlock.
        drivingStateService.registerDrivingStateChangeListener(
                new ICarDrivingStateChangeListener.Stub() {
                    @Override
                    public void onDrivingStateChanged(CarDrivingStateEvent event)
                            throws RemoteException {
                        // EVENT 2 [new thread]: this callback is called from within
                        // handlePropertyEvent(), which might (but shouldn't) lock
                        // CarDrivingStateService

                        // Notify that the dispatching process has started
                        dispatchingStartedSignal.countDown();

                        try {
                            // EVENT 3b [new thread]: Wait until init() has finished. If these
                            // threads don't have lock dependencies, there is no reason there
                            // would be an issue with waiting.
                            //
                            // In the real world, this wait could represent a long-running
                            // task, or hitting the below line that attempts to access the
                            // CarUxRestrictionsManagerService (which might be locked while init
                            // () is running).
                            //
                            // If there is a deadlock while waiting for init to complete, we will
                            // never progress past this line.
                            initCompleteSignal.await();
                        } catch (InterruptedException e) {
                            Assert.fail("onDrivingStateChanged thread interrupted");
                        }

                        // Attempt to access CarUxRestrictionsManagerService. If
                        // CarUxRestrictionsManagerService is locked because it is doing its own
                        // work, then this will wait.
                        //
                        // This line won't execute in the deadlock flow. However, it is an example
                        // of a real-world piece of code that would serve the same role as the above
                        uxRestrictionsService.getCurrentUxRestrictions();
                    }
                });

        // EVENT 1 [new thread]: handlePropertyEvent() is called, which locks CarDrivingStateService
        // Ideally CarPropertyService would trigger the change event, but since that is mocked
        // we manually trigger the event. This event is what eventually triggers the dispatch to
        // ICarDrivingStateChangeListener that was defined above.
        Runnable propertyChangeEventRunnable =
                () -> drivingStateService.handlePropertyEvent(
                        new CarPropertyEvent(CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE,
                                new CarPropertyValue<>(
                                        VehiclePropertyIds.PERF_VEHICLE_SPEED, 0, 100f)));
        Thread thread = new Thread(propertyChangeEventRunnable);
        thread.start();

        // Wait until propertyChangeEventRunnable has triggered and the
        // ICarDrivingStateChangeListener callback declared above started to run.
        dispatchingStartedSignal.await();

        // EVENT 3a [main thread]: init() is called, which locks CarUxRestrictionsManagerService
        // If init() is synchronized, thereby locking CarUxRestrictionsManagerService, and it
        // internally attempts to access CarDrivingStateService, and if CarDrivingStateService has
        // been locked because of the above listener, then both classes are locked and waiting on
        // each other, so we would encounter a deadlock.
        uxRestrictionsService.init();

        // If there is a deadlock in init(), then this will never be called
        initCompleteSignal.countDown();

        // wait for thread to join to leave in a deterministic state
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Assert.fail("Thread failed to join");
        }
    }

    // This test only involves calling a few methods and should finish very quickly. If it doesn't
    // finish in 20s, we probably encountered a deadlock.
    @Test(timeout = 20000)
    public void testSetUxRChangeBroadcastEnabled_NoDeadlockWithCarDrivingStateService()
            throws Exception {

        CarDrivingStateService drivingStateService = new CarDrivingStateService(mSpyContext,
                mMockCarPropertyService);
        CarUxRestrictionsManagerService uxRestrictionService = new CarUxRestrictionsManagerService(
                mSpyContext, drivingStateService, mMockCarPropertyService);

        CountDownLatch dispatchingStartedSignal = new CountDownLatch(1);
        CountDownLatch initCompleteSignal = new CountDownLatch(1);

        // See testInitService_NoDeadlockWithCarDrivingStateService for details on why a deadlock
        // may occur. This test could fail for the same reason, except the callback we register here
        // is purely to introduce a delay, and the deadlock actually happens inside the callback
        // that CarUxRestrictionsManagerService#init() registers internally.
        drivingStateService.registerDrivingStateChangeListener(
                new ICarDrivingStateChangeListener.Stub() {
                    @Override
                    public void onDrivingStateChanged(CarDrivingStateEvent event)
                            throws RemoteException {
                        // EVENT 2 [new thread]: this callback is called from within
                        // handlePropertyEvent(), which might (but shouldn't) lock
                        // CarDrivingStateService

                        // Notify that the dispatching process has started
                        dispatchingStartedSignal.countDown();

                        try {
                            // EVENT 3b [new thread]: Wait until init() has finished. If these
                            // threads don't have lock dependencies, there is no reason there
                            // would be an issue with waiting.
                            //
                            // In the real world, this wait could represent a long-running
                            // task, or hitting the line inside
                            // CarUxRestrictionsManagerService#init()'s internal registration
                            // that attempts to access the CarUxRestrictionsManagerService (which
                            // might be locked while init() is running).
                            //
                            // If there is a deadlock while waiting for init to complete, we will
                            // never progress past this line.
                            initCompleteSignal.await();
                        } catch (InterruptedException e) {
                            Assert.fail("onDrivingStateChanged thread interrupted");
                        }
                    }
                });

        // The init() method internally registers a callback to CarDrivingStateService
        uxRestrictionService.init();

        // EVENT 1 [new thread]: handlePropertyEvent() is called, which locks CarDrivingStateService
        // Ideally CarPropertyService would trigger the change event, but since that is mocked
        // we manually trigger the event. This event eventually triggers the dispatch to
        // ICarDrivingStateChangeListener that was defined above and a dispatch to the registration
        // that CarUxRestrictionsManagerService internally made to CarDrivingStateService in
        // CarUxRestrictionsManagerService#init().
        Runnable propertyChangeEventRunnable =
                () -> drivingStateService.handlePropertyEvent(
                        new CarPropertyEvent(CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE,
                                new CarPropertyValue<>(
                                        VehiclePropertyIds.PERF_VEHICLE_SPEED, 0, 100f)));
        Thread thread = new Thread(propertyChangeEventRunnable);
        thread.start();

        // Wait until propertyChangeEventRunnable has triggered and the
        // ICarDrivingStateChangeListener callback declared above started to run.
        dispatchingStartedSignal.await();

        // EVENT 3a [main thread]: a synchronized method is called, which locks
        // CarUxRestrictionsManagerService
        //
        // Any synchronized method that internally accesses CarDrivingStateService could encounter a
        // deadlock if the above setup locks CarDrivingStateService.
        uxRestrictionService.setUxRChangeBroadcastEnabled(true);

        // If there is a deadlock in init(), then this will never be called
        initCompleteSignal.countDown();

        // wait for thread to join to leave in a deterministic state
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Assert.fail("Thread failed to join");
        }
    }


    private CarUxRestrictionsConfiguration createEmptyConfig() {
        return createEmptyConfig(null);
    }

    private CarUxRestrictionsConfiguration createEmptyConfig(Byte port) {
        Builder builder = new Builder();
        if (port != null) {
            builder.setPhysicalPort(port);
        }
        return builder.build();
    }

    private void setUpMockParkedState() {
        when(mMockDrivingStateService.getCurrentDrivingState()).thenReturn(
                new CarDrivingStateEvent(CarDrivingStateEvent.DRIVING_STATE_PARKED, 0));

        CarPropertyValue<Float> speed = new CarPropertyValue<>(VehicleProperty.PERF_VEHICLE_SPEED,
                0, 0f);
        when(mMockCarPropertyService.getProperty(VehicleProperty.PERF_VEHICLE_SPEED, 0))
                .thenReturn(speed);
    }

    private void setUpMockDrivingState() {
        when(mMockDrivingStateService.getCurrentDrivingState()).thenReturn(
                new CarDrivingStateEvent(CarDrivingStateEvent.DRIVING_STATE_MOVING, 0));

        CarPropertyValue<Float> speed = new CarPropertyValue<>(VehicleProperty.PERF_VEHICLE_SPEED,
                0, 30f);
        when(mMockCarPropertyService.getProperty(VehicleProperty.PERF_VEHICLE_SPEED, 0))
                .thenReturn(speed);
    }

    private File setupMockFile(String filename, CarUxRestrictionsConfiguration config)
            throws IOException {
        File f = new File(mTempSystemCarDir, filename);
        assertTrue(f.createNewFile());

        if (config != null) {
            try (JsonWriter writer = new JsonWriter(
                    new OutputStreamWriter(new FileOutputStream(f), "UTF-8"))) {
                writer.beginArray();
                config.writeJson(writer);
                writer.endArray();
            }
        }
        return f;
    }

    private CarUxRestrictionsConfiguration readFile(File file) throws Exception {
        try (JsonReader reader = new JsonReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            CarUxRestrictionsConfiguration config = null;
            reader.beginArray();
            config = CarUxRestrictionsConfiguration.readJson(reader);
            reader.endArray();
            return config;
        }
    }
}
