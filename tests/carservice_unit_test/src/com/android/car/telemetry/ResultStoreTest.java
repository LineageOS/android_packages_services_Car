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

package com.android.car.telemetry;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.os.PersistableBundle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(MockitoJUnitRunner.class)
public class ResultStoreTest {
    private static final PersistableBundle TEST_INTERIM_BUNDLE = new PersistableBundle();
    private static final PersistableBundle TEST_FINAL_BUNDLE = new PersistableBundle();

    private File mTestRootDir;
    private File mTestInterimResultDir;
    private File mTestFinalResultDir;
    private ResultStore mResultStore;

    @Mock
    private Handler mMockHandler;
    @Mock
    private ResultStore.FinalResultCallback mMockFinalResultCallback;
    @Captor
    private ArgumentCaptor<PersistableBundle> mBundleCaptor;


    @Before
    public void setUp() throws Exception {
        // execute all handler posts immediately
        when(mMockHandler.post(any())).thenAnswer(i -> {
            ((Runnable) i.getArguments()[0]).run();
            return true;
        });
        TEST_INTERIM_BUNDLE.putString("test key", "interim value");
        TEST_FINAL_BUNDLE.putString("test key", "final value");

        mTestRootDir = Files.createTempDirectory("car_telemetry_test").toFile();
        mTestInterimResultDir = new File(mTestRootDir, ResultStore.INTERIM_RESULT_DIR);
        mTestFinalResultDir = new File(mTestRootDir, ResultStore.FINAL_RESULT_DIR);

        mResultStore = new ResultStore(mMockHandler, mMockHandler, mTestRootDir);
    }

    @Test
    public void testConstructor_shouldCreateResultsFolder() {
        // constructor is called in setUp()
        assertThat(mTestInterimResultDir.exists()).isTrue();
        assertThat(mTestFinalResultDir.exists()).isTrue();
    }

    @Test
    public void testConstructor_shouldLoadInterimResultsIntoMemory() throws Exception {
        String testInterimFileName = "test_file_1";
        writeBundleToFile(mTestInterimResultDir, testInterimFileName, TEST_INTERIM_BUNDLE);

        mResultStore = new ResultStore(mMockHandler, mMockHandler, mTestRootDir);

        // should compare value instead of reference
        assertThat(mResultStore.getInterimResult(testInterimFileName).toString())
                .isEqualTo(TEST_INTERIM_BUNDLE.toString());
    }

    @Test
    public void testShutdown_shouldWriteResultsToFileAndCheckContent() throws Exception {
        String testInterimFileName = "test_file_1";
        String testFinalFileName = "test_file_2";
        writeBundleToFile(mTestInterimResultDir, testInterimFileName, TEST_INTERIM_BUNDLE);
        writeBundleToFile(mTestFinalResultDir, testFinalFileName, TEST_FINAL_BUNDLE);

        mResultStore.shutdown();

        assertThat(new File(mTestInterimResultDir, testInterimFileName).exists()).isTrue();
        assertThat(new File(mTestFinalResultDir, testFinalFileName).exists()).isTrue();
        // the content check will need to be modified when data encryption is implemented
        PersistableBundle interimData =
                readBundleFromFile(mTestInterimResultDir, testInterimFileName);
        assertThat(interimData.toString()).isEqualTo(TEST_INTERIM_BUNDLE.toString());
        PersistableBundle finalData = readBundleFromFile(mTestFinalResultDir, testFinalFileName);
        assertThat(finalData.toString()).isEqualTo(TEST_FINAL_BUNDLE.toString());
    }

    @Test
    public void testGetFinalResult_whenNoData_shouldReceiveNull() throws Exception {
        String metricsConfigName = "my_metrics_config";

        mResultStore.getFinalResult(metricsConfigName, true, mMockFinalResultCallback);

        verify(mMockFinalResultCallback).onFinalResult(eq(metricsConfigName),
                mBundleCaptor.capture());
        assertThat(mBundleCaptor.getValue()).isNull();
    }

    @Test
    public void testGetFinalResult_whenDataCorrupt_shouldReceiveNull() throws Exception {
        String metricsConfigName = "my_metrics_config";
        Files.write(new File(mTestFinalResultDir, metricsConfigName).toPath(),
                "not a bundle".getBytes(StandardCharsets.UTF_8));

        mResultStore.getFinalResult(metricsConfigName, true, mMockFinalResultCallback);

        verify(mMockFinalResultCallback).onFinalResult(eq(metricsConfigName),
                mBundleCaptor.capture());
        assertThat(mBundleCaptor.getValue()).isNull();
    }

