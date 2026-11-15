/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ui.wizard;

import javafx.beans.property.StringProperty;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.task.TaskListener;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.fsl.FslTaskCenter;
import org.jackhuang.hmcl.util.SettingsMap;
import org.jackhuang.hmcl.util.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.Queue;
import java.util.concurrent.CancellationException;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

public abstract class TaskExecutorDialogWizardDisplayer extends AbstractWizardDisplayer {

    public TaskExecutorDialogWizardDisplayer(Queue<Object> cancelQueue) {
        super(cancelQueue);
    }

    /// FSL: called right after the task went to the background; closes the wizard page.
    protected void onTaskStarted() {
    }

    /// Displays a task and releases its optional resources after execution stops.
    @Override
    public void handleTask(SettingsMap settings, Task<?> task) {
        @Nullable WizardProvider.TaskCleanup cleanup = settings.remove(WizardProvider.TaskCleanup.KEY);
        // FSL: like PCL, the installation runs in the task button in the lower right corner; the
        // wizard closes at once so the rest of the launcher stays usable.
        String title = i18n("message.doing");
        if (settings.containsKey("title")) {
            Object titleValue = settings.get("title");
            if (titleValue instanceof StringProperty titleProperty && titleProperty.get() != null)
                title = titleProperty.get();
            else if (titleValue instanceof String titleMessage)
                title = titleMessage;
        }
        String taskTitle = title;

        runInFX(() -> {
            TaskExecutor executor = task.executor(new TaskListener() {
                @Override
                public void onStop(boolean success, TaskExecutor executor) {
                    @Nullable Exception failure = success ? null : executor.getException();
                    if (cleanup != null) {
                        try {
                            cleanup.cleanup();
                        } catch (Exception cleanupFailure) {
                            if (failure == null || failure instanceof CancellationException) {
                                failure = cleanupFailure;
                            } else if (failure != cleanupFailure) {
                                failure.addSuppressed(cleanupFailure);
                            }
                        }
                    }
                    @Nullable Exception completedFailure = failure;
                    runInFX(() -> {
                        if (success && completedFailure == null) {
                            onEnd();
                        } else {
                            if (completedFailure == null) {
                                onEnd();
                                return;
                            }

                            if (completedFailure instanceof CancellationException) {
                                onEnd();
                                return;
                            }

                            if (completedFailure.getCause() instanceof OutOfMemoryError outOfMemoryError) {
                                try {
                                    Controllers.dialog(StringUtils.getStackTrace(outOfMemoryError), null, MessageType.ERROR, () -> onEnd());
                                } catch (OutOfMemoryError ignored) {
                                    onEnd();
                                }
                                return;
                            }

                            String appendix = StringUtils.getStackTrace(completedFailure);
                            if (settings.get(WizardProvider.FailureCallback.KEY) != null)
                                settings.get(WizardProvider.FailureCallback.KEY).onFail(settings, completedFailure, () -> onEnd());
                            else if (settings.get("failure_message") instanceof String failureMessage)
                                Controllers.dialog(appendix, failureMessage, MessageType.ERROR, () -> onEnd());
                            else if (!settings.containsKey("forbid_failure_message"))
                                Controllers.dialog(appendix, i18n("wizard.failed"), MessageType.ERROR, () -> onEnd());
                        }

                    });
                }
            });
            FslTaskCenter.track(executor, taskTitle, true);
            executor.start();
            onTaskStarted();
        });
    }
}
