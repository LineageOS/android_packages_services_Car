/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.car.media;

import static org.junit.Assert.assertThrows;

import android.car.test.AbstractExpectableTestCase;
import android.os.Parcel;

import org.junit.Test;

public final class CarVolumeGroupInfoUnitTest extends AbstractExpectableTestCase {

    private static final int TEST_ZONE_ID = 8;
    private static final int TEST_SECONDARY_ZONE_ID = 6;
    private static final int TEST_PRIMARY_GROUP_ID = 7;
    private static final int TEST_SECONDARY_GROUP_ID = 5;
    private static final String TEST_GROUP_NAME = "3";
    private static final String TEST_SECOND_GROUP_NAME = "09";
    private static final int TEST_PARCEL_FLAGS = 0;

    @Test
    public void build_buildsGroupInfo() {
        CarVolumeGroupInfo info = new CarVolumeGroupInfo.Builder(TEST_ZONE_ID,
                TEST_PRIMARY_GROUP_ID, TEST_GROUP_NAME).build();

        expectWithMessage("Car volume info build info zone id")
                .that(info.getZoneId()).isEqualTo(TEST_ZONE_ID);
        expectWithMessage("Car volume info build info group id")
                .that(info.getId()).isEqualTo(TEST_PRIMARY_GROUP_ID);
        expectWithMessage("Car volume info build info group name")
                .that(info.getName()).isEqualTo(TEST_GROUP_NAME);
    }

    @Test
    public void setId_buildsGroupInfo() {
        CarVolumeGroupInfo.Builder builder = new CarVolumeGroupInfo.Builder(TEST_ZONE_ID,
                TEST_PRIMARY_GROUP_ID, TEST_GROUP_NAME);
        CarVolumeGroupInfo info = builder.setId(TEST_SECONDARY_GROUP_ID).build();

        expectWithMessage("Car volume info group id")
                .that(info.getId()).isEqualTo(TEST_SECONDARY_GROUP_ID);
    }

    @Test

    public void setZoneId_buildsGroupInfo() {
        CarVolumeGroupInfo.Builder builder = new CarVolumeGroupInfo.Builder(TEST_ZONE_ID,
                TEST_PRIMARY_GROUP_ID, TEST_GROUP_NAME);
        CarVolumeGroupInfo info = builder.setZoneId(TEST_SECONDARY_ZONE_ID).build();

        expectWithMessage("Car volume info group zone id")
                .that(info.getZoneId()).isEqualTo(TEST_SECONDARY_ZONE_ID);
    }

    @Test
    public void setName_buildsGroupInfo() {
        CarVolumeGroupInfo.Builder builder = new CarVolumeGroupInfo.Builder(TEST_ZONE_ID,
                TEST_PRIMARY_GROUP_ID, TEST_GROUP_NAME);
        CarVolumeGroupInfo info = builder.setName(TEST_SECOND_GROUP_NAME).build();

        expectWithMessage("Car volume info group id")
                .that(info.getName()).isEqualTo(TEST_SECOND_GROUP_NAME);
    }

    @Test
    public void setName_withNullName_fails() {
        CarVolumeGroupInfo.Builder builder = new CarVolumeGroupInfo.Builder(TEST_ZONE_ID,
                TEST_PRIMARY_GROUP_ID, TEST_GROUP_NAME);

        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                builder.setName(/* name= */ null)
        );

