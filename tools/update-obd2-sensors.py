#!/usr/bin/env python3.4
#
# Copyright (C) 2016 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# This script generates useful representations of the current list of OBD2
# diagnostic sensors we support. It is meant as an easy way to update the
# list of diagnostic sensors and get all the required lists pretty-printed
# and ready for use.
# The script contains three parts:
# 1) the part marked DO NOT MODIFY THIS. This defines a domain-specific language
# that allows one to give a list of sensors;
# 2) the part marked ACTUAL SENSOR DEFINITIONS HERE. This part gives the list
# of diagnostic sensors, provided in the DSL defined above;
# 3) the generate() call at the very end. This triggers the script to perform
# its generation task.
# To keep it simple, this script will produce its output
# to stdout, also in three parts:
# 1) Vehicle HAL enumerations;
# 2) Java classes with a list of sensor identifiers;
# 3) Java @interfaces with a list of sensor identifiers.
# The several parts contain comments that indicate which files the content has
# to be pasted into. Should there be a need, the script could be extended to
# automatically insert the content in the files.
# To run:
# $ ./update-obd2-sensors.py

## DO NOT MODIFY THIS
## This code is the machinery required to make the sensor generator DSL work
class SensorList(object):
    """A list of sensors ordered by a unique identifier."""
    def __init__(self, descriptor):
        self.sensors = []
        self.id = -1
        self.descriptor = descriptor

    def addSensor(self, sensor):
        """Add a new sensor to the list."""
        if not hasattr(sensor, 'id'):
            self.id += 1
            sensor.id = self.id
        self.sensors.append(sensor)

    def finalizeList(self):
        """Complete the list, adding well-known sensor information."""
        self.id -= 1
        lastSystemSensor = self.sensorClass("LAST_SYSTEM_INDEX",
            id=self.sensors[-1].name)
        vendorStartSensor = self.sensorClass("VENDOR_START_INDEX",
            id="LAST_SYSTEM_INDEX + 1")
        # make calling finalizeList idempotent
        self.finalizeList = lambda: self
        return self

    def __getitem__(self, key):
        return self.sensors.__getitem__(key)

class SensorPolicy(object):
    """A formatter object that does processing on sensor data."""
    @classmethod
    def indentLines(cls, string, numSpaces):
        indent = ' ' * numSpaces
        parts = string.split('\n')
        parts = [indent + part for part in parts]
        return '\n'.join(parts) + "\n"

    def sensor(self, theSensor, theSensors):
        """Produce output for a sensor."""
        pass

    def prefix(self, theSensors):
        """Prefix string before any sensor data is generated."""
        return ""

    def suffix(self):
        """Suffix string after all sensor data is generated."""
        return ""

    def indent(self):
        """Indentation level for individual sensor data."""
        return 0

    def separator(self):
        """Separator between individual sensor data entries."""
        return ""

    def description(self):
        """A description of this policy."""
        return "A sensor policy."

    def sensors(self, theSensors):
        """Produce output for all sensors."""
        theSensors = theSensors.finalizeList()
        s = self.prefix(theSensors) + "\n"
        first = True
        for theSensor in theSensors:
            if first:
                first = False
            else:
                s += self.separator()
            sensorLine = SensorPolicy.indentLines(self.sensor(theSensor,
                theSensors), self.indent())
            s += sensorLine
        s += self.suffix(theSensors) + "\n"
        return s

class HalSensorPolicy(SensorPolicy):
    """The sensor policy that emits Vehicle HAL sensor descriptions."""
    def sensor(self, theSensor, theSensors):
        s = ""
        if theSensor.comment:
            s = theSensor.comment + "\n"
        s = s + theSensor.name + " = " + str(theSensor.id) + ","
        return s

    def prefix(self, theSensors):
        return "enum Obd2%sSensorIndex : int32_t {" % (theSensors.descriptor)

    def suffix(self, theSensors):
        return "}"

    def indent(self):
        return 2

    def separator(self):
        return "\n"

    def description(self):
        return "/** this goes in types.hal **/"

