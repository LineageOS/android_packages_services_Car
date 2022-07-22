/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.car.fakevhal;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.expectThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

@RunWith(AndroidJUnit4.class)
public class FakeVhalConfigParserUnitTest {
    private FakeVhalConfigParser mFakeVhalConfigParser;

    @Before
    public void setUp() {
        mFakeVhalConfigParser = new FakeVhalConfigParser();
    }

    @Test
    public void testConfigFileNotExist() throws Exception {
        File tempFile = new File("NotExist.json");

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class,
                () -> mFakeVhalConfigParser.parseJsonConfig(tempFile, false));

        assertThat(thrown).hasMessageThat().contains("Missing required file");
    }

    @Test
    public void testConfigFileNotExistAndFileIsOptional() throws Exception {
        File tempFile = new File("NotExist.json");

        assertThat(mFakeVhalConfigParser.parseJsonConfig(tempFile, true)).isEmpty();
    }

    @Test
    public void testConfigFileHaveInvalidJsonObject() throws Exception {
        String fileContent = "This is a config file.";
        File tempFile = createTempFile(fileContent);

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class,
                () -> mFakeVhalConfigParser.parseJsonConfig(tempFile, true));

        assertThat(thrown).hasMessageThat()
            .contains("This file does not contain a valid JSONObject.");
    }

    @Test
    public void testConfigFileRootIsNotArray() throws Exception {
        String jsonString = "{\"properties\": 123}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, true));

        assertThat(thrown).hasMessageThat().contains("field value is not a valid JSONArray.");
    }

    @Test
    public void testConfigFileRootHasElementIsNotJsonObject() throws Exception {
        String jsonString = "{\"properties\": [{}, 123]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, true));

        assertThat(thrown).hasMessageThat().contains("properties array has an invalid JSON element"
                + " at index 1");
    }

    private File createTempFile(String fileContent) throws Exception {
        File tempFile = File.createTempFile("DefaultProperties", ".json");
        tempFile.deleteOnExit();
        FileOutputStream os = new FileOutputStream(tempFile);
        os.write(fileContent.getBytes());
        return tempFile;
    }
}
