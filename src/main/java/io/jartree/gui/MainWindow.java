package io.jartree.gui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import io.jartree.compare.ClassChange;
import io.jartree.compare.CompareOptions;
import io.jartree.compare.ComparisonResult;
import io.jartree.compare.JarTreeComparer;
import io.jartree.compare.LibraryDiff;
import io.jartree.compare.LibraryStatus;
import io.jartree.compare.Limits;
import io.jartree.compare.ResourceChange;
import io.jartree.compare.ResultCache;
import io.jartree.compare.ResultReader;
import io.jartree.gui.Item.ClassItem;
import io.jartree.gui.Item.ResourceItem;
import io.jartree.report.ConsoleReport;
import io.jartree.report.HtmlReport;
import io.jartree.report.JsonReport;
import io.jartree.report.PatchReport;

/** Main window: inputs and options on top, result tree and details below. */
final class MainWindow extends BorderPane {

    private static final FileChooser.ExtensionFilter ARCHIVES = new FileChooser.ExtensionFilter(
            "Archives", "*.jar", "*.war", "*.ear", "*.zip", "*.rar", "*.sar", "*.aar");
    private static final FileChooser.ExtensionFilter JSON = new FileChooser.ExtensionFilter("JSON report", "*.json");

    private final Stage stage;
    private final HostServices hostServices;
    private final Settings settings = new Settings();
    private final ObjectProperty<DiffView.Mode> diffMode = new SimpleObjectProperty<>(DiffView.Mode.UNIFIED);

    private final PathField oldPath = new PathField("Old directory or archive (drop a folder or file here)");
    private final PathField newPath = new PathField("New directory or archive (drop a folder or file here)");
    private final OptionsPane options = new OptionsPane();
    private final Button compareButton = new Button("Compare");
    private final Button cancelButton = new Button("Cancel");

    private final Map<LibraryStatus, ToggleButton> statusToggles = new EnumMap<>(LibraryStatus.class);
    private final TextField search = new TextField();
    private final CheckBox showNoise = new CheckBox("Show build noise");
    private final ResultTree tree = new ResultTree();
    private final DetailView detail = new DetailView(diffMode);
    private final SplitPane split = new SplitPane(tree, detail);

    private final Label statusMessage = new Label("Ready");
    private final Label resultInfo = new Label();
    private final ProgressBar progress = new ProgressBar();
    private final List<String> log = new ArrayList<>();
    private final CheckMenuItem exportVisibleOnly = new CheckMenuItem("Export visible libraries only");
    private final List<MenuItem> resultActions = new ArrayList<>();

    private final VBox notices = new VBox();

    private ComparisonResult result;
    private Task<ComparisonResult> running;
    /** Paths and result-relevant options of the displayed result; null when nothing is displayed. */
    private Map<String, String> appliedSettings;

    MainWindow(Stage stage, HostServices hostServices) {
        this.stage = stage;
        this.hostServices = hostServices;
        getStyleClass().add("main-window");

        notices.getStyleClass().add("notices");
        VBox top = new VBox(menuBar(), inputForm(), filterBar(), notices);
        setTop(top);
        split.setDividerPositions(settings.getDouble("divider", 0.5));
        SplitPane.setResizableWithParent(tree, false);
        setCenter(split);
        setBottom(statusBar());

        tree.getSelectionModel().selectedItemProperty().addListener((obs, o, n) ->
                detail.show(n == null ? null : n.getValue()));
        tree.setContextMenu(treeContextMenu());

        loadSettings();
        setResultAvailable(false);
        installDragAndDrop();
        options.onResultOptionChange(this::updateNotices);
        oldPath.getEditor().textProperty().addListener((obs, o, n) -> updateNotices());
        newPath.getEditor().textProperty().addListener((obs, o, n) -> updateNotices());
    }

    // ------------------------------------------------------------------ layout

