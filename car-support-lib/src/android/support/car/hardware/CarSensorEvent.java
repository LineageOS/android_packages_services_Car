/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.support.car.hardware;

import android.location.GpsSatellite;
import android.location.Location;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.support.car.annotation.VersionDef;
import android.support.car.os.ExtendableParcelable;

/**
 * A CarSensorEvent object corresponds to a single sensor event coming from the car. The sensor
 * data is stored in a sensor-type specific format in the object's float and byte arrays.
 *
 * To aid unmarshalling the object's data arrays, this class provides static nested classes and
 * conversion methods, for example {@link DrivingStatusData} and {@link #getDrivingStatusData}. The
 * conversion methods each have an optional data parameter which, if not null, will be used and
 * returned. This parameter should be used to avoid unnecessary object churn whenever possible.
 * Additionally, calling a conversion method on a CarSensorEvent object with an inappropriate type
 * will result in an {@code UnsupportedOperationException} being thrown.
 */
public class CarSensorEvent extends ExtendableParcelable {

    private static final int VERSION = 1;

    /**
     * Bitmask of driving restrictions.
     */
    /** No restrictions. */
    public static final int DRIVE_STATUS_UNRESTRICTED = 0;
    /** No video playback allowed. */
    public static final int DRIVE_STATUS_NO_VIDEO = 0x1;
    /** No keyboard or rotary controller input allowed. */
    public static final int DRIVE_STATUS_NO_KEYBOARD_INPUT = 0x2;
    /** No voice input allowed. */
    public static final int DRIVE_STATUS_NO_VOICE_INPUT = 0x4;
    /** No setup / configuration allowed. */
    public static final int DRIVE_STATUS_NO_CONFIG = 0x8;
    /** Limit displayed message length. */
    public static final int DRIVE_STATUS_LIMIT_MESSAGE_LEN = 0x10;
    /** represents case where all of the above items are restricted */
    public static final int DRIVE_STATUS_FULLY_RESTRICTED = DRIVE_STATUS_NO_VIDEO |
            DRIVE_STATUS_NO_KEYBOARD_INPUT | DRIVE_STATUS_NO_VOICE_INPUT | DRIVE_STATUS_NO_CONFIG |
            DRIVE_STATUS_LIMIT_MESSAGE_LEN;
    /**
     * Indices for {@link CarSensorManager#SENSOR_TYPE_COMPASS} in floatValues.
     * Angles are in degrees. Can be NaN if it is not available.
     */
    public static final int INDEX_COMPASS_BEARING = 0;
    /**
     * Indices for {@link CarSensorManager#SENSOR_TYPE_COMPASS} in floatValues.
     * Angles are in degrees. Can be NaN if it is not available.
     */
    public static final int INDEX_COMPASS_PITCH   = 1;
    /**
     * Indices for {@link CarSensorManager#SENSOR_TYPE_COMPASS} in floatValues.
     * Angles are in degrees. Can be NaN if it is not available.
     */
    public static final int INDEX_COMPASS_ROLL    = 2;


    private static final long MILLI_IN_NANOS = 1000000L;

    /** Sensor type for this event like {@link CarSensorManager#SENSOR_TYPE_CAR_SPEED}. */
    @VersionDef(version = 1)
    public final int sensorType;

    /**
     * When this data was acquired in car or received from car. It is elapsed real-time of data
     * reception from car in nanoseconds since system boot.
     */
    @VersionDef(version = 1)
    public final long timeStampNs;
    /**
     * array holding float type of sensor data. If the sensor has single value, only floatValues[0]
     * should be used. */
    @VersionDef(version = 1)
    public final float[] floatValues;
    /** array holding int type of sensor data */
    @VersionDef(version = 1)
    public final int[] intValues;

    /**
     * Constructs a {@link CarSensorEvent} from a {@link Parcel}.  Handled by
     * CarSensorManager implementations.  App developers need not worry about constructing these
     * objects.
     */
    public CarSensorEvent(Parcel in) {
        super(in, VERSION);
        int lastPosition = readHeader(in);
        sensorType = in.readInt();
        timeStampNs = in.readLong();
        int len = in.readInt();
        floatValues = new float[len];
        in.readFloatArray(floatValues);
        len = in.readInt();
        intValues = new int[len];
        in.readIntArray(intValues);
        // version 1 up to here
        completeReading(in, lastPosition);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        int startingPosition = writeHeader(dest);
        dest.writeInt(sensorType);
        dest.writeLong(timeStampNs);
        dest.writeInt(floatValues.length);
        dest.writeFloatArray(floatValues);
        dest.writeInt(intValues.length);
        dest.writeIntArray(intValues);
        // version 1 up to here
        completeWriting(dest, startingPosition);
    }

