/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.car.vms;

import android.car.annotation.FutureFeature;
import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A dependency for a VMS layer on other VMS layers.
 *
 * @hide
 */
@FutureFeature
public final class VmsLayerDependency implements Parcelable {
    private final VmsLayer mLayer;
    private final List<VmsLayer> mDependency;

    /**
     * Construct a dependency for layer on other layers.
     */
    public VmsLayerDependency(VmsLayer layer, List<VmsLayer> dependencies) {
        mLayer = layer;
        mDependency = Collections.unmodifiableList(dependencies);
    }

    /**
     * Constructs a layer without a dependency.
     */
    public VmsLayerDependency(VmsLayer layer) {
        mLayer = layer;
        mDependency = Collections.emptyList();
    }

    /**
     * Checks if a layer has a dependency.
     */
    public boolean hasDependencies() {
        return (!mDependency.isEmpty());
    }

    public VmsLayer getLayer() {
        return mLayer;
    }

    /**
     * Returns the dependencies.
     */
    public List<VmsLayer> getDependencies() {
        return mDependency;
    }

    public static final Parcelable.Creator<VmsLayerDependency> CREATOR = new
        Parcelable.Creator<VmsLayerDependency>() {
            public VmsLayerDependency createFromParcel(Parcel in) {
                return new VmsLayerDependency(in);
            }
            public VmsLayerDependency[] newArray(int size) {
                return new VmsLayerDependency[size];
            }
        };

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeParcelable(mLayer, flags);
        out.writeParcelableList(mDependency, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    private VmsLayerDependency(Parcel in) {
        mLayer = in.readParcelable(VmsLayer.class.getClassLoader());
        List<VmsLayer> dependency = new ArrayList<>();
        in.readParcelableList(dependency, VmsLayer.class.getClassLoader());
        mDependency = Collections.unmodifiableList(dependency);
    }
}