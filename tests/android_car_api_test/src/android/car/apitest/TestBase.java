/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.car.apitest;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;

/**
 * Base class for all tests in this package (even those that don't need to connect to Car).
 */
abstract class TestBase extends TmpBaseTestCase {

    protected final Context getContext() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }
}

// TODO(b/155447204): inline its methods
/**
 * Temporary class used to convert JUnit assertions to Truth - will be removed in a follow-up CL
 */
abstract class TmpBaseTestCase {

    public static void assertTrue(boolean expression) {
        assertThat(expression).isTrue();
    }

    public static void assertFalse(boolean expression) {
        assertThat(expression).isFalse();
    }

    public static void assertEquals(Object expected, Object actual) {
        assertThat(actual).isEqualTo(expected);
    }

    public static void assertEquals(int expected, int actual) {
        assertThat(actual).isEqualTo(expected);
    }

    public static void assertEquals(String message, Object expected, Object actual) {
        assertWithMessage(message).that(actual).isEqualTo(expected);
    }

    public static void assertNotSame(Object expected, Object actual) {
        assertThat(actual).isNotSameAs(expected);
    }

    public static void assertNotNull(Object object) {
        assertThat(object).isNotNull();
    }

    public static void assertNull(Object object) {
        assertThat(object).isNull();
    }

    public static void assertArrayEquals(int[] expected, int[] actual) {
        Assert.assertArrayEquals(expected, actual);
    }

    public static void assertArrayEquals(Object[] expected, Object[] actual) {
        Assert.assertArrayEquals(expected, actual);
    }
}