    public static final Parcelable.Creator<CarSensorEvent> CREATOR
            = new Parcelable.Creator<CarSensorEvent>() {
        public CarSensorEvent createFromParcel(Parcel in) {
            return new CarSensorEvent(in);
        }

        public CarSensorEvent[] newArray(int size) {
            return new CarSensorEvent[size];
        }
    };

    /**
     * Constructs a {@link CarSensorEvent} from integer values.  Handled by
     * CarSensorManager implementations.  App developers need not worry about constructing these
     * objects.
     */
    public CarSensorEvent(int sensorType, long timeStampNs, int floatValueSize, int intValueSize) {
        super(VERSION);
        this.sensorType = sensorType;
        this.timeStampNs = timeStampNs;
        floatValues = new float[floatValueSize];
        intValues = new int[intValueSize];
    }

    /** @hide */
    CarSensorEvent(int sensorType, long timeStampNs, float[] floatValues, int[] intValues) {
        super(VERSION);
        this.sensorType = sensorType;
        this.timeStampNs = timeStampNs;
        this.floatValues = floatValues;
        this.intValues = intValues;
    }

    private void checkType(int type) {
        if (sensorType == type) {
            return;
        }
        throw new UnsupportedOperationException(String.format(
                "Invalid sensor type: expected %d, got %d", type, sensorType));
    }

    /**
     * Holds data about the car's compass readings.
     */
    public static class CompassData {
        /** The time in nanoseconds since system boot. */
        public final long timeStampNs;
        /** The bearing in degrees. If unsupported by the car, this value is NaN. */
        public final float bearing;
        /** The pitch in degrees. Nose down is positive.  If unsupported by the car, this value is NaN. */
        public final float pitch;
        /** The roll in degrees. Right door down is positive.  If unsupported by the car, this value is NaN. */
        public final float roll;

        public CompassData(long timeStampNs, float bearing, float pitch, float roll) {
            this.timeStampNs = timeStampNs;
            this.bearing = bearing;
            this.pitch = pitch;
            this.roll = roll;
        }
    }

    /**
     * Convenience method for obtaining a {@link CompassData} object from a CarSensorEvent object
     * with type {@link CarSensorManager#SENSOR_TYPE_COMPASS}.
     *
     * @return a CompassData object corresponding to the data contained in the CarSensorEvent.
     */
    public CompassData getCompassData() {
        checkType(CarSensorManager.SENSOR_TYPE_COMPASS);
        return new CompassData(0, floatValues[INDEX_COMPASS_BEARING],
                floatValues[INDEX_COMPASS_PITCH], floatValues[INDEX_COMPASS_ROLL]);
    }

    /**
     * Tells whether or not the parking brake is engaged.
     */
    public static class ParkingBrakeData {
        /** The time in nanoseconds since system boot. */
        public final long timeStampNs;
        /** True if the parking brake is engaged. */
        public final boolean isEngaged;

        public ParkingBrakeData(long timeStampNs, boolean isEngaged) {
            this.timeStampNs = timeStampNs;
            this.isEngaged = isEngaged;
        }
    }

    /**
     * Convenience method for obtaining a {@link ParkingBrakeData} object from a CarSensorEvent
     * object with type {@link CarSensorManager#SENSOR_TYPE_PARKING_BRAKE}.
     *
     * @return a ParkingBreakData object corresponding to the data contained in the CarSensorEvent.
     */
    public ParkingBrakeData getParkingBrakeData() {
        checkType(CarSensorManager.SENSOR_TYPE_PARKING_BRAKE);
        return new ParkingBrakeData(timeStampNs, (intValues[0] == 1));
    }

    /**
     * Indicates if the system is in "night mode."  This is generally a state where the screen is
     * darkened  or showing a darker pallet.
     */
    public static class NightData {
        /** The time in nanoseconds since system boot. */
        public final long timeStampNs;
        /** True if the system is in night mode. */
        public final boolean isNightMode;

        public NightData(long timeStampNs, boolean isNightMode) {
            this.timeStampNs = timeStampNs;
            this.isNightMode = isNightMode;
        }
    }

