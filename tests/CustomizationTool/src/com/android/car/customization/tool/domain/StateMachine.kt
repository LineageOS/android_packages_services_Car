/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.car.customization.tool.domain

/**
 * A base class for state machines.
 *
 * It provides all the basic properties and functions and also implements the Observer pattern
 * to make the state machine "reactive".
 *
 * @property allowDuplicates defines if the state observers should be notified or not
 * if the new state produced by the reducer function is the same as the previous one.
 */
internal abstract class StateMachine<STATE, ACTION>(
    private val allowDuplicates: Boolean,
) : Subject<STATE>() {

    /**
     * The current state of the tool.
     */
    protected abstract var currentState: STATE

    /**
     * The entity that observes the changes in the state.
     */
    private var observable: Observer<STATE>? = null

    /**
     * Registers an observer for state updates. When registering the observer also receives
     * immediately the current state (Hot observable).
     */
    final override fun register(observer: Observer<STATE>) {
        observable = observer
        observer.render(currentState)
    }

    /**
     * Removes an observer from the list.
     */
    final override fun unregister() {
        observable = null
    }

    /**
     * Notifies the new state to all of the [Observer].
     */
    final override fun updateState(newState: STATE) {
        if (!allowDuplicates && currentState != newState) {
            currentState = newState
            observable?.render(newState)
        }
    }

    /**
     * Public function to accept actions from outside.
     */
    fun handleAction(action: ACTION) {
        updateState(reducer(currentState, action))
    }

    /**
     * This function processes the current state and an action to produce a new one.
     */
    protected abstract fun reducer(state: STATE, action: ACTION): STATE
}
