/*
 * Copyright (c) 2021 by Gerrit Grunwald
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hansolo.fx.jdkmon;

import com.dustinredmond.fxtrayicon.FXTrayIcon;
import eu.hansolo.fx.jdkmon.controls.MacOSWindowButton;
import eu.hansolo.fx.jdkmon.controls.MacOSWindowButton.Size;
import eu.hansolo.fx.jdkmon.notification.Notification;
import eu.hansolo.fx.jdkmon.notification.NotificationBuilder;
import eu.hansolo.fx.jdkmon.notification.NotifierBuilder;
import eu.hansolo.fx.jdkmon.tools.Detector;
import eu.hansolo.fx.jdkmon.tools.Detector.MacOSAccentColor;
import eu.hansolo.fx.jdkmon.tools.Detector.OperatingSystem;
import eu.hansolo.fx.jdkmon.tools.Distribution;
import eu.hansolo.fx.jdkmon.tools.FileEvent;
import eu.hansolo.fx.jdkmon.tools.FileObserver;
import eu.hansolo.fx.jdkmon.tools.FileWatcher;
import eu.hansolo.fx.jdkmon.tools.Finder;
import eu.hansolo.fx.jdkmon.tools.Fonts;
import eu.hansolo.fx.jdkmon.tools.Helper;
import eu.hansolo.fx.jdkmon.tools.PropertyManager;
import eu.hansolo.fx.jdkmon.tools.ResizeHelper;
import io.foojay.api.discoclient.DiscoClient;
import io.foojay.api.discoclient.event.DownloadEvt;
import io.foojay.api.discoclient.event.Evt;
import io.foojay.api.discoclient.event.EvtObserver;
import io.foojay.api.discoclient.event.EvtType;
import io.foojay.api.discoclient.pkg.ArchiveType;
import io.foojay.api.discoclient.pkg.Pkg;
import io.foojay.api.discoclient.util.OutputFormat;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.BooleanPropertyBase;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.DirectoryChooser;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;


/**
 * User: hansolo
 * Date: 24.03.21
 * Time: 15:35
 */
public class Main extends Application {
    private static final long                         INITIAL_DELAY_IN_HOURS   = 3;
    private static final long                         RESCAN_INTERVAL_IN_HOURS = 3;
    private static final PseudoClass                  DARK_MODE_PSEUDO_CLASS   = PseudoClass.getPseudoClass("dark");
    private        final Image                        dukeNotificationIcon     = new Image(Main.class.getResourceAsStream("duke_notification.png"));
    private              Notification.Notifier        notifier;
    private              BooleanProperty              darkMode;
    private              MacOSAccentColor             accentColor;
    private              AnchorPane                   headerPane;
    private              MacOSWindowButton            closeWindowButton;
    private              Label                        windowTitle;
    private              StackPane                    pane;
    private              BorderPane                   mainPane;
    private              ScheduledExecutorService     executor;
    //private              FileWatcher                  fileWatcher;
    //private              FileObserver                 fileObserver;
    private              Stage                        stage;
    private              ObservableList<Distribution> distros;
    private              Finder                       finder;
    private              Label                        titleLabel;
    private              Label                        searchPathLabel;
    private              VBox                         distroBox;
    private              VBox                         vBox;
    private              String                       searchPath;
    private              DirectoryChooser             directoryChooser;
    private              ProgressBar                  progressBar;
    private              DiscoClient                  discoClient;
    private              BooleanProperty              blocked;
    private              AtomicBoolean                checkingForUpdates;
    private              boolean                      trayIconSupported;