    /**
     * Convenience method for obtaining a {@link NightData} object from a CarSensorEvent object with
     * type {@link CarSensorManager#SENSOR_TYPE_NIGHT}.
     *
     * @return a NightData object corresponding to the data contained in the CarSensorEvent.
     */
    public NightData getNightData() {
        checkType(CarSensorManager.SENSOR_TYPE_NIGHT);
        return new NightData(timeStampNs, (intValues[0] == 1));
    }

    /**
     * Indicates what restrictions are in effect based on the status of the vehicle.
     */
    public static class DrivingStatusData {
        /**
         * The time in nanoseconds since system boot.
         */
        public final long timeStampNs;
        /**
         * A bitmask with the following field values:  {@link #DRIVE_STATUS_NO_VIDEO},
         * {@link #DRIVE_STATUS_NO_KEYBOARD_INPUT}, {@link #DRIVE_STATUS_NO_VOICE_INPUT},
         * {@link #DRIVE_STATUS_NO_CONFIG}, {@link #DRIVE_STATUS_LIMIT_MESSAGE_LEN}. You may read
         * this or use the convenience methods.
         */
        public final int status;

        public DrivingStatusData(long timeStampNs, int status) {
            this.timeStampNs = timeStampNs;
            this.status = status;
        }

        /**
         * @return True if the keyboard is not allowed at this time.
         */
        public boolean isKeyboardRestricted() {
            return DRIVE_STATUS_NO_KEYBOARD_INPUT == (status & DRIVE_STATUS_NO_KEYBOARD_INPUT);
        }

        /**
         * @return True if voice commands are not allowed at this time.
         */
        public boolean isVoiceRestricted() {
            return DRIVE_STATUS_NO_VOICE_INPUT == (status & DRIVE_STATUS_NO_VOICE_INPUT);
        }

        /**
         * @return True if video is not allowed at this time.
         */
        public boolean isVideoRestricted() {
            return DRIVE_STATUS_NO_VIDEO == (status & DRIVE_STATUS_NO_VIDEO);
        }

        /**
         * @return True if configuration should not be performed at this time.
         */
        public boolean isConfigurationRestricted() {
            return DRIVE_STATUS_NO_CONFIG == (status & DRIVE_STATUS_NO_CONFIG);
        }

        /**
         * @return True if message length should be limited at this time.
         */
        public boolean isMessageLengthRestricted() {
            return DRIVE_STATUS_LIMIT_MESSAGE_LEN == (status & DRIVE_STATUS_LIMIT_MESSAGE_LEN);
        }

        /**
         * @return True if all restrictions are in place at this time.
         */
        public boolean isFullyRestricted() {
            return DRIVE_STATUS_FULLY_RESTRICTED == (status & DRIVE_STATUS_FULLY_RESTRICTED);
        }
    }

    /**
     * Convenience method for obtaining a {@link DrivingStatusData} object from a CarSensorEvent
     * object with type {@link CarSensorManager#SENSOR_TYPE_DRIVING_STATUS}.
     *
     * @return a DrivingStatusData object corresponding to the data contained in the CarSensorEvent.
     */
    public DrivingStatusData getDrivingStatusData() {
        checkType(CarSensorManager.SENSOR_TYPE_DRIVING_STATUS);
        return new DrivingStatusData(timeStampNs, intValues[0]);
    }


    /*things that are currently hidden*/


    /**
     * Index in {@link #floatValues} for {@link CarSensorManager#SENSOR_TYPE_FUEL_LEVEL} type of
     * sensor. This value is fuel level in percentile.
     * @hide
     */
    public static final int INDEX_FUEL_LEVEL_IN_PERCENTILE = 0;
    /**
     * Index in {@link #floatValues} for {@link CarSensorManager#SENSOR_TYPE_FUEL_LEVEL} type of
     * sensor. This value is fuel level in coverable distance. The unit is Km.
     * @hide
     */
    public static final int INDEX_FUEL_LEVEL_IN_DISTANCE = 1;
    /**
     * Index in {@link #intValues} for {@link CarSensorManager#SENSOR_TYPE_FUEL_LEVEL} type of
     * sensor. This value is set to 1 if fuel low level warning is on.
     * @hide
     */
    public static final int INDEX_FUEL_LOW_WARNING = 0;

