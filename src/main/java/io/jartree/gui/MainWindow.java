package io.jartree.gui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javafx.application.HostServices;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import io.jartree.compare.ComparisonResult;
import io.jartree.compare.ResultCache;
import io.jartree.report.ConsoleReport;
import io.jartree.report.HtmlReport;
import io.jartree.report.JsonReport;
import io.jartree.report.PatchReport;

/**
 * Application window: a tab per comparison, with the menu, the status bar and the log shared between them.
 * Every tab holds its own {@link ComparisonPane} and can run its own comparison.
 */
final class MainWindow extends BorderPane {

    private static final FileChooser.ExtensionFilter JSON = new FileChooser.ExtensionFilter("JSON report", "*.json");
    private static final FileChooser.ExtensionFilter COMPARISON = new FileChooser.ExtensionFilter(
            "Comparison", "*." + ComparisonFile.EXTENSION);
    private static final FileChooser.ExtensionFilter OPENABLE = new FileChooser.ExtensionFilter(
            "Comparison or JSON report", "*." + ComparisonFile.EXTENSION, "*.json");

    private final Stage stage;
    private final HostServices hostServices;
    private final Settings settings = new Settings();
    private final ObjectProperty<DiffView.Mode> diffMode = new SimpleObjectProperty<>(DiffView.Mode.UNIFIED);

    private final TabPane tabs = new TabPane();
    private final Tab newTab = new Tab("+");
    private final Label statusMessage = new Label("Ready");
    private final Label resultInfo = new Label();
    private final ProgressBar progress = new ProgressBar();
    private final List<String> log = new ArrayList<>();
    private final CheckMenuItem exportVisibleOnly = new CheckMenuItem("Export visible libraries only");
    private final List<MenuItem> resultActions = new ArrayList<>();
    private final ChangeListener<String> titleListener = (obs, o, n) -> showTitle(n);
    /** The pane whose title is currently shown in the window title. */
    private ComparisonPane titled;