        expectWithMessage("Null volume info set name exception")
                .that(thrown).hasMessageThat().contains("Volume info name");
    }

    @Test
    public void build_withNullName_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new CarVolumeGroupInfo.Builder(TEST_ZONE_ID,
                        TEST_PRIMARY_GROUP_ID, /* name= */ null)
        );

        expectWithMessage("Null volume info name exception")
                .that(thrown).hasMessageThat().contains("Volume info name");
    }

    @Test
    public void builder_withReuse_fails() {
        CarVolumeGroupInfo.Builder builder = new CarVolumeGroupInfo.Builder(TEST_ZONE_ID,
                TEST_PRIMARY_GROUP_ID, TEST_GROUP_NAME);
        builder.build();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                builder.build()
        );

        expectWithMessage("Reuse builder exception")
                .that(thrown).hasMessageThat().contains("should not be reused");
    }

    @Test
    public void writeToParcel() {
        CarVolumeGroupInfo info = new CarVolumeGroupInfo.Builder(TEST_ZONE_ID,
                TEST_PRIMARY_GROUP_ID, TEST_GROUP_NAME).build();
        Parcel parcel = Parcel.obtain();

        info.writeToParcel(parcel, TEST_PARCEL_FLAGS);
        parcel.setDataPosition(/* position= */ 0);

        expectWithMessage("Car volume info from parcel")
                .that(new CarVolumeGroupInfo(parcel)).isEqualTo(info);
    }

    @Test
    public void createFromParcel() {
        CarVolumeGroupInfo info = new CarVolumeGroupInfo.Builder(TEST_ZONE_ID,
                TEST_PRIMARY_GROUP_ID, TEST_GROUP_NAME).build();
        Parcel parcel = Parcel.obtain();
        info.writeToParcel(parcel, TEST_PARCEL_FLAGS);
        parcel.setDataPosition(/* position= */ 0);

        expectWithMessage("Car volume info created from parcel")
                .that(CarVolumeGroupInfo.CREATOR.createFromParcel(parcel)).isEqualTo(info);
    }


    @Test
    public void newArray() {
        CarVolumeGroupInfo[] infos = CarVolumeGroupInfo.CREATOR.newArray(/* size= */ 3);

        expectWithMessage("Car volume infos size").that(infos)
                .hasLength(3);
    }

    @Test
    public void equals_forSameContent() {
        CarVolumeGroupInfo info = new CarVolumeGroupInfo.Builder(TEST_ZONE_ID,
                TEST_PRIMARY_GROUP_ID, TEST_GROUP_NAME).build();
        CarVolumeGroupInfo infoWithSameContent = new CarVolumeGroupInfo.Builder(TEST_ZONE_ID,
                TEST_PRIMARY_GROUP_ID, TEST_GROUP_NAME).build();

        expectWithMessage("Car volume info with same content")
                .that(info).isEqualTo(infoWithSameContent);
    }

    @Test
    public void equals_forNull() {
        CarVolumeGroupInfo info = new CarVolumeGroupInfo.Builder(TEST_ZONE_ID,
                TEST_PRIMARY_GROUP_ID, TEST_GROUP_NAME).build();

        expectWithMessage("Car volume info null content")
                .that(info).isNotNull();
    }

    @Test
    public void describeContents() {
        CarVolumeGroupInfo info = new CarVolumeGroupInfo.Builder(TEST_ZONE_ID,
                TEST_PRIMARY_GROUP_ID, TEST_GROUP_NAME).build();

        expectWithMessage("Car volume info contents")
                .that(info.describeContents()).isEqualTo(0);
    }

    @Test
    public void hashCode_forSameContent() {
        CarVolumeGroupInfo info = new CarVolumeGroupInfo.Builder(TEST_ZONE_ID,
                TEST_PRIMARY_GROUP_ID, TEST_GROUP_NAME).build();
        CarVolumeGroupInfo infoWithSameContent = new CarVolumeGroupInfo.Builder(TEST_ZONE_ID,
                TEST_PRIMARY_GROUP_ID, TEST_GROUP_NAME).build();

        expectWithMessage("Car volume info hash with same content")
                .that(info.hashCode()).isEqualTo(infoWithSameContent.hashCode());
    }

    @Test
    public void toString_forContent() {
        CarVolumeGroupInfo info = new CarVolumeGroupInfo.Builder(TEST_ZONE_ID,
                TEST_PRIMARY_GROUP_ID, TEST_GROUP_NAME).build();

        expectWithMessage("Car volume info name")
                .that(info.toString()).contains(TEST_GROUP_NAME);
        expectWithMessage("Car volume info group id")
                .that(info.toString()).contains(Integer.toString(TEST_PRIMARY_GROUP_ID));
        expectWithMessage("Car volume info group zone")
                .that(info.toString()).contains(Integer.toString(TEST_ZONE_ID));
    }
}