    private MenuBar menuBar() {
        MenuItem open = new MenuItem("Open report…");
        open.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN));
        open.setOnAction(e -> chooseReport());
        MenuItem save = new MenuItem("Save report as JSON…");
        save.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN));
        save.setOnAction(e -> export("json"));
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
        MenuItem compare = new MenuItem("Compare");
        compare.setAccelerator(new KeyCodeCombination(KeyCode.F5));
        compare.setOnAction(e -> compare());
        compare.disableProperty().bind(compareButton.disableProperty());
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(e -> stage.close());
        Menu file = new Menu("File", null, compare, new SeparatorMenuItem(), open, save, exportMenu, browser,
                new SeparatorMenuItem(), exit);
        resultActions.addAll(List.of(save, html, patch, json, browser));

        MenuItem find = new MenuItem("Find…");
        find.setAccelerator(new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN));
        find.setOnAction(e -> {
            search.requestFocus();
            search.selectAll();
        });
        MenuItem expand = new MenuItem("Expand all");
        expand.setOnAction(e -> tree.expandAll(true));
        MenuItem collapse = new MenuItem("Collapse all");
        collapse.setOnAction(e -> tree.expandAll(false));
        CheckMenuItem noise = new CheckMenuItem("Show build noise");
        noise.selectedProperty().bindBidirectional(showNoise.selectedProperty());
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
        nextClass.setOnAction(e -> tree.selectNextClass(true));
        MenuItem previousClass = new MenuItem("Previous changed class");
        previousClass.setAccelerator(new KeyCodeCombination(KeyCode.F7, KeyCombination.SHIFT_DOWN));
        previousClass.setOnAction(e -> tree.selectNextClass(false));
        MenuItem sortByChanges = new MenuItem("Sort by most changes");
        sortByChanges.setAccelerator(new KeyCodeCombination(KeyCode.M, KeyCombination.SHORTCUT_DOWN));
        sortByChanges.setOnAction(e -> tree.sortByChanges());
        MenuItem unsorted = new MenuItem("Original order");
        unsorted.setOnAction(e -> tree.getSortOrder().clear());
        Menu view = new Menu("View", null, find, nextClass, previousClass, new SeparatorMenuItem(), sortByChanges,
                unsorted, new SeparatorMenuItem(), expand, collapse, new SeparatorMenuItem(), noise,
                new SeparatorMenuItem(), unified, side, new SeparatorMenuItem(), timings, showLog);
        diffMode.addListener((obs, o, n) -> settings.put("diffMode", n.name()));

        MenuItem about = new MenuItem("About");
        about.setOnAction(e -> AboutDialog.show(stage, hostServices));
        Menu help = new Menu("Help", null, about);
        MenuItem clearCache = new MenuItem("Clear cache\u2026");
        clearCache.setOnAction(e -> clearCache());
        Menu tools = new Menu("Tools", null, clearCache);
        return new MenuBar(file, view, tools, help);
    }

    private GridPane inputForm() {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("input-form");
        grid.setHgap(8);
        grid.setVgap(6);
        grid.setPadding(new Insets(10, 10, 6, 10));
        ColumnConstraints field = new ColumnConstraints();
        field.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(new ColumnConstraints(), field);

        grid.addRow(0, new Label("Old"), oldPath, browseDir(oldPath), browseFile(oldPath));
        grid.addRow(1, new Label("New"), newPath, browseDir(newPath), browseFile(newPath));

        Button swap = new Button("⇅ Swap");
        swap.setMinWidth(Region.USE_PREF_SIZE);
        compareButton.setMinWidth(Region.USE_PREF_SIZE);
        cancelButton.setMinWidth(Region.USE_PREF_SIZE);
        swap.setTooltip(new Tooltip("Swap old and new"));
        swap.setOnAction(e -> {
            String t = oldPath.getText();
            oldPath.setText(newPath.getText());
            newPath.setText(t);
        });
        compareButton.setDefaultButton(true);
        compareButton.getStyleClass().add("accent");
        compareButton.setOnAction(e -> compare());
        cancelButton.setOnAction(e -> {
            if (running != null) {
                running.cancel(true);
            }
        });
        cancelButton.setDisable(true);
        HBox buttons = new HBox(8, swap, compareButton, cancelButton);
        buttons.setAlignment(Pos.TOP_RIGHT);
        grid.add(options, 0, 2, 2, 1);
        grid.add(buttons, 2, 2, 2, 1);
        oldPath.getEditor().setOnAction(e -> compare());
        newPath.getEditor().setOnAction(e -> compare());
        return grid;
    }

    private Button browseDir(PathField target) {
        Button b = new Button("Folder…");
        b.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Choose directory");
            browseStart(target).ifPresent(chooser::setInitialDirectory);
            File dir = chooser.showDialog(stage);
            if (dir != null) {
                target.setText(dir.getAbsolutePath());
                settings.put("lastBrowseDir", dir.getParent());
            }
        });
        return b;
    }

    private Optional<File> browseStart(PathField target) {
        Optional<File> dir = initialDirectory(target.getText());
        return dir.isPresent() ? dir : initialDirectory(settings.get("lastBrowseDir", ""));
    }

    private Button browseFile(PathField target) {
        Button b = new Button("Archive…");
        b.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose archive");
            chooser.getExtensionFilters().addAll(ARCHIVES, new FileChooser.ExtensionFilter("All files", "*.*"));
            browseStart(target).ifPresent(chooser::setInitialDirectory);
            File f = chooser.showOpenDialog(stage);
            if (f != null) {
                target.setText(f.getAbsolutePath());
                settings.put("lastBrowseDir", f.getParent());
            }
        });
        return b;
    }

    private static Optional<File> initialDirectory(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        File f = new File(text.trim());
        File dir = f.isDirectory() ? f : f.getParentFile();
        return dir != null && dir.isDirectory() ? Optional.of(dir) : Optional.empty();
    }

    private HBox filterBar() {
        HBox bar = new HBox(6);
        bar.getStyleClass().add("filter-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 10, 6, 10));
        for (LibraryStatus s : LibraryStatus.values()) {
            ToggleButton t = new ToggleButton(s.name().toLowerCase(Locale.ROOT));
            t.getStyleClass().addAll("status-chip", "chip-" + s.name().toLowerCase(Locale.ROOT));
            t.setSelected(s != LibraryStatus.UNCHANGED);
            t.selectedProperty().addListener((obs, o, n) -> applyFilter());
            statusToggles.put(s, t);
            bar.getChildren().add(t);
        }
        search.setPromptText("Filter libraries, classes, resources (Ctrl+F)");
        search.textProperty().addListener((obs, o, n) -> applyFilter());
        HBox.setHgrow(search, Priority.ALWAYS);
        search.setMaxWidth(Double.MAX_VALUE);
        showNoise.selectedProperty().addListener((obs, o, n) -> {
            settings.putBoolean("showNoise", n);
            applyFilter();
        });
        Region gap = new Region();
        gap.setMinWidth(8);
        bar.getChildren().addAll(gap, search, showNoise);
        return bar;
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
        HBox.setHgrow(statusMessage, Priority.ALWAYS);
        Button timingsButton = new Button("Timings");
        timingsButton.getStyleClass().add("flat");
        timingsButton.setMinWidth(Region.USE_PREF_SIZE);
        timingsButton.setTooltip(new Tooltip("Where the time of the comparison was spent"));
        timingsButton.setOnAction(e -> showTimings());
        HBox bar = new HBox(10, statusMessage, resultInfo, progress, timingsButton, logButton);
        bar.getStyleClass().add("status-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 10, 4, 10));
        statusMessage.setMinWidth(0);
        return bar;
    }

    private ContextMenu treeContextMenu() {
        MenuItem copyName = new MenuItem("Copy name");
        copyName.setOnAction(e -> {
            Item item = tree.selectedItem();
            if (item != null) {
                DiffView.copyToClipboard(ResultTree.nameText(item));
            }
        });
        MenuItem copyDiff = new MenuItem("Copy diff");
        copyDiff.setOnAction(e -> {
            String diff = diffOf(tree.selectedItem());
            if (!diff.isEmpty()) {
                DiffView.copyToClipboard(diff);
            }
        });
        MenuItem saveDiff = new MenuItem("Save diff of selection…");
        saveDiff.setOnAction(e -> saveSelectionDiff());
        MenuItem expand = new MenuItem("Expand all");
        expand.setOnAction(e -> tree.expandAll(true));
        MenuItem collapse = new MenuItem("Collapse all");
        collapse.setOnAction(e -> tree.expandAll(false));
        ContextMenu menu = new ContextMenu(copyName, copyDiff, saveDiff, new SeparatorMenuItem(), expand, collapse);
        menu.setOnShowing(e -> {
            Item item = tree.selectedItem();
            copyName.setDisable(item == null);
            boolean hasDiff = !diffOf(item).isEmpty();
            copyDiff.setDisable(!hasDiff);
            saveDiff.setDisable(!hasDiff);
        });
        return menu;
    }

    // ------------------------------------------------------------------ actions

    void compare() {
        if (running != null) {
            return;
        }
        Path oldRoot = pathOf(oldPath, "old");
        Path newRoot = oldRoot == null ? null : pathOf(newPath, "new");
        if (oldRoot == null || newRoot == null) {
            return;
        }
        rememberPaths();
        saveSettings();
        Map<String, String> requested = settingsSnapshot();
        CompareOptions opts = options.toOptions();
        ResultCache cache = options.useCache() ? new ResultCache(ResultCache.defaultDirectory()) : null;
        log("Comparing " + oldRoot + " with " + newRoot);

        Task<ComparisonResult> task = new Task<>() {
            @Override
            protected ComparisonResult call() throws Exception {
                updateProgress(-1, 1);
                updateMessage("Starting comparison…");
                return new JarTreeComparer(opts, cache, new JarTreeComparer.Progress() {
                    @Override
                    public void message(String message) {
                        updateMessage(message);
                        Platform.runLater(() -> log(message));
                    }

                    @Override
                    public void libraryDone(int done, int total) {
                        updateProgress(done, total);
                    }
                }).compare(oldRoot, newRoot);
            }
        };
        running = task;
        updateNotices();
        statusMessage.textProperty().bind(task.messageProperty());
        progress.progressProperty().bind(task.progressProperty());
        progress.setVisible(true);
        compareButton.setDisable(true);
        cancelButton.setDisable(false);

        task.stateProperty().addListener((obs, o, state) -> {
            if (state != Worker.State.SUCCEEDED && state != Worker.State.FAILED && state != Worker.State.CANCELLED) {
                return;
            }
            statusMessage.textProperty().unbind();
            progress.progressProperty().unbind();
            progress.setVisible(false);
            compareButton.setDisable(false);
            cancelButton.setDisable(true);
            running = null;
            switch (state) {
                case SUCCEEDED -> {
                    ComparisonResult r = task.getValue();
                    appliedSettings = requested;
                    showResult(r, oldRoot.getFileName() + " ⇄ " + newRoot.getFileName());
                    String reused = cache == null ? "" : cacheSummary(cache);
                    statusMessage.setText("Compared in " + r.duration().toMillis() + " ms" + reused);
                    log("Done: " + countsText(r));
                    log("Time: " + r.timings().line() + " (phase times summed over worker threads)");
                }
                case CANCELLED -> {
                    statusMessage.setText("Cancelled");
                    log("Cancelled");
                }
                default -> {
                    statusMessage.setText("Comparison failed");
                    log("Failed: " + task.getException());
                    Dialogs.error(stage, "Comparison failed", task.getException());
                }
            }
            if (state != Worker.State.SUCCEEDED) {
                updateNotices();
            }
        });
        Thread t = new Thread(task, "jartree-gui-compare");
        t.setDaemon(true);
        t.start();
    }

    private Path pathOf(PathField field, String which) {
        String text = field.getText() == null ? "" : field.getText().trim();
        if (text.isEmpty()) {
            Dialogs.info(stage, "Missing input", "Choose the " + which + " directory or archive.");
            field.requestFocus();
            return null;
        }
        Path p = Path.of(text);
        if (!Files.exists(p)) {
            Dialogs.info(stage, "Not found", "The " + which + " path does not exist:\n" + text);
            field.requestFocus();
            return null;
        }
        return p.toAbsolutePath().normalize();
    }

    /** Displays a result; {@code source} is shown in the window title. */
    void showResult(ComparisonResult r, String source) {
        this.result = r;
        Map<LibraryStatus, Integer> counts = r.countsByStatus();
        statusToggles.forEach((s, t) -> t.setText(s.name().toLowerCase(Locale.ROOT) + "  " + counts.get(s)));
        tree.setLibraries(r.libraries());
        applyFilter();
        stage.setTitle("jartree-compare — " + source);
        setResultAvailable(true);
        r.warnings().forEach(w -> log("Warning: " + w));
        r.limits().hints().forEach(h -> log("Limit reached: " + h));
        detail.show(null);
        updateNotices();
    }

    ResultTree tree() {
        return tree;
    }

    void openReport(Path file) {
        try {
            ComparisonResult r = ResultReader.read(file);
            oldPath.setText(r.oldRoot().toString());
            newPath.setText(r.newRoot().toString());
            rememberPaths();
            settings.put("lastReport", file.toString());
            // the options used to create the report are unknown; changes from now on need a new comparison
            appliedSettings = settingsSnapshot();
            // the report records the limits it was created with
            if (r.limits().maxClasses() > 0) {
                appliedSettings.put("Max classes / library", String.valueOf(r.limits().maxClasses()));
                appliedSettings.put("Nested archive depth", String.valueOf(r.limits().nestedDepth()));
            }
            showResult(r, file.getFileName().toString());
            statusMessage.setText("Opened " + file);
            log("Opened report " + file);
            settings.put("lastReportDir", file.getParent() == null ? null : file.getParent().toString());
        } catch (IOException e) {
            Dialogs.error(stage, "Could not open report", e);
        }
    }

    private void chooseReport() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open JSON report");
        chooser.getExtensionFilters().add(JSON);
        initialDirectory(settings.get("lastReportDir", "")).ifPresent(chooser::setInitialDirectory);
        File f = chooser.showOpenDialog(stage);
        if (f != null) {
            openReport(f.toPath());
        }
    }

    private void export(String kind) {
        if (result == null) {
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
        ComparisonResult toWrite = exportVisibleOnly.isSelected() ? visibleResult() : result;
        try {
            switch (kind) {
                case "html" -> new HtmlReport(true).write(toWrite, f.toPath());
                case "json" -> new JsonReport(true).write(toWrite, f.toPath());
                default -> new PatchReport(showNoise.isSelected(), false).write(toWrite, f.toPath());
            }
            settings.put("lastExportDir", f.getParent());
            statusMessage.setText("Exported " + f);
            log("Exported " + f);
        } catch (IOException e) {
            Dialogs.error(stage, "Export failed", e);
        }
    }

    private void openInBrowser() {
        if (result == null) {
            return;
        }
        try {
            Path html = Files.createTempFile("jartree-report-", ".html");
            html.toFile().deleteOnExit();
            new HtmlReport(true).write(exportVisibleOnly.isSelected() ? visibleResult() : result, html);
            hostServices.showDocument(html.toUri().toString());
        } catch (IOException e) {
            Dialogs.error(stage, "Could not create the HTML report", e);
        }
    }

    private ComparisonResult visibleResult() {
        return new ComparisonResult(result.oldRoot(), result.newRoot(), result.oldLibraryCount(),
                result.newLibraryCount(), tree.visibleLibraries(), result.warnings(), result.started(),
                result.duration(), result.limits(), result.timings());
    }

    private void saveSelectionDiff() {
        Item item = tree.selectedItem();
        String diff = diffOf(item);
        if (diff.isEmpty()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save diff");
        String name = ResultTree.nameText(item).replaceAll("[^A-Za-z0-9._-]+", "_");
        chooser.setInitialFileName(name + ".diff");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Diff", "*.diff", "*.patch"));
        File f = chooser.showSaveDialog(stage);
        if (f != null) {
            try {
                Files.writeString(f.toPath(), diff, StandardCharsets.UTF_8);
                statusMessage.setText("Saved " + f);
            } catch (IOException e) {
                Dialogs.error(stage, "Could not save " + f, e);
            }
        }
    }

    /** Unified diff of a class, resource, or everything visible below a library. */
    private String diffOf(Item item) {
        if (item == null) {
            return "";
        }
        if (item instanceof ClassItem c) {
            return classDiff(c.change());
        }
        if (item instanceof ResourceItem r) {
            return r.change().diff().text();
        }
        StringBuilder sb = new StringBuilder();
        LibraryDiff lib = item.library();
        for (ClassChange c : lib.classes()) {
            if (showNoise.isSelected() || c.isSignificant()) {
                sb.append(classDiff(c));
            }
        }
        for (ResourceChange r : lib.resources()) {
            if (showNoise.isSelected() || !r.noise()) {
                sb.append(r.diff().text());
            }
        }
        return sb.toString();
    }

    private static String classDiff(ClassChange c) {
        return c.sourceDiff().isEmpty() ? c.bytecodeDiff().text() : c.sourceDiff().text();
    }

    private void applyFilter() {
        Set<LibraryStatus> selected = EnumSet.noneOf(LibraryStatus.class);
        statusToggles.forEach((s, t) -> {
            if (t.isSelected()) {
                selected.add(s);
            }
        });
        tree.setFilter(selected, search.getText(), showNoise.isSelected());
        if (result != null) {
            int shown = tree.visibleLibraries().size();
            resultInfo.setText(shown + " of " + result.libraries().size() + " libraries shown · "
                    + result.oldLibraryCount() + " old / " + result.newLibraryCount() + " new");
        }
    }

    private void setResultAvailable(boolean available) {
        resultActions.forEach(m -> m.setDisable(!available));
    }

    private void installDragAndDrop() {
        for (PathField field : List.of(oldPath, newPath)) {
            field.setOnDragOver(e -> {
                if (e.getDragboard().hasFiles()) {
                    e.acceptTransferModes(TransferMode.COPY);
                }
                e.consume();
            });
            field.setOnDragDropped(e -> {
                List<File> files = e.getDragboard().getFiles();
                if (!files.isEmpty()) {
                    field.setText(files.get(0).getAbsolutePath());
                    if (files.size() > 1 && field == oldPath) {
                        newPath.setText(files.get(1).getAbsolutePath());
                    }
                }
                e.setDropCompleted(!files.isEmpty());
                e.consume();
            });
        }
        // a JSON report dropped anywhere else is opened
        setOnDragOver(e -> {
            if (e.getDragboard().hasFiles() && e.getDragboard().getFiles().get(0).getName().endsWith(".json")) {
                e.acceptTransferModes(TransferMode.COPY);
            }
        });
        setOnDragDropped(e -> {
            File f = e.getDragboard().getFiles().get(0);
            openReport(f.toPath());
            e.setDropCompleted(true);
        });
    }

    private void showTimings() {
        if (result != null) {
            TimingsDialog.show(stage, result);
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
        log.add(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + message);
    }

    private static String countsText(ComparisonResult r) {
        List<String> parts = new ArrayList<>();
        r.countsByStatus().forEach((s, n) -> {
            if (n > 0) {
                parts.add(n + " " + s.name().toLowerCase(Locale.ROOT));
            }
        });
        return String.join(", ", parts);
    }

    // ------------------------------------------------------------------ settings

    private void loadSettings() {
        List<String> history = history();
        oldPath.setHistory(history);
        newPath.setHistory(history);
        oldPath.setText(settings.get("oldPath", ""));
        newPath.setText(settings.get("newPath", ""));
        // remember what was typed or chosen right away, even if the application is not closed normally
        oldPath.getEditor().textProperty().addListener((obs, o, n) -> settings.put("oldPath", n));
        newPath.getEditor().textProperty().addListener((obs, o, n) -> settings.put("newPath", n));
        showNoise.setSelected(settings.getBoolean("showNoise", false));
        try {
            diffMode.set(DiffView.Mode.valueOf(settings.get("diffMode", DiffView.Mode.UNIFIED.name())));
        } catch (IllegalArgumentException e) {
            diffMode.set(DiffView.Mode.UNIFIED);
        }
        options.load(settings);
    }

    void saveSettings() {
        settings.put("oldPath", oldPath.getText());
        settings.put("newPath", newPath.getText());
        if (split.getDividers().size() > 0) {
            settings.putDouble("divider", split.getDividerPositions()[0]);
        }
        options.save(settings);
        settings.flush();
    }

    // ------------------------------------------------------------------ notices

    private Map<String, String> settingsSnapshot() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("Old path", oldPath.getText().trim());
        m.put("New path", newPath.getText().trim());
        m.putAll(options.toOptions().resultSettings());
        return m;
    }

    /** Shows which options changed since the displayed comparison and which limits it reached. */
    private void updateNotices() {
        notices.getChildren().clear();
        compareButton.getStyleClass().remove("needs-run");
        if (result == null || running != null) {
            return;
        }
        List<String> changed = new ArrayList<>();
        if (appliedSettings != null) {
            settingsSnapshot().forEach((key, value) -> {
                String before = appliedSettings.get(key);
                if (!value.equals(before)) {
                    changed.add(key.endsWith("path") ? key
                            : key + ": " + display(before) + " \u2192 " + display(value));
                }
            });
        }
        if (!changed.isEmpty()) {
            compareButton.getStyleClass().add("needs-run");
            String list = String.join(" \u00B7 ", changed.subList(0, Math.min(4, changed.size())))
                    + (changed.size() > 4 ? " \u00B7 \u2026" : "");
            notices.getChildren().add(notice("notice-info", "\u21BB",
                    "Changed since the displayed comparison: " + list
                            + ". Press Compare (F5) to apply the changes to the result.",
                    button("Compare now", this::compare)));
        }
        if (!result.warnings().isEmpty()) {
            List<String> w = result.warnings();
            notices.getChildren().add(notice("notice-warn", "\u26A0",
                    w.get(0) + (w.size() > 1 ? "  (and " + (w.size() - 1) + " more warning(s))" : ""),
                    button("Show log", this::showLog)));
        }
        Limits limits = result.limits();
        if (limits.classLimitReached()) {
            int needed = limits.maxClassesNeeded();
            notices.getChildren().add(notice("notice-warn", "\u26A0",
                    limits.librariesOverClassLimit() + (limits.librariesOverClassLimit() == 1 ? " library" : " libraries")
                            + " reached the limit of " + limits.maxClasses() + " decompiled classes per library: "
                            + limits.skippedClasses() + " changed class(es) were not decompiled. Set "
                            + "\u201CMax classes / library\u201D to at least " + needed + " to decompile all of them.",
                    button("Set to " + needed + " and compare", () -> {
                        options.setMaxClasses(needed);
                        compare();
                    })));
        }
        if (limits.depthLimitReached()) {
            int needed = limits.nestedDepthNeeded();
            String example = limits.unopenedArchives().isEmpty() ? "" : " (e.g. " + limits.unopenedArchives().get(0) + ")";
            notices.getChildren().add(notice("notice-warn", "\u26A0",
                    limits.unopenedArchives().size() + " nested archive(s) were not opened because of the nested "
                            + "archive depth limit of " + limits.nestedDepth() + example + ". Set \u201CNested archive "
                            + "depth\u201D to at least " + needed + " to open all of them.",
                    button("Set to " + needed + " and compare", () -> {
                        options.setNestedDepth(needed);
                        compare();
                    })));
        }
    }

    private static String display(String value) {
        return value == null || value.isEmpty() ? "(none)" : value;
    }

    private static Button button(String text, Runnable action) {
        Button b = new Button(text);
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setOnAction(e -> action.run());
        return b;
    }

    private HBox notice(String style, String icon, String message, Button action) {
        Label i = new Label(icon);
        i.getStyleClass().add("notice-icon");
        i.setMinWidth(Region.USE_PREF_SIZE);
        Label text = new Label(message);
        text.setWrapText(true);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button close = new Button("\u2715");
        close.setMinWidth(Region.USE_PREF_SIZE);
        close.getStyleClass().add("flat");
        close.setTooltip(new Tooltip("Hide this message"));
        HBox box = new HBox(10, i, text, action, close);
        close.setOnAction(e -> notices.getChildren().remove(box));
        box.getStyleClass().addAll("notice", style);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(6, 10, 6, 10));
        return box;
    }

    private List<String> history() {
        String stored = settings.get("pathHistory", "");
        return stored.isEmpty() ? List.of() : List.of(stored.split("\n"));
    }

    /** Adds the current old and new paths to the drop-down history of both fields. */
    private void rememberPaths() {
        List<String> history = PathField.remember(history(), newPath.getText());
        history = PathField.remember(history, oldPath.getText());
        settings.put("pathHistory", String.join("\n", history));
        oldPath.setHistory(history);
        newPath.setHistory(history);
        settings.flush();
    }

    private static String cacheSummary(ResultCache cache) {
        if (cache.libraryHits() == 0 && cache.sourceHits() == 0) {
            return "";
        }
        return " \u00B7 from cache: " + cache.libraryHits() + " libraries, " + cache.sourceHits() + " classes";
    }

    private void clearCache() {
        ResultCache cache = new ResultCache(ResultCache.defaultDirectory());
        long size = cache.size();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete " + ConsoleReport.size(size) + " of cached results and decompiled sources in\n"
                        + cache.directory() + "?", ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setTitle("jartree-compare");
        confirm.setHeaderText("Clear cache");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            try {
                cache.clear();
                statusMessage.setText("Cache cleared");
                log("Cache cleared (" + ConsoleReport.size(size) + ")");
            } catch (IOException e) {
                Dialogs.error(stage, "Could not clear the cache", e);
            }
        }
    }

    void setPaths(String oldText, String newText) {
        oldPath.setText(oldText);
        newPath.setText(newText);
    }

    Settings settings() {
        return settings;
    }
}
