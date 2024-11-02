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
package com.android.car.user;

import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.os.UserHandle;

public final class MockedUserHandleBuilder {
    private final UserHandle mUser;
    private UserHandleHelper mUserHandleHelper;

    private MockedUserHandleBuilder(UserHandleHelper userHandleHelper,
            @UserIdInt int userId) {
        mUserHandleHelper = userHandleHelper;
        mUser = UserHandle.of(userId);
        // enabled by default
        when(mUserHandleHelper.isEnabledUser(mUser)).thenReturn(true);
        when(mUserHandleHelper.getExistingUserHandle(userId)).thenReturn(mUser);
    }

    private MockedUserHandleBuilder setAdmin() {
        when(mUserHandleHelper.isAdminUser(mUser)).thenReturn(true);
        return this;
    }

    private MockedUserHandleBuilder setGuest() {
        when(mUserHandleHelper.isGuestUser(mUser)).thenReturn(true);
        return this;
    }

    private MockedUserHandleBuilder setEphemeral() {
        when(mUserHandleHelper.isEphemeralUser(mUser)).thenReturn(true);
        return this;
    }

    private MockedUserHandleBuilder setManagedProfile() {
        when(mUserHandleHelper.isManagedProfile(mUser)).thenReturn(true);
        return this;
    }

    private MockedUserHandleBuilder expectGettersFail() {
        RuntimeException exception = new RuntimeException("D'OH!");
        when(mUserHandleHelper.isAdminUser(mUser)).thenThrow(exception);
        when(mUserHandleHelper.isEnabledUser(mUser)).thenThrow(exception);
        when(mUserHandleHelper.isProfileUser(mUser)).thenThrow(exception);
        when(mUserHandleHelper.isInitializedUser(mUser)).thenThrow(exception);
        return this;
    }

    private UserHandle build() {
        return mUser;
    }

    /**
     * Creates a mocked {@link UserHandle} that represents a regular user that exists.
     *
     * <p>The following properties will be set for this user:
     * <ul>
     *     <li>{@link UserHandleHelper#isEnabledUser(UserHandle)} will return {@code true}.</li>
     *     <li>{@link UserHandleHelper#getExistingUserHandle(int)} will return the created user.
     *     </li>
     * </ul>
     *
     * @param userHandleHelper The {@link UserHandleHelper} mock to configure.
     * @param userId The user id of the regular user.
     * @return A {@link UserHandle} object representing the specified user.
     */
    public static UserHandle expectRegularUserExists(@NonNull UserHandleHelper userHandleHelper,
            @UserIdInt int userId) {
        return new MockedUserHandleBuilder(userHandleHelper, userId).build();
    }

    /**
     * Creates a mocked {@link UserHandle} that represents a user that exists but whose properties
     * cannot be retrieved via the provided {@link UserHandleHelper}.
     *
     * <p>The following methods of {@link UserHandleHelper} will throw a {@link RuntimeException}
     * when called with the created user:
     * <ul>
     *     <li>{@link UserHandleHelper#isAdminUser(UserHandle)}</li>
     *     <li>{@link UserHandleHelper#isEnabledUser(UserHandle)}</li>
     *     <li>{@link UserHandleHelper#isProfileUser(UserHandle)}</li>
     *     <li>{@link UserHandleHelper#isInitializedUser(UserHandle)}</li>
     * </ul>
     *
     * <p>{@link UserHandleHelper#getExistingUserHandle(int)} will return the created user.
     *
     * @param userHandleHelper The {@link UserHandleHelper} mock to configure.
     * @param userId The user id of the user.
     * @return A {@link UserHandle} object representing the specified user.
     */
    public static UserHandle expectUserExistsButGettersFail(
            @NonNull UserHandleHelper userHandleHelper, @UserIdInt int userId) {
        return new MockedUserHandleBuilder(userHandleHelper, userId).expectGettersFail().build();
    }

    /**
     * Creates a mocked {@link UserHandle} that represents the system user.
     *
     * <p>The following properties will be set for this user:
     * <ul>
     *     <li>{@link UserHandleHelper#isEnabledUser(UserHandle)} will return {@code true}.</li>
     *     <li>{@link UserHandleHelper#getExistingUserHandle(int)} will return the created user.
     *     </li>
     * </ul>
     *
     * @param userHandleHelper The {@link UserHandleHelper} mock to configure.
     * @param userId The user id of the system user.
     * @return A {@link UserHandle} object representing the system user.
     */
    public static UserHandle expectSystemUserExists(@NonNull UserHandleHelper userHandleHelper,
            @UserIdInt int userId) {
        return new MockedUserHandleBuilder(userHandleHelper, userId).build();
    }

