/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.car.util.concurrent;

import android.annotation.NonNull;
import android.annotation.SuppressLint;

import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

//TODO(b/162007404): move to core framework
/**
 * Custom {@link Future} that provides a way to be handled asynchronously.
 *
 * @param <T> type returned by the {@link Future}.
 */
// Future usage is not recommended because it's "missing non-blocking listening, making it hard to
// use with asynchronous code", which is exactly what this interface is addressing, so it's ok to
// ignore this warning.
@SuppressLint("BadFuture")
public interface AsyncFuture<T> extends Future<T> {

    /**
     * Executes the given {@code} (in the provided {@code executor}) when the future is completed.
     */
    @NonNull AsyncFuture<T> whenCompleteAsync(
            @NonNull BiConsumer<? super T, ? super Throwable> action,
            @NonNull Executor executor);
}
