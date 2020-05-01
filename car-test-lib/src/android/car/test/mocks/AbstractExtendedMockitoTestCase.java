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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.notNull;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.os.UserManager;
import android.util.Log;
import android.util.Slog;

import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;
import com.android.internal.util.Preconditions;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.mockito.MockitoSession;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.quality.Strictness;
import org.mockito.session.MockitoSessionBuilder;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for tests that must use {@link com.android.dx.mockito.inline.extended.ExtendedMockito}
 * to mock static classes and final methods.
 *
 * <p><b>Note: </b> this class automatically spy on {@link Log} and {@link Slog} and fail tests that
 * all any of their {@code wtf()} methods. If a test is expect to call {@code wtf()}, it should be
 * annotated with {@link ExpectWtf}.
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
public abstract class AbstractExtendedMockitoTestCase {

    private static final boolean VERBOSE = false;
    private static final String TAG = AbstractExtendedMockitoTestCase.class.getSimpleName();

    private final List<Class<?>> mStaticSpiedClasses = new ArrayList<>();
    private final List<Class<?>> mStaticMockedClasses = new ArrayList<>();

    // Tracks (S)Log.wtf() calls made during code execution, then used on verifyWtfNeverLogged()
    private final List<RuntimeException> mWtfs = new ArrayList<>();

    private MockitoSession mSession;

    @Rule
    public final WtfCheckerRule mWtfCheckerRule = new WtfCheckerRule();

    @Before
    public final void startSession() {
        if (VERBOSE) Log.v(TAG, getLogPrefix() + "startSession()");
        mSession = newSessionBuilder().startMocking();

        interceptWtfCalls();
    }

    @After
    public final void finishSession() {
        if (VERBOSE) Log.v(TAG, getLogPrefix() + "finishSession()");
        if (mSession != null) {
            mSession.finishMocking();
        }
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
        AndroidMockitoHelper.mockAmGetCurrentUser(userId);
    }

    /**
     * Mocks a call to {@link UserManager#isHeadlessSystemUserMode()}.
     *
     * @param mode result of such call
     *
     * @throws IllegalStateException if class didn't override {@link #newSessionBuilder()} and
     * called {@code spyStatic(UserManager.class)} on the session passed to it.
     */
    protected final void mockIsHeadlessSystemUserMode(boolean mode) {
        if (VERBOSE) Log.v(TAG, getLogPrefix() + "mockIsHeadlessSystemUserMode(" + mode + ")");
        assertSpied(UserManager.class);
        AndroidMockitoHelper.mockUmIsHeadlessSystemUserMode(mode);
    }

    protected void interceptWtfCalls() {
        doAnswer((invocation) -> {
            return addWtf(invocation);
        }).when(() -> Log.wtf(anyString(), anyString()));
        doAnswer((invocation) -> {
            return addWtf(invocation);
        }).when(() -> Log.wtf(anyString(), anyString(), notNull()));
        doAnswer((invocation) -> {
            return addWtf(invocation);
        }).when(() -> Slog.wtf(anyString(), anyString()));
        doAnswer((invocation) -> {
            return addWtf(invocation);
        }).when(() -> Slog.wtf(anyString(), anyString(), notNull()));
    }

    private Object addWtf(InvocationOnMock invocation) {
        String message = "Called " + invocation;
        Log.d(TAG, message); // Log always, as some test expect it
        mWtfs.add(new IllegalStateException(message));
        return null;
    }

    private void verifyWtfLogged() {
        Preconditions.checkState(!mWtfs.isEmpty(), "no wtf() called");
    }

    private void verifyWtfNeverLogged() {
        int size = mWtfs.size();

        switch (size) {
            case 0:
                return;
            case 1:
                throw mWtfs.get(0);
            default:
                StringBuilder msg = new StringBuilder("wtf called ").append(size).append(" times")
                        .append(": ").append(mWtfs);
                throw new AssertionError(msg.toString());
        }
    }

    @NonNull
    private MockitoSessionBuilder newSessionBuilder() {
        StaticMockitoSessionBuilder builder = mockitoSession()
                .strictness(getSessionStrictness());
        CustomMockitoSessionBuilder customBuilder =
                new CustomMockitoSessionBuilder(builder, mStaticSpiedClasses, mStaticMockedClasses)
                    .spyStatic(Log.class)
                    .spyStatic(Slog.class);
        onSessionBuilder(customBuilder);
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
     * (like {@link AbstractExtendedMockitoTestCase#mockGetCurrentUser(int)} fail if the test case
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

        // TODO(b/148403316): this is only used to mock Settings.Global / Settings.Secure, and using
        // spy on such occurrence doesn't work - hopefully we can get rid of this method by
        // refactoring how Settings are mocked.
        /**
         * Same as {@link StaticMockitoSessionBuilder#mockStatic(Class)}.
         */
        public <T> CustomMockitoSessionBuilder mockStatic(Class<T> clazz) {
            Preconditions.checkState(!mStaticMockedClasses.contains(clazz),
                    "already called mockStatic() on " + clazz);
            mStaticMockedClasses.add(clazz);
            mBuilder.mockStatic(clazz);
            return this;
        }

        /**
         * Same as {@link StaticMockitoSessionBuilder#spyStatic(Class)}.
         */
        public <T> CustomMockitoSessionBuilder spyStatic(Class<T> clazz) {
            Preconditions.checkState(!mStaticSpiedClasses.contains(clazz),
                    "already called spyStatic() on " + clazz);
            mStaticSpiedClasses.add(clazz);
            mBuilder.spyStatic(clazz);
            return this;
        }
    }

    private final class WtfCheckerRule implements TestRule {

        @Override
        public Statement apply(Statement base, Description description) {
            return new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    String testName = description.getMethodName();

                    if (VERBOSE) Log.v(TAG, "running " + testName);
                    base.evaluate();

                    Method testMethod = AbstractExtendedMockitoTestCase.this.getClass()
                            .getMethod(testName);
                    ExpectWtf expectWtfAnnotation = testMethod.getAnnotation(ExpectWtf.class);

                    if (expectWtfAnnotation != null) {
                        if (VERBOSE) Log.v(TAG, "expecting wtf()");
                        verifyWtfLogged();
                    } else {
                        if (VERBOSE) Log.v(TAG, "NOT expecting wtf()");
                        verifyWtfNeverLogged();
                    }
                }
            };
        }
    }

    /**
     * Annotation used on test methods that are expect to call {@code wtf()} methods on {@link Log}
     * or {@link Slog} - if such methods are not annotated with this annotation, they will fail.
     */
    @Retention(RUNTIME)
    @Target({METHOD})
    public static @interface ExpectWtf {
    }
}