    /**
     *  GEAR_* represents meaning of intValues[0] for {@link CarSensorManager#SENSOR_TYPE_GEAR}
     *  sensor type.
     *  GEAR_NEUTRAL means transmission gear is in neutral state, and the car may be moving.
     * @hide
     */
    public static final int GEAR_NEUTRAL    = 0;
    /**
     * intValues[0] from 1 to 99 represents transmission gear number for moving forward.
     * GEAR_FIRST is for gear number 1.
     * @hide
     */
    public static final int GEAR_FIRST      = 1;
    /** Gear number 2. @hide */
    public static final int GEAR_SECOND     = 2;
    /** Gear number 3. @hide */
    public static final int GEAR_THIRD      = 3;
    /** Gear number 4. @hide */
    public static final int GEAR_FOURTH     = 4;
    /** Gear number 5. @hide */
    public static final int GEAR_FIFTH      = 5;
    /** Gear number 6. @hide */
    public static final int GEAR_SIXTH      = 6;
    /** Gear number 7. @hide */
    public static final int GEAR_SEVENTH    = 7;
    /** Gear number 8. @hide */
    public static final int GEAR_EIGHTH     = 8;
    /** Gear number 9. @hide */
    public static final int GEAR_NINTH      = 9;
    /** Gear number 10. @hide */
    public static final int GEAR_TENTH      = 10;
    /**
     * This is for transmission without specific gear number for moving forward like CVT. It tells
     * that car is in a transmission state to move it forward.
     * @hide
     */
    public static final int GEAR_DRIVE      = 100;
    /** Gear in parking state @hide */
    public static final int GEAR_PARK       = 101;
    /** Gear in reverse @hide */
    public static final int GEAR_REVERSE    = 102;

    /**
     * Indices for {@link CarSensorManager#SENSOR_TYPE_GYROSCOPE} in floatValues.
     * Rotation speed is in rad/s. Any component can be NaN if it is not available.
     */
    /**@hide*/
    public static final int INDEX_GYROSCOPE_X = 0;
    /**@hide*/
    public static final int INDEX_GYROSCOPE_Y = 1;
    /**@hide*/
    public static final int INDEX_GYROSCOPE_Z = 2;

    /**
     * Indices for {@link CarSensorManager#SENSOR_TYPE_GPS_SATELLITE}.
     * Both byte values and float values are used.
     * Two first bytes encode number of satellites in-use/in-view (or 0xFF if unavailable).
     * Then optionally with INDEX_GPS_SATELLITE_ARRAY_BYTE_OFFSET offset and interval
     * INDEX_GPS_SATELLITE_ARRAY_BYTE_INTERVAL between elements are encoded boolean flags of whether
     * particular satellite from in-view participate in in-use subset.
     * Float values with INDEX_GPS_SATELLITE_ARRAY_FLOAT_OFFSET offset and interval
     * INDEX_GPS_SATELLITE_ARRAY_FLOAT_INTERVAL between elements can optionally contain
     * per-satellite values of signal strength and other values or NaN if unavailable.
     */
    /**@hide*/
    public static final int INDEX_GPS_SATELLITE_NUMBER_IN_USE = 0;
    /**@hide*/
    public static final int INDEX_GPS_SATELLITE_NUMBER_IN_VIEW = 1;
    /**@hide*/
    public static final int INDEX_GPS_SATELLITE_ARRAY_INT_OFFSET = 2;
    /**@hide*/
    public static final int INDEX_GPS_SATELLITE_ARRAY_INT_INTERVAL = 1;
    /**@hide*/
    public static final int INDEX_GPS_SATELLITE_ARRAY_FLOAT_OFFSET = 0;
    /**@hide*/
    public static final int INDEX_GPS_SATELLITE_ARRAY_FLOAT_INTERVAL = 4;
    /**@hide*/
    public static final int INDEX_GPS_SATELLITE_PRN_OFFSET = 0;
    /**@hide*/
    public static final int INDEX_GPS_SATELLITE_SNR_OFFSET = 1;
    /**@hide*/
    public static final int INDEX_GPS_SATELLITE_AZIMUTH_OFFSET = 2;
    /**@hide*/
    public static final int INDEX_GPS_SATELLITE_ELEVATION_OFFSET = 3;


