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

package com.android.car.customization.tool.domain.menu

import android.content.Context
import android.content.res.Resources
import com.android.car.customization.tool.domain.FakeMenuAction
import com.android.car.customization.tool.domain.FakeMenuActionReducer
import com.android.car.customization.tool.domain.firstLevelPanelLauncher
import com.android.car.customization.tool.domain.firstLevelSubNavigation
import com.android.car.customization.tool.domain.rootNode
import com.android.car.customization.tool.domain.secondLevelSubNavigation
import javax.inject.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
internal class MenuControllerTest {

    @Mock
    private lateinit var mockContext: Context

    private val fakeRootProvider = Provider { rootNode }

    private val firstLevelWithDuplicateIds = listOf(firstLevelPanelLauncher, firstLevelPanelLauncher)
    private val rootNodeWithDuplicates = MenuItem.SubMenuNavigation(
        displayTextRes = 0,
        firstLevelWithDuplicateIds
    )
    private val fakeRootProviderWithDuplicates = Provider { rootNodeWithDuplicates }

    @Test
    fun build_menu_test() {
        val underTest = MenuController(fakeRootProvider, mockContext, mapOf())
        val menu = underTest.buildMenu()

        // Verifying that given a Provider, the Menu is built correctly
        assertEquals(Menu(rootNode, currentParentNode = rootNode), menu)
    }

    @Test
    fun expect_exception_when_menu_has_duplicate_ids() {
        val mockResources: Resources = mock()
        `when`(mockResources.getResourceName(Mockito.anyInt())).thenReturn("mock")
        `when`(mockContext.resources).thenReturn(mockResources)

        val underTest = MenuController(fakeRootProviderWithDuplicates, mockContext, mapOf())

        assertThrows(IllegalStateException::class.java) {
            underTest.buildMenu()
        }
    }

    @Test
    fun when_opening_submenu_the_currentParent_is_updated_correctly() {
        val expectedMenu = Menu(rootNode, currentParentNode = firstLevelSubNavigation)
        val underTest = MenuController(fakeRootProvider, mockContext, mapOf())

        val menuAfterAction = underTest.handleAction(
            Menu(rootNode, rootNode),
            OpenSubMenuAction(firstLevelSubNavigation.displayTextRes)
        )

        assertEquals(expectedMenu, menuAfterAction)
    }

    @Test
    fun expect_exception_if_the_submenu_is_not_a_direct_child_of_the_current_parent() {
        val underTest = MenuController(fakeRootProvider, mockContext, mapOf())

        assertThrows(IllegalArgumentException::class.java) {
            underTest.handleAction(
                Menu(rootNode, currentParentNode = rootNode),
                OpenSubMenuAction(secondLevelSubNavigation.displayTextRes)
            )
        }
    }

    @Test
    fun when_navigating_up_then_the_currentParent_is_updated_correctly() {
        val expectedMenu = Menu(rootNode, currentParentNode = rootNode)
        val underTest = MenuController(fakeRootProvider, mockContext, mapOf())

        val menuAfterAction =
            underTest.handleAction(
                Menu(rootNode, currentParentNode = firstLevelSubNavigation),
                NavigateUpAction
            )

        assertEquals(expectedMenu, menuAfterAction)
    }

    @Test
    fun when_the_menu_is_reloaded_then_the_menu_is_updated_and_the_currentParent_preserved() {
        val underTest = MenuController(fakeRootProvider, mockContext, mapOf())
        val expectedMenu = Menu(rootNode, currentParentNode = firstLevelSubNavigation)

        val actualMenu =
            underTest.handleAction(Menu(rootNode, firstLevelSubNavigation), ReloadMenuAction)

        // When creating a new menu is very important that the currentParent value is also recalculated
        // correctly otherwise the user will be moved to a different sub menu by accident
        assertEquals(expectedMenu, actualMenu)
    }

    @Test
    fun expect_exception_when_action_is_not_supported() {
        val underTest = MenuController(fakeRootProvider, mockContext, mapOf())

        assertThrows(IllegalArgumentException::class.java) {
            underTest.handleAction(Menu(rootNode, currentParentNode = rootNode), FakeMenuAction())
        }
    }

    @Test
    fun when_an_additional_reducer_is_provided_the_menuController_uses_it() {
        val fakeMenuActionReducer = spy(FakeMenuActionReducer())
        val fakeAction = FakeMenuAction()
        val underTest = MenuController(
            fakeRootProvider,
            mockContext,
            mapOf(
                FakeMenuAction::class.java to fakeMenuActionReducer
            )
        )

        underTest.handleAction(Menu(rootNode, currentParentNode = rootNode), fakeAction)

        // Testing that the "expandability" mechanism works, if a new reducer is provided to the controller
        // then it can be used
        verify(fakeMenuActionReducer, times(/*wantedNumberOfInvocations=*/1)).reduce(
            Menu(rootNode, currentParentNode = rootNode),
            fakeAction
        )
    }
}
