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

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.car.cluster.navigation.ImageReference;

import java.io.FileNotFoundException;
import java.io.IOException;


/**
 * Class for retrieving bitmap images from a ContentProvider
 */
public class ImageResolver {
    private static final String TAG = "Cluster.ImageResolver";

    private static ImageResolver sImageResolver = new ImageResolver();

    private ImageResolver() {}

    public static ImageResolver getInstance() {
        return sImageResolver;
    }

    /**
     * Returns a bitmap from an URI string from a content provider
     *
     * @param context View context
     */
    @Nullable
    public Bitmap getBitmap(Context context, Uri uri) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Requesting: " + uri);
        }
        try {
            ContentResolver contentResolver = context.getContentResolver();
            ParcelFileDescriptor fileDesc = contentResolver.openFileDescriptor(uri, "r");
            if (fileDesc != null) {
                Bitmap bitmap = BitmapFactory.decodeFileDescriptor(fileDesc.getFileDescriptor());
                fileDesc.close();
                return bitmap;
            } else {
                Log.e(TAG, "Null pointer: Failed to create pipe for uri string: " + uri);
            }
        } catch (FileNotFoundException e) {
            Log.e(TAG, "File not found for uri string: " + uri, e);
        } catch (IOException e) {
            Log.e(TAG, "File descriptor could not close: ", e);
        }

        return null;
    }

    /**
     * Returns a bitmap from a Car Instrument Cluster {@link ImageReference} that would fit inside
     * the provided size. Either width, height or both should be greater than 0.
     *
     * @param context View context
     * @param width required width, or 0 if width is flexible based on height.
     * @param height required height, or 0 if height is flexible based on width.
     */
    @Nullable
    public Bitmap getBitmapConstrained(Context context, ImageReference img, int width,
            int height) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, String.format("Requesting image %s (width: %d, height: %d)",
                    img.getRawContentUri(), width, height));
        }

        // Adjust the size to fit in the requested box.
        Point adjusted = getAdjustedSize(img.getOriginalWidth(), img.getOriginalHeight(), width,
                height);
        if (adjusted == null) {
            Log.e(TAG, "The provided image has no original size: " + img.getRawContentUri());
            return null;
        }
        Bitmap bitmap = getBitmap(context, img.getContentUri(adjusted.x, adjusted.y));
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, String.format("Returning image %s (width: %d, height: %d)",
                    img.getRawContentUri(), width, height));
        }
        return bitmap != null ? Bitmap.createScaledBitmap(bitmap, adjusted.x, adjusted.y, true)
                : null;
    }

    /**
     * Returns an image size that exactly fits inside a requested box, maintaining an original size
     * aspect ratio.
     *
     * @param originalWidth original width (must be != 0)
     * @param originalHeight original height (must be != 0)
     * @param requestedWidth required width, or 0 if width is flexible based on height.
     * @param requestedHeight required height, or 0 if height is flexible based on width.
     */
    @Nullable
    public Point getAdjustedSize(int originalWidth, int originalHeight, int requestedWidth,
            int requestedHeight) {
        if (originalWidth <= 0 || originalHeight <= 0) {
            return null;
        } else if (requestedWidth == 0 && requestedHeight == 0) {
            throw new IllegalArgumentException("At least one of width or height must be != 0");
        }
        // If width is flexible or if both width and height are set and the original image is wider
        // than the space provided, then scale the width.
        float requiredRatio = requestedHeight > 0 ? ((float) requestedWidth) / requestedHeight : 0;
        float imageRatio = ((float) originalWidth) / originalHeight;
        Point res = new Point(requestedWidth, requestedHeight);
        if (requestedWidth == 0 || (requestedHeight != 0 && imageRatio < requiredRatio)) {
            res.x = (int) (((float) requestedHeight / originalHeight) * originalWidth);
        } else {
            res.y = (int) (((float) requestedWidth / originalWidth) * originalHeight);
        }
        return res;
    }
}
