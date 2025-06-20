/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Skin;
import javafx.scene.image.Image;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.AuthenticationException;
import org.jackhuang.hmcl.auth.CredentialExpiredException;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorAccount;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorServer;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftService;
import org.jackhuang.hmcl.auth.offline.OfflineAccount;
import org.jackhuang.hmcl.auth.yggdrasil.CompleteGameProfile;
import org.jackhuang.hmcl.auth.yggdrasil.TextureType;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.DialogController;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.MultiFileItem;
import org.jackhuang.hmcl.util.skin.InvalidSkinException;
import org.jackhuang.hmcl.util.skin.NormalizedSkin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static java.util.Collections.emptySet;
import static javafx.beans.binding.Bindings.createBooleanBinding;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

public class AccountListItem extends RadioButton {

    private final Account account;
    private final StringProperty title = new SimpleStringProperty();
    private final StringProperty subtitle = new SimpleStringProperty();

    public AccountListItem(Account account) {
        this.account = account;
        getStyleClass().clear();
        setUserData(account);

        String loginTypeName = Accounts.getLocalizedLoginTypeName(Accounts.getAccountFactory(account));
        String portableSuffix = account.isPortable() ? ", " + i18n("account.portable") : "";
        if (account instanceof AuthlibInjectorAccount) {
            AuthlibInjectorServer server = ((AuthlibInjectorAccount) account).getServer();
            subtitle.bind(Bindings.concat(
                    loginTypeName, ", ", i18n("account.injector.server"), ": ",
                    Bindings.createStringBinding(server::getName, server), portableSuffix));
        } else {
            subtitle.set(loginTypeName + portableSuffix);
        }

        StringBinding characterName = Bindings.createStringBinding(account::getCharacter, account);
        if (account instanceof OfflineAccount) {
            title.bind(characterName);
        } else {
            title.bind(
                    account.getUsername().isEmpty() ? characterName :
                            Bindings.concat(account.getUsername(), " - ", characterName));
        }
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new AccountListItemSkin(this);
    }

    public Task<?> refreshAsync() {
        return Task.runAsync(() -> {
            account.clearCache();
            try {
                account.logIn();
            } catch (CredentialExpiredException e) {
                try {
                    DialogController.logIn(account);
                } catch (CancellationException e1) {
                    // ignore cancellation
                } catch (Exception e1) {
                    LOG.warning("Failed to refresh " + account + " with password", e1);
                    throw e1;
                }
            } catch (AuthenticationException e) {
                LOG.warning("Failed to refresh " + account + " with token", e);
                throw e;
            }
        });
    }

    public ObservableBooleanValue canUploadSkin() {
        if (account instanceof AuthlibInjectorAccount) {
            AuthlibInjectorAccount aiAccount = (AuthlibInjectorAccount) account;
            ObjectBinding<Optional<CompleteGameProfile>> profile = aiAccount.getYggdrasilService().getProfileRepository().binding(aiAccount.getUUID());
            return createBooleanBinding(() -> {
                Set<TextureType> uploadableTextures = profile.get()
                        .map(AuthlibInjectorAccount::getUploadableTextures)
                        .orElse(emptySet());
                return uploadableTextures.contains(TextureType.SKIN);
            }, profile);
        } else if (account instanceof OfflineAccount || account.canUploadSkin()) {
            return createBooleanBinding(() -> true);
        } else {
            return createBooleanBinding(() -> false);
        }
    }

    /**
     * @return the skin upload task, null if no file is selected
     */
    @Nullable
    public Task<?> uploadSkin() {
        if (account instanceof OfflineAccount) {
            Controllers.dialog(new OfflineAccountSkinPane((OfflineAccount) account));
            return null;
        }
        if (!account.canUploadSkin()) {
            return null;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle(i18n("account.skin.upload"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(i18n("account.skin.file"), "*.png"));
        File selectedFile = chooser.showOpenDialog(Controllers.getStage());
        if (selectedFile == null) {
            return null;
        }

        return refreshAsync()
                .thenRunAsync(() -> {
                    Image skinImg;
                    try (FileInputStream input = new FileInputStream(selectedFile)) {
                        skinImg = new Image(input);
                    } catch (IOException e) {
                        throw new InvalidSkinException("Failed to read skin image", e);
                    }
                    if (skinImg.isError()) {
                        throw new InvalidSkinException("Failed to read skin image", skinImg.getException());
                    }
                    NormalizedSkin skin = new NormalizedSkin(skinImg);
                    String model = skin.isSlim() ? "slim" : "";
                    LOG.info("Uploading skin [" + selectedFile + "], model [" + model + "]");
                    account.uploadSkin(skin.isSlim(), selectedFile.toPath());
                })
                .thenComposeAsync(refreshAsync())
                .whenComplete(Schedulers.javafx(), e -> {
                    if (e != null) {
                        Controllers.dialog(Accounts.localizeErrorMessage(e), i18n("account.skin.upload.failed"), MessageType.ERROR);
                    }
                });
    }

    public ObservableBooleanValue canChangeCape() {
        if (account.canChangeCape()) {
            return createBooleanBinding(() -> true);
        } else {
            return createBooleanBinding(() -> false);
        }
    }

    @Nullable
    public Task<?> changeCape() {
        if (!account.canChangeCape()) {
            return null;
        }

        return refreshAsync().thenRunAsync(() -> {
            List<MicrosoftService.MinecraftProfileResponseCape> capes = account.getCapes();
            if (capes.isEmpty()) {
                Platform.runLater(() -> Controllers.dialog(i18n("account.cape.none")));
            } else {
                VBox vBox = new VBox();
                vBox.setPadding(new javafx.geometry.Insets(10, 10, 10, 10));

                MultiFileItem<MicrosoftService.MinecraftProfileResponseCape> capeItem = new MultiFileItem<>();
                List<MultiFileItem.Option<MicrosoftService.MinecraftProfileResponseCape>> options = new ArrayList<>();
                for (MicrosoftService.MinecraftProfileResponseCape cape : capes) {
                    options.add(new MultiFileItem.Option<>(cape.alias, cape));
                }
                capeItem.loadChildren(options);

                vBox.getChildren().addAll(capeItem);

                Platform.runLater(()->Controllers.confirm(vBox, () -> {
                    MicrosoftService.MinecraftProfileResponseCape cape = capeItem.getSelectedData();
                    if (cape != null) {
                        try {
                            account.changeCape(cape.id);
                            Controllers.showToast(i18n("account.cape.change.success"));
                        } catch (AuthenticationException e) {
                            Controllers.dialog(Accounts.localizeErrorMessage(e), i18n("account.cape.change.failed"), MessageType.ERROR);
                        }
                    }
                }, ()->{}));
            }
        }).thenComposeAsync(refreshAsync()).whenComplete(Schedulers.javafx(), e->{
            if (e != null) {
                Controllers.dialog(Accounts.localizeErrorMessage(e), i18n("account.cape.change.failed"), MessageType.ERROR);
            }
        });
    }

    public void remove() {
        Accounts.getAccounts().remove(account);
    }

    public Account getAccount() {
        return account;
    }

    public String getTitle() {
        return title.get();
    }

    public void setTitle(String title) {
        this.title.set(title);
    }

    public StringProperty titleProperty() {
        return title;
    }

    public String getSubtitle() {
        return subtitle.get();
    }

    public void setSubtitle(String subtitle) {
        this.subtitle.set(subtitle);
    }

    public StringProperty subtitleProperty() {
        return subtitle;
    }
}