class JavaSensorPolicy(SensorPolicy):
    """The sensor policy that emits Java sensor descriptions."""
    def sensor(self, theSensor, theSensors):
        sensorName = theSensor.name.replace("_INDEX", "")
        sensorId = str(theSensor.id).replace("_INDEX", "")
        return "public static final int " + sensorName + " = " + \
            str(sensorId) + ";"

    def prefix(self, theSensors):
        s = "public static final class Obd2%sSensorIndex {\n" % theSensors.descriptor
        s += "    private Obd2%sSensorIndex() {}\n" % theSensors.descriptor
        return s

    def suffix(self, theSensors):
        return "}"

    def indent(self):
        return 4

    def description(self):
        return "/** this goes in CarDiagnosticEvent.java **/"

class IntDefSensorPolicy(SensorPolicy):
    """The sensor policy that emits @IntDef sensor descriptions."""
    def sensor(self, theSensor, theSensors):
        sensorName = theSensor.name.replace("_INDEX", "")
        return "Obd2%sSensorIndex.%s," % (theSensors.descriptor,sensorName)

    def prefix(self, theSensors):
        return "@Retention(RetentionPolicy.SOURCE)\n@IntDef({"

    def suffix(self, theSensors):
        return "})\npublic @interface %sSensorIndex {}" % theSensors.descriptor

    def description(self):
        return "/** this goes in CarDiagnosticEvent.java **/"

class SensorMeta(type):
    """Metaclass for sensor classes."""
    def __new__(cls, name, parents, dct):
        sensorList = dct['sensorList']
        class SensorBase(object):
            def __init__(self, name, comment=None, id=None):
                self.name = name
                self.comment = comment if comment else ""
                if id: self.id = id
                sensorList.addSensor(self)
            def __repr__(self):
                s = ""
                if self.comment:
                    s = s + self.comment + "\n"
                s = s + self.name + " = " + str(self.id)
                return s

        newClass = super().__new__(cls, name, (SensorBase,), dct)
        sensorList.sensorClass = newClass
        return newClass

intSensors = SensorList(descriptor="Integer")
floatSensors = SensorList(descriptor="Float")

class intSensor(metaclass=SensorMeta):
    sensorList = intSensors

class floatSensor(metaclass=SensorMeta):
    sensorList = floatSensors

def applyPolicy(policy):
    """Given a sensor policy, apply it to all known sensor types"""
    print(policy.description())
    print(policy.sensors(intSensors))
    print(policy.sensors(floatSensors))

def java():
    applyPolicy(JavaSensorPolicy())

def hal():
    applyPolicy(HalSensorPolicy())

def intdef():
    applyPolicy(IntDefSensorPolicy())

def generate():
    """Generate data for all sensors."""
    hal()
    java()
    intdef()

## ACTUAL SENSOR DEFINITIONS HERE
## Write sensor definitions here; terminate list with generate().

intSensor(name="FUEL_SYSTEM_STATUS", comment="/* refer to FuelSystemStatus for a description of this value. */")
intSensor(name="MALFUNCTION_INDICATOR_LIGHT_ON")
intSensor(name="IGNITION_MONITORS_SUPPORTED", comment="/* refer to IgnitionMonitorKind for a description of this value. */")
intSensor(name="IGNITION_SPECIFIC_MONITORS", comment=r"""/*
 * The value of this sensor is a bitmask that specifies whether ignition-specific
 * tests are available and whether they are complete. The semantics of the individual
 * bits in this value are given by, respectively, SparkIgnitionMonitors and
 * CompressionIgnitionMonitors depending on the value of IGNITION_MONITORS_SUPPORTED.
 */""")