    @Override public void init() {
        notifier = NotifierBuilder.create()
                                  .popupLocation(OperatingSystem.MACOS == Detector.getOperatingSystem() ? Pos.TOP_RIGHT : Pos.BOTTOM_RIGHT)
                                  .popupLifeTime(Duration.millis(5000))
                                  //.styleSheet(getClass().getResource("windows-notification.css").toExternalForm())
                                  .build();

        pane     = new StackPane();
        darkMode = new BooleanPropertyBase(false) {
            @Override protected void invalidated() { pane.pseudoClassStateChanged(DARK_MODE_PSEUDO_CLASS, get()); }
            @Override public Object getBean() { return Main.this; }
            @Override public String getName() { return "darkMode"; }
        };
        darkMode.set(Detector.isDarkMode());
        if (Detector.OperatingSystem.MACOS == Detector.getOperatingSystem()) {
            accentColor = Detector.getMacOSAccentColor();
            if (darkMode.get()) {
                pane.setStyle("-selection-color: " + Helper.colorToCss(accentColor.getColorDark()));
            } else {
                pane.setStyle("-selection-color: " + Helper.colorToCss(accentColor.getColorAqua()));
            }
        } else {
            accentColor = MacOSAccentColor.MULTI_COLOR;
        }

        closeWindowButton = new MacOSWindowButton(MacOSWindowButton.Type.CLOSE, Size.SMALL);
        closeWindowButton.setDarkMode(darkMode.get());

        windowTitle = new Label("JDK Mon");
        windowTitle.setFont(Fonts.sfProTextMedium(12));
        windowTitle.setTextFill(darkMode.get() ? Color.web("#dddddd") : Color.web("#000000"));
        windowTitle.setMouseTransparent(true);
        windowTitle.setAlignment(Pos.CENTER);

        AnchorPane.setTopAnchor(closeWindowButton, 5d);
        AnchorPane.setLeftAnchor(closeWindowButton, 5d);
        AnchorPane.setTopAnchor(windowTitle, 0d);
        AnchorPane.setRightAnchor(windowTitle, 0d);
        AnchorPane.setBottomAnchor(windowTitle, 0d);
        AnchorPane.setLeftAnchor(windowTitle, 0d);

        headerPane = new AnchorPane();
        headerPane.getStyleClass().add("header");
        headerPane.setMinHeight(21);
        headerPane.setMaxHeight(21);
        headerPane.setPrefHeight(21);
        headerPane.setEffect(new DropShadow(BlurType.TWO_PASS_BOX, Color.rgb(0, 0, 0, 0.1), 1, 0.0, 0, 1));
        headerPane.getChildren().addAll(closeWindowButton, windowTitle);


        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(() -> rescan(), INITIAL_DELAY_IN_HOURS, RESCAN_INTERVAL_IN_HOURS, TimeUnit.HOURS);

        discoClient        = new DiscoClient();
        blocked            = new SimpleBooleanProperty(false);
        checkingForUpdates = new AtomicBoolean(false);

        searchPath = PropertyManager.INSTANCE.getString(PropertyManager.SEARCH_PATH);

        //fileWatcher  = new FileWatcher(new File(searchPath));
        //fileObserver = new FileObserver() {
        //    @Override public void onCreated(final FileEvent evt) { rescan(); }
        //    @Override public void onModified(final FileEvent evt) { }
        //    @Override public void onDeleted(final FileEvent evt) { rescan(); }
        //};
        //setupFileWatcher();

        directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Choose search path");
        directoryChooser.setInitialDirectory(new File(System.getProperty("user.home")));

        distros = FXCollections.observableArrayList();
        finder  = new Finder();
        distros.setAll(finder.getDistributions(searchPath));

        titleLabel = new Label("Distributions found in");
        titleLabel.setFont(Font.font(titleLabel.getFont().getFamily(), FontWeight.BOLD, 12));

        searchPathLabel = new Label(searchPath);
        searchPathLabel.getStyleClass().add("small-label");

        VBox titleBox = new VBox(5, titleLabel, searchPathLabel);

        List<HBox> distroEntries = new ArrayList<>();
        finder.getAvailableUpdates(distros).entrySet().forEach(entry -> distroEntries.add(getDistroEntry(entry.getKey(), entry.getValue())));
        distroBox = new VBox(10);
        distroBox.getChildren().setAll(distroEntries);

        progressBar = new ProgressBar();
        progressBar.setProgress(0);
        progressBar.setVisible(false);

        Separator separator = new Separator(Orientation.HORIZONTAL);
        VBox.setMargin(separator, new Insets(5, 0, 5, 0));

        vBox = new VBox(5, titleBox, separator, distroBox, progressBar);


        pane.getChildren().add(vBox);
        pane.getStyleClass().add("jdk-mon");
        pane.setPadding(new Insets(10));

        mainPane = new BorderPane();
        mainPane.setTop(headerPane);
        mainPane.setCenter(pane);

        // Adjustments related to dark/light mode
        if (darkMode.get()) {
            headerPane.setBackground(new Background(new BackgroundFill(Color.web("#343535"), new CornerRadii(10, 10, 0, 0, false), Insets.EMPTY)));
            pane.setBackground(new Background(new BackgroundFill(Color.web("#1d1f20"), new CornerRadii(0, 0, 10, 10, false), Insets.EMPTY)));
            mainPane.setBackground(new Background(new BackgroundFill(Color.web("#1d1f20"), new CornerRadii(10), Insets.EMPTY)));
            mainPane.setBorder(new Border(new BorderStroke(Color.web("#515352"), BorderStrokeStyle.SOLID, new CornerRadii(10, 10, 10, 10, false), new BorderWidths(1))));
        } else {
            headerPane.setBackground(new Background(new BackgroundFill(Color.web("#efedec"), new CornerRadii(10, 10, 0, 0, false), Insets.EMPTY)));
            pane.setBackground(new Background(new BackgroundFill(Color.web("#ecebe9"), new CornerRadii(0, 0, 10, 10, false), Insets.EMPTY)));
            mainPane.setBackground(new Background(new BackgroundFill(Color.web("#ecebe9"), new CornerRadii(10), Insets.EMPTY)));
            mainPane.setBorder(new Border(new BorderStroke(Color.web("#f6f4f4"), BorderStrokeStyle.SOLID, new CornerRadii(10, 10, 10, 10, false), new BorderWidths(1))));
        }

        registerListeners();
    }

