/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.ui.account;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import com.jfoenix.controls.JFXProgressBar;
import com.jfoenix.controls.JFXSpinner;
import io.nayuki.qrcodegen.QrCode;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.AuthInfo;
import org.jackhuang.hmcl.auth.AuthenticationException;
import org.jackhuang.hmcl.auth.OAuth;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
import org.jackhuang.hmcl.auth.yggdrasil.YggdrasilService;
import org.jackhuang.hmcl.game.NativeClientOAuthSession;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.theme.Themes;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.WeakListenerHolder;
import org.jackhuang.hmcl.ui.construct.*;
import org.jackhuang.hmcl.upgrade.IntegrityChecker;
import org.jackhuang.hmcl.util.FslWebComponent;
import org.jackhuang.hmcl.util.QrCodeUtils;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

public class MicrosoftAccountLoginPane extends JFXDialogLayout implements DialogAware {
    private final Account accountToRelogin;
    private final Consumer<AuthInfo> loginCallback;
    private final Runnable cancelCallback;

    @SuppressWarnings("FieldCanBeLocal")
    private final WeakListenerHolder holder = new WeakListenerHolder();

    private final ObjectProperty<Step> step = new SimpleObjectProperty<>();

    private TaskExecutor browserTaskExecutor;
    private TaskExecutor deviceTaskExecutor;

    private final JFXButton btnLogin;
    private final SpinnerPane loginButtonSpinner;

    public MicrosoftAccountLoginPane() {
        this(false);
    }

    public MicrosoftAccountLoginPane(boolean bodyonly) {
        this(null, null, null, bodyonly);
    }

    public MicrosoftAccountLoginPane(Account account, Consumer<AuthInfo> callback, Runnable onCancel, boolean bodyonly) {
        this.accountToRelogin = account;
        this.loginCallback = callback;
        this.cancelCallback = onCancel;

        getStyleClass().add("microsoft-login-dialog");
        if (bodyonly) {
            this.pseudoClassStateChanged(PseudoClass.getPseudoClass("bodyonly"), true);
        } else {
            Label heading = new Label(accountToRelogin != null ? i18n("account.login.refresh") : i18n("account.create.microsoft"));
            heading.getStyleClass().add("header-label");
            setHeading(heading);
        }

        this.setMaxWidth(650);

        onEscPressed(this, this::onCancel);

        btnLogin = new JFXButton(i18n("account.login"));
        btnLogin.getStyleClass().add("dialog-accept");

        loginButtonSpinner = new SpinnerPane();
        loginButtonSpinner.getStyleClass().add("small-spinner-pane");
        loginButtonSpinner.setContent(btnLogin);

        JFXButton btnCancel = new JFXButton(i18n("button.cancel"));
        btnCancel.getStyleClass().add("dialog-cancel");
        btnCancel.setOnAction(e -> onCancel());

        setActions(loginButtonSpinner, btnCancel);

        holder.registerWeak(Accounts.OAUTH_CALLBACK.onOpenBrowserAuthorizationCode, event -> Platform.runLater(() -> {
            if (step.get() instanceof Step.StartAuthorizationCodeLogin)
                step.set(new Step.WaitForOpenBrowser(event.getUrl()));
        }));

        holder.registerWeak(Accounts.OAUTH_CALLBACK.onGrantDeviceCode, event -> Platform.runLater(() -> {
            if (step.get() instanceof Step.StartDeviceCodeLogin)
                step.set(new Step.WaitForScanQrCode(event.getUserCode(), event.getVerificationUri()));
        }));

        holder.registerWeak(Accounts.OAUTH_CALLBACK.onLoginCompletedDeviceCode, event -> Platform.runLater(() -> {
            if (step.get() instanceof Step.WaitForScanQrCode)
                step.set(new Step.DeviceLoginCompleted());
        }));

        // FSL: after the user signs in, show which verification step is running
        holder.registerWeak(Accounts.OAUTH_CALLBACK.onLoginStage, event -> Platform.runLater(() -> {
            Step current = step.get();
            if (current instanceof Step.WaitForOpenBrowser
                    || current instanceof Step.DeviceLoginCompleted
                    || current instanceof Step.Verifying) {
                step.set(new Step.Verifying(event.getStage()));
            }
        }));

        this.step.set(Accounts.OAUTH_CALLBACK.getClientId().isEmpty()
                ? new Step.Init()
                : new Step.StartAuthorizationCodeLogin());
        FXUtils.onChangeAndOperate(step, this::onStep);
    }