    /**
     * Index for {@link CarSensorManager#SENSOR_TYPE_LOCATION} in floatValues.
     * Each bit intValues[0] represents whether the corresponding data is present.
     */
    /**@hide*/
    public static final int INDEX_LOCATION_LATITUDE  = 0;
    /**@hide*/
    public static final int INDEX_LOCATION_LONGITUDE = 1;
    /**@hide*/
    public static final int INDEX_LOCATION_ACCURACY  = 2;
    /**@hide*/
    public static final int INDEX_LOCATION_ALTITUDE  = 3;
    /**@hide*/
    public static final int INDEX_LOCATION_SPEED     = 4;
    /**@hide*/
    public static final int INDEX_LOCATION_BEARING   = 5;
    /**@hide*/
    public static final int INDEX_LOCATION_MAX = INDEX_LOCATION_BEARING;
    /**@hide*/
    public static final int INDEX_LOCATION_LATITUDE_INTS = 1;
    /**@hide*/
    public static final int INDEX_LOCATION_LONGITUDE_INTS = 2;


    /**
     * Index for {@link CarSensorManager#SENSOR_TYPE_ENVIRONMENT} in floatValues.
     * Temperature in Celsius degrees.
     * @hide
     */
    public static final int INDEX_ENVIRONMENT_TEMPERATURE = 0;
    /**
     * Index for {@link CarSensorManager#SENSOR_TYPE_ENVIRONMENT} in floatValues.
     * Pressure in kPa.
     * @hide
     */
    public static final int INDEX_ENVIRONMENT_PRESSURE = 1;


    /**
     * Indices for {@link CarSensorManager#SENSOR_TYPE_ACCELEROMETER} in floatValues.
     * Acceleration (gravity) is in m/s^2. Any component can be NaN if it is not available.
     */
    /**@hide*/
    public static final int INDEX_ACCELEROMETER_X = 0;
    /**@hide*/
    public static final int INDEX_ACCELEROMETER_Y = 1;
    /**@hide*/
    public static final int INDEX_ACCELEROMETER_Z = 2;


    /** @hide */
    public static class EnvironmentData {
        /** The time in nanoseconds since system boot. */
        public final long timeStampNs;
        /** If unsupported by the car, this value is NaN. */
        public final float temperature;
        /** If unsupported by the car, this value is NaN. */
        public final float pressure;

        public EnvironmentData(long timeStampNs, float temperature, float pressure) {
            this.timeStampNs = timeStampNs;
            this.temperature = temperature;
            this.pressure = pressure;
        }
    }

    /**
     * Convenience method for obtaining an {@link EnvironmentData} object from a CarSensorEvent
     * object with type {@link CarSensorManager#SENSOR_TYPE_ENVIRONMENT}.
     *
     * @return an EnvironmentData object corresponding to the data contained in the CarSensorEvent.
     * @hide
     */
    public EnvironmentData getEnvironmentData() {
        checkType(CarSensorManager.SENSOR_TYPE_ENVIRONMENT);

        float temperature = floatValues[INDEX_ENVIRONMENT_TEMPERATURE];
        float pressure = floatValues[INDEX_ENVIRONMENT_PRESSURE];
        return new EnvironmentData(timeStampNs, temperature, pressure);
    }

    /** @hide */
    public static class GearData {
        /** The time in nanoseconds since system boot. */
        public final long timeStampNs;
        public final int gear;

        public GearData(long timeStampNs, int gear) {
            this.timeStampNs = timeStampNs;
            this.gear = gear;
        }
    }

    /**
     * Convenience method for obtaining a {@link GearData} object from a CarSensorEvent
     * object with type {@link CarSensorManager#SENSOR_TYPE_GEAR}.
     *
     * @return a GearData object corresponding to the data contained in the CarSensorEvent.
     * @hide
     */
    public GearData getGearData() {
        checkType(CarSensorManager.SENSOR_TYPE_GEAR);
        return new GearData(timeStampNs,intValues[0] );
    }

    /** @hide */
    public static class FuelLevelData {
        /** The time in nanoseconds since system boot. */
        public final long timeStampNs;
        /** Fuel level in %. If unsupported by the car, this value is -1. */
        public final int level;
        /** Fuel as possible range in Km. If unsupported by the car, this value is -1. */
        public final float range;
        /** If unsupported by the car, this value is false. */
        public final boolean lowFuelWarning;

        public FuelLevelData(long timeStampNs, int level, float range, boolean lowFuelWarning) {
            this.timeStampNs = timeStampNs;
            this.level = level;
            this.range = range;
            this.lowFuelWarning = lowFuelWarning;
        }
    }