    MainWindow(Stage stage, HostServices hostServices) {
        this.stage = stage;
        this.hostServices = hostServices;
        getStyleClass().add("main-window");

        setTop(menuBar());
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        newTab.setClosable(false);
        newTab.setId("new-tab");
        newTab.setTooltip(new Tooltip("New comparison (Ctrl+N)"));
        tabs.getTabs().add(newTab);
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, old, tab) -> {
            if (tab == newTab) {
                // the "+" tab is a button: open a comparison instead of showing it
                if (old != null && old != newTab) {
                    tabs.getSelectionModel().select(old);
                }
                addTab(null);
            } else {
                activated(pane(tab));
            }
        });
        setCenter(tabs);
        setBottom(statusBar());

        try {
            diffMode.set(DiffView.Mode.valueOf(settings.get("diffMode", DiffView.Mode.UNIFIED.name())));
        } catch (IllegalArgumentException e) {
            diffMode.set(DiffView.Mode.UNIFIED);
        }
        diffMode.addListener((obs, o, n) -> settings.put("diffMode", n.name()));
        installDragAndDrop();
        restoreTabs();
    }

    // ------------------------------------------------------------------ tabs

    /** Opens a tab; with {@code paths} null it starts from the remembered defaults. */
    ComparisonPane addTab(String[] paths) {
        ComparisonPane pane = new ComparisonPane(stage, settings, diffMode, this::log, this::showLog);
        if (paths != null) {
            pane.setPaths(paths[0], paths[1]);
        }
        Tab tab = new Tab();
        tab.setContent(pane);
        tab.textProperty().bind(pane.titleProperty());
        tab.setUserData(pane);
        Tooltip tooltip = new Tooltip();
        // the file Ctrl+S saves to, which a comparison run since opening or saving no longer names the tab after
        tooltip.textProperty().bind(Bindings.createStringBinding(() -> pane.titleProperty().get()
                        + (pane.fileProperty().get() == null ? "" : "\nSaved as " + pane.fileProperty().get()),
                pane.titleProperty(), pane.fileProperty()));
        tab.setTooltip(tooltip);
        tab.setOnCloseRequest(e -> {
            if (pane.isRunning() && !confirm("Stop the comparison?",
                    "A comparison is running in this tab. Close it and stop the comparison?")) {
                e.consume();
                return;
            }
            pane.cancel();
        });
        pane.resultProperty().addListener((obs, o, n) -> {
            if (pane == activePane()) {
                updateResultActions();
            }
        });
        tabs.getTabs().add(tabs.getTabs().size() - 1, tab);
        tabs.getSelectionModel().select(tab);
        return pane;
    }

    private static ComparisonPane pane(Tab tab) {
        return tab == null ? null : (ComparisonPane) tab.getUserData();
    }

    ComparisonPane activePane() {
        return pane(tabs.getSelectionModel().getSelectedItem());
    }

    private void activated(ComparisonPane pane) {
        statusMessage.textProperty().unbind();
        resultInfo.textProperty().unbind();
        progress.progressProperty().unbind();
        progress.visibleProperty().unbind();
        // the stage title follows the active tab; it is set, not bound, so that the caller can set it too
        if (titled != null) {
            titled.titleProperty().removeListener(titleListener);
        }
        titled = pane;
        if (pane == null) {
            statusMessage.setText("Ready");
            resultInfo.setText("");
            progress.setVisible(false);
            showTitle(null);
        } else {
            statusMessage.textProperty().bind(pane.statusProperty());
            resultInfo.textProperty().bind(pane.infoProperty());
            progress.progressProperty().bind(pane.progressProperty());
            progress.visibleProperty().bind(pane.runningProperty());
            pane.titleProperty().addListener(titleListener);
            showTitle(pane.titleProperty().get());
        }
        updateResultActions();
    }

    private void showTitle(String tabTitle) {
        stage.setTitle(tabTitle == null ? "jartree-compare" : "jartree-compare — " + tabTitle);
    }

    private void updateResultActions() {
        ComparisonPane pane = activePane();
        boolean available = pane != null && pane.result() != null;
        resultActions.forEach(m -> m.setDisable(!available));
    }

    private boolean confirm(String header, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(stage);
        alert.setTitle("jartree-compare");
        alert.setHeaderText(header);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    // ------------------------------------------------------------------ menu and status bar

    private MenuBar menuBar() {
        MenuItem newComparison = new MenuItem("New comparison");
        newComparison.setAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN));
        newComparison.setOnAction(e -> addTab(null));
        MenuItem duplicate = new MenuItem("Duplicate comparison");
        duplicate.setOnAction(e -> {
            ComparisonPane pane = activePane();
            if (pane != null) {
                addTab(new String[] {pane.oldPathText(), pane.newPathText()});
            }
        });
        MenuItem closeTab = new MenuItem("Close comparison");
        closeTab.setAccelerator(new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN));
        closeTab.setOnAction(e -> closeActiveTab());
        MenuItem compare = new MenuItem("Compare");
        compare.setAccelerator(new KeyCodeCombination(KeyCode.F5));
        compare.setOnAction(e -> withPane(ComparisonPane::compare));
        MenuItem open = new MenuItem("Open…");
        open.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN));
        open.setOnAction(e -> chooseFile());
        MenuItem save = new MenuItem("Save comparison");
        save.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN));
        save.setOnAction(e -> withPane(p -> saveComparison(p, false)));
        MenuItem saveAs = new MenuItem("Save comparison as…");
        saveAs.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN,
                KeyCombination.SHIFT_DOWN));
        saveAs.setOnAction(e -> withPane(p -> saveComparison(p, true)));
        MenuItem html = new MenuItem("HTML report…");
        html.setOnAction(e -> export("html"));
        MenuItem patch = new MenuItem("Unified diff (patch)…");
        patch.setOnAction(e -> export("diff"));
        MenuItem json = new MenuItem("JSON report…");
        json.setOnAction(e -> export("json"));
        Menu exportMenu = new Menu("Export", null, html, patch, json, new SeparatorMenuItem(), exportVisibleOnly);
        MenuItem browser = new MenuItem("Open HTML report in browser");
        browser.setAccelerator(new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN));
        browser.setOnAction(e -> openInBrowser());
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(e -> stage.close());
        Menu file = new Menu("File", null, newComparison, duplicate, closeTab, new SeparatorMenuItem(), compare,
                new SeparatorMenuItem(), open, save, saveAs, new SeparatorMenuItem(), exportMenu, browser,
                new SeparatorMenuItem(), exit);
        resultActions.addAll(List.of(html, patch, json, browser));

        MenuItem find = new MenuItem("Find…");
        find.setAccelerator(new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN));
        find.setOnAction(e -> withPane(ComparisonPane::focusSearch));
        MenuItem expand = new MenuItem("Expand all");
        expand.setAccelerator(new KeyCodeCombination(KeyCode.E, KeyCombination.SHORTCUT_DOWN,
                KeyCombination.SHIFT_DOWN));
        expand.setOnAction(e -> withPane(p -> p.tree().expandAll(true)));
        MenuItem collapse = new MenuItem("Collapse all");
        collapse.setAccelerator(new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN,
                KeyCombination.SHIFT_DOWN));
        collapse.setOnAction(e -> withPane(p -> p.tree().expandAll(false)));
        CheckMenuItem noise = new CheckMenuItem("Show build noise");
        noise.setOnAction(e -> withPane(p -> p.showNoiseBox().setSelected(noise.isSelected())));
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, o, t) -> {
            ComparisonPane pane = pane(t);
            noise.setSelected(pane != null && pane.showNoise());
        });
        // rebuilt on every opening, so that it shows the columns of the active tab
        Menu columns = new Menu("Columns");
        columns.getItems().add(new MenuItem());
        columns.setOnShowing(e -> {
            columns.getItems().clear();
            ComparisonPane pane = activePane();
            if (pane == null) {
                return;
            }
            for (TreeTableColumn<Item, ?> col : pane.tree().hideableColumns()) {
                CheckMenuItem item = new CheckMenuItem(col.getText());
                item.setSelected(col.isVisible());
                item.setOnAction(a -> col.setVisible(item.isSelected()));
                columns.getItems().add(item);
            }
        });
        ToggleGroup modes = new ToggleGroup();
        RadioMenuItem unified = new RadioMenuItem("Unified diff");
        RadioMenuItem side = new RadioMenuItem("Side-by-side diff");
        unified.setToggleGroup(modes);
        side.setToggleGroup(modes);
        unified.setOnAction(e -> diffMode.set(DiffView.Mode.UNIFIED));
        side.setOnAction(e -> diffMode.set(DiffView.Mode.SIDE_BY_SIDE));
        (diffMode.get() == DiffView.Mode.UNIFIED ? unified : side).setSelected(true);
        diffMode.addListener((obs, o, n) -> (n == DiffView.Mode.UNIFIED ? unified : side).setSelected(true));
        MenuItem timings = new MenuItem("Timings…");
        timings.setAccelerator(new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN));
        timings.setOnAction(e -> showTimings());
        resultActions.add(timings);
        MenuItem showLog = new MenuItem("Log…");
        showLog.setOnAction(e -> showLog());
        MenuItem nextClass = new MenuItem("Next changed class");
        nextClass.setAccelerator(new KeyCodeCombination(KeyCode.F7));
        nextClass.setOnAction(e -> withPane(p -> p.tree().selectNextClass(true)));
        MenuItem previousClass = new MenuItem("Previous changed class");
        previousClass.setAccelerator(new KeyCodeCombination(KeyCode.F7, KeyCombination.SHIFT_DOWN));
        previousClass.setOnAction(e -> withPane(p -> p.tree().selectNextClass(false)));
        MenuItem sortByChanges = new MenuItem("Sort by most changes");
        sortByChanges.setAccelerator(new KeyCodeCombination(KeyCode.M, KeyCombination.SHORTCUT_DOWN));
        sortByChanges.setOnAction(e -> withPane(p -> p.tree().sortByChanges()));
        MenuItem unsorted = new MenuItem("Original order");
        unsorted.setOnAction(e -> withPane(p -> p.tree().getSortOrder().clear()));
        MenuItem nextTab = new MenuItem("Next comparison");
        nextTab.setAccelerator(new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN));
        nextTab.setOnAction(e -> selectTab(1));
        MenuItem previousTab = new MenuItem("Previous comparison");
        previousTab.setAccelerator(new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN,
                KeyCombination.SHIFT_DOWN));
        previousTab.setOnAction(e -> selectTab(-1));
        Menu view = new Menu("View", null, find, nextClass, previousClass, new SeparatorMenuItem(), sortByChanges,
                unsorted, new SeparatorMenuItem(), expand, collapse, new SeparatorMenuItem(), columns, noise,
                new SeparatorMenuItem(), unified, side, new SeparatorMenuItem(), nextTab, previousTab,
                new SeparatorMenuItem(), timings, showLog);

        MenuItem clearCache = new MenuItem("Clear cache…");
        clearCache.setOnAction(e -> clearCache());
        Menu tools = new Menu("Tools", null, clearCache);

        MenuItem about = new MenuItem("About");
        about.setOnAction(e -> AboutDialog.show(stage, hostServices));
        Menu help = new Menu("Help", null, about);
        return new MenuBar(file, view, tools, help);
    }

    private HBox statusBar() {
        progress.setPrefWidth(180);
        progress.setVisible(false);
        Button logButton = new Button("Log");
        logButton.getStyleClass().add("flat");
        logButton.setOnAction(e -> showLog());
        logButton.setMinWidth(Region.USE_PREF_SIZE);
        resultInfo.setMinWidth(Region.USE_PREF_SIZE);
        statusMessage.setMaxWidth(Double.MAX_VALUE);
        statusMessage.setMinWidth(0);
        HBox.setHgrow(statusMessage, Priority.ALWAYS);
        Button timingsButton = new Button("Timings");
        timingsButton.getStyleClass().add("flat");
        timingsButton.setMinWidth(Region.USE_PREF_SIZE);
        timingsButton.setTooltip(new Tooltip("Where the time of the comparison was spent"));
        timingsButton.setOnAction(e -> showTimings());
        HBox bar = new HBox(10, statusMessage, resultInfo, progress, timingsButton, logButton);
        bar.getStyleClass().add("status-bar");
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        bar.setPadding(new javafx.geometry.Insets(4, 10, 4, 10));
        return bar;
    }

    private void withPane(java.util.function.Consumer<ComparisonPane> action) {
        ComparisonPane pane = activePane();
        if (pane != null) {
            action.accept(pane);
        }
    }

    private void selectTab(int direction) {
        int count = tabs.getTabs().size() - 1;
        if (count <= 0) {
            return;
        }
        int index = tabs.getSelectionModel().getSelectedIndex();
        tabs.getSelectionModel().select(((index + direction) % count + count) % count);
    }

    private void closeActiveTab() {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        if (tab == null || tab == newTab) {
            return;
        }
        ComparisonPane pane = pane(tab);
        if (pane.isRunning() && !confirm("Stop the comparison?",
                "A comparison is running in this tab. Close it and stop the comparison?")) {
            return;
        }
        pane.cancel();
        tabs.getTabs().remove(tab);
    }

    // ------------------------------------------------------------------ actions

    void compare() {
        withPane(ComparisonPane::compare);
    }

    /**
     * Opens a comparison file or a JSON report in a new tab (or in the current one when it is still empty). A
     * comparison file that is already open in a tab is only brought to the front.
     */
    void open(Path file) {
        boolean comparison = ComparisonFile.isComparisonFile(file);
        if (comparison) {
            Path normalized = file.toAbsolutePath().normalize();
            for (Tab tab : tabs.getTabs()) {
                ComparisonPane pane = pane(tab);
                if (pane != null && normalized.equals(pane.fileProperty().get())) {
                    tabs.getSelectionModel().select(tab);
                    return;
                }
            }
        }
        ComparisonPane pane = activePane();
        if (pane == null || pane.result() != null || pane.isRunning() || pane.fileProperty().get() != null) {
            pane = addTab(null);
        }
        if (comparison) {
            pane.openComparison(file);
        } else {
            pane.openReport(file);
        }
    }

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open comparison or JSON report");
        chooser.getExtensionFilters().addAll(OPENABLE, COMPARISON, JSON);
        initialDirectory(settings.get("lastComparisonDir", settings.get("lastReportDir", "")))
                .ifPresent(chooser::setInitialDirectory);
        File f = chooser.showOpenDialog(stage);
        if (f != null) {
            open(f.toPath());
        }
    }

    /** Saves the comparison of {@code pane} to its file, asking for one when it has none or {@code choose} is set. */
    private void saveComparison(ComparisonPane pane, boolean choose) {
        Path target = pane.fileProperty().get();
        if (choose || target == null) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save comparison");
            chooser.getExtensionFilters().add(COMPARISON);
            chooser.setInitialFileName(target != null ? target.getFileName().toString()
                    : pane.titleProperty().get().replaceAll("[^A-Za-z0-9._-]+", "_") + "." + ComparisonFile.EXTENSION);
            initialDirectory(target != null ? target.toString() : settings.get("lastComparisonDir", ""))
                    .ifPresent(chooser::setInitialDirectory);
            File f = chooser.showSaveDialog(stage);
            if (f == null) {
                return;
            }
            target = f.toPath();
            if (!ComparisonFile.isComparisonFile(target)) {
                target = target.resolveSibling(target.getFileName() + "." + ComparisonFile.EXTENSION);
            }
        }
        pane.saveComparison(target);
    }

    private void export(String kind) {
        ComparisonPane pane = activePane();
        if (pane == null || pane.result() == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export " + kind.toUpperCase(Locale.ROOT));
        chooser.setInitialFileName("jartree-report." + kind);
        switch (kind) {
            case "html" -> chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("HTML", "*.html"));
            case "json" -> chooser.getExtensionFilters().add(JSON);
            default -> chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Diff", "*.diff", "*.patch"));
        }
        initialDirectory(settings.get("lastExportDir", "")).ifPresent(chooser::setInitialDirectory);
        File f = chooser.showSaveDialog(stage);
        if (f == null) {
            return;
        }
        ComparisonResult toWrite = exportVisibleOnly.isSelected() ? pane.visibleResult() : pane.result();
        try {
            switch (kind) {
                case "html" -> new HtmlReport(true).write(toWrite, f.toPath());
                case "json" -> new JsonReport(true).write(toWrite, f.toPath());
                default -> new PatchReport(pane.showNoise(), false).write(toWrite, f.toPath());
            }
            settings.put("lastExportDir", f.getParent());
            pane.statusProperty().set("Exported " + f);
            log("Exported " + f);
        } catch (IOException e) {
            Dialogs.error(stage, "Export failed", e);
        }
    }

    private void openInBrowser() {
        ComparisonPane pane = activePane();
        if (pane == null || pane.result() == null) {
            return;
        }
        try {
            Path html = Files.createTempFile("jartree-report-", ".html");
            html.toFile().deleteOnExit();
            new HtmlReport(true).write(exportVisibleOnly.isSelected() ? pane.visibleResult() : pane.result(), html);
            hostServices.showDocument(html.toUri().toString());
        } catch (IOException e) {
            Dialogs.error(stage, "Could not create the HTML report", e);
        }
    }

    private void showTimings() {
        ComparisonPane pane = activePane();
        if (pane != null && pane.result() != null) {
            TimingsDialog.show(stage, pane.result());
        }
    }

    private void showLog() {
        TextArea area = new TextArea(String.join("\n", log));
        area.setEditable(false);
        area.getStyleClass().add("mono");
        area.positionCaret(area.getLength());
        Stage dialog = new Stage();
        dialog.initOwner(stage);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("jartree-compare — log");
        Scene scene = new Scene(new BorderPane(area), 760, 420);
        scene.getStylesheets().addAll(getScene().getStylesheets());
        dialog.setScene(scene);
        dialog.show();
    }

    private void log(String message) {
        String tab = activePane() == null ? "" : "[" + activePane().titleProperty().get() + "] ";
        log.add(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + tab + message);
    }

    private void clearCache() {
        ResultCache cache = new ResultCache(ResultCache.defaultDirectory());
        long size = cache.size();
        if (!confirm("Clear cache", "Delete " + ConsoleReport.size(size)
                + " of cached results and decompiled sources in\n" + cache.directory() + "?")) {
            return;
        }
        try {
            cache.clear();
            statusMessage.setText("Cache cleared");
            log("Cache cleared (" + ConsoleReport.size(size) + ")");
        } catch (IOException e) {
            Dialogs.error(stage, "Could not clear the cache", e);
        }
    }

    private void installDragAndDrop() {
        // a comparison file or a JSON report dropped on the window opens in a tab
        setOnDragOver(e -> {
            if (e.getDragboard().hasFiles() && isOpenable(e.getDragboard().getFiles().get(0).toPath())) {
                e.acceptTransferModes(TransferMode.COPY);
            }
        });
        setOnDragDropped(e -> {
            File f = e.getDragboard().getFiles().get(0);
            open(f.toPath());
            e.setDropCompleted(true);
        });
    }

    /** Whether {@link #open} accepts the file: a comparison file or a JSON report. */
    static boolean isOpenable(Path file) {
        return ComparisonFile.isComparisonFile(file)
                || file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json");
    }

    static Optional<File> initialDirectory(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        File f = new File(text.trim());
        File dir = f.isDirectory() ? f : f.getParentFile();
        return dir != null && dir.isDirectory() ? Optional.of(dir) : Optional.empty();
    }

    // ------------------------------------------------------------------ settings

    /**
     * Reopens the tabs of the last session. A tab saved as a comparison file is reopened from that file (with its
     * result, if the file holds one); the others get their paths back, and comparisons are not re-run.
     */
    private void restoreTabs() {
        String stored = settings.get("tabs", "");
        for (String line : stored.split("\n")) {
            String[] fields = line.split("\t", -1);
            if (fields.length < 2) {
                continue;
            }
            Path file = fields.length > 2 && !fields[2].isBlank() ? Path.of(fields[2]) : null;
            if (file != null && Files.isRegularFile(file)) {
                addTab(new String[] {fields[0], fields[1]}).openComparison(file);
            } else if (!(fields[0].isBlank() && fields[1].isBlank())) {
                addTab(new String[] {fields[0], fields[1]});
            }
        }
        if (tabs.getTabs().size() == 1) {
            addTab(null);
        }
        tabs.getSelectionModel().select(0);
    }

    void saveSettings() {
        List<String> open = new ArrayList<>();
        for (Tab tab : tabs.getTabs()) {
            ComparisonPane pane = pane(tab);
            if (pane != null) {
                Path file = pane.fileProperty().get();
                open.add(pane.oldPathText() + "\t" + pane.newPathText() + "\t" + (file == null ? "" : file));
            }
        }
        settings.put("tabs", String.join("\n", open));
        ComparisonPane active = activePane();
        if (active != null) {
            active.saveSettings();
        }
        settings.flush();
    }

    void setPaths(String oldText, String newText) {
        withPane(p -> p.setPaths(oldText, newText));
    }

    ResultTree tree() {
        ComparisonPane pane = activePane();
        return pane == null ? null : pane.tree();
    }

    Settings settings() {
        return settings;
    }
}