    private void registerListeners() {
        EvtObserver<DownloadEvt> downloadObserver = e -> {
            EvtType<? extends Evt> type = e.getEvtType();
            if (type.equals(DownloadEvt.DOWNLOAD_STARTED)) {
                blocked.set(true);
                progressBar.setVisible(true);
            } else if (type.equals(DownloadEvt.DOWNLOAD_PROGRESS)) {
                progressBar.setProgress((double) e.getFraction() / (double) e.getFileSize());
            } else if (type.equals(DownloadEvt.DOWNLOAD_FINISHED)) {
                blocked.set(false);
                progressBar.setVisible(false);
                progressBar.setProgress(0);
            } else if (type.equals(DownloadEvt.DOWNLOAD_FAILED)) {
                blocked.set(false);
                progressBar.setVisible(false);
                progressBar.setProgress(0);
            }
        };
        discoClient.setOnEvt(DownloadEvt.DOWNLOAD_STARTED, downloadObserver);
        discoClient.setOnEvt(DownloadEvt.DOWNLOAD_PROGRESS, downloadObserver);
        discoClient.setOnEvt(DownloadEvt.DOWNLOAD_FINISHED, downloadObserver);
        discoClient.setOnEvt(DownloadEvt.DOWNLOAD_FAILED, downloadObserver);

        headerPane.setOnMousePressed(press -> headerPane.setOnMouseDragged(drag -> {
            stage.setX(drag.getScreenX() - press.getSceneX());
            stage.setY(drag.getScreenY() - press.getSceneY());
        }));

        closeWindowButton.setOnMouseReleased((Consumer<MouseEvent>) e -> {
            if (stage.isShowing()) {
                if (trayIconSupported) {
                    stage.hide();
                } else {
                    stage.setMaximized(false);
                }
            } else {
                if (trayIconSupported) {
                    stage.show();
                } else {
                    stage.setWidth(330);
                    stage.setHeight(242);
                    stage.centerOnScreen();
                }
            }
        });
        closeWindowButton.setOnMouseEntered(e -> closeWindowButton.setHovered(true));
        closeWindowButton.setOnMouseExited(e -> closeWindowButton.setHovered(false));

        progressBar.prefWidthProperty().bind(mainPane.widthProperty());
    }


