/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.automotive.computepipe.runner;

import android.automotive.computepipe.runner.PipeInputConfigInputType;
import android.automotive.computepipe.runner.PipeInputConfigFormatType;

/**
 * Options for the client to choose from
 */
parcelable PipeInputConfigInputOptions {
    /**
     * input stream type
     */
    PipeInputConfigInputType type;
    /**
     * format of the input stream
     */
    PipeInputConfigFormatType format;
    /**
     * width resolution of the input stream
     */
    int width;
    /**
     * height resolution of the input stream
     */
    int height;
    /**
     * stride for the frame
     */
    int stride;
    /**
     * camera identifier to disambiguate multiple instances
     * of InputType. If only one of a certain type is present
     * this should be 0. For VIDEO_FILE this should be 0.
     */
    int camId;
}

