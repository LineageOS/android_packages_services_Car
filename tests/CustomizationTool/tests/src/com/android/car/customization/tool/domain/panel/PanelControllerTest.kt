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

package com.android.car.customization.tool.domain.panel

import com.android.car.customization.tool.domain.EmptyPanelReducer
import javax.inject.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.times
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
internal class PanelControllerTest {

    @Test
    fun openPanel_action_opens_a_panel_and_the_extras_are_collected_correctly() {
        val expectedPanel = Panel(emptyList())
        val extras = mapOf("extra_key" to "extra_value")
        val emptyPanelReducer = EmptyPanelReducer()
        val underTest = PanelController(
            mapOf(EmptyPanelReducer::class.java to Provider { emptyPanelReducer })
        )

        val actualPanel = underTest.handleAction(
            panel = null,
            OpenPanelAction(EmptyPanelReducer::class, extras)
        )

        assertEquals(expectedPanel, actualPanel)
        assertEquals(extras, emptyPanelReducer.bundle)
    }

    @Test
    fun when_the_panel_is_closed_the_value_for_the_state_is_null() {
        val underTest = PanelController(mapOf())

        assertNull(underTest.handleAction(Panel(emptyList()), ClosePanelAction))
    }

    @Test
    fun when_the_panel_is_reloaded_the_reducer_builds_a_new_panel_from_scratch() {
        val emptyPanelReducer = Mockito.spy(EmptyPanelReducer())
        val underTest = PanelController(
            mapOf(EmptyPanelReducer::class.java to Provider { emptyPanelReducer })
        )

        underTest.handleAction(panel = null, OpenPanelAction(EmptyPanelReducer::class))
        underTest.handleAction(Panel(emptyList()), ReloadPanelAction)

        Mockito.verify(emptyPanelReducer, times(/*wantedNumberOfInvocations=*/2)).build()
    }
}