    private void onStep(Step currentStep) {
        VBox rootContainer = new VBox(10);
        setBody(rootContainer);
        rootContainer.setAlignment(Pos.TOP_CENTER);

        StackPane warningPane = new StackPane();

        if (!IntegrityChecker.isOfficial()) {
            var unofficialHintPane = new HintPane(MessageDialogPane.MessageType.WARNING);
            unofficialHintPane.setSegment(i18n("unofficial.hint"));
            warningPane.getChildren().add(unofficialHintPane);
        } else if (Accounts.OAUTH_CALLBACK.getClientId().isEmpty()) {
            HintPane snapshotHint = new HintPane(MessageDialogPane.MessageType.WARNING);
            snapshotHint.setSegment(i18n("account.methods.microsoft.snapshot"));
            warningPane.getChildren().add(snapshotHint);
            btnLogin.setDisable(true);
            loginButtonSpinner.setLoading(false);
            return;
        }

        rootContainer.getChildren().add(warningPane);

        if (currentStep instanceof Step.Init) {
            btnLogin.setOnAction(e -> this.step.set(new Step.StartAuthorizationCodeLogin()));
            loginButtonSpinner.setLoading(false);

            var hintPane = new HintPane(MessageDialogPane.MessageType.INFO);
            hintPane.setText(i18n("account.methods.microsoft.hint"));
            rootContainer.getChildren().add(hintPane);
        } else if (currentStep instanceof Step.StartAuthorizationCodeLogin) {
            loginButtonSpinner.setLoading(false);
            cancelAllTasks();

            rootContainer.getChildren().add(new JFXSpinner());

            // FSL: the embedded login window needs javafx.web, which is downloaded on first use
            if (!FslWebComponent.isLoaded() && FslWebComponent.isSupported()) {
                // Keep "Log in" unavailable until the login window can actually be opened
                loginButtonSpinner.setLoading(true);

                var hintPane = new HintPane(MessageDialogPane.MessageType.INFO);
                hintPane.setText(i18n("account.methods.microsoft.methods.embedded.preparing"));
                rootContainer.getChildren().add(hintPane);

                browserTaskExecutor = FslWebComponent.getInstallTask()
                        .whenComplete(Schedulers.javafx(), exception -> {
                            if (!(this.step.get() instanceof Step.StartAuthorizationCodeLogin)) {
                                return;
                            }
                            if (exception == null) {
                                this.step.set(new Step.StartAuthorizationCodeLogin());
                            } else if (!(exception instanceof CancellationException)) {
                                LOG.warning("Failed to prepare the embedded Microsoft login", exception);
                                this.step.set(new Step.LoginFailed(i18n("account.methods.microsoft.methods.embedded.failed")
                                        + "\n" + I18n.localizeErrorMessage(exception)));
                            }
                        })
                        .executor(true);
                setBody(rootContainer);
                return;
            }

            browserTaskExecutor = Task.supplyAsync(() -> Accounts.FACTORY_MICROSOFT.create(null, null, null, null, OAuth.GrantFlow.AUTHORIZATION_CODE))
                    .whenComplete(Schedulers.javafx(), this::onLoginCompleted)
                    .executor(true);
        } else if (currentStep instanceof Step.StartDeviceCodeLogin) {
            loginButtonSpinner.setLoading(true);
            cancelAllTasks();

            rootContainer.getChildren().add(new JFXSpinner());

            deviceTaskExecutor = Task.supplyAsync(() -> Accounts.FACTORY_MICROSOFT.create(null, null, null, null, OAuth.GrantFlow.DEVICE))
                    .whenComplete(Schedulers.javafx(), this::onLoginCompleted)
                    .executor(true);
        } else if (currentStep instanceof Step.WaitForOpenBrowser wait) {
            @Nullable NativeClientOAuthSession nativeSession = Accounts.OAUTH_CALLBACK.getNativeSession();
            if (nativeSession != null) {
                // FSL: like the browser flow, the login window opens only when "Log in" is clicked
                // The button spins while the window is open, and stops if the window is closed early
                btnLogin.setOnAction(e -> {
                    loginButtonSpinner.setLoading(true);
                    FslMicrosoftLoginWindow.show(nativeSession, wait.url(), () -> {
                        if (this.step.get() == currentStep) {
                            loginButtonSpinner.setLoading(false);
                        }
                    });
                });
                loginButtonSpinner.setLoading(false);

                HintPane hintPane = new HintPane(MessageDialogPane.MessageType.INFO);
                hintPane.setText(i18n("account.methods.microsoft.methods.embedded.hint"));
                rootContainer.getChildren().add(hintPane);
            } else {
                btnLogin.setOnAction(e -> {
                    FXUtils.openLink(wait.url());
                    loginButtonSpinner.setLoading(true);
                });
                loginButtonSpinner.setLoading(false);

                HintPane hintPane = new HintPane(MessageDialogPane.MessageType.INFO);
                hintPane.setSegment(
                        i18n("account.methods.microsoft.methods.browser.hint", StringUtils.escapeXmlAttribute(wait.url()), wait.url()),
                        FXUtils::copyText
                );

                rootContainer.getChildren().add(hintPane);
            }
        } else if (currentStep instanceof Step.WaitForScanQrCode wait) {
            loginButtonSpinner.setLoading(true);

            VBox leftVBox = new VBox(6);
            leftVBox.setAlignment(Pos.CENTER);
            VBox rightVBox = new VBox(6);

            HBox hbox = new HBox(leftVBox, rightVBox);

            leftVBox.prefWidthProperty().bind(hbox.widthProperty().divide(2));
            rightVBox.prefWidthProperty().bind(hbox.widthProperty().divide(2));
            leftVBox.setMaxWidth(Double.MAX_VALUE);
            rightVBox.setMaxWidth(Double.MAX_VALUE);

            HBox.setHgrow(leftVBox, Priority.ALWAYS);
            HBox.setHgrow(rightVBox, Priority.ALWAYS);

            String scanUri = "https://www.microsoft.com/link".equals(wait.verificationUri())
                    ? "https://www.microsoft.com/link?otc=" + wait.userCode()
                    : wait.verificationUri();

            var deviceHint = new HintPane(MessageDialogPane.MessageType.INFO);
            deviceHint.setSegment(i18n("account.methods.microsoft.methods.device.hint",
                    StringUtils.escapeXmlAttribute(scanUri),
                    wait.verificationUri(),
                    wait.userCode()
            ));

            var qrCode = new SVGPath();
            qrCode.fillProperty().bind(Themes.colorSchemeProperty().getPrimary());
            qrCode.setContent(QrCodeUtils.toSVGPath(QrCode.encodeText(scanUri, QrCode.Ecc.MEDIUM)));
            qrCode.setScaleX(3);
            qrCode.setScaleY(3);

            var lblCode = new Label(wait.userCode());
            lblCode.getStyleClass().add("code-label");
            lblCode.setStyle("-fx-font-family: \"" + Objects.requireNonNullElse(settings().logFontFamilyProperty().get(), FXUtils.DEFAULT_MONOSPACE_FONT) + "\";");

            var codeBox = new StackPane(lblCode);
            codeBox.getStyleClass().add("code-box");
            codeBox.setCursor(Cursor.HAND);
            FXUtils.onClicked(codeBox, () -> FXUtils.copyText(wait.userCode()));
            codeBox.setMaxWidth(USE_PREF_SIZE);

            leftVBox.getChildren().setAll(new Group(qrCode), codeBox);
            rightVBox.getChildren().setAll(warningPane, deviceHint);

            rootContainer.getChildren().setAll(hbox);
        } else if (currentStep instanceof Step.DeviceLoginCompleted) {
            loginButtonSpinner.setLoading(true);

            var hintPane = new HintPane(MessageDialogPane.MessageType.INFO);
            hintPane.setSegment(i18n("account.methods.microsoft.methods.device.hint.completed"));

            rootContainer.getChildren().addAll(hintPane);
        } else if (currentStep instanceof Step.Verifying verifying) {
            // FSL: signed in to Microsoft; the launcher is now verifying the account step by step
            loginButtonSpinner.setLoading(true);
            if (FslWebComponent.isLoaded()) FslMicrosoftLoginWindow.close();

            int total = OAuth.LoginStage.values().length;
            int index = verifying.stage().ordinal() + 1;

            var title = new Label(i18n("account.methods.microsoft.stage.title", index, total));
            title.getStyleClass().add("subtitle-label");

            var stageLabel = new Label(i18n("account.methods.microsoft.stage." + verifying.stage().name().toLowerCase(Locale.ROOT)));

            var progress = new JFXProgressBar((double) (index - 1) / total);
            progress.setMaxWidth(Double.MAX_VALUE);

            var row = new HBox(10, new JFXSpinner(), stageLabel);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getChildren().get(0).getStyleClass().add("small-spinner");

            var box = new VBox(10, title, row, progress);
            box.setAlignment(Pos.CENTER_LEFT);
            box.setMaxWidth(Double.MAX_VALUE);
            rootContainer.getChildren().add(box);
        } else if (currentStep instanceof Step.LoginFailed failed) {
            btnLogin.setOnAction(e -> this.step.set(new Step.StartAuthorizationCodeLogin()));
            loginButtonSpinner.setLoading(false);
            cancelAllTasks();

            HintPane errHintPane = new HintPane(MessageDialogPane.MessageType.ERROR);
            errHintPane.setSegment(failed.message());
            rootContainer.getChildren().add(errHintPane);
        }

        var linkBox = new FlowPane(8, 8);
        linkBox.setAlignment(Pos.CENTER_LEFT);
        linkBox.setPrefWrapLength(500);

        if (currentStep instanceof Step.Init || currentStep instanceof Step.StartAuthorizationCodeLogin || currentStep instanceof Step.WaitForOpenBrowser) {
            JFXHyperlink useQrCode = new JFXHyperlink(i18n("account.methods.microsoft.methods.device"));
            useQrCode.setOnAction(e -> this.step.set(new Step.StartDeviceCodeLogin()));
            linkBox.getChildren().add(useQrCode);
        } else if (currentStep instanceof Step.StartDeviceCodeLogin || currentStep instanceof Step.WaitForScanQrCode) {
            JFXHyperlink userBrowser = new JFXHyperlink(i18n("account.methods.microsoft.methods.browser"));
            userBrowser.setOnAction(e -> this.step.set(new Step.StartAuthorizationCodeLogin()));
            linkBox.getChildren().add(userBrowser);
        }

        JFXHyperlink profileLink = new JFXHyperlink(i18n("account.methods.microsoft.profile"));
        profileLink.setExternalLink("https://account.live.com/editprof.aspx");
        JFXHyperlink purchaseLink = new JFXHyperlink(i18n("account.methods.microsoft.purchase"));
        purchaseLink.setExternalLink(YggdrasilService.PURCHASE_URL);

        linkBox.getChildren().addAll(profileLink, purchaseLink);
        rootContainer.getChildren().add(linkBox);

        setBody(rootContainer);
    }

