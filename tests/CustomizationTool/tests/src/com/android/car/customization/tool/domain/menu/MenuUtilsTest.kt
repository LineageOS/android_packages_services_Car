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
import com.android.car.customization.tool.domain.FakeToggleAction
import com.android.car.customization.tool.domain.dropDownItemA
import com.android.car.customization.tool.domain.dropDownItemB
import com.android.car.customization.tool.domain.findNode
import com.android.car.customization.tool.domain.firstLevelSubNavigation
import com.android.car.customization.tool.domain.rootNode
import com.android.car.customization.tool.domain.secondLevelSubNavigation
import com.android.car.customization.tool.domain.secondLevelSwitch1
import com.android.car.customization.tool.domain.thirdLevelDropDown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner

/**
 * Tests to verify that the utility functions to interact with the tree structure of the tree
 * work properly
 */
@RunWith(MockitoJUnitRunner::class)
internal class MenuUtilsTest {

    @Mock
    private lateinit var mockContext: Context

    @Test
    fun parent_of_element_found_correctly() {
        val firstLevelNode = rootNode.findParentOf(secondLevelSubNavigation)
        val zeroLevelNode = rootNode.findParentOf(firstLevelSubNavigation)

        assertEquals(firstLevelSubNavigation, firstLevelNode)
        assertEquals(rootNode, zeroLevelNode)
    }

    @Test
    fun child_subMenuNavigation_node_found_correctly() {
        val childFromFirstLevel = rootNode
            .findSubMenuNavigationNode(firstLevelSubNavigation.displayTextRes)
        val childFromSecondLevel = rootNode
            .findSubMenuNavigationNode(secondLevelSubNavigation.displayTextRes)
        val notExistingChild = rootNode.findSubMenuNavigationNode(nodeId = 100)

        assertEquals(firstLevelSubNavigation, childFromFirstLevel)
        assertEquals(secondLevelSubNavigation, childFromSecondLevel)
        assertNull(notExistingChild)
    }

    @Test
    fun when_modifying_a_switch_the_value_is_updated_correctly() {
        val expectedSwitch = secondLevelSwitch1.copy(
            isChecked = true,
            action = FakeToggleAction(newValue = true)
        )
        val startingMenu = Menu(rootNode, currentParentNode = rootNode)

        val newMenu = startingMenu.modifySwitch(
            mockContext,
            secondLevelSwitch1.displayTextRes,
            newState = true
        )
        val newSwitch = newMenu.rootNode.findNode(secondLevelSwitch1.displayTextRes)

        assertEquals(expectedSwitch, newSwitch)
    }

    @Test
    fun when_selecting_a_new_active_item_in_a_dropdown_the_menu_is_updated_correctly() {
        val expectedDropDownItem1 = dropDownItemA.copy(isActive = false)
        val expectedDropDownItem2 = dropDownItemB.copy(isActive = true)
        val expectedDropDown = thirdLevelDropDown.copy(
            items = listOf(expectedDropDownItem1, expectedDropDownItem2)
        )

        val startingMenu = Menu(rootNode, currentParentNode = rootNode)

        val newMenu = startingMenu.modifyDropDown(
            mockContext,
            thirdLevelDropDown.displayTextRes,
            dropDownItemB.action
        )
        val newDropDown = newMenu.rootNode.findNode(thirdLevelDropDown.displayTextRes)

        assertEquals(expectedDropDown, newDropDown)
    }
}