    /**
     * Creates a mocked {@link UserHandle} that represents a managed profile.
     *
     * <p>The following properties will be set for this user:
     * <ul>
     *     <li>{@link UserHandleHelper#isEnabledUser(UserHandle)} will return {@code true}.</li>
     *     <li>{@link UserHandleHelper#isManagedProfile(UserHandle)} will return {@code true}.</li>
     *     <li>{@link UserHandleHelper#getExistingUserHandle(int)} will return the created user.
     *     </li>
     * </ul>
     *
     * @param userHandleHelper The {@link UserHandleHelper} mock to configure.
     * @param userId The user id of the managed profile.
     * @return A {@link UserHandle} object representing the managed profile.
     */
    public static UserHandle expectManagedProfileExists(@NonNull UserHandleHelper userHandleHelper,
            @UserIdInt int userId) {
        return new MockedUserHandleBuilder(userHandleHelper, userId).setManagedProfile()
                .build();
    }

    /**
     * Creates a mocked {@link UserHandle} that represents an admin user.
     *
     * <p>The following properties will be set for this user:
     * <ul>
     *     <li>{@link UserHandleHelper#isEnabledUser(UserHandle)} will return {@code true}.</li>
     *     <li>{@link UserHandleHelper#isAdminUser(UserHandle)} will return {@code true}.</li>
     *     <li>{@link UserHandleHelper#getExistingUserHandle(int)} will return the created user.
     *     </li>
     * </ul>
     *
     * @param userHandleHelper The {@link UserHandleHelper} mock to configure.
     * @param userId The user id of the admin user.
     * @return A {@link UserHandle} object representing the admin user.
     */
    public static UserHandle expectAdminUserExists(@NonNull UserHandleHelper userHandleHelper,
            @UserIdInt int userId) {
        return new MockedUserHandleBuilder(userHandleHelper, userId).setAdmin().build();
    }

    /**
     * Creates a mocked {@link UserHandle} that represents an ephemeral user.
     *
     * <p>The following properties will be set for this user:
     * <ul>
     *     <li>{@link UserHandleHelper#isEnabledUser(UserHandle)} will return {@code true}.</li>
     *     <li>{@link UserHandleHelper#isEphemeralUser(UserHandle)} will return {@code true}.</li>
     *     <li>{@link UserHandleHelper#getExistingUserHandle(int)} will return the created user.
     *     </li>
     * </ul>
     *
     * @param userHandleHelper The {@link UserHandleHelper} mock to configure.
     * @param userId The user id of the ephemeral user.
     * @return A {@link UserHandle} object representing the ephemeral user.
     */
    public static UserHandle expectEphemeralUserExists(@NonNull UserHandleHelper userHandleHelper,
            @UserIdInt int userId) {
        return new MockedUserHandleBuilder(userHandleHelper, userId).setEphemeral().build();
    }

    /**
     * Creates a mocked {@link UserHandle} that represents a guest user.
     *
     * <p>The following properties will be set for this user:
     * <ul>
     *     <li>{@link UserHandleHelper#isEnabledUser(UserHandle)} will return {@code true}.</li>
     *     <li>{@link UserHandleHelper#isGuestUser(UserHandle)} will return {@code true}.</li>
     *     <li>{@link UserHandleHelper#getExistingUserHandle(int)} will return the created user.
     *     </li>
     *     <li>{@link UserHandleHelper#isEphemeralUser(UserHandle)} will return {@code isEphemeral}.
     *     </li>
     * </ul>
     *
     * @param userHandleHelper The {@link UserHandleHelper} mock to configure.
     * @param userId The user id of the guest user.
     * @param isEphemeral Whether the guest user is ephemeral.
     * @return A {@link UserHandle} object representing the guest user.
     */
    public static UserHandle expectGuestUserExists(@NonNull UserHandleHelper userHandleHelper,
            @UserIdInt int userId, boolean isEphemeral) {
        if (isEphemeral) {
            return new MockedUserHandleBuilder(userHandleHelper, userId).setGuest()
                    .setEphemeral().build();
        }
        return new MockedUserHandleBuilder(userHandleHelper, userId).setGuest().build();
    }
}
