/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.wm.shell.automotive;

import android.graphics.Rect;
import android.os.Build;
import android.util.ArraySet;
import android.view.InsetsFrameProvider;

import com.android.wm.shell.dagger.WMSingleton;
import com.android.wm.shell.sysui.ShellCommandHandler;

import dagger.Lazy;

import java.io.PrintWriter;
import java.util.List;

import javax.inject.Inject;

/**
 * Handles the shell commands for the Car Wm Shell.
 *
 * <p> Use with {@code adb shell wm shell car-wm-shell &lt;command&gt;}.
 */
@WMSingleton
public final class AutoWmShellCommandHandler implements
        ShellCommandHandler.ShellCommandActionHandler {

    private static final String COMMAND_HELP = "help";
    private static final String COMMAND_DUMP = "dump";
    private static final String COMMAND_GET_ROOT_TASKS = "get-root-tasks";
    private static final String COMMAND_SET_FOCUS_ROOT_TASK = "set-focus-root-task";
    private static final String COMMAND_GET_INSETS = "get-insets";
    private static final String COMMAND_UPDATE_INSET = "update-inset";
    private static final String COMMAND_REMOVE_INSET = "remove-inset";

    private final AutoLayoutManager mAutoLayoutManager;
    private final AutoCaptionController mAutoCaptionController;
    private final Lazy<AutoTaskStackController> mAutoTaskStackController;
    private final AutoDecorManager mAutoDecorManager;
    private final AutoTaskRepository mTaskRepository;

    @Inject
    AutoWmShellCommandHandler(ShellCommandHandler shellCommandHandler,
            Lazy<AutoTaskStackController> autoTaskStackController,
            AutoTaskRepository taskRepository,
            AutoDecorManager autoDecorManager,
            AutoLayoutManager autoLayoutManager,
            AutoCaptionController autoCaptionController) {
        shellCommandHandler.addCommandCallback("car-wm-shell", this, this);
        shellCommandHandler.addDumpCallback(this::dump, this);
        mAutoTaskStackController = autoTaskStackController;
        mAutoDecorManager = autoDecorManager;
        mTaskRepository = taskRepository;
        mAutoLayoutManager = autoLayoutManager;
        mAutoCaptionController = autoCaptionController;
    }

    private void setFocusedRootTask(String[] args, PrintWriter pw) {
        if (args.length > 1) {
            int rootTaskId = Integer.parseInt(args[1]);
            AutoTaskStackTransaction ast = new AutoTaskStackTransaction().setFocusedTaskStack(
                    rootTaskId);
            AutoTaskStackControllerImpl impl =
                    (AutoTaskStackControllerImpl) mAutoTaskStackController.get();
            impl.startTransition(ast);
            pw.println("Focus set to root task " + rootTaskId);
            return;
        }

        pw.println("Invalid argument. Usage set-focus-root-task <Root-task-id>");
    }

    private void printRootTasks(String[] args, PrintWriter pw) {
        AutoTaskStackControllerImpl impl =
                (AutoTaskStackControllerImpl) mAutoTaskStackController.get();
        List<AutoTaskStack> tasks = impl.getRootTasks();
        pw.println("Root tasks: " + tasks.size());
        for (AutoTaskStack task : tasks) {
            RootTaskStack rootTaskStack = (RootTaskStack) task;
            if (rootTaskStack != null) {
                pw.println("ID: " + task.getId() + " Name: " + task.getName() + " Top Activity: "
                        + rootTaskStack.getRootTaskInfo().topActivity);
            }
        }
    }

    @Override
    public boolean onShellCommand(String[] args, PrintWriter pw) {
        if (Build.IS_USER) {
            // User builds are not supported.
            pw.println("User builds are not supported.");
            return false;
        }
        // More commands can be added here.
        switch (args[0]) {
            case COMMAND_HELP:
                pw.println("USAGE: adb shell wm shell car-wm-shell <supported-commands>");
                pw.println("supported-commands:");
                printShellCommandHelp(pw, "\t");
                return true;
            case COMMAND_DUMP:
                dump(args, pw, "");
                return true;
            case COMMAND_GET_ROOT_TASKS:
                printRootTasks(args, pw);
                return true;
            case COMMAND_SET_FOCUS_ROOT_TASK:
                setFocusedRootTask(args, pw);
                return true;
            case COMMAND_GET_INSETS:
                getInsets(args, pw);
                return true;
            case COMMAND_UPDATE_INSET:
                updateInset(args, pw);
                return true;
            case COMMAND_REMOVE_INSET:
                removeInset(args, pw);
                return true;
            default:
                pw.println("Invalid command: " + args[0]);
                return false;
        }
    }

    private void removeInset(String[] args, PrintWriter pw) {
        if (args.length < 4) {
            pw.println(
                    "Invalid argument. Usage:remove-inset <Root-task-id> <index-id> <inset-type>");
            return;
        }
        int rootTaskId = Integer.parseInt(args[1]);
        int index = Integer.parseInt(args[2]);
        int insetType = Integer.parseInt(args[3]);
        mAutoLayoutManager.removeInsets(mTaskRepository.getRootTaskStack(rootTaskId), index,
                insetType);
        pw.println("Inset removed. Index: " + index + " type: " + insetType);
    }

    private void updateInset(String[] args, PrintWriter pw) {
        if (args.length < 7) {
            pw.println(
                    "Invalid argument. Usage:remove-inset <Root-task-id> <index-id> <inset-type> "
                            + "<frame-rect>");
            return;
        }

        int rootTaskId = Integer.parseInt(args[1]);
        int index = Integer.parseInt(args[2]);
        int insetType = Integer.parseInt(args[3]);
        Rect rect = new Rect(Integer.parseInt(args[4]), Integer.parseInt(args[5]),
                Integer.parseInt(args[6]), Integer.parseInt(args[7]));
        mAutoLayoutManager.addOrUpdateInsets(mTaskRepository.getRootTaskStack(rootTaskId), index,
                insetType, rect);
        pw.println("Inset updated. Index: " + index + " type: " + insetType + " frame: " + rect);
    }

    private void getInsets(String[] args, PrintWriter pw) {
        int rootTaskId = Integer.parseInt(args[1]);
        ArraySet<InsetsFrameProvider> insets = mAutoLayoutManager.getInsets(rootTaskId);

        if (insets != null) {
            pw.println("RootTaskId: " + rootTaskId + " has " + insets.size() + " insets.");
            for (InsetsFrameProvider inset: insets) {
                pw.println("Index: " + inset.getIndex() + " Type: "
                        + inset.getType() + " Bounds: " + inset.getArbitraryRectangle());
            }
        } else {
            pw.println("RootTaskId: " + rootTaskId + " has no insets.");
        }
    }

    void dump(String[] args, PrintWriter pw, String prefix) {
        if (args.length > 1) {
            switch (args[1]) {
                case "AutoTaskRepository":
                    mTaskRepository.dump(pw, prefix);
                    return;
                case "AutoDecorManager":
                    mAutoDecorManager.dump(pw, prefix);
                    return;
                case "AutoTaskStackController":
                    ((AutoTaskStackControllerImpl) mAutoTaskStackController.get()).dump(pw, prefix);
                    return;
                case "AutoLayoutManager":
                    mAutoLayoutManager.dump(pw, prefix);
                    return;
                case "AutoCaptionController":
                    mAutoCaptionController.dump(pw, prefix);
                    return;
            }
        }
        // dump everything
        dump(pw, prefix);
    }

    void dump(PrintWriter pw, String prefix) {
        ((AutoTaskStackControllerImpl) mAutoTaskStackController.get()).dump(pw, prefix);
        mAutoDecorManager.dump(pw, prefix);
        mTaskRepository.dump(pw, prefix);
        mAutoLayoutManager.dump(pw, prefix);
        mAutoCaptionController.dump(pw, prefix);
    }

    @Override
    public void printShellCommandHelp(PrintWriter pw, String prefix) {
        pw.printf(prefix + "%s\n", COMMAND_HELP);
        pw.println(prefix + "\tPrints car-wm-shell help");
        pw.printf(prefix + "%s [className]\n", COMMAND_DUMP);
        pw.println(prefix + "\tDumps the Car window manager shell. Supported className: ");
        pw.println(prefix + "\t\tAutoTaskRepository");
        pw.println(prefix + "\t\tAutoDecorManager");
        pw.println(prefix + "\t\tAutoTaskStackController ");
        pw.println(prefix + "\t\tAutoLayoutManager");
        pw.println(prefix + "\t\tAutoCaptionController ");
        pw.println(prefix + "\tif no className is provided, then it dumps everything.");
        pw.printf(prefix + "%s\n", COMMAND_GET_ROOT_TASKS);
        pw.println(prefix + "\tProvides the existing root tasks");
        pw.printf(prefix + "%s <root-task-id>\n", COMMAND_SET_FOCUS_ROOT_TASK);
        pw.println(prefix + "\tSets the provided root task as focused");
        pw.printf(prefix + "%s <root-task-id>\n", COMMAND_GET_INSETS);
        pw.println(prefix + "\tProvides the existing inset of a root task");
        pw.printf(prefix + "%s <Root-task-id> <index-id> <inset-type> <inset-frame>\n",
                COMMAND_UPDATE_INSET);
        pw.println(prefix + "\tupdate inset to the given root task. inset-id and inset-type");
        pw.println(prefix + "\tshould be integers. inset-frame should be 4 integer values");
        pw.println(prefix + "\tdefining the rectangle");
        pw.printf(prefix + "%s <Root-task-id> <index-id> <inset-type>\n", COMMAND_REMOVE_INSET);
        pw.println(prefix + "\tremove inset to the given root task. inset-id and inset-type");
        pw.println(prefix + "\tshould be integers. Inset matching with the inset-type and index");
        pw.println(prefix + "\twill be removed");
    }
}