    @Test
    public void testGetFinalResult_whenDeleteFlagTrue_shouldDeleteData() throws Exception {
        String testFinalFileName = "my_metrics_config";
        writeBundleToFile(mTestFinalResultDir, testFinalFileName, TEST_FINAL_BUNDLE);

        mResultStore.getFinalResult(testFinalFileName, true, mMockFinalResultCallback);

        verify(mMockFinalResultCallback).onFinalResult(eq(testFinalFileName),
                mBundleCaptor.capture());
        // should compare value instead of reference
        assertThat(mBundleCaptor.getValue().toString()).isEqualTo(TEST_FINAL_BUNDLE.toString());
        assertThat(new File(mTestFinalResultDir, testFinalFileName).exists()).isFalse();
    }

    @Test
    public void testGetFinalResult_whenDeleteFlagFalse_shouldNotDeleteData() throws Exception {
        String testFinalFileName = "my_metrics_config";
        writeBundleToFile(mTestFinalResultDir, testFinalFileName, TEST_FINAL_BUNDLE);

        mResultStore.getFinalResult(testFinalFileName, false, mMockFinalResultCallback);

        verify(mMockFinalResultCallback).onFinalResult(eq(testFinalFileName),
                mBundleCaptor.capture());
        // should compare value instead of reference
        assertThat(mBundleCaptor.getValue().toString()).isEqualTo(TEST_FINAL_BUNDLE.toString());
        assertThat(new File(mTestFinalResultDir, testFinalFileName).exists()).isTrue();
    }

    @Test
    public void testPutFinalResult_shouldRemoveInterimResultFromMemory() throws Exception {
        String metricsConfigName = "my_metrics_config";
        mResultStore.putInterimResult(metricsConfigName, TEST_INTERIM_BUNDLE);

        mResultStore.putFinalResult(metricsConfigName, TEST_FINAL_BUNDLE);

        assertThat(mResultStore.getInterimResult(metricsConfigName)).isNull();
    }

    @Test
    public void testPutInterimResultAndShutdown_shouldReplaceExistingFile() throws Exception {
        String newKey = "new key";
        String newValue = "new value";
        String metricsConfigName = "my_metrics_config";
        writeBundleToFile(mTestInterimResultDir, metricsConfigName, TEST_INTERIM_BUNDLE);
        TEST_INTERIM_BUNDLE.putString(newKey, newValue);

        mResultStore.putInterimResult(metricsConfigName, TEST_INTERIM_BUNDLE);
        mResultStore.shutdown();

        PersistableBundle bundle = readBundleFromFile(mTestInterimResultDir, metricsConfigName);
        assertThat(bundle.getString(newKey)).isEqualTo(newValue);
        assertThat(bundle.toString()).isEqualTo(TEST_INTERIM_BUNDLE.toString());
    }

    @Test
    public void testPutFinalResultAndShutdown_shouldRemoveInterimResultFile() throws Exception {
        String metricsConfigName = "my_metrics_config";
        writeBundleToFile(mTestInterimResultDir, metricsConfigName, TEST_INTERIM_BUNDLE);

        mResultStore.putFinalResult(metricsConfigName, TEST_FINAL_BUNDLE);
        mResultStore.shutdown();

        assertThat(new File(mTestInterimResultDir, metricsConfigName).exists()).isFalse();
        assertThat(new File(mTestFinalResultDir, metricsConfigName).exists()).isTrue();
    }

    /**
     * Writes a persistable bundle to the result directory with the given directory and file name,
     * and verifies that it was successfully written.
     */
    private void writeBundleToFile(
            File dir, String fileName, PersistableBundle persistableBundle) throws Exception {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        persistableBundle.writeToStream(byteArrayOutputStream);
        Files.write(
                new File(dir, fileName).toPath(),
                byteArrayOutputStream.toByteArray());
        assertWithMessage("bundle is not written to the result directory")
                .that(new File(dir, fileName).exists()).isTrue();
    }

    /** Reads a persistable bundle from the given path. */
    private PersistableBundle readBundleFromFile(
            File dir, String fileName) throws Exception {
        File file = new File(dir, fileName);
        try (FileInputStream fis = new FileInputStream(file)) {
            return PersistableBundle.readFromStream(fis);
        }
    }
}
