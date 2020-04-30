/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.car;

import static android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociationType.CUSTOM_1;
import static android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociationType.CUSTOM_2;
import static android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociationType.CUSTOM_3;
import static android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociationType.CUSTOM_4;
import static android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociationType.KEY_FOB;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.UiModeManager;
import android.car.Car;
import android.car.input.CarInputManager;
import android.car.input.RotaryEvent;
import android.car.user.CarUserManager;
import android.car.user.GetUserIdentificationAssociationResponse;
import android.car.user.UserSwitchResult;
import android.car.userlib.HalCallback;
import android.car.userlib.UserHalHelper;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoResponseAction;
import android.hardware.automotive.vehicle.V2_0.SwitchUserMessageType;
import android.hardware.automotive.vehicle.V2_0.SwitchUserStatus;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociation;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociationType;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociationValue;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationGetRequest;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationResponse;
import android.hardware.automotive.vehicle.V2_0.UserInfo;
import android.hardware.automotive.vehicle.V2_0.UsersInfo;
import android.hardware.automotive.vehicle.V2_0.VehicleArea;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;

import com.android.car.am.FixedActivityService;
import com.android.car.audio.CarAudioService;
import com.android.car.garagemode.GarageModeService;
import com.android.car.hal.InputHalService;
import com.android.car.hal.UserHalService;
import com.android.car.hal.VehicleHal;
import com.android.car.pm.CarPackageManagerService;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.user.CarUserService;
import com.android.internal.infra.AndroidFuture;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class CarShellCommand extends ShellCommand {

    private static final String NO_INITIAL_USER = "N/A";

    private static final String TAG = CarShellCommand.class.getSimpleName();
    private static final boolean VERBOSE = false;

    private static final String COMMAND_HELP = "-h";
    private static final String COMMAND_DAY_NIGHT_MODE = "day-night-mode";
    private static final String COMMAND_INJECT_VHAL_EVENT = "inject-vhal-event";
    private static final String COMMAND_INJECT_ERROR_EVENT = "inject-error-event";
    private static final String COMMAND_ENABLE_UXR = "enable-uxr";
    private static final String COMMAND_GARAGE_MODE = "garage-mode";
    private static final String COMMAND_GET_DO_ACTIVITIES = "get-do-activities";
    private static final String COMMAND_GET_CARPROPERTYCONFIG = "get-carpropertyconfig";
    private static final String COMMAND_GET_PROPERTY_VALUE = "get-property-value";
    private static final String COMMAND_PROJECTION_AP_TETHERING = "projection-tethering";
    private static final String COMMAND_PROJECTION_UI_MODE = "projection-ui-mode";
    private static final String COMMAND_RESUME = "resume";
    private static final String COMMAND_SUSPEND = "suspend";
    private static final String COMMAND_SET_UID_TO_ZONE = "set-audio-zone-for-uid";
    private static final String COMMAND_START_FIXED_ACTIVITY_MODE = "start-fixed-activity-mode";
    private static final String COMMAND_STOP_FIXED_ACTIVITY_MODE = "stop-fixed-activity-mode";
    private static final String COMMAND_ENABLE_FEATURE = "enable-feature";
    private static final String COMMAND_DISABLE_FEATURE = "disable-feature";
    private static final String COMMAND_INJECT_KEY = "inject-key";
    private static final String COMMAND_INJECT_ROTARY = "inject-rotary";
    private static final String COMMAND_GET_INITIAL_USER_INFO = "get-initial-user-info";
    private static final String COMMAND_SWITCH_USER = "switch-user";
    private static final String COMMAND_GET_INITIAL_USER = "get-initial-user";
    private static final String COMMAND_SET_USER_ID_TO_OCCUPANT_ZONE =
            "set-occupant-zone-for-user";
    private static final String COMMAND_RESET_USER_ID_IN_OCCUPANT_ZONE =
            "reset-user-in-occupant-zone";
    private static final String COMMAND_GET_USER_AUTH_ASSOCIATION =
            "get-user-auth-association";

    // Whitelist of commands allowed in user build. All these command should be protected with
    // a permission. K: command, V: required permission.
    // Only commands with permission already granted to shell user should be allowed.
    // Commands that can affect safety should be never allowed in user build.
    private static final ArrayMap<String, String> USER_BUILD_COMMAND_TO_PERMISSION_MAP;
    static {
        USER_BUILD_COMMAND_TO_PERMISSION_MAP = new ArrayMap<>();
        USER_BUILD_COMMAND_TO_PERMISSION_MAP.put(COMMAND_GARAGE_MODE,
                android.Manifest.permission.DEVICE_POWER);
        USER_BUILD_COMMAND_TO_PERMISSION_MAP.put(COMMAND_RESUME,
                android.Manifest.permission.DEVICE_POWER);
        USER_BUILD_COMMAND_TO_PERMISSION_MAP.put(COMMAND_SUSPEND,
                android.Manifest.permission.DEVICE_POWER);
        USER_BUILD_COMMAND_TO_PERMISSION_MAP.put(COMMAND_GET_INITIAL_USER,
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        USER_BUILD_COMMAND_TO_PERMISSION_MAP.put(COMMAND_GET_INITIAL_USER_INFO,
                android.Manifest.permission.MANAGE_USERS);
        USER_BUILD_COMMAND_TO_PERMISSION_MAP.put(COMMAND_SWITCH_USER,
                android.Manifest.permission.MANAGE_USERS);
        USER_BUILD_COMMAND_TO_PERMISSION_MAP.put(COMMAND_GET_USER_AUTH_ASSOCIATION,
                android.Manifest.permission.MANAGE_USERS);
    }

    private static final String DEVICE_POWER_PERMISSION = "android.permission.DEVICE_POWER";

    private static final String PARAM_DAY_MODE = "day";
    private static final String PARAM_NIGHT_MODE = "night";
    private static final String PARAM_SENSOR_MODE = "sensor";
    private static final String PARAM_VEHICLE_PROPERTY_AREA_GLOBAL = "0";
    private static final String PARAM_ON_MODE = "on";
    private static final String PARAM_OFF_MODE = "off";
    private static final String PARAM_QUERY_MODE = "query";
    private static final String PARAM_REBOOT = "reboot";

    private static final int RESULT_OK = 0;
    private static final int RESULT_ERROR = -1; // Arbitrary value, any non-0 is fine

    private static final SparseArray<String> VALID_USER_AUTH_TYPES;
    private static final int INVALID_USER_AUTH_TYPE = -1;
    private static final String VALID_USER_AUTH_TYPES_HELP;

    static {
        VALID_USER_AUTH_TYPES = new SparseArray<String>(5);
        VALID_USER_AUTH_TYPES.put(KEY_FOB, UserIdentificationAssociationType.toString(KEY_FOB));
        VALID_USER_AUTH_TYPES.put(CUSTOM_1, UserIdentificationAssociationType.toString(CUSTOM_1));
        VALID_USER_AUTH_TYPES.put(CUSTOM_2, UserIdentificationAssociationType.toString(CUSTOM_2));
        VALID_USER_AUTH_TYPES.put(CUSTOM_3, UserIdentificationAssociationType.toString(CUSTOM_3));
        VALID_USER_AUTH_TYPES.put(CUSTOM_4, UserIdentificationAssociationType.toString(CUSTOM_4));

        StringBuilder help = new StringBuilder("Valid types are: ");
        int size = VALID_USER_AUTH_TYPES.size();
        for (int i = 0; i < size; i++) {
            help.append(VALID_USER_AUTH_TYPES.valueAt(i));
            if (i != size - 1) {
                help.append(", ");
            }
        }
        VALID_USER_AUTH_TYPES_HELP = help.append('.').toString();
    }

    private final Context mContext;
    private final VehicleHal mHal;
    private final CarAudioService mCarAudioService;
    private final CarPackageManagerService mCarPackageManagerService;
    private final CarProjectionService mCarProjectionService;
    private final CarPowerManagementService mCarPowerManagementService;
    private final FixedActivityService mFixedActivityService;
    private final CarFeatureController mFeatureController;
    private final CarInputService mCarInputService;
    private final CarNightService mCarNightService;
    private final SystemInterface mSystemInterface;
    private final GarageModeService mGarageModeService;
    private final CarUserService mCarUserService;
    private final CarOccupantZoneService mCarOccupantZoneService;

    CarShellCommand(Context context,
            VehicleHal hal,
            CarAudioService carAudioService,
            CarPackageManagerService carPackageManagerService,
            CarProjectionService carProjectionService,
            CarPowerManagementService carPowerManagementService,
            FixedActivityService fixedActivityService,
            CarFeatureController featureController,
            CarInputService carInputService,
            CarNightService carNightService,
            SystemInterface systemInterface,
            GarageModeService garageModeService,
            CarUserService carUserService,
            CarOccupantZoneService carOccupantZoneService) {
        mContext = context;
        mHal = hal;
        mCarAudioService = carAudioService;
        mCarPackageManagerService = carPackageManagerService;
        mCarProjectionService = carProjectionService;
        mCarPowerManagementService = carPowerManagementService;
        mFixedActivityService = fixedActivityService;
        mFeatureController = featureController;
        mCarInputService = carInputService;
        mCarNightService = carNightService;
        mSystemInterface = systemInterface;
        mGarageModeService = garageModeService;
        mCarUserService = carUserService;
        mCarOccupantZoneService = carOccupantZoneService;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            onHelp();
            return RESULT_ERROR;
        }
        ArrayList<String> argsList = new ArrayList<>();
        argsList.add(cmd);
        String arg = null;
        do {
            arg = getNextArg();
            if (arg != null) {
                argsList.add(arg);
            }
        } while (arg != null);
        String[] args = new String[argsList.size()];
        argsList.toArray(args);
        return exec(args, getOutPrintWriter());
    }

    @Override
    public void onHelp() {
        showHelp(getOutPrintWriter());
    }

    private static void showHelp(PrintWriter pw) {
        pw.println("Car service commands:");
        pw.println("\t-h");
        pw.println("\t  Print this help text.");
        pw.println("\tday-night-mode [day|night|sensor]");
        pw.println("\t  Force into day/night mode or restore to auto.");
        pw.println("\tinject-vhal-event property [zone] data(can be comma separated list)");
        pw.println("\t  Inject a vehicle property for testing.");
        pw.println("\tinject-error-event property zone errorCode");
        pw.println("\t  Inject an error event from VHAL for testing.");
        pw.println("\tenable-uxr true|false");
        pw.println("\t  Enable/Disable UX restrictions and App blocking.");
        pw.println("\tgarage-mode [on|off|query|reboot]");
        pw.println("\t  Force into or out of garage mode, or check status.");
        pw.println("\t  With 'reboot', enter garage mode, then reboot when it completes.");
        pw.println("\tget-do-activities pkgname");
        pw.println("\t  Get Distraction Optimized activities in given package.");
        pw.println("\tget-carpropertyconfig [propertyId]");
        pw.println("\t  Get a CarPropertyConfig by Id in Hex or list all CarPropertyConfigs");
        pw.println("\tget-property-value [propertyId] [areaId]");
        pw.println("\t  Get a vehicle property value by property id in Hex and areaId");
        pw.println("\t  or list all property values for all areaId");
        pw.println("\tsuspend");
        pw.println("\t  Suspend the system to Deep Sleep.");
        pw.println("\tresume");
        pw.println("\t  Wake the system up after a 'suspend.'");
        pw.println("\tprojection-tethering [true|false]");
        pw.println("\t  Whether tethering should be used when creating access point for"
                + " wireless projection");
        pw.println("\t--metrics");
        pw.println("\t  When used with dumpsys, only metrics will be in the dumpsys output.");
        pw.printf("\t%s [zoneid] [uid]\n", COMMAND_SET_UID_TO_ZONE);
        pw.println("\t  Maps the audio zoneid to uid.");
        pw.println("\tstart-fixed-activity displayId packageName activityName");
        pw.println("\t  Start an Activity the specified display as fixed mode");
        pw.println("\tstop-fixed-mode displayId");
        pw.println("\t  Stop fixed Activity mode for the given display. "
                + "The Activity will not be restarted upon crash.");
        pw.println("\tenable-feature featureName");
        pw.println("\t  Enable the requested feature. Change will happen after reboot.");
        pw.println("\t  This requires root/su.");
        pw.println("\tdisable-feature featureName");
        pw.println("\t  Disable the requested feature. Change will happen after reboot");
        pw.println("\t  This requires root/su.");
        pw.println("\tinject-key [-d display] [-t down_delay_ms] key_code");
        pw.println("\t  inject key down / up event to car service");
        pw.println("\t  display: 0 for main, 1 for cluster. If not specified, it will be 0.");
        pw.println("\t  down_delay_ms: delay from down to up key event. If not specified,");
        pw.println("\t                 it will be 0");
        pw.println("\t  key_code: int key code defined in android KeyEvent");
        pw.println("\tinject-rotary [-d display] [-i input_type] [-c clockwise]");
        pw.println("\t              [-dt delta_times_ms]");
        pw.println("\t  inject rotary input event to car service.");
        pw.println("\t  display: 0 for main, 1 for cluster. If not specified, it will be 0.");
        pw.println("\t  input_type: 10 for navigation controller input, 11 for volume");
        pw.println("\t              controller input. If not specified, it will be 10.");
        pw.println("\t  clockwise: true if the event is clockwise, false if the event is");
        pw.println("\t             counter-clockwise. If not specified, it will be false.");
        pw.println("\t  delta_times_ms: a list of delta time (current time minus event time)");
        pw.println("\t                  in descending order. If not specified, it will be 0.");

        pw.printf("\t%s <REQ_TYPE> [--timeout TIMEOUT_MS]\n", COMMAND_GET_INITIAL_USER_INFO);
        pw.println("\t  Calls the Vehicle HAL to get the initial boot info, passing the given");
        pw.println("\t  REQ_TYPE (which could be either FIRST_BOOT, FIRST_BOOT_AFTER_OTA, ");
        pw.println("\t  COLD_BOOT, RESUME, or any numeric value that would be passed 'as-is')");
        pw.println("\t  and an optional TIMEOUT_MS to wait for the HAL response (if not set,");
        pw.println("\t  it will use a  default value).");

        pw.printf("\t%s <USER_ID> [--hal-only] [--timeout TIMEOUT_MS]\n", COMMAND_SWITCH_USER);
        pw.println("\t  Switches to user USER_ID using the HAL integration.");
        pw.println("\t  The --hal-only option only calls HAL, without switching the user,");
        pw.println("\t  while the --timeout defines how long to wait for the HAL response");

        pw.printf("\t%s\n", COMMAND_GET_INITIAL_USER);
        pw.printf("\t  Gets the id of the initial user (or %s when it's not available)\n",
                NO_INITIAL_USER);

        pw.printf("\t%s [occupantZoneId] [userId]\n", COMMAND_SET_USER_ID_TO_OCCUPANT_ZONE);
        pw.println("\t  Maps the occupant zone id to user id.");
        pw.printf("\t%s [occupantZoneId]\n", COMMAND_RESET_USER_ID_IN_OCCUPANT_ZONE);
        pw.println("\t  Unmaps the user assigned to occupant zone id.");

        pw.printf("\t%s [--hal-only] [--user USER_ID] (TYPE1) [...TYPE_N]\n",
                COMMAND_GET_USER_AUTH_ASSOCIATION);
        pw.println("\t  Checks whether the given user authentication types are associated with ");
        pw.println("\t  the given user (or current user when not specified).");
        pw.println("\t  By defalut it calls CarUserManager, but using --hal-only will call just "
                + "UserHalService.");
        pw.printf("\t  %s\n", VALID_USER_AUTH_TYPES_HELP);
    }

    private static int showInvalidArguments(PrintWriter pw) {
        pw.println("Incorrect number of arguments.");
        showHelp(pw);
        return RESULT_ERROR;
    }

    private void runSetZoneIdForUid(String zoneString, String uidString) {
        int uid = Integer.parseInt(uidString);
        int zoneId = Integer.parseInt(zoneString);
        mCarAudioService.setZoneIdForUid(zoneId, uid);
    }

    private void runSetOccupantZoneIdForUserId(String occupantZoneIdString,
            String userIdString) {
        int userId = Integer.parseInt(userIdString);
        int occupantZoneId = Integer.parseInt(occupantZoneIdString);
        if (!mCarOccupantZoneService.assignProfileUserToOccupantZone(occupantZoneId, userId)) {
            throw new IllegalStateException("Failed to set userId " + userId + " to occupantZoneId "
                    + occupantZoneIdString);
        }
    }

    private void runResetOccupantZoneId(String occupantZoneIdString) {
        int occupantZoneId = Integer.parseInt(occupantZoneIdString);
        if (!mCarOccupantZoneService
                .assignProfileUserToOccupantZone(occupantZoneId, UserHandle.USER_NULL)) {
            throw new IllegalStateException("Failed to reset occupantZoneId "
                    + occupantZoneIdString);
        }
    }

    int exec(String[] args, PrintWriter writer) {
        String cmd = args[0];
        String requiredPermission = USER_BUILD_COMMAND_TO_PERMISSION_MAP.get(cmd);
        if (VERBOSE) {
            Log.v(TAG, "cmd: " + cmd + ", requiredPermission: " + requiredPermission);
        }
        if (Build.IS_USER && requiredPermission == null) {
            throw new SecurityException("The command " + cmd + "requires non-user build");
        }
        if (requiredPermission != null) {
            if (!ICarImpl.hasPermission(mContext, requiredPermission)) {
                throw new SecurityException("The command " + cmd + "requires permission:"
                        + requiredPermission);
            }
        }
        switch (cmd) {
            case COMMAND_HELP:
                showHelp(writer);
                break;
            case COMMAND_DAY_NIGHT_MODE: {
                String value = args.length < 2 ? "" : args[1];
                forceDayNightMode(value, writer);
                break;
            }
            case COMMAND_GARAGE_MODE: {
                String value = args.length < 2 ? "" : args[1];
                forceGarageMode(value, writer);
                break;
            }
            case COMMAND_INJECT_VHAL_EVENT:
                String zone = PARAM_VEHICLE_PROPERTY_AREA_GLOBAL;
                String data;
                if (args.length != 3 && args.length != 4) {
                    return showInvalidArguments(writer);
                } else if (args.length == 4) {
                    // Zoned
                    zone = args[2];
                    data = args[3];
                } else {
                    // Global
                    data = args[2];
                }
                injectVhalEvent(args[1], zone, data, false, writer);
                break;
            case COMMAND_INJECT_ERROR_EVENT:
                if (args.length != 4) {
                    return showInvalidArguments(writer);
                }
                String errorAreaId = args[2];
                String errorCode = args[3];
                injectVhalEvent(args[1], errorAreaId, errorCode, true, writer);
                break;
            case COMMAND_ENABLE_UXR:
                if (args.length != 2) {
                    return showInvalidArguments(writer);
                }
                boolean enableBlocking = Boolean.valueOf(args[1]);
                if (mCarPackageManagerService != null) {
                    mCarPackageManagerService.setEnableActivityBlocking(enableBlocking);
                }
                break;
            case COMMAND_GET_DO_ACTIVITIES:
                if (args.length != 2) {
                    return showInvalidArguments(writer);
                }
                String pkgName = args[1].toLowerCase();
                if (mCarPackageManagerService != null) {
                    String[] doActivities =
                            mCarPackageManagerService.getDistractionOptimizedActivities(
                                    pkgName);
                    if (doActivities != null) {
                        writer.println("DO Activities for " + pkgName);
                        for (String a : doActivities) {
                            writer.println(a);
                        }
                    } else {
                        writer.println("No DO Activities for " + pkgName);
                    }
                }
                break;
            case COMMAND_GET_CARPROPERTYCONFIG:
                String propertyId = args.length < 2 ? "" : args[1];
                mHal.dumpPropertyConfigs(writer, propertyId);
                break;
            case COMMAND_GET_PROPERTY_VALUE:
                String propId = args.length < 2 ? "" : args[1];
                String areaId = args.length < 3 ? "" : args[2];
                mHal.dumpPropertyValueByCommend(writer, propId, areaId);
                break;
            case COMMAND_PROJECTION_UI_MODE:
                if (args.length != 2) {
                    return showInvalidArguments(writer);
                }
                mCarProjectionService.setUiMode(Integer.valueOf(args[1]));
                break;
            case COMMAND_PROJECTION_AP_TETHERING:
                if (args.length != 2) {
                    return showInvalidArguments(writer);
                }
                mCarProjectionService.setAccessPointTethering(Boolean.valueOf(args[1]));
                break;
            case COMMAND_RESUME:
                mCarPowerManagementService.forceSimulatedResume();
                writer.println("Resume: Simulating resuming from Deep Sleep");
                break;
            case COMMAND_SUSPEND:
                mCarPowerManagementService.forceSuspendAndMaybeReboot(false);
                writer.println("Resume: Simulating powering down to Deep Sleep");
                break;
            case COMMAND_SET_UID_TO_ZONE:
                if (args.length != 3) {
                    return showInvalidArguments(writer);
                }
                runSetZoneIdForUid(args[1], args[2]);
                break;
            case COMMAND_SET_USER_ID_TO_OCCUPANT_ZONE:
                if (args.length != 3) {
                    return showInvalidArguments(writer);
                }
                runSetOccupantZoneIdForUserId(args[1], args[2]);
                break;
            case COMMAND_RESET_USER_ID_IN_OCCUPANT_ZONE:
                if (args.length != 2) {
                    return showInvalidArguments(writer);
                }
                runResetOccupantZoneId(args[1]);
                break;
            case COMMAND_START_FIXED_ACTIVITY_MODE:
                startFixedActivity(args, writer);
                break;
            case COMMAND_STOP_FIXED_ACTIVITY_MODE:
                stopFixedMode(args, writer);
                break;
            case COMMAND_ENABLE_FEATURE:
                if (args.length != 2) {
                    return showInvalidArguments(writer);
                }
                enableDisableFeature(args, writer, /* enable= */ true);
                break;
            case COMMAND_DISABLE_FEATURE:
                if (args.length != 2) {
                    return showInvalidArguments(writer);
                }
                enableDisableFeature(args, writer, /* enable= */ false);
                break;
            case COMMAND_INJECT_KEY:
                if (args.length < 2) {
                    return showInvalidArguments(writer);
                }
                injectKey(args, writer);
                break;
            case COMMAND_INJECT_ROTARY:
                if (args.length < 1) {
                    return showInvalidArguments(writer);
                }
                injectRotary(args, writer);
                break;
            case COMMAND_GET_INITIAL_USER_INFO:
                getInitialUserInfo(args, writer);
                break;
            case COMMAND_SWITCH_USER:
                switchUser(args, writer);
                break;
            case COMMAND_GET_INITIAL_USER:
                getInitialUser(writer);
                break;
            case COMMAND_GET_USER_AUTH_ASSOCIATION:
                getUserAuthAssociation(args, writer);
                break;
            default:
                writer.println("Unknown command: \"" + cmd + "\"");
                showHelp(writer);
                return RESULT_ERROR;
        }
        return RESULT_OK;
    }

    private void startFixedActivity(String[] args, PrintWriter writer) {
        if (args.length != 4) {
            writer.println("Incorrect number of arguments");
            showHelp(writer);
            return;
        }
        int displayId;
        try {
            displayId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            writer.println("Wrong display id:" + args[1]);
            return;
        }
        String packageName = args[2];
        String activityName = args[3];
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(packageName, activityName));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        if (!mFixedActivityService.startFixedActivityModeForDisplayAndUser(intent, options,
                displayId, ActivityManager.getCurrentUser())) {
            writer.println("Failed to start");
            return;
        }
        writer.println("Succeeded");
    }

    private void stopFixedMode(String[] args, PrintWriter writer) {
        if (args.length != 2) {
            writer.println("Incorrect number of arguments");
            showHelp(writer);
            return;
        }
        int displayId;
        try {
            displayId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            writer.println("Wrong display id:" + args[1]);
            return;
        }
        mFixedActivityService.stopFixedActivityMode(displayId);
    }

    private void enableDisableFeature(String[] args, PrintWriter writer, boolean enable) {
        if (Binder.getCallingUid() != Process.ROOT_UID) {
            writer.println("Only allowed to root/su");
            return;
        }
        String featureName = args[1];
        long id = Binder.clearCallingIdentity();
        // no permission check here
        int r;
        if (enable) {
            r = mFeatureController.enableFeature(featureName);
        } else {
            r = mFeatureController.disableFeature(featureName);
        }
        switch (r) {
            case Car.FEATURE_REQUEST_SUCCESS:
                if (enable) {
                    writer.println("Enabled feature:" + featureName);
                } else {
                    writer.println("Disabled feature:" + featureName);
                }
                break;
            case Car.FEATURE_REQUEST_ALREADY_IN_THE_STATE:
                if (enable) {
                    writer.println("Already enabled:" + featureName);
                } else {
                    writer.println("Already disabled:" + featureName);
                }
                break;
            case Car.FEATURE_REQUEST_MANDATORY:
                writer.println("Cannot change mandatory feature:" + featureName);
                break;
            case Car.FEATURE_REQUEST_NOT_EXISTING:
                writer.println("Non-existing feature:" + featureName);
                break;
            default:
                writer.println("Unknown error:" + r);
                break;
        }
        Binder.restoreCallingIdentity(id);
    }

    private void injectKey(String[] args, PrintWriter writer) {
        int i = 1; // 0 is command itself
        int display = InputHalService.DISPLAY_MAIN;
        int delayMs = 0;
        int keyCode = KeyEvent.KEYCODE_UNKNOWN;
        try {
            while (i < args.length) {
                switch (args[i]) {
                    case "-d":
                        i++;
                        display = Integer.parseInt(args[i]);
                        break;
                    case "-t":
                        i++;
                        delayMs = Integer.parseInt(args[i]);
                        break;
                    default:
                        if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                            throw new IllegalArgumentException("key_code already set:"
                                    + keyCode);
                        }
                        keyCode = Integer.parseInt(args[i]);
                }
                i++;
            }
        } catch (Exception e) {
            writer.println("Invalid args:" + e);
            showHelp(writer);
            return;
        }
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            writer.println("Missing key code or invalid keycode");
            showHelp(writer);
            return;
        }
        if (display != InputHalService.DISPLAY_MAIN
                && display != InputHalService.DISPLAY_INSTRUMENT_CLUSTER) {
            writer.println("Invalid display:" + display);
            showHelp(writer);
            return;
        }
        if (delayMs < 0) {
            writer.println("Invalid delay:" + delayMs);
            showHelp(writer);

            return;
        }
        KeyEvent keyDown = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
        mCarInputService.onKeyEvent(keyDown, display);
        SystemClock.sleep(delayMs);
        KeyEvent keyUp = new KeyEvent(KeyEvent.ACTION_UP, keyCode);
        mCarInputService.onKeyEvent(keyUp, display);
        writer.println("Succeeded");
    }

    private void injectRotary(String[] args, PrintWriter writer) {
        int i = 1; // 0 is command itself
        int display = InputHalService.DISPLAY_MAIN;
        int inputType = CarInputManager.INPUT_TYPE_ROTARY_NAVIGATION;
        boolean clockwise = false;
        List<Long> deltaTimeMs = new ArrayList<>();
        try {
            while (i < args.length) {
                switch (args[i]) {
                    case "-d":
                        i++;
                        display = Integer.parseInt(args[i]);
                        break;
                    case "-i":
                        i++;
                        inputType = Integer.parseInt(args[i]);
                        break;
                    case "-c":
                        i++;
                        clockwise = Boolean.parseBoolean(args[i]);
                        break;
                    case "-dt":
                        i++;
                        while (i < args.length) {
                            deltaTimeMs.add(Long.parseLong(args[i]));
                            i++;
                        }
                        break;
                    default:
                        writer.println("Invalid option at index " + i + ": " + args[i]);
                        return;
                }
                i++;
            }
        } catch (Exception e) {
            writer.println("Invalid args:" + e);
            showHelp(writer);
            return;
        }
        if (deltaTimeMs.isEmpty()) {
            deltaTimeMs.add(0L);
        }
        for (int j = 0; j < deltaTimeMs.size(); j++) {
            if (deltaTimeMs.get(j) < 0) {
                writer.println("Delta time shouldn't be negative: " + deltaTimeMs.get(j));
                showHelp(writer);
                return;
            }
            if (j > 0 && deltaTimeMs.get(j) > deltaTimeMs.get(j - 1)) {
                writer.println("Delta times should be in descending order");
                showHelp(writer);
                return;
            }
        }
        long[] uptimeMs = new long[deltaTimeMs.size()];
        long currentUptime = SystemClock.uptimeMillis();
        for (int j = 0; j < deltaTimeMs.size(); j++) {
            uptimeMs[j] = currentUptime - deltaTimeMs.get(j);
        }
        RotaryEvent rotaryEvent = new RotaryEvent(inputType, clockwise, uptimeMs);
        mCarInputService.onRotaryEvent(rotaryEvent, display);
        writer.println("Succeeded in injecting: " + rotaryEvent);
    }

    private void getInitialUserInfo(String[] args, PrintWriter writer) {
        if (args.length < 2) {
            writer.println("Insufficient number of args");
            return;
        }

        // Gets the request type
        String typeArg = args[1];
        int requestType = UserHalHelper.parseInitialUserInfoRequestType(typeArg);

        int timeout = 1_000;
        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--timeout":
                    timeout = Integer.parseInt(args[++i]);
                    break;
                default:
                    writer.println("Invalid option at index " + i + ": " + arg);
                    return;

            }
        }

        Log.d(TAG, "handleGetInitialUserInfo(): type=" + requestType + " (" + typeArg
                + "), timeout=" + timeout);

        UserHalService userHal = mHal.getUserHal();
        // TODO(b/150413515): use UserHalHelper to populate it with current users
        UsersInfo usersInfo = new UsersInfo();
        CountDownLatch latch = new CountDownLatch(1);

        userHal.getInitialUserInfo(requestType, timeout, usersInfo, (status, resp) -> {
            try {
                Log.d(TAG, "GetUserInfoResponse: status=" + status + ", resp=" + resp);
                writer.printf("Call status: %s\n",
                        UserHalHelper.halCallbackStatusToString(status));
                if (status != HalCallback.STATUS_OK) {
                    return;
                }
                writer.printf("Request id: %d\n", resp.requestId);
                writer.printf("Action: %s\n",
                        InitialUserInfoResponseAction.toString(resp.action));
            } finally {
                latch.countDown();
            }
        });
        waitForHal(writer, latch, timeout);
    }

    private static void waitForHal(PrintWriter writer, CountDownLatch latch, int timeoutMs) {
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                writer.printf("HAL didn't respond in %dms\n", timeoutMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.println("Interrupted waiting for HAL");
        }
        return;
    }

    private void switchUser(String[] args, PrintWriter writer) {
        if (args.length < 2) {
            writer.println("Insufficient number of args");
            return;
        }

        int targetUserId = Integer.parseInt(args[1]);
        int timeout = 1_000;
        boolean halOnly = false;

        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--timeout":
                    timeout = Integer.parseInt(args[++i]);
                    break;
                case "--hal-only":
                    halOnly = true;
                    break;
                default:
                    writer.println("Invalid option at index " + i + ": " + arg);
                    return;
            }
        }

        Log.d(TAG, "handleSwitchUser(): target=" + targetUserId + ", halOnly=" + halOnly
                + ", timeout=" + timeout);

        if (halOnly) {
            CountDownLatch latch = new CountDownLatch(1);
            UserHalService userHal = mHal.getUserHal();
            // TODO(b/150413515): use UserHalHelper to populate it with current users
            UsersInfo usersInfo = new UsersInfo();
            UserInfo targetUserInfo = new UserInfo();
            targetUserInfo.userId = targetUserId;
            // TODO(b/150413515): use UserHalHelper to set user flags

            userHal.switchUser(targetUserInfo, timeout, usersInfo, (status, resp) -> {
                try {
                    Log.d(TAG, "SwitchUserResponse: status=" + status + ", resp=" + resp);
                    writer.printf("Call Status: %s\n",
                            UserHalHelper.halCallbackStatusToString(status));
                    if (status != HalCallback.STATUS_OK) {
                        return;
                    }
                    writer.printf("Request id: %d\n", resp.requestId);
                    writer.printf("Message type: %s\n",
                            SwitchUserMessageType.toString(resp.messageType));
                    writer.printf("Switch Status: %s\n", SwitchUserStatus.toString(resp.status));
                    String errorMessage = resp.errorMessage;
                    if (!TextUtils.isEmpty(errorMessage)) {
                        writer.printf("Error message: %s", errorMessage);
                    }
                    // TODO(b/150409110): If HAL returned OK, make a "post-switch" call to the HAL
                    // indicating an Android error. This is to "rollback" the HAL switch.
                } finally {
                    latch.countDown();
                }
            });
            waitForHal(writer, latch, timeout);
            return;
        }
        Car car = Car.createCar(mContext);
        CarUserManager carUserManager =
                (CarUserManager) car.getCarManager(Car.CAR_USER_SERVICE);
        AndroidFuture<UserSwitchResult> future = carUserManager.switchUser(targetUserId);
        UserSwitchResult result = null;
        try {
            result = future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Log.e(TAG, "exception calling CarUserManager.switchUser(" + targetUserId + ")", e);
        }
        if (result == null) {
            writer.printf("Service didn't respond in %d ms", timeout);
            return;
        }
        writer.printf("UserSwitchResult: status = %s\n",
                UserSwitchResult.statusToString(result.getStatus()));
        String msg = result.getErrorMessage();
        if (msg != null && !msg.isEmpty()) {
            writer.printf("UserSwitchResult: Message = %s\n", msg);
        }
    }

    private void getInitialUser(PrintWriter writer) {
        android.content.pm.UserInfo user = mCarUserService.getInitialUser();
        writer.println(user == null ? NO_INITIAL_USER : user.id);
    }

    private void getUserAuthAssociation(String[] args, PrintWriter writer) {
        if (args.length < 2) {
            writer.println("invalid usage, must pass at least 1 argument");
            return;
        }

        boolean halOnly = false;
        int userId = UserHandle.USER_CURRENT;

        UserIdentificationGetRequest request = new UserIdentificationGetRequest();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--user":
                    try {
                        userId = Integer.parseInt(args[++i]);
                    } catch (Exception e) {
                        writer.printf("Invalid user id at index %d (from %s): %s\n", i + 1,
                                Arrays.toString(args), arg);
                    }
                    break;
                case "--hal-only":
                    halOnly = true;
                    break;
                default:
                    int type = parseAuthType(arg);
                    if (type == INVALID_USER_AUTH_TYPE) {
                        writer.printf("Invalid type at index %d (from %s): %s. %s\n", i + 1,
                                Arrays.toString(args), arg, VALID_USER_AUTH_TYPES_HELP);
                        return;
                    }
                    request.associationTypes.add(type);
            }

        }
        if (userId == UserHandle.USER_CURRENT) {
            userId = ActivityManager.getCurrentUser();
        }
        int requestSize = request.associationTypes.size();
        if (halOnly) {
            request.numberAssociationTypes = requestSize;
            // TODO(b/150413515): use UserHalHelper to set user flags
            request.userInfo.userId = userId;

            Log.d(TAG, "getUserAuthAssociation(): user=" + userId + ", halOnly=" + halOnly
                    + ", request=" + request);
            UserIdentificationResponse response = mHal.getUserHal().getUserAssociation(request);
            Log.d(TAG, "getUserAuthAssociation(): response=" + response);

            if (response == null) {
                writer.println("null response");
                return;
            }

            if (!TextUtils.isEmpty(response.errorMessage)) {
                writer.printf("Error message: %s\n", response.errorMessage);
            }
            int numberAssociations = response.associations.size();
            writer.printf("%d associations:\n", numberAssociations);
            for (int i = 0; i < numberAssociations; i++) {
                UserIdentificationAssociation association = response.associations.get(i);
                writer.printf("  %s\n", association);
            }
            return;
        }

        Context context;
        if (userId == mContext.getUserId()) {
            context = mContext;
        } else {
            context = mContext.createContextAsUser(UserHandle.of(userId), /* flags= */ 0);
        }
        int actualUserId = Binder.getCallingUid();
        if (actualUserId != userId) {
            writer.printf("Emulating call for user id %d, but caller's user id is %d, so that's "
                    + "what CarUserService will use when calling HAL.\n", userId, actualUserId);
        }

        Car car = Car.createCar(context);
        CarUserManager carUserManager = (CarUserManager) car.getCarManager(Car.CAR_USER_SERVICE);
        int[] types = new int[requestSize];
        for (int i = 0; i < types.length; i++) {
            types[i] = request.associationTypes.get(i);
        }
        GetUserIdentificationAssociationResponse response = carUserManager
                .getUserIdentificationAssociation(types);
        if (response == null) {
            writer.println("null response");
            return;
        }
        String errorMessage = response.getErrorMessage();
        if (!TextUtils.isEmpty(errorMessage)) {
            writer.printf("Error message: %s\n", errorMessage);
        }
        int[] values = response.getValues();
        writer.printf("%d associations:\n", values.length);
        for (int i = 0; i < values.length; i++) {
            writer.printf("  %s\n", UserIdentificationAssociationValue.toString(values[i]));
        }
    }

    private static int parseAuthType(@NonNull String type) {
        for (int i = 0; i < VALID_USER_AUTH_TYPES.size(); i++) {
            if (VALID_USER_AUTH_TYPES.valueAt(i).equals(type)) {
                return VALID_USER_AUTH_TYPES.keyAt(i);
            }
        }
        return INVALID_USER_AUTH_TYPE;
    }

    private void forceDayNightMode(String arg, PrintWriter writer) {
        int mode;
        switch (arg) {
            case PARAM_DAY_MODE:
                mode = CarNightService.FORCED_DAY_MODE;
                break;
            case PARAM_NIGHT_MODE:
                mode = CarNightService.FORCED_NIGHT_MODE;
                break;
            case PARAM_SENSOR_MODE:
                mode = CarNightService.FORCED_SENSOR_MODE;
                break;
            default:
                writer.println("Unknown value. Valid argument: " + PARAM_DAY_MODE + "|"
                        + PARAM_NIGHT_MODE + "|" + PARAM_SENSOR_MODE);
                return;
        }
        int current = mCarNightService.forceDayNightMode(mode);
        String currentMode = null;
        switch (current) {
            case UiModeManager.MODE_NIGHT_AUTO:
                currentMode = PARAM_SENSOR_MODE;
                break;
            case UiModeManager.MODE_NIGHT_YES:
                currentMode = PARAM_NIGHT_MODE;
                break;
            case UiModeManager.MODE_NIGHT_NO:
                currentMode = PARAM_DAY_MODE;
                break;
        }
        writer.println("DayNightMode changed to: " + currentMode);
    }

    private void forceGarageMode(String arg, PrintWriter writer) {
        switch (arg) {
            case PARAM_ON_MODE:
                mSystemInterface.setDisplayState(false);
                mGarageModeService.forceStartGarageMode();
                writer.println("Garage mode: " + mGarageModeService.isGarageModeActive());
                break;
            case PARAM_OFF_MODE:
                mSystemInterface.setDisplayState(true);
                mGarageModeService.stopAndResetGarageMode();
                writer.println("Garage mode: " + mGarageModeService.isGarageModeActive());
                break;
            case PARAM_QUERY_MODE:
                mGarageModeService.dump(writer);
                break;
            case PARAM_REBOOT:
                mCarPowerManagementService.forceSuspendAndMaybeReboot(true);
                writer.println("Entering Garage Mode. Will reboot when it completes.");
                break;
            default:
                writer.println("Unknown value. Valid argument: " + PARAM_ON_MODE + "|"
                        + PARAM_OFF_MODE + "|" + PARAM_QUERY_MODE + "|" + PARAM_REBOOT);
        }
    }

    /**
     * Inject a fake  VHAL event
     *
     * @param property the Vehicle property Id as defined in the HAL
     * @param zone     Zone that this event services
     * @param isErrorEvent indicates the type of event
     * @param value    Data value of the event
     * @param writer   PrintWriter
     */
    private void injectVhalEvent(String property, String zone, String value,
            boolean isErrorEvent, PrintWriter writer) {
        if (zone != null && (zone.equalsIgnoreCase(PARAM_VEHICLE_PROPERTY_AREA_GLOBAL))) {
            if (!isPropertyAreaTypeGlobal(property)) {
                writer.println("Property area type inconsistent with given zone");
                return;
            }
        }
        try {
            if (isErrorEvent) {
                mHal.injectOnPropertySetError(property, zone, value);
            } else {
                mHal.injectVhalEvent(property, zone, value);
            }
        } catch (NumberFormatException e) {
            writer.println("Invalid property Id zone Id or value" + e);
            showHelp(writer);
        }
    }

    // Check if the given property is global
    private static boolean isPropertyAreaTypeGlobal(@Nullable String property) {
        if (property == null) {
            return false;
        }
        return (Integer.decode(property) & VehicleArea.MASK) == VehicleArea.GLOBAL;
    }
}