    private void cancelAllTasks() {
        if (browserTaskExecutor != null) browserTaskExecutor.cancel();
        if (deviceTaskExecutor != null) deviceTaskExecutor.cancel();
        // FSL: unblock a login waiting for the embedded window and close that window
        Accounts.OAUTH_CALLBACK.cancelNativeSession();
        if (FslWebComponent.isLoaded()) FslMicrosoftLoginWindow.close();
    }

    private void onCancel() {
        cancelAllTasks();
        if (cancelCallback != null) cancelCallback.run();
        fireEvent(new DialogCloseEvent());
    }

    private void onLoginCompleted(MicrosoftAccount account, Exception exception) {
        if (FslWebComponent.isLoaded()) FslMicrosoftLoginWindow.close();
        if (exception == null) {
            boolean storageReadOnly = accountToRelogin != null
                    ? Accounts.isAccountFilesReadOnly(accountToRelogin)
                    : Accounts.isAccountFilesReadOnly(account);
            if (storageReadOnly) {
                Controllers.confirmBackupAndOverwrite(i18n("account.storage.read_only"), () -> {
                    Accounts.forceOverwriteAccountFiles(accountToRelogin != null ? accountToRelogin : account);
                    completeLogin(account);
                });
                return;
            }

            completeLogin(account);
        } else if (!(exception instanceof CancellationException)) {
            this.step.set(new Step.LoginFailed(Accounts.localizeErrorMessage(exception)));
        }
    }

