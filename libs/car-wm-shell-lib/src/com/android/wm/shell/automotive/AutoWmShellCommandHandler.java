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
        // More commands can be added here.
        switch (args[0]) {
            case "dump":
                dump(args, pw, "");
                return true;
            case "get-root-tasks":
                printRootTasks(args, pw);
                return true;
            case "set-focus-root-task":
                setFocusedRootTask(args, pw);
                return true;
            case "get-insets":
                getInsets(args, pw);
                return true;
            case "update-inset":
                updateInset(args, pw);
                return true;
            case "remove-inset":
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
        pw.println(prefix + "dump");
        pw.println(prefix + "  Dumps the Car window manager shell");
        pw.println(prefix + "get-root-tasks");
        pw.println(prefix + "  Provides the existing root tasks");
        pw.println(prefix + "set-focus-root-task <Root-task-id>");
        pw.println(prefix + "  Sets the provided root task as focused");
        pw.println(prefix + "get-insets <root-task-id>");
        pw.println(prefix + "  Provides the existing inset of a root task");
        pw.println(prefix + "update-inset <Root-task-id> <index-id> <inset-type> <inset-frame>");
        pw.println(prefix
                + "  update inset to the given root task. inset-id and inset-type should be "
                + "integers. inset-frame should be 4 integer values defining the rectangle");
        pw.println(prefix + "remove-inset <Root-task-id> <index-id> <inset-type> ");
        pw.println(prefix
                + "  remove inset to the given root task. inset-id and inset-type should be "
                + "integers. Inset matching with the inset-type and index will be removed");
    }
}
