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
package android.car.test.mocks;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.util.Log;

import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;
import com.android.internal.util.Preconditions;

import org.junit.After;
import org.junit.Before;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.mockito.session.MockitoSessionBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for tests that must use {@link com.android.dx.mockito.inline.extended.ExtendedMockito}
 * to mock static classes and final methods.
 *
 * <p><b>Note: </b>when using this class, you must include the following
 * dependencies on {@code Android.bp} (or {@code Android.mk}:
 * <pre><code>
    jni_libs: [
        "libdexmakerjvmtiagent",
        "libstaticjvmtiagent",
    ],

   LOCAL_JNI_SHARED_LIBRARIES := \
      libdexmakerjvmtiagent \
      libstaticjvmtiagent \
 *  </code></pre>
 */
public abstract class AbstractExtendMockitoTestCase {

    private static final boolean VERBOSE = false;
    private static final String TAG = AbstractExtendMockitoTestCase.class.getSimpleName();

    private final List<Class<?>> mStaticSpiedClasses = new ArrayList<>();
    private final List<Class<?>> mStaticMockedClasses = new ArrayList<>();

    private MockitoSession mSession;

    @Before
    public final void startSession() {
        if (VERBOSE) Log.v(TAG, getLogPrefix() + "startSession()");
        mSession = newSessionBuilder().startMocking();
    }

    @After
    public final void finishSession() {
        if (VERBOSE) Log.v(TAG, getLogPrefix() + "finishSession()");
        mSession.finishMocking();
    }

    /**
     * Subclasses can use this method to initialize the Mockito session that's started before every
     * test on {@link #startSession()}.
     *
     * <p>Typically, it should be overridden when mocking static methods.
     */
    protected void onSessionBuilder(@NonNull CustomMockitoSessionBuilder session) {
        if (VERBOSE) Log.v(TAG, getLogPrefix() + "onSessionBuilder()");
    }

    /**
     * Changes the value of the session created by
     * {@link #onSessionBuilder(CustomMockitoSessionBuilder)}.
     *
     * <p>By default it's set to {@link Strictness.LENIENT}, but subclasses can overwrite this
     * method to change the behavior.
     */
    @NonNull
    protected Strictness getSessionStrictness() {
        return Strictness.LENIENT;
    }

    /**
     * Mocks a call to {@link ActivityManager#getCurrentUser()}.
     *
     * @param userId result of such call
     *
     * @throws IllegalStateException if class didn't override {@link #newSessionBuilder()} and
     * called {@code spyStatic(ActivityManager.class)} on the session passed to it.
     */
    protected final void mockGetCurrentUser(@UserIdInt int userId) {
        if (VERBOSE) Log.v(TAG, getLogPrefix() + "mockGetCurrentUser(" + userId + ")");
        assertSpied(ActivityManager.class);
        doReturn(userId).when(() -> ActivityManager.getCurrentUser());
    }

    @NonNull
    private MockitoSessionBuilder newSessionBuilder() {
        StaticMockitoSessionBuilder builder = mockitoSession()
                .strictness(getSessionStrictness());
        onSessionBuilder(new CustomMockitoSessionBuilder(builder, mStaticSpiedClasses,
                mStaticMockedClasses));
        return builder.initMocks(this);
    }

    private String getLogPrefix() {
        return getClass().getSimpleName() + ".";
    }

    private void assertSpied(Class<?> clazz) {
        Preconditions.checkArgument(mStaticSpiedClasses.contains(clazz),
                "did not call spyStatic() on %s", clazz.getName());
    }

    /**
     * Custom {@code MockitoSessionBuilder} used to make sure some pre-defined mock stations
     * (like {@link AbstractExtendMockitoTestCase#mockGetCurrentUser(int)} fail if the test case
     * didn't explicitly set it to spy / mock the required classes.
     *
     * <p><b>NOTE: </b>for now it only provides simple {@link #mockStatic(Class)} and
     * {@link #spyStatic(Class)}, but more methods (as provided by
     * {@link StaticMockitoSessionBuilder}) could be provided as needed.
     */
    public static final class CustomMockitoSessionBuilder {
        private final StaticMockitoSessionBuilder mBuilder;
        private final List<Class<?>> mStaticSpiedClasses;
        private final List<Class<?>> mStaticMockedClasses;

        private CustomMockitoSessionBuilder(StaticMockitoSessionBuilder builder,
                List<Class<?>> staticSpiedClasses, List<Class<?>> staticMockedClasses) {
            mBuilder = builder;
            mStaticSpiedClasses = staticSpiedClasses;
            mStaticMockedClasses = staticMockedClasses;
        }

        /**
         * Same as {@link StaticMockitoSessionBuilder#mockStatic(Class)}.
         */
        public <T> CustomMockitoSessionBuilder mockStatic(Class<T> clazz) {
            mStaticMockedClasses.add(clazz);
            mBuilder.mockStatic(clazz);
            return this;
        }

        /**
         * Same as {@link StaticMockitoSessionBuilder#spyStatic(Class)}.
         */
        public <T> CustomMockitoSessionBuilder spyStatic(Class<T> clazz) {
            mStaticSpiedClasses.add(clazz);
            mBuilder.spyStatic(clazz);
            return this;
        }
    }
}