    @Override public void start(final Stage stage) {
        this.stage             = stage;
        this.trayIconSupported = FXTrayIcon.isSupported();

        if (trayIconSupported) {
            FXTrayIcon trayIcon = new FXTrayIcon(stage, getClass().getResource("duke.png"));
            trayIcon.setTrayIconTooltip("JDK Mon");
            trayIcon.addExitItem(false);

            MenuItem rescanItem = new MenuItem("Rescan");
            rescanItem.setOnAction(e -> rescan());
            trayIcon.addMenuItem(rescanItem);

            MenuItem searchPathItem = new MenuItem("Search path");
            searchPathItem.setOnAction( e -> selectSearchPath());
            trayIcon.addMenuItem(searchPathItem);

            MenuItem exitItem = new MenuItem("Exit");
            exitItem.setOnAction(e -> stop());
            trayIcon.addMenuItem(exitItem);

            trayIcon.show();
        } else {
            MenuBar menuBar = new MenuBar();
            menuBar.useSystemMenuBarProperty().set(true);

            Menu menu = new Menu("JDK Mon");

            MenuItem mainItem = new MenuItem("JDK Mon");
            mainItem.setOnAction(e -> {
                stage.setWidth(330);
                stage.setHeight(242);
                stage.centerOnScreen();
            });
            menu.getItems().add(mainItem);

            MenuItem rescanItem = new MenuItem("Rescan");
            rescanItem.setOnAction(e -> rescan());
            menu.getItems().add(rescanItem);

            MenuItem searchPathItem = new MenuItem("Search path");
            searchPathItem.setOnAction( e -> selectSearchPath());
            menu.getItems().add(searchPathItem);

            MenuItem exitItem = new MenuItem("Exit");
            exitItem.setOnAction(e -> stop());
            menu.getItems().add(exitItem);

            menuBar.getMenus().add(menu);
            mainPane.getChildren().add(menuBar);
        }

        Scene scene = new Scene(mainPane);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(Main.class.getResource("jdk-mon.css").toExternalForm());

        stage.setTitle("JDK Mon");
        stage.setScene(scene);
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.show();
        stage.centerOnScreen();
        stage.focusedProperty().addListener((o, ov, nv) -> {
            if (nv) {
                if (darkMode.get()) {
                    headerPane.setBackground(new Background(new BackgroundFill(Color.web("#343535"), new CornerRadii(10, 10, 0, 0, false), Insets.EMPTY)));
                    windowTitle.setTextFill(Color.web("#dddddd"));
                } else {
                    headerPane.setBackground(new Background(new BackgroundFill(Color.web("#edefef"), new CornerRadii(10, 10, 0, 0, false), Insets.EMPTY)));
                    windowTitle.setTextFill(Color.web("#000000"));
                }
                closeWindowButton.setDisable(false);
            } else {
                if (darkMode.get()) {
                    headerPane.setBackground(new Background(new BackgroundFill(Color.web("#282927"), new CornerRadii(10, 10, 0, 0, false), Insets.EMPTY)));
                    windowTitle.setTextFill(Color.web("#696a68"));
                } else {
                    headerPane.setBackground(new Background(new BackgroundFill(Color.web("#e5e7e7"), new CornerRadii(10, 10, 0, 0, false), Insets.EMPTY)));
                    windowTitle.setTextFill(Color.web("#a9a6a6"));
                    closeWindowButton.setStyle("-fx-fill: #ceccca;");
                }
                closeWindowButton.setDisable(true);
            }
        });

        ResizeHelper.addResizeListener(stage);
    }

    @Override public void stop() {
        executor.shutdownNow();
        Platform.exit();
        System.exit(0);
    }


    private void rescan() {
        Platform.runLater(() -> {
            if (checkingForUpdates.get()) { return; }
            distros.setAll(finder.getDistributions(searchPath));
            checkForUpdates();
        });
    }

    private void checkForUpdates() {
        checkingForUpdates.set(true);
        AtomicBoolean updatesAvailable = new AtomicBoolean(false);
        StringBuilder msgBuilder       = new StringBuilder();
        List<Node>    distroEntries    = new ArrayList<>();
        finder.getAvailableUpdates(distros).entrySet().forEach(entry -> {
            HBox distroEntry = getDistroEntry(entry.getKey(), entry.getValue());
            distroEntries.add(distroEntry);
            if (distroEntry.getChildren().size() > 1) {
                msgBuilder.append(entry.getKey().getName()).append(" ").append(((Label)distroEntry.getChildren().get(2)).getText()).append("\n");
                updatesAvailable.set(true);
            }
        });
        Platform.runLater(() -> distroBox.getChildren().setAll(distroEntries));
        
        if (updatesAvailable.get()) {
            Notification notification = NotificationBuilder.create().title("New updates available").message(msgBuilder.toString()).image(dukeNotificationIcon).build();
            notifier.notify(notification);
        }
        checkingForUpdates.set(false);
    }