intSensor(name="INTAKE_AIR_TEMPERATURE")
intSensor(name="COMMANDED_SECONDARY_AIR_STATUS", comment="/* refer to SecondaryAirStatus for a description of this value. */")
intSensor(name="NUM_OXYGEN_SENSORS_PRESENT")
intSensor(name="RUNTIME_SINCE_ENGINE_START")
intSensor(name="DISTANCE_TRAVELED_WITH_MALFUNCTION_INDICATOR_LIGHT_ON")
intSensor(name="WARMUPS_SINCE_CODES_CLEARED")
intSensor(name="DISTANCE_TRAVELED_SINCE_CODES_CLEARED")
intSensor(name="ABSOLUTE_BAROMETRIC_PRESSURE")
intSensor(name="CONTROL_MODULE_VOLTAGE")
intSensor(name="AMBIENT_AIR_TEMPERATURE")
intSensor(name="TIME_WITH_MALFUNCTION_LIGHT_ON")
intSensor(name="TIME_SINCE_TROUBLE_CODES_CLEARED")
intSensor(name="MAX_FUEL_AIR_EQUIVALENCE_RATIO")
intSensor(name="MAX_OXYGEN_SENSOR_VOLTAGE")
intSensor(name="MAX_OXYGEN_SENSOR_CURRENT")
intSensor(name="MAX_INTAKE_MANIFOLD_ABSOLUTE_PRESSURE")
intSensor(name="MAX_AIR_FLOW_RATE_FROM_MASS_AIR_FLOW_SENSOR")
intSensor(name="FUEL_TYPE", comment="/* refer to FuelType for a description of this value. */")
intSensor(name="FUEL_RAIL_ABSOLUTE_PRESSURE")
intSensor(name="ENGINE_OIL_TEMPERATURE")
intSensor(name="DRIVER_DEMAND_PERCENT_TORQUE")
intSensor(name="ENGINE_ACTUAL_PERCENT_TORQUE")
intSensor(name="ENGINE_REFERENCE_PERCENT_TORQUE")
intSensor(name="ENGINE_PERCENT_TORQUE_DATA_IDLE")
intSensor(name="ENGINE_PERCENT_TORQUE_DATA_POINT1")
intSensor(name="ENGINE_PERCENT_TORQUE_DATA_POINT2")
intSensor(name="ENGINE_PERCENT_TORQUE_DATA_POINT3")
intSensor(name="ENGINE_PERCENT_TORQUE_DATA_POINT4")

