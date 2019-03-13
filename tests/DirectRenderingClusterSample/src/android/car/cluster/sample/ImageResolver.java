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

import android.graphics.Bitmap;
import android.graphics.Point;
import android.net.Uri;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.cluster.navigation.ImageReference;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Class for retrieving bitmap images from a ContentProvider
 */
public class ImageResolver {
    private static final String TAG = "Cluster.ImageResolver";
    private static final int IMAGE_CACHE_SIZE_BYTES = 4 * 1024 * 1024; /* 4 mb */

    private final BitmapFetcher mFetcher;
    private final LruCache<String, Bitmap> mCache = new LruCache<String, Bitmap>(
            IMAGE_CACHE_SIZE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    public interface BitmapFetcher {
        Bitmap getBitmap(Uri uri);
    }

    /**
     * Creates a resolver that delegate the image retrieval to the given fetcher.
     */
    public ImageResolver(BitmapFetcher fetcher) {
        mFetcher = fetcher;
    }

    /**
     * Returns a {@link CompletableFuture} that provides a bitmap from a {@link ImageReference}.
     * This image would fit inside the provided size. Either width, height or both should be greater
     * than 0.
     *
     * @param width required width, or 0 if width is flexible based on height.
     * @param height required height, or 0 if height is flexible based on width.
     */
    @NonNull
    public CompletableFuture<Bitmap> getBitmap(@NonNull ImageReference img, int width, int height) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, String.format("Requesting image %s (width: %d, height: %d)",
                    img.getRawContentUri(), width, height));
        }

        return CompletableFuture.supplyAsync(() -> {
            // Adjust the size to fit in the requested box.
            Point adjusted = getAdjustedSize(img.getOriginalWidth(), img.getOriginalHeight(), width,
                    height);
            if (adjusted == null) {
                Log.e(TAG, "The provided image has no original size: " + img.getRawContentUri());
                return null;
            }
            Uri uri = img.getContentUri(adjusted.x, adjusted.y);
            Bitmap bitmap = mCache.get(uri.toString());
            if (bitmap == null) {
                bitmap = mFetcher.getBitmap(uri);
                if (bitmap == null) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "Unable to fetch image: " + uri);
                    }
                    return null;
                }
                if (bitmap.getWidth() != adjusted.x || bitmap.getHeight() != adjusted.y) {
                    bitmap = Bitmap.createScaledBitmap(bitmap, adjusted.x, adjusted.y, true);
                }
                mCache.put(uri.toString(), bitmap);
            }
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, String.format("Returning image %s (width: %d, height: %d)",
                        img.getRawContentUri(), width, height));
            }
            return bitmap != null ? Bitmap.createScaledBitmap(bitmap, adjusted.x, adjusted.y, true)
                    : null;
        });
    }

    /**
     * Same as {@link #getBitmap(ImageReference, int, int)} but it works on a list of images. The
     * returning {@link CompletableFuture} will contain a map from each {@link ImageReference} to
     * its bitmap. If any image fails to be fetched, the whole future completes exceptionally.
     *
     * @param width required width, or 0 if width is flexible based on height.
     * @param height required height, or 0 if height is flexible based on width.
     */
    @NonNull
    public CompletableFuture<Map<ImageReference, Bitmap>> getBitmaps(
            @NonNull List<ImageReference> imgs, int width, int height) {
        CompletableFuture<Map<ImageReference, Bitmap>> future = new CompletableFuture<>();

        Map<ImageReference, CompletableFuture<Bitmap>> bitmapFutures = imgs.stream().collect(
                Collectors.toMap(
                        img -> img,
                        img -> getBitmap(img, width, height)));

        CompletableFuture.allOf(bitmapFutures.values().toArray(new CompletableFuture[0]))
                .thenAccept(v -> {
                    Map<ImageReference, Bitmap> bitmaps = bitmapFutures.entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry
                                    .getValue().join()));
                    future.complete(bitmaps);
                })
                .exceptionally(ex -> {
                    future.completeExceptionally(ex);
                    return null;
                });

        return future;
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