    private HBox getDistroEntry(final Distribution distribution, final List<Pkg> pkgs) {
        Label distroLabel = new Label(new StringBuilder(distribution.getName()).append(distribution.getFxBundled() ? " (FX)" : "").append("  ").append(distribution.getVersion()).toString());
        distroLabel.setMinWidth(180);
        distroLabel.setAlignment(Pos.CENTER_LEFT);
        distroLabel.setMaxWidth(Double.MAX_VALUE);

        HBox hBox = new HBox(5, distroLabel);

        if (pkgs.isEmpty()) { return hBox; }

        Pkg     firstPkg         = pkgs.get(0);
        String  nameToCheck      = firstPkg.getDistribution().getApiString();
        Boolean fxBundledToCheck = firstPkg.isJavaFXBundled();
        String  versionToCheck   = firstPkg.getJavaVersion().getVersionNumber().toString(OutputFormat.REDUCED_COMPRESSED, true, false);
        for (Distribution distro : distros) {
            if (distro.getApiString().equals(nameToCheck) && distro.getVersion().equals(versionToCheck) && distro.getFxBundled() == fxBundledToCheck) {
                return hBox;
            }
        }

        Label  arrowLabel   = new Label(" -> ");
        hBox.getChildren().add(arrowLabel);


        // ******************** Create popup **********************************
        Popup popup = new Popup();

        MacOSWindowButton closePopupButton = new MacOSWindowButton(MacOSWindowButton.Type.CLOSE, Size.SMALL);
        closePopupButton.setDarkMode(darkMode.get());
        closePopupButton.setOnMouseReleased((Consumer<MouseEvent>) e -> popup.hide());
        closePopupButton.setOnMouseEntered(e -> closePopupButton.setHovered(true));
        closePopupButton.setOnMouseExited(e -> closePopupButton.setHovered(false));

        Label popupTitle = new Label("Alternative distribution");
        popupTitle.setFont(Fonts.sfProTextMedium(12));
        popupTitle.setTextFill(darkMode.get() ? Color.web("#dddddd") : Color.web("#000000"));
        popupTitle.setMouseTransparent(true);
        popupTitle.setAlignment(Pos.CENTER);

        AnchorPane.setTopAnchor(closePopupButton, 5d);
        AnchorPane.setLeftAnchor(closePopupButton, 5d);
        AnchorPane.setTopAnchor(popupTitle, 0d);
        AnchorPane.setRightAnchor(popupTitle, 0d);
        AnchorPane.setBottomAnchor(popupTitle, 0d);
        AnchorPane.setLeftAnchor(popupTitle, 0d);

        AnchorPane popupHeader = new AnchorPane();
        popupHeader.getStyleClass().add("header");
        popupHeader.setMinHeight(21);
        popupHeader.setMaxHeight(21);
        popupHeader.setPrefHeight(21);
        popupHeader.setEffect(new DropShadow(BlurType.TWO_PASS_BOX, Color.rgb(0, 0, 0, 0.1), 1, 0.0, 0, 1));
        popupHeader.getChildren().addAll(closePopupButton, popupTitle);

        Label popupMsg = new Label(firstPkg.getDistributionName() + " " + firstPkg.getJavaVersion().toString(true) + " available");
        popupMsg.setTextFill(darkMode.get() ? Color.web("#dddddd") : Color.web("#868687"));
        popupMsg.getStyleClass().add("msg-label");

        HBox otherDistroPkgsBox = new HBox(5);

        VBox popupContent = new VBox(5, popupMsg, otherDistroPkgsBox);
        popupContent.setPadding(new Insets(10));

        BorderPane popupPane = new BorderPane();
        popupPane.getStyleClass().add("popup");
        popupPane.setTop(popupHeader);
        popupPane.setCenter(popupContent);

        // Adjustments related to dark/light mode
        if (darkMode.get()) {
            popupHeader.setBackground(new Background(new BackgroundFill(Color.web("#343535"), new CornerRadii(10, 10, 0, 0, false), Insets.EMPTY)));
            popupContent.setBackground(new Background(new BackgroundFill(Color.web("#1d1f20"), new CornerRadii(0, 0, 10, 10, false), Insets.EMPTY)));
            popupPane.setBackground(new Background(new BackgroundFill(Color.web("#1d1f20"), new CornerRadii(10), Insets.EMPTY)));
            popupPane.setBorder(new Border(new BorderStroke(Color.web("#515352"), BorderStrokeStyle.SOLID, new CornerRadii(10, 10, 10, 10, false), new BorderWidths(1))));
        } else {
            popupHeader.setBackground(new Background(new BackgroundFill(Color.web("#efedec"), new CornerRadii(10, 10, 0, 0, false), Insets.EMPTY)));
            popupContent.setBackground(new Background(new BackgroundFill(Color.web("#e3e5e5"), new CornerRadii(0, 0, 10, 10, false), Insets.EMPTY)));
            popupPane.setBackground(new Background(new BackgroundFill(Color.web("#ecebe9"), new CornerRadii(10), Insets.EMPTY)));
            popupPane.setBorder(new Border(new BorderStroke(Color.web("#f6f4f4"), BorderStrokeStyle.SOLID, new CornerRadii(10, 10, 10, 10, false), new BorderWidths(1))));
        }

        popup.getContent().add(popupPane);
        // ********************************************************************


        if (distribution.getApiString().equals(nameToCheck)) {
            Label versionLabel = new Label(firstPkg.getJavaVersion().toString(true));
            versionLabel.setMinWidth(56);
            hBox.getChildren().add(versionLabel);
        } else {
            // There is a newer update for the currently installed version from another distribution
            Region infoIcon = new Region();
            infoIcon.getStyleClass().add("icon");
            infoIcon.setId("info");
            infoIcon.setOnMousePressed(e -> {
                if (null != popup) {
                    popup.setX(e.getScreenX() + 10);
                    popup.setY(e.getScreenY() + 10);
                    popup.show(stage);
                }
            });
            hBox.getChildren().add(infoIcon);
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        hBox.getChildren().add(spacer);

        Collections.sort(pkgs, Comparator.comparing(Pkg::getArchiveType));
        pkgs.forEach(pkg -> {
                ArchiveType archiveType      = pkg.getArchiveType();
                Label       archiveTypeLabel = new Label(archiveType.getUiString());
                archiveTypeLabel.getStyleClass().add("tag-label");
                archiveTypeLabel.setTooltip(new Tooltip("Download " + pkg.getFileName()));
                switch (archiveType) {
                    case APK, BIN, CAB, EXE, MSI, ZIP -> archiveTypeLabel.setBackground(new Background(
                        new BackgroundFill(darkMode.get() ? MacOSAccentColor.GREEN.getColorDark() : MacOSAccentColor.GREEN.getColorAqua(), new CornerRadii(2.5), Insets.EMPTY)));
                    case DEB, TAR, TAR_GZ, TAR_Z, RPM -> archiveTypeLabel.setBackground(new Background(
                        new BackgroundFill(darkMode.get() ? MacOSAccentColor.ORANGE.getColorDark() : MacOSAccentColor.ORANGE.getColorAqua(), new CornerRadii(2.5), Insets.EMPTY)));
                    case PKG, DMG -> archiveTypeLabel.setBackground(new Background(
                        new BackgroundFill(darkMode.get() ? MacOSAccentColor.YELLOW.getColorDark() : MacOSAccentColor.YELLOW.getColorAqua(), new CornerRadii(2.5), Insets.EMPTY)));
                }
                archiveTypeLabel.disableProperty().bind(blocked);
                archiveTypeLabel.setOnMouseClicked(e -> { if (!blocked.get()) { downloadPkg(pkg.getId(), pkg.getFileName()); }});

                if (pkg.getDistribution().getApiString().equals(distribution.getApiString())) {
                    hBox.getChildren().add(archiveTypeLabel);
                } else {
                    // Add tags to popup
                    otherDistroPkgsBox.getChildren().add(archiveTypeLabel);
                }
        });
        return hBox;
    }

    private void downloadPkg(final String pkgId, final String filename) {
        directoryChooser.setTitle("Choose folder for download");
        final File targetFolder = directoryChooser.showDialog(stage);
        if (null != targetFolder) {
            discoClient.downloadPkg(pkgId, targetFolder.getAbsolutePath() + File.separator + filename);
            new Alert(AlertType.INFORMATION, "Download started. Update will be saved to " + targetFolder).show();
        }
    }

    private void selectSearchPath() {
        boolean searchPathExists = new File(searchPath).exists();
        directoryChooser.setTitle("Choose search path");
        directoryChooser.setInitialDirectory(searchPathExists ? new File(searchPath) : new File(System.getProperty("user.home")));
        final File selectedFolder = directoryChooser.showDialog(stage);
        if (null != selectedFolder) {
            searchPath = selectedFolder.getAbsolutePath() + File.separator;
            PropertyManager.INSTANCE.set(PropertyManager.SEARCH_PATH, searchPath);
            //setupFileWatcher();
            searchPathLabel.setText(searchPath);
            rescan();
        }
    }

    /*
    private void setupFileWatcher() {
        if (null != fileWatcher) { fileWatcher.removeObserver(fileObserver); }
        fileWatcher = new FileWatcher(new File(searchPath));
        fileWatcher.addObserver(fileObserver);
        fileWatcher.watch();
    }
    */

    public static void main(String[] args) {
        launch(args);
    }
}
