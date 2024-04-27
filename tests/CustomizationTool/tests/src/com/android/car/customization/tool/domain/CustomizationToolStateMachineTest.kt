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

import com.android.car.customization.tool.domain.menu.Menu
import com.android.car.customization.tool.domain.menu.MenuController
import com.android.car.customization.tool.domain.menu.OpenSubMenuAction
import com.android.car.customization.tool.domain.panel.OpenPanelAction
import com.android.car.customization.tool.domain.panel.Panel
import com.android.car.customization.tool.domain.panel.PanelController
import javax.inject.Provider
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
internal class CustomizationToolStateMachineTest {

    private lateinit var fakeObserver: Observer<PageState>

    @Mock
    private lateinit var mockMenuController: MenuController

    @Before
    fun setUp() {
        fakeObserver = spy(
            object : Observer<PageState> {
                override fun render(state: PageState) {}
            })
    }

    @Test
    fun when_the_action_toggle_is_received_the_page_state_is_updated() {
        `when`(mockMenuController.buildMenu()).thenReturn(Menu(rootNode, currentParentNode = rootNode))
        val underTest = CustomizationToolStateMachine(mockMenuController, mock())

        underTest.register(fakeObserver)

        verify(fakeObserver, times(/*wantedNumberOfInvocations=*/1)).render(
            PageState(
                isOpen = false,
                Menu(rootNode, rootNode),
                panel = null
            )
        )
        underTest.handleAction(ToggleUiAction)

        verify(fakeObserver, times(/*wantedNumberOfInvocations=*/1)).render(
            PageState(
                isOpen = true,
                Menu(rootNode, rootNode),
                panel = null
            )
        )
        verifyNoMoreInteractions(fakeObserver)
    }

    @Test
    fun when_a_menu_action_is_received_then_it_is_forwarded_to_the_MenuController() {
        val initialMenu = Menu(rootNode, rootNode)
        `when`(mockMenuController.buildMenu()).thenReturn(initialMenu)
        `when`(
            mockMenuController.handleAction(
                initialMenu,
                OpenSubMenuAction(firstLevelSubNavigation.displayTextRes)
            )
        ).thenReturn(Menu(rootNode, firstLevelSubNavigation))

        val underTest = CustomizationToolStateMachine(mockMenuController, mock())
        underTest.register(fakeObserver)
        underTest.handleAction(OpenSubMenuAction(firstLevelSubNavigation.displayTextRes))

        // Verifying that the State Machine redirects the MenuAction to the MenuController
        verify(mockMenuController, times(/*wantedNumberOfInvocations=*/1)).handleAction(
            Menu(rootNode, rootNode),
            OpenSubMenuAction(firstLevelSubNavigation.displayTextRes)
        )

        // Verifying that the result from the MenuController is actually used in the new PageState
        verify(fakeObserver, times(/*wantedNumberOfInvocations=*/1)).render(
            PageState(
                isOpen = false,
                Menu(rootNode, firstLevelSubNavigation),
                panel = null
            )
        )
    }

    @Test
    fun when_a_panel_action_is_received_then_it_is_forwarded_to_the_PanelController() {
        val emptyPanelReducer = EmptyPanelReducer()
        val spyPanelController =
            spy(PanelController(mapOf(EmptyPanelReducer::class.java to Provider { emptyPanelReducer })))

        val underTest = CustomizationToolStateMachine(mockMenuController, spyPanelController)
        underTest.register(fakeObserver)

        underTest.handleAction(OpenPanelAction(EmptyPanelReducer::class))

        // Verifying that the State Machine redirects the PanelAction to the PanelController
        verify(spyPanelController, times(/*wantedNumberOfInvocations=*/1)).handleAction(
            panel = null,
            OpenPanelAction(EmptyPanelReducer::class)
        )

        // Verifying that the result from the PanelController is actually used in the new PageState
        verify(fakeObserver, times(/*wantedNumberOfInvocations=*/1)).render(
            PageState(
                isOpen = false,
                Menu(rootNode, rootNode),
                Panel(emptyList())
            )
        )
    }

    @Test
    fun expect_exception_when_action_is_not_implemented_in_the_state_machine() {
        val mockMenuController = MenuController({ rootNode }, mock(), mapOf())
        val underTest = CustomizationToolStateMachine(mockMenuController, mock())

        assertThrows(IllegalArgumentException::class.java) {
            underTest.handleAction(FakeAction)
        }
    }
}