    /**
     * Convenience method for obtaining a {@link FuelLevelData} object from a CarSensorEvent object
     * with type {@link CarSensorManager#SENSOR_TYPE_FUEL_LEVEL}.
     *
     * @return a FuelLevel object corresponding to the data contained in the CarSensorEvent.
     */
    public FuelLevelData getFuelLevelData() {
        checkType(CarSensorManager.SENSOR_TYPE_FUEL_LEVEL);
        int level = -1;
        float range = -1;
        if (floatValues != null) {
            if (floatValues[INDEX_FUEL_LEVEL_IN_PERCENTILE] >= 0) {
                level = (int) floatValues[INDEX_FUEL_LEVEL_IN_PERCENTILE];
            }

            if (floatValues[INDEX_FUEL_LEVEL_IN_DISTANCE] >= 0) {
                range = floatValues[INDEX_FUEL_LEVEL_IN_DISTANCE];
            }
        }
        boolean lowFuelWarning = (intValues[0] == 1);
        return new FuelLevelData(timeStampNs, level, range, lowFuelWarning);
    }

    /** @hide */
    public static class OdometerData {
        /** The time in nanoseconds since system boot. */
        public final long timeStampNs;
        public final float kms;

        public OdometerData(long timeStampNs, float kms) {
            this.timeStampNs = timeStampNs;
            this.kms = kms;
        }
    }

    /**
     * Convenience method for obtaining an {@link OdometerData} object from a CarSensorEvent
     * object with type {@link CarSensorManager#SENSOR_TYPE_ODOMETER}.
     *
     * @return an OdometerData object corresponding to the data contained in the CarSensorEvent.
     * @hide
     */
    public OdometerData getOdometerData() {
        checkType(CarSensorManager.SENSOR_TYPE_ODOMETER);
            return new OdometerData(timeStampNs,floatValues[0]);
    }

    /** @hide */
    public static class RpmData {
        /** The time in nanoseconds since system boot. */
        public final long timeStampNs;
        public final float rpm;

        public RpmData(long timeStampNs, float rpm) {
            this.timeStampNs = timeStampNs;
            this.rpm = rpm;
        }
    }

    /**
     * Convenience method for obtaining a {@link RpmData} object from a CarSensorEvent object with
     * type {@link CarSensorManager#SENSOR_TYPE_RPM}.
     *
     * @return a RpmData object corresponding to the data contained in the CarSensorEvent.
     */
    public RpmData getRpmData() {
        checkType(CarSensorManager.SENSOR_TYPE_RPM);
        return new RpmData(timeStampNs, floatValues[0]);
    }

    /** @hide */
    public static class CarSpeedData {
        /** The time in nanoseconds since system boot. */
        public final long timeStampNs;
        public final float carSpeed;

        public CarSpeedData(long timeStampNs, float carSpeed) {
            this.timeStampNs = timeStampNs;
            this.carSpeed = carSpeed;
        }
    }

    /**
     * Convenience method for obtaining a {@link CarSpeedData} object from a CarSensorEvent
     * object with type {@link CarSensorManager#SENSOR_TYPE_CAR_SPEED}.
     *
     * @return a CarSpeedData object corresponding to the data contained in the CarSensorEvent.
     * @hide
     */
    public CarSpeedData getCarSpeedData() {
        checkType(CarSensorManager.SENSOR_TYPE_CAR_SPEED);
        return new CarSpeedData(timeStampNs, floatValues[0]);
    }

