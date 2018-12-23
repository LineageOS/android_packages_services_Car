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

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.Objects;

/**
 * {@link AndroidViewModel} for cluster information.
 */
public class ClusterViewModel extends AndroidViewModel {
    /**
     * Reference to a component (e.g.: an activity) and whether such component is visible or not.
     */
    public static class ComponentVisibility {
        /**
         * Application component name
         */
        public final ComponentName mComponent;
        /**
         * Whether the component is currently visible to the user or not.
         */
        public final boolean mIsVisible;

        /**
         * Creates a new component visibility reference
         */
        private ComponentVisibility(ComponentName component, boolean isVisible) {
            mComponent = component;
            mIsVisible = isVisible;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ComponentVisibility that = (ComponentVisibility) o;
            return mIsVisible == that.mIsVisible
                    && Objects.equals(mComponent, that.mComponent);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mComponent, mIsVisible);
        }
    }

    private final MutableLiveData<ComponentVisibility> mFreeNavigationActivity =
            new MutableLiveData<>(new ComponentVisibility(null, false));
    private final MutableLiveData<Boolean> mNavigationFocus = new MutableLiveData<>(false);

    /**
     * New {@link ClusterViewModel} instance
     */
    public ClusterViewModel(@NonNull Application application) {
        super(application);
    }

    /**
     * Returns a {@link LiveData} providing the activity selected to be displayed on the cluster
     * when navigation focus is not granted (a.k.a.: free navigation). It also indicates whether
     * such activity is currently visible to the user or not.
     */
    public LiveData<ComponentVisibility> getFreeNavigationActivity() {
        return mFreeNavigationActivity;
    }

    /**
     * Returns a {@link LiveData} indicating whether navigation focus is currently being granted
     * or not. This indicates whether a navigation application is currently providing driving
     * directions. Instrument cluster can use this signal to show/hide turn-by-turn
     * directions UI, and hide/show the free navigation activity
     * (see {@link #getFreeNavigationActivity()}).
     */
    public LiveData<Boolean> getNavigationFocus() {
        return mNavigationFocus;
    }

    /**
     * Sets the activity selected to be displayed on the cluster when no driving directions are
     * being provided, and whether such activity is currently visible to the user or not
     */
    public void setFreeNavigationActivity(ComponentName application, boolean isVisible) {
        ComponentVisibility newValue = new ComponentVisibility(application, isVisible);
        if (!Objects.equals(mFreeNavigationActivity.getValue(), newValue)) {
            mFreeNavigationActivity.setValue(new ComponentVisibility(application, isVisible));
        }
    }

    /**
     * Sets whether navigation focus is currently being granted or not.
     */
    public void setNavigationFocus(boolean navigationFocus) {
        if (mNavigationFocus.getValue() == null || mNavigationFocus.getValue() != navigationFocus) {
            mNavigationFocus.setValue(navigationFocus);
        }
    }
}