    /// Adds the logged-in account, selects it, and completes the login callback.
    private void completeLogin(MicrosoftAccount account) {
        boolean replaced;
        int reloginIndex = accountToRelogin != null ? Accounts.getAccounts().indexOf(accountToRelogin) : -1;
        if (reloginIndex != -1 && Accounts.isSameAccount(accountToRelogin, account)) {
            // keep the entry where it was
            account.setPortable(accountToRelogin.isPortable());
            Accounts.getAccounts().set(reloginIndex, account);
            replaced = true;
        } else {
            if (accountToRelogin != null) Accounts.getAccounts().remove(accountToRelogin);
            // FSL: logging into an already-added account updates it instead of adding a duplicate
            replaced = Accounts.addOrReplaceAccount(account);
        }

        Accounts.setSelectedAccount(account);

        if (loginCallback != null) {
            try {
                loginCallback.accept(account.logIn());
            } catch (AuthenticationException e) {
                this.step.set(new Step.LoginFailed(Accounts.localizeErrorMessage(e)));
                return;
            }
        }
        fireEvent(new DialogCloseEvent());
        // FSL: make it obvious the account was added
        Controllers.showToast(i18n(replaced ? "account.updated" : "account.methods.microsoft.added", account.getProfileName()));
    }

    private sealed interface Step {
        final class Init implements Step {
        }

        final class StartAuthorizationCodeLogin implements Step {
        }

        record WaitForOpenBrowser(String url) implements Step {

        }

        final class StartDeviceCodeLogin implements Step {
        }

        record WaitForScanQrCode(String userCode, String verificationUri) implements Step {

        }

        record DeviceLoginCompleted() implements Step {

        }

        record LoginFailed(String message) implements Step {
        }

        /// FSL: signed in to Microsoft, verifying the account; `stage` is the step in progress.
        record Verifying(OAuth.LoginStage stage) implements Step {
        }
    }

}