    /**
     * Convenience method for obtaining a {@link Location} object from a CarSensorEvent
     * object with type {@link CarSensorManager#SENSOR_TYPE_LOCATION}.
     *
     * @param location an optional output parameter which, if non-null, will be used by this method
     *     instead of a newly created object.
     * @return a Location object corresponding to the data contained in the CarSensorEvent.
     * @hide
     */
    public Location getLocation(Location location) {
        checkType(CarSensorManager.SENSOR_TYPE_LOCATION);
        if (location == null) {
            location = new Location("Car-GPS");
        }
        // intValues[0]: bit flags for the presence of other values following.
        int presense = intValues[0];
        if ((presense & (0x1 << INDEX_LOCATION_LATITUDE)) != 0) {
            int latE7 = intValues[INDEX_LOCATION_LATITUDE_INTS];
            location.setLatitude(latE7 * 1e-7);
        }
        if ((presense & (0x1 << INDEX_LOCATION_LONGITUDE)) != 0) {
            int longE7 = intValues[INDEX_LOCATION_LONGITUDE_INTS];
            location.setLongitude(longE7 * 1e-7);
        }
        if ((presense & (0x1 << INDEX_LOCATION_ACCURACY)) != 0) {
            location.setAccuracy(floatValues[INDEX_LOCATION_ACCURACY]);
        }
        if ((presense & (0x1 << INDEX_LOCATION_ALTITUDE)) != 0) {
            location.setAltitude(floatValues[INDEX_LOCATION_ALTITUDE]);
        }
        if ((presense & (0x1 << INDEX_LOCATION_SPEED)) != 0) {
            location.setSpeed(floatValues[INDEX_LOCATION_SPEED]);
        }
        if ((presense & (0x1 << INDEX_LOCATION_BEARING)) != 0) {
            location.setBearing(floatValues[INDEX_LOCATION_BEARING]);
        }
        location.setElapsedRealtimeNanos(timeStampNs);
        // There is a risk of scheduler delaying 2nd elapsedRealtimeNs value.
        // But will not try to fix it assuming that is acceptable as UTC time's accuracy is not
        // guaranteed in Location data.
        long currentTimeMs = System.currentTimeMillis();
        long elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos();
        location.setTime(
                currentTimeMs - (elapsedRealtimeNs - timeStampNs) / MILLI_IN_NANOS);
        return location;
    }

    /** @hide */
    public static class AccelerometerData  {
        /** The time in nanoseconds since system boot. */
        public final long timeStampNs;
        /** If unsupported by the car, this value is NaN. */
        public final float x;
        /** If unsupported by the car, this value is NaN. */
        public final float y;
        /** If unsupported by the car, this value is NaN. */
        public final float z;

