/*
 * Copyright (C) 2024 The Android Open Source Project.
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
package com.android.systemui.car.displayarea;

import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.SurfaceControl;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowAnimationState;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.shared.TransitionUtil;
import com.android.wm.shell.transition.Transitions;

/**
 * Handles the opening transition.
 * Allows the {@link CarDisplayAreaController} to set the DisplayArea bounds and layer properly.
 */
public class CarDisplayAreaTransitions implements Transitions.TransitionHandler {
    private static final boolean DBG = Build.isDebuggable();
    private static final String TAG = CarDisplayAreaTransitions.class.getSimpleName();
    private Callback mCallback;

    public CarDisplayAreaTransitions(Transitions transitions) {
        transitions.addHandler(this);
    }

    void registerCallback(Callback callback) {
        mCallback = callback;
    }

    void unregisterCallback() {
        mCallback = null;
    }

    /**
     * Starts a transition animation. This is always called if handleRequest returned non-null
     * for a particular transition. Otherwise, it is only called if no other handler before
     * it handled the transition.
     *
     * @param startTransaction  the transaction given to the handler to be applied before the
     *                          transition animation. Note the handler is expected to call on
     *                          {@link SurfaceControl.Transaction#apply()} for startTransaction.
     * @param finishTransaction the transaction given to the handler to be applied after the
     *                          transition animation. Unlike startTransaction, the handler is NOT
     *                          expected to apply this transaction. The Transition system will
     *                          apply it when finishCallback is called.
     * @param finishCallback    Call this when finished. This MUST be called on main thread.
     * @return true if transition was handled, false if not (falls-back to default).
     */
    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        logIfDebuggable("Changes = " + info);
        if (mCallback == null) {
            return false;
        }
        if (TransitionUtil.isOpeningType(info.getType())) {
            mCallback.onStartAnimation(startTransaction, finishTransaction);
        }
        startTransaction.apply();
        return false;
    }

    /**
     * Attempts to merge a different transition's animation into an animation that this handler
     * is currently playing. If a merge is not possible/supported, this should be a no-op.
     *
     * This gets called if another transition becomes ready while this handler is still playing
     * an animation. This is called regardless of whether this handler claims to support that
     * particular transition or not.
     *
     * When this happens, there are 2 options:
     * 1. Do nothing. This effectively rejects the merge request. This is the "safest" option.
     * 2. Merge the incoming transition into this one. The implementation is up to this
     * handler. To indicate that this handler has "consumed" the merge transition, it
     * must call the finishCallback immediately, or at-least before the original
     * transition's finishCallback is called.
     *
     * @param transition     This is the transition that wants to be merged.
     * @param info           Information about what is changing in the transition.
     * @param t              Contains surface changes that resulted from the transition.
     * @param mergeTarget    This is the transition that we are attempting to merge with (ie. the
     *                       one this handler is currently already animating).
     * @param finishCallback Call this if merged. This MUST be called on main thread.
     */
    @Override
    public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull IBinder mergeTarget,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        logIfDebuggable("mergeAnimation");
        Transitions.TransitionHandler.super.mergeAnimation(transition, info, t, mergeTarget,
                finishCallback);
    }

    /**
     * Checks whether this handler is capable of taking over a transition matching `info`.
     * {@link TransitionHandler#takeOverAnimation(IBinder, TransitionInfo,
     * SurfaceControl.Transaction, TransitionFinishCallback, WindowAnimationState[])} is
     * guaranteed to succeed if called on the handler returned by this method.
     *
     * Note that the handler returned by this method can either be itself, or a different one
     * selected by this handler to take care of the transition on its behalf.
     *
     * @param transition The transition that should be taken over.
     * @param info       Information about the transition to be taken over.
     * @return A handler capable of taking over a matching transition, or null.
     */
    @Nullable
    @Override
    public Transitions.TransitionHandler getHandlerForTakeover(@NonNull IBinder transition,
            @NonNull TransitionInfo info) {
        logIfDebuggable("getHandlerForTakeover");
        return Transitions.TransitionHandler.super.getHandlerForTakeover(transition, info);
    }

    /**
     * Attempt to take over a running transition. This must succeed if this handler was returned
     * by {@link TransitionHandler#getHandlerForTakeover(IBinder, TransitionInfo)}.
     *
     * @param transition     The transition that should be taken over.
     * @param info           Information about the what is changing in the transition.
     * @param transaction    Contains surface changes that resulted from the transition. Any
     *                       additional changes should be added to this transaction and committed
     *                       inside this method.
     * @param finishCallback Call this at the end of the animation, if the take-over succeeds.
     *                       Note that this will be called instead of the callback originally
     *                       passed to startAnimation(), so the caller should make sure all
     *                       necessary cleanup happens here. This MUST be called on main thread.
     * @param states         The animation states of the transition's window at the time this method
     *                       was
     *                       called.
     * @return true if the transition was taken over, false if not.
     */
    @Override
    public boolean takeOverAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction transaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback,
            @NonNull WindowAnimationState[] states) {
        logIfDebuggable("takeOverAnimation");
        return Transitions.TransitionHandler.super.takeOverAnimation(transition, info, transaction,
                finishCallback, states);
    }

    /**
     * Potentially handles a startTransition request.
     *
     * @param transition The transition whose start is being requested.
     * @param request    Information about what is requested.
     * @return WCT to apply with transition-start or null. If a WCT is returned here, this
     * handler will be the first in line to animate.
     */
    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        // TODO(b/379166633): replace TaskStackListener in CarDisplayAreaController with this.
        logIfDebuggable("handleRequest, trigger task is " + request.getTriggerTask());
        WindowContainerTransaction wct = new WindowContainerTransaction();
        if (mCallback != null) {
            mCallback.onHandleRequest(wct, request);
        }

        return wct;
    }

    /**
     * Called when a transition which was already "claimed" by this handler has been merged
     * into another animation or has been aborted. Gives this handler a chance to clean-up any
     * expectations.
     *
     * @param transition        The transition been consumed.
     * @param aborted           Whether the transition is aborted or not.
     * @param finishTransaction The transaction to be applied after the transition animated.
     */
    @Override
    public void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
            @Nullable SurfaceControl.Transaction finishTransaction) {
        Transitions.TransitionHandler.super.onTransitionConsumed(transition, aborted,
                finishTransaction);
        logIfDebuggable("onTransitionConsumed");
    }

    /**
     * Sets transition animation scale settings value to handler.
     *
     * @param scale The setting value of transition animation scale.
     */
    @Override
    public void setAnimScaleSetting(float scale) {
        Transitions.TransitionHandler.super.setAnimScaleSetting(scale);
        logIfDebuggable("setAnimScaleSetting");
    }

    /**
     * Callback interface for listening to actions from
     * {@link Transitions.TransitionHandler}.
     */
    interface Callback {
        /**
         * Triggered on when {@link Transitions.TransitionHandler}
         * starts a transition animation.
         */
        void onStartAnimation(@NonNull SurfaceControl.Transaction startTransaction,
                @NonNull SurfaceControl.Transaction finishTransaction);

        /**
         * Triggered on when {@link Transitions.TransitionHandler}
         * handles a request
         */
        void onHandleRequest(WindowContainerTransaction wct,
                @NonNull TransitionRequestInfo request);
    }

    private static void logIfDebuggable(String message) {
        if (DBG) {
            Log.d(TAG, message);
        }
    }
}
