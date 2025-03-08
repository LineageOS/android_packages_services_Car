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

import com.android.wm.shell.dagger.WMSingleton;
import com.android.wm.shell.sysui.ShellCommandHandler;

import dagger.Lazy;

import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * Handles the shell commands for the Car Wm Shell.
 *
 * <p> Use with {@code adb shell wm shell car-wm-shell &lt;command&gt;}.
 */
@WMSingleton
public final class AutoWmShellCommandHandler implements
        ShellCommandHandler.ShellCommandActionHandler {

    private Lazy<AutoTaskStackController> mAutoTaskStackController;
    private AutoDecorManager mAutoDecorManager;
    private AutoTaskRepository mTaskRepository;
    @Inject
    AutoWmShellCommandHandler(ShellCommandHandler shellCommandHandler,
            Lazy<AutoTaskStackController> autoTaskStackController,
            AutoTaskRepository taskRepository,
            AutoDecorManager autoDecorManager) {
        shellCommandHandler.addCommandCallback("car-wm-shell", this, this);
        shellCommandHandler.addDumpCallback(this::dump, this);
        mAutoTaskStackController = autoTaskStackController;
        mAutoDecorManager = autoDecorManager;
        mTaskRepository = taskRepository;
    }

    @Override
    public boolean onShellCommand(String[] args, PrintWriter pw) {
        // More commands can be added here.
        switch (args[0]) {
            case "dump":
                dump(pw, "");
                return true;
            default:
                pw.println("Invalid command: " + args[0]);
                return false;
        }
    }

    void dump(PrintWriter pw, String prefix) {
        ((AutoTaskStackControllerImpl) mAutoTaskStackController.get()).dump(pw, prefix);
        mAutoDecorManager.dump(pw, prefix);
        mTaskRepository.dump(pw, prefix);
    }

    @Override
    public void printShellCommandHelp(PrintWriter pw, String prefix) {
        pw.println(prefix + "dump");
        pw.println(prefix + "  Dumps the Car window manager shell");
    }
}
