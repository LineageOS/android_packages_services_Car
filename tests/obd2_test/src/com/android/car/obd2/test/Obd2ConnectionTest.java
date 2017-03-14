/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.car.obd2.test;

import static org.junit.Assert.*;

import com.android.car.obd2.Obd2Command;
import com.android.car.obd2.Obd2Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import org.junit.Test;

public class Obd2ConnectionTest {
    private static int[] stringsToIntArray(String... strings) {
        ArrayList<Integer> arrayList = new ArrayList<>();
        for (String string : strings) {
            string.chars().forEach(arrayList::add);
        }
        return arrayList.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] concatIntArrays(int[] array1, int[] array2) {
        int[] newArray = Arrays.copyOf(array1, array1.length + array2.length);
        System.arraycopy(array2, 0, newArray, array1.length, array2.length);
        return newArray;
    }

    private static final String[] EXPECTED_INIT_COMMANDS =
            new String[] {
                "ATD\r", "ATZ\r", "AT E0\r", "AT L0\r", "AT S0\r", "AT H0\r", "AT SP 0\r"
            };

    private static final int AIR_TEMPERATURE_PID = 0x46;
    private static final int ENGINE_OIL_TEMPERATURE_PID = 0x5C;

    private static final String[] EXPECTED_AIR_TEMPERATURE_COMMANDS = new String[] {"0146\r"};
    private static final String[] EXPECTED_ENGINE_OIL_TEMPERATURE_COMMANDS =
            new String[] {"015C\r"};

    private static final String OBD2_PROMPT = ">";

    private static final String[] EXPECTED_INIT_RESPONSES =
            new String[] {
                OBD2_PROMPT,
                OBD2_PROMPT,
                OBD2_PROMPT,
                OBD2_PROMPT,
                OBD2_PROMPT,
                OBD2_PROMPT,
                OBD2_PROMPT
            };

    private static final String[] EXPECTED_AIR_TEMPERATURE_RESPONSES =
            new String[] {"41 46 A1", OBD2_PROMPT};
    private static final String[] EXPECTED_ENGINE_OIL_TEMPERATURE_RESPONSES =
            new String[] {"41 5C 87", OBD2_PROMPT};

    @Test
    public void testEstablishConnection() {
        MockObd2UnderlyingTransport transport =
                new MockObd2UnderlyingTransport(
                        stringsToIntArray(EXPECTED_INIT_COMMANDS),
                        stringsToIntArray(EXPECTED_INIT_RESPONSES));
        Obd2Connection obd2Connection = new Obd2Connection(transport);
    }

    @Test
    public void testAmbientAirTemperature() {
        MockObd2UnderlyingTransport transport =
                new MockObd2UnderlyingTransport(
                        concatIntArrays(
                                stringsToIntArray(EXPECTED_INIT_COMMANDS),
                                stringsToIntArray(EXPECTED_AIR_TEMPERATURE_COMMANDS)),
                        concatIntArrays(
                                stringsToIntArray(EXPECTED_INIT_RESPONSES),
                                stringsToIntArray(EXPECTED_AIR_TEMPERATURE_RESPONSES)));
        Obd2Connection obd2Connection = new Obd2Connection(transport);
        Obd2Command<Float> airTemperatureCommand =
                Obd2Command.getLiveFrameCommand(Obd2Command.getFloatCommand(AIR_TEMPERATURE_PID));
        Optional<Float> airTemperature = Optional.empty();
        try {
            airTemperature = airTemperatureCommand.run(obd2Connection);
        } catch (Exception e) {
            assertTrue("airTemperature caused an exception: " + e, false);
        }
        assertTrue(airTemperature.isPresent());
        assertEquals(63.137257, (double) airTemperature.get(), 0.001);
    }

    @Test
    public void testEngineOilTemperature() {
        MockObd2UnderlyingTransport transport =
                new MockObd2UnderlyingTransport(
                        concatIntArrays(
                                stringsToIntArray(EXPECTED_INIT_COMMANDS),
                                stringsToIntArray(EXPECTED_ENGINE_OIL_TEMPERATURE_COMMANDS)),
                        concatIntArrays(
                                stringsToIntArray(EXPECTED_INIT_RESPONSES),
                                stringsToIntArray(EXPECTED_ENGINE_OIL_TEMPERATURE_RESPONSES)));
        Obd2Connection obd2Connection = new Obd2Connection(transport);
        Obd2Command<Integer> engineOilTemperatureCommand =
                Obd2Command.getLiveFrameCommand(
                        Obd2Command.getIntegerCommand(ENGINE_OIL_TEMPERATURE_PID));
        Optional<Integer> engineOilTemperature = Optional.empty();
        try {
            engineOilTemperature = engineOilTemperatureCommand.run(obd2Connection);
        } catch (Exception e) {
            assertTrue("engineOilTemperature caused an exception: " + e, false);
        }
        assertTrue(engineOilTemperature.isPresent());
        assertEquals(95, (int) engineOilTemperature.get());
    }
}
