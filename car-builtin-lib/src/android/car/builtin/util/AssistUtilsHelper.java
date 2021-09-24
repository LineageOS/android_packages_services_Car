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

import android.annotation.SystemApi;
import android.content.Context;
import android.os.Bundle;
import android.os.UserHandle;

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

    @VisibleForTesting
    static final String EXTRA_CAR_PUSH_TO_TALK =
            "com.android.car.input.EXTRA_CAR_PUSH_TO_TALK";

    private final AssistUtils mAssistUtils;

    public AssistUtilsHelper(Context context) {
        mAssistUtils = new AssistUtils(context);
    }

    /**
     * Determines if assistant component is currently active for the passed in user handle.
     *
     * <p>See {@link AssistUtils#getAssistComponentForUser(int)}.
     */
    public boolean hasAssistantComponentForUser(UserHandle handle) {
        Objects.requireNonNull(handle, "UserHandle can not be null.");
        return mAssistUtils.getAssistComponentForUser(handle.getIdentifier()) != null;
    }

    /**
     * Shows the {@link android.service.voice.VoiceInteractionSession.SHOW_SOURCE_PUSH_TO_TALK}
     * session for active service.
     */
    public void showPushToTalkSessionForActiveService(
            VoiceInteractionSessionShowCallbackHelper callback) {
        Objects.requireNonNull(callback, "On shown callback must not be null.");
        Bundle args = new Bundle();
        args.putBoolean(EXTRA_CAR_PUSH_TO_TALK, true);

        IVoiceInteractionSessionShowCallback callbackWrapper =
                new InternalVoiceInteractionSessionShowCallback(callback);

        mAssistUtils.showSessionForActiveService(args, SHOW_SOURCE_PUSH_TO_TALK,
                callbackWrapper, /* activityToken= */ null);
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
}