        public AccelerometerData(long timeStampNs, float x, float y, float z) {
            this.timeStampNs = timeStampNs;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /**
     * Convenience method for obtaining an {@link AccelerometerData} object from a CarSensorEvent
     * object with type {@link CarSensorManager#SENSOR_TYPE_ACCELEROMETER}.
     *
     * @return a AccelerometerData object corresponding to the data contained in the CarSensorEvent.
     */
    public AccelerometerData getAccelerometerData() {
        checkType(CarSensorManager.SENSOR_TYPE_ACCELEROMETER);
        float x = floatValues[INDEX_ACCELEROMETER_X];
        float y = floatValues[INDEX_ACCELEROMETER_Y];
        float z = floatValues[INDEX_ACCELEROMETER_Z];
        return new AccelerometerData(timeStampNs, x, y, z);
    }

    /** @hide */
    public static class GyroscopeData {
        /** The time in nanoseconds since system boot. */
        public final long timeStampNs;
        /** If unsupported by the car, this value is NaN. */
        public final float x;
        /** If unsupported by the car, this value is NaN. */
        public final float y;
        /** If unsupported by the car, this value is NaN. */
        public final float z;

        public GyroscopeData(long timeStampNs, float x, float y, float z) {
            this.timeStampNs = timeStampNs;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /**
     * Convenience method for obtaining a {@link GyroscopeData} object from a CarSensorEvent object
     * with type {@link CarSensorManager#SENSOR_TYPE_GYROSCOPE}.
     *
     * @return a GyroscopeData object corresponding to the data contained in the CarSensorEvent.
     */
    public GyroscopeData getGyroscopeData() {
        checkType(CarSensorManager.SENSOR_TYPE_GYROSCOPE);
        float x = floatValues[INDEX_GYROSCOPE_X];
        float y = floatValues[INDEX_GYROSCOPE_Y];
        float z = floatValues[INDEX_GYROSCOPE_Z];
        return new GyroscopeData(timeStampNs, x, y, z);
    }

    // android.location.GpsSatellite doesn't have a public constructor, so that can't be used.
    /**
     * Class that contains GPS satellite status. For more info on meaning of these fields refer
     * to the documentation to the {@link GpsSatellite} class.
     * @hide
     */
    public static class GpsSatelliteData {
        /** The time in nanoseconds since system boot. */
        public final long timeStampNs;
        /**
         * Number of satellites used in GPS fix or -1 of unavailable.
         */
        public final int numberInUse;
        /**
         * Number of satellites in view or -1 of unavailable.
         */
        public final int numberInView;
        /**
         * Per-satellite flag if this satellite was used for GPS fix.
         * Can be null if per-satellite data is unavailable.
         */
        public final boolean[] usedInFix ;
        /**
         * Per-satellite pseudo-random id.
         * Can be null if per-satellite data is unavailable.
         */
        public final int[] prn ;
        /**
         * Per-satellite signal to noise ratio.
         * Can be null if per-satellite data is unavailable.
         */
        public final float[] snr ;
        /**
         * Per-satellite azimuth.
         * Can be null if per-satellite data is unavailable.
         */
        public final float[] azimuth ;
        /**
         * Per-satellite elevation.
         * Can be null if per-satellite data is unavailable.
         */
        public final float[] elevation ;

        public GpsSatelliteData(long timeStampNs, int numberInUse, int numberInView,
                boolean[] usedInFix, int[] prn, float[] snr, float[] azimuth, float[] elevation) {
            this.timeStampNs = timeStampNs;
            this.numberInUse = numberInUse;
            this.numberInView = numberInView;
            this.usedInFix = usedInFix;
            this.prn = prn;
            this.snr = snr;
            this.azimuth = azimuth;
            this.elevation = elevation;
        }
    }

    private final int intOffset = CarSensorEvent.INDEX_GPS_SATELLITE_ARRAY_INT_OFFSET;
    private final int intInterval = CarSensorEvent.INDEX_GPS_SATELLITE_ARRAY_INT_INTERVAL;
    private final int floatOffset = CarSensorEvent.INDEX_GPS_SATELLITE_ARRAY_FLOAT_OFFSET;
    private final int floatInterval = CarSensorEvent.INDEX_GPS_SATELLITE_ARRAY_FLOAT_INTERVAL;

    /**
     * Convenience method for obtaining a {@link GpsSatelliteData} object from a CarSensorEvent
     * object with type {@link CarSensorManager#SENSOR_TYPE_GPS_SATELLITE} with optional per-satellite info.
     *
     * @param withPerSatellite whether to include per-satellite data.
     * @return a GpsSatelliteData object corresponding to the data contained in the CarSensorEvent.
     * @hide
     */
    public GpsSatelliteData getGpsSatelliteData(boolean withPerSatellite) {
        checkType(CarSensorManager.SENSOR_TYPE_GPS_SATELLITE);

        //init all vars
        int numberInUse = intValues[CarSensorEvent.INDEX_GPS_SATELLITE_NUMBER_IN_USE];
        int numberInView = intValues[CarSensorEvent.INDEX_GPS_SATELLITE_NUMBER_IN_VIEW];
        boolean[] usedInFix = null;
        int[] prn = null;
        float[] snr = null;
        float[] azimuth = null;
        float[] elevation = null;

        if (withPerSatellite && numberInView >= 0) {
            final int numberOfSats = (floatValues.length - floatOffset) / floatInterval;
            usedInFix = new boolean[numberOfSats];
            prn = new int[numberOfSats];
            snr = new float[numberOfSats];
            azimuth = new float[numberOfSats];
            elevation = new float[numberOfSats];

            for (int i = 0; i < numberOfSats; ++i) {
                int iInt = intOffset + intInterval * i;
                int iFloat = floatOffset + floatInterval * i;
                usedInFix[i] = intValues[iInt] != 0;
                prn[i] = Math.round(
                        floatValues[iFloat + CarSensorEvent.INDEX_GPS_SATELLITE_PRN_OFFSET]);
                snr[i] =
                        floatValues[iFloat + CarSensorEvent.INDEX_GPS_SATELLITE_SNR_OFFSET];
                azimuth[i] = floatValues[iFloat
                        + CarSensorEvent.INDEX_GPS_SATELLITE_AZIMUTH_OFFSET];
                elevation[i] = floatValues[iFloat
                        + CarSensorEvent.INDEX_GPS_SATELLITE_ELEVATION_OFFSET];
            }
        }
        return new GpsSatelliteData(timeStampNs, numberInUse, numberInView, usedInFix, prn, snr,
                azimuth, elevation);
    }

    /** @hide */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getName() + "[");
        sb.append("type:" + Integer.toHexString(sensorType));
        if (floatValues != null && floatValues.length > 0) {
            sb.append(" float values:");
            for (float v: floatValues) {
                sb.append(" " + v);
            }
        }
        if (intValues != null && intValues.length > 0) {
            sb.append(" int values:");
            for (int v: intValues) {
                sb.append(" " + v);
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
