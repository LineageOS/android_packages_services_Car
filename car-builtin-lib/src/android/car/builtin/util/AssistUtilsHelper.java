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

package android.car.builtin.util;

import static android.service.voice.VoiceInteractionSession.SHOW_SOURCE_PUSH_TO_TALK;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Bundle;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.AssistUtils;
import com.android.internal.app.IVoiceInteractionSessionShowCallback;

import java.util.Objects;

/**
 * Class to wrap {@link AssistUtils}.
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class AssistUtilsHelper {

    private static final String TAG = AssistUtilsHelper.class.getSimpleName();

    @VisibleForTesting
    static final String EXTRA_CAR_PUSH_TO_TALK =
            "com.android.car.input.EXTRA_CAR_PUSH_TO_TALK";

    /**
     * Shows the {@link android.service.voice.VoiceInteractionSession.SHOW_SOURCE_PUSH_TO_TALK}
     * session for active service, if the assistant component is active for the current user.
     *
     * @return whether the assistant component is active for the current user.
     */
    public static boolean showPushToTalkSessionForActiveService(@NonNull Context context,
            @NonNull VoiceInteractionSessionShowCallbackHelper callback) {
        Objects.requireNonNull(callback, "On shown callback must not be null.");
        Objects.requireNonNull(context, "context cannot be null");

        AssistUtils assistUtils = new AssistUtils(context);
        int currentUserId = ActivityManager.getCurrentUser();


        if (assistUtils.getAssistComponentForUser(currentUserId) == null) {
            Slogf.d(TAG, "showPushToTalkSessionForActiveService(): no component for user %d",
                    currentUserId);
            return false;
        }

        Bundle args = new Bundle();
        args.putBoolean(EXTRA_CAR_PUSH_TO_TALK, true);

        IVoiceInteractionSessionShowCallback callbackWrapper =
                new InternalVoiceInteractionSessionShowCallback(callback);

        assistUtils.showSessionForActiveService(args, SHOW_SOURCE_PUSH_TO_TALK, callbackWrapper,
                /* activityToken= */ null);
        return true;
    }

    /**
     * See {@link IVoiceInteractionSessionShowCallback}
     */
    public interface VoiceInteractionSessionShowCallbackHelper {
        /**
         * See {@link IVoiceInteractionSessionShowCallback#onFailed()}
         */
        void onFailed();

        /**
         * See {@link IVoiceInteractionSessionShowCallback#onShow()}
         */
        void onShown();
    }

    private static final class InternalVoiceInteractionSessionShowCallback extends
            IVoiceInteractionSessionShowCallback.Stub {
        private final VoiceInteractionSessionShowCallbackHelper mCallbackHelper;

        InternalVoiceInteractionSessionShowCallback(
                VoiceInteractionSessionShowCallbackHelper callbackHelper) {
            mCallbackHelper = callbackHelper;
        }

        @Override
        public void onFailed() {
            if (mCallbackHelper == null) {
                return;
            }
            mCallbackHelper.onFailed();
        }

        @Override
        public void onShown() {
            if (mCallbackHelper == null) {
                return;
            }
            mCallbackHelper.onShown();
        }
    }

    private AssistUtilsHelper(Context context) {
        throw new UnsupportedOperationException("contains only static members");
    }
}
