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
package android.car.cluster.sample;

import static org.junit.Assert.assertEquals;

import android.graphics.Point;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.internal.DoNotInstrument;

@RunWith(RobolectricTestRunner.class)
@DoNotInstrument
public class ImageResolverTest {
    private ImageResolver mImageResolver;

    @Before
    public void setup() {
        mImageResolver = ImageResolver.getInstance();
    }

    @Test
    public void adjustedSize_widerImageInSquareBox() {
        assertEquals(new Point(20, 10), mImageResolver.getAdjustedSize(40, 20, 20, 20));
    }

    @Test
    public void adjustedSize_tallerImageInSquareBox() {
        assertEquals(new Point(10, 20), mImageResolver.getAdjustedSize(20, 40, 20, 20));
    }

    @Test
    public void adjustedSize_narrowerImageInSquareBox() {
        assertEquals(new Point(10, 20), mImageResolver.getAdjustedSize(5, 10, 20, 20));
    }

    @Test
    public void adjustedSize_shorterImageInSquareBox() {
        assertEquals(new Point(20, 8), mImageResolver.getAdjustedSize(5, 2, 20, 20));
    }

    @Test
    public void adjustedSize_widerImageInTallRectangle() {
        assertEquals(new Point(20, 10), mImageResolver.getAdjustedSize(40, 20, 20, 40));
    }

    @Test
    public void adjustedSize_tallerImageInTallRectangle() {
        assertEquals(new Point(20, 40), mImageResolver.getAdjustedSize(5, 10, 20, 40));
    }

    @Test
    public void adjustedSize_widerImageInWideRectangle() {
        assertEquals(new Point(40, 20), mImageResolver.getAdjustedSize(10, 5, 40, 20));
    }

    @Test
    public void adjustedSize_tallerImageInWideRectangle() {
        assertEquals(new Point(10, 20), mImageResolver.getAdjustedSize(5, 10, 40, 20));
    }

    @Test
    public void adjustedSize_nullIfNoOriginalWidth() {
        assertEquals(null, mImageResolver.getAdjustedSize(0, 10, 40, 20));
    }

    @Test
    public void adjustedSize_nullIfNoOriginalHeight() {
        assertEquals(null, mImageResolver.getAdjustedSize(5, 0, 40, 20));
    }

    @Test(expected = IllegalArgumentException.class)
    public void adjustedSize_exceptionIfRequestedWidthAndHeightNoProvided() {
        assertEquals(null, mImageResolver.getAdjustedSize(5, 10, 0, 0));
    }

    @Test
    public void adjustedSize_flexibleWidth() {
        assertEquals(new Point(20, 30), mImageResolver.getAdjustedSize(40, 60, 0, 30));
    }

    @Test
    public void adjustedSize_flexibleHeight() {
        assertEquals(new Point(20, 20), mImageResolver.getAdjustedSize(40, 40, 20, 0));
    }
}