floatSensor(name="CALCULATED_ENGINE_LOAD")
floatSensor(name="ENGINE_COOLANT_TEMPERATURE")
floatSensor(name="SHORT_TERM_FUEL_TRIM_BANK1")
floatSensor(name="LONG_TERM_FUEL_TRIM_BANK1")
floatSensor(name="SHORT_TERM_FUEL_TRIM_BANK2")
floatSensor(name="LONG_TERM_FUEL_TRIM_BANK2")
floatSensor(name="FUEL_PRESSURE")
floatSensor(name="INTAKE_MANIFOLD_ABSOLUTE_PRESSURE")
floatSensor(name="ENGINE_RPM")
floatSensor(name="VEHICLE_SPEED")
floatSensor(name="TIMING_ADVANCE")
floatSensor(name="MAF_AIR_FLOW_RATE")
floatSensor(name="THROTTLE_POSITION")
floatSensor(name="OXYGEN_SENSOR1_VOLTAGE")
floatSensor(name="OXYGEN_SENSOR1_SHORT_TERM_FUEL_TRIM")
floatSensor(name="OXYGEN_SENSOR1_FUEL_AIR_EQUIVALENCE_RATIO")
floatSensor(name="OXYGEN_SENSOR2_VOLTAGE")
floatSensor(name="OXYGEN_SENSOR2_SHORT_TERM_FUEL_TRIM")
floatSensor(name="OXYGEN_SENSOR2_FUEL_AIR_EQUIVALENCE_RATIO")
floatSensor(name="OXYGEN_SENSOR3_VOLTAGE")
floatSensor(name="OXYGEN_SENSOR3_SHORT_TERM_FUEL_TRIM")
floatSensor(name="OXYGEN_SENSOR3_FUEL_AIR_EQUIVALENCE_RATIO")
floatSensor(name="OXYGEN_SENSOR4_VOLTAGE")
floatSensor(name="OXYGEN_SENSOR4_SHORT_TERM_FUEL_TRIM")
floatSensor(name="OXYGEN_SENSOR4_FUEL_AIR_EQUIVALENCE_RATIO")
floatSensor(name="OXYGEN_SENSOR5_VOLTAGE")
floatSensor(name="OXYGEN_SENSOR5_SHORT_TERM_FUEL_TRIM")
floatSensor(name="OXYGEN_SENSOR5_FUEL_AIR_EQUIVALENCE_RATIO")
floatSensor(name="OXYGEN_SENSOR6_VOLTAGE")
floatSensor(name="OXYGEN_SENSOR6_SHORT_TERM_FUEL_TRIM")
floatSensor(name="OXYGEN_SENSOR6_FUEL_AIR_EQUIVALENCE_RATIO")
floatSensor(name="OXYGEN_SENSOR7_VOLTAGE")
floatSensor(name="OXYGEN_SENSOR7_SHORT_TERM_FUEL_TRIM")
floatSensor(name="OXYGEN_SENSOR7_FUEL_AIR_EQUIVALENCE_RATIO")
floatSensor(name="OXYGEN_SENSOR8_VOLTAGE")
floatSensor(name="OXYGEN_SENSOR8_SHORT_TERM_FUEL_TRIM")
floatSensor(name="OXYGEN_SENSOR8_FUEL_AIR_EQUIVALENCE_RATIO")
floatSensor(name="FUEL_RAIL_PRESSURE")
floatSensor(name="FUEL_RAIL_GAUGE_PRESSURE")
floatSensor(name="COMMANDED_EXHAUST_GAS_RECIRCULATION")
floatSensor(name="EXHAUST_GAS_RECIRCULATION_ERROR")
floatSensor(name="COMMANDED_EVAPORATIVE_PURGE")
floatSensor(name="FUEL_TANK_LEVEL_INPUT")
floatSensor(name="EVAPORATION_SYSTEM_VAPOR_PRESSURE")
floatSensor(name="CATALYST_TEMPERATURE_BANK1_SENSOR1")
floatSensor(name="CATALYST_TEMPERATURE_BANK2_SENSOR1")
floatSensor(name="CATALYST_TEMPERATURE_BANK1_SENSOR2")
floatSensor(name="CATALYST_TEMPERATURE_BANK2_SENSOR2")
floatSensor(name="ABSOLUTE_LOAD_VALUE")
floatSensor(name="FUEL_AIR_COMMANDED_EQUIVALENCE_RATIO")
floatSensor(name="RELATIVE_THROTTLE_POSITION")
floatSensor(name="ABSOLUTE_THROTTLE_POSITION_B")
floatSensor(name="ABSOLUTE_THROTTLE_POSITION_C")
floatSensor(name="ACCELERATOR_PEDAL_POSITION_D")
floatSensor(name="ACCELERATOR_PEDAL_POSITION_E")
floatSensor(name="ACCELERATOR_PEDAL_POSITION_F")
floatSensor(name="COMMANDED_THROTTLE_ACTUATOR")
floatSensor(name="ETHANOL_FUEL_PERCENTAGE")
floatSensor(name="ABSOLUTE_EVAPORATION_SYSTEM_VAPOR_PRESSURE")
floatSensor(name="SHORT_TERM_SECONDARY_OXYGEN_SENSOR_TRIM_BANK1")
floatSensor(name="SHORT_TERM_SECONDARY_OXYGEN_SENSOR_TRIM_BANK2")
floatSensor(name="SHORT_TERM_SECONDARY_OXYGEN_SENSOR_TRIM_BANK3")
floatSensor(name="SHORT_TERM_SECONDARY_OXYGEN_SENSOR_TRIM_BANK4")
floatSensor(name="LONG_TERM_SECONDARY_OXYGEN_SENSOR_TRIM_BANK1")
floatSensor(name="LONG_TERM_SECONDARY_OXYGEN_SENSOR_TRIM_BANK2")
floatSensor(name="LONG_TERM_SECONDARY_OXYGEN_SENSOR_TRIM_BANK3")
floatSensor(name="LONG_TERM_SECONDARY_OXYGEN_SENSOR_TRIM_BANK4")
floatSensor(name="RELATIVE_ACCELERATOR_PEDAL_POSITION")
floatSensor(name="HYBRID_BATTERY_PACK_REMAINING_LIFE")
floatSensor(name="FUEL_INJECTION_TIMING")
floatSensor(name="ENGINE_FUEL_RATE")

generate()
