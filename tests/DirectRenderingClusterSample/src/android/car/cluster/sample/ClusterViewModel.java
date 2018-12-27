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
package android.car.cluster.sample;

import android.app.Application;
import android.content.ComponentName;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.Objects;

/**
 * {@link AndroidViewModel} for cluster information.
 */
public class ClusterViewModel extends AndroidViewModel {
    private static final String TAG = "Cluster.ViewModel";

    public enum NavigationActivityState {
        /** No activity has been selected to be displayed on the navigation fragment yet */
        NOT_SELECTED,
        /** An activity has been selected, but it is not yet visible to the user */
        LOADING,
        /** Navigation activity is visible to the user */
        VISIBLE,
    }

    private ComponentName mFreeNavigationActivity;
    private ComponentName mCurrentNavigationActivity;
    private final MutableLiveData<NavigationActivityState> mNavigationActivityStateLiveData =
            new MutableLiveData<>();
    private final MutableLiveData<Boolean> mNavigationFocus = new MutableLiveData<>(false);

    /**
     * New {@link ClusterViewModel} instance
     */
    public ClusterViewModel(@NonNull Application application) {
        super(application);
    }

    /**
     * Returns a {@link LiveData} providing the current state of the activity displayed on the
     * navigation fragment.
     */
    public LiveData<NavigationActivityState> getNavigationActivityState() {
        return mNavigationActivityStateLiveData;
    }

    /**
     * Returns a {@link LiveData} indicating whether navigation focus is currently being granted
     * or not. This indicates whether a navigation application is currently providing driving
     * directions.
     */
    public LiveData<Boolean> getNavigationFocus() {
        return mNavigationFocus;
    }

    /**
     * Sets the activity selected to be displayed on the cluster when no driving directions are
     * being provided.
     */
    public void setFreeNavigationActivity(ComponentName activity) {
        if (!Objects.equals(activity, mFreeNavigationActivity)) {
            mFreeNavigationActivity = activity;
            updateNavigationActivityLiveData();
        }
    }

    /**
     * Sets the activity currently being displayed on the cluster.
     */
    public void setCurrentNavigationActivity(ComponentName activity) {
        if (!Objects.equals(activity, mCurrentNavigationActivity)) {
            mCurrentNavigationActivity = activity;
            updateNavigationActivityLiveData();
        }
    }

    /**
     * Sets whether navigation focus is currently being granted or not.
     */
    public void setNavigationFocus(boolean navigationFocus) {
        if (mNavigationFocus.getValue() == null || mNavigationFocus.getValue() != navigationFocus) {
            mNavigationFocus.setValue(navigationFocus);
            updateNavigationActivityLiveData();
        }
    }

    private void updateNavigationActivityLiveData() {
        NavigationActivityState newState = calculateNavigationActivityState();
        if (newState != mNavigationActivityStateLiveData.getValue()) {
            mNavigationActivityStateLiveData.setValue(newState);
        }
    }

    private NavigationActivityState calculateNavigationActivityState() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, String.format("Current state: current activity = '%s', free nav activity = "
                            + "'%s', focus = %s", mCurrentNavigationActivity,
                    mFreeNavigationActivity,
                    mNavigationFocus.getValue()));
        }
        if (mNavigationFocus.getValue() != null && mNavigationFocus.getValue()) {
            // Car service controls which activity is displayed while driving, so we assume this
            // has already been taken care of.
            return NavigationActivityState.VISIBLE;
        } else if (mFreeNavigationActivity == null) {
            return NavigationActivityState.NOT_SELECTED;
        } else if (Objects.equals(mFreeNavigationActivity, mCurrentNavigationActivity)) {
            return NavigationActivityState.VISIBLE;
        } else {
            return NavigationActivityState.LOADING;
        }
    }
}
