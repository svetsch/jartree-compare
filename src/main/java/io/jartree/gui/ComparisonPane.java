package io.jartree.gui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
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

/**
 * One comparison: its own paths, options, filters, notices and result view. Several of these live side by side
 * in the tabs of the {@link MainWindow}.
 */
final class ComparisonPane extends BorderPane {

    private static final FileChooser.ExtensionFilter ARCHIVES = new FileChooser.ExtensionFilter(
            "Archives", "*.jar", "*.war", "*.ear", "*.zip", "*.rar", "*.sar", "*.aar");

    private final Stage stage;
    private final Settings settings;
    private final Consumer<String> log;
    private final Runnable showLog;
    private final ObjectProperty<DiffView.Mode> diffMode;

    private final PathField oldPath = new PathField("Old directory or archive (drop a folder or file here)");
    private final PathField newPath = new PathField("New directory or archive (drop a folder or file here)");
    private final OptionsPane options = new OptionsPane();
    private final Button compareButton = new Button("Compare");
    private final Button cancelButton = new Button("Cancel");

    private final Map<LibraryStatus, ToggleButton> statusToggles = new EnumMap<>(LibraryStatus.class);
    private final TextField search = new TextField();
    private final CheckBox showNoise = new CheckBox("Show build noise");
    private final ResultTree tree = new ResultTree();
    private final DetailView detail;
    private final SplitPane split;
    private final VBox notices = new VBox();

    private final StringProperty title = new SimpleStringProperty("New comparison");
    private final StringProperty status = new SimpleStringProperty("Ready");
    private final StringProperty info = new SimpleStringProperty("");
    private final DoubleProperty progress = new SimpleDoubleProperty(-1);
    private final BooleanProperty running = new SimpleBooleanProperty(false);
    private final ObjectProperty<ComparisonResult> result = new SimpleObjectProperty<>();

    private Task<ComparisonResult> task;
    /** Paths and result-relevant options of the displayed result; null when nothing is displayed. */
    private Map<String, String> appliedSettings;

    ComparisonPane(Stage stage, Settings settings, ObjectProperty<DiffView.Mode> diffMode, Consumer<String> log,
                   Runnable showLog) {
        this.stage = stage;
        this.settings = settings;
        this.diffMode = diffMode;
        this.log = log;
        this.showLog = showLog;
        this.detail = new DetailView(diffMode);
        this.split = new SplitPane(tree, detail);

        getStyleClass().add("comparison-pane");
        notices.getStyleClass().add("notices");
        setTop(new VBox(inputForm(), filterBar(), notices));
        split.setDividerPositions(settings.getDouble("divider", 0.5));
        SplitPane.setResizableWithParent(tree, false);
        setCenter(split);

        tree.getSelectionModel().selectedItemProperty().addListener((obs, o, n) ->
                detail.show(n == null ? null : n.getValue()));
        tree.setContextMenu(treeContextMenu());

        loadSettings();
        installDragAndDrop();
        options.onResultOptionChange(this::updateNotices);
        oldPath.getEditor().textProperty().addListener((obs, o, n) -> updateNotices());
        newPath.getEditor().textProperty().addListener((obs, o, n) -> updateNotices());
    }

    // ------------------------------------------------------------------ state seen by the window

    /** Tab title: the two file names, or the name of an opened report. */
    StringProperty titleProperty() {
        return title;
    }

    StringProperty statusProperty() {
        return status;
    }

    StringProperty infoProperty() {
        return info;
    }

    DoubleProperty progressProperty() {
        return progress;
    }

    BooleanProperty runningProperty() {
        return running;
    }

    ObjectProperty<ComparisonResult> resultProperty() {
        return result;
    }

    ComparisonResult result() {
        return result.get();
    }

    ResultTree tree() {
        return tree;
    }

    OptionsPane options() {
        return options;
    }

    boolean showNoise() {
        return showNoise.isSelected();
    }

    String describePaths() {
        return oldPath.getText() + " ⇄ " + newPath.getText();
    }

    // ------------------------------------------------------------------ layout

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
        cancelButton.setOnAction(e -> cancel());
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

    private Optional<File> browseStart(PathField target) {
        Optional<File> dir = MainWindow.initialDirectory(target.getText());
        return dir.isPresent() ? dir : MainWindow.initialDirectory(settings.get("lastBrowseDir", ""));
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
    }

    // ------------------------------------------------------------------ comparison

    void compare() {
        if (task != null) {
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
        log.accept("Comparing " + oldRoot + " with " + newRoot);

        Task<ComparisonResult> comparison = new Task<>() {
            @Override
            protected ComparisonResult call() throws Exception {
                updateProgress(-1, 1);
                updateMessage("Starting comparison…");
                return new JarTreeComparer(opts, cache, new JarTreeComparer.Progress() {
                    @Override
                    public void message(String message) {
                        updateMessage(message);
                        Platform.runLater(() -> log.accept(message));
                    }

                    @Override
                    public void libraryDone(int done, int total) {
                        updateProgress(done, total);
                    }
                }).compare(oldRoot, newRoot);
            }
        };
        task = comparison;
        title.set(oldRoot.getFileName() + " ⇄ " + newRoot.getFileName());
        updateNotices();
        status.bind(comparison.messageProperty());
        progress.bind(comparison.progressProperty());
        running.set(true);
        compareButton.setDisable(true);
        cancelButton.setDisable(false);

        comparison.stateProperty().addListener((obs, o, state) -> {
            if (state != Worker.State.SUCCEEDED && state != Worker.State.FAILED && state != Worker.State.CANCELLED) {
                return;
            }
            status.unbind();
            progress.unbind();
            running.set(false);
            compareButton.setDisable(false);
            cancelButton.setDisable(true);
            task = null;
            switch (state) {
                case SUCCEEDED -> {
                    ComparisonResult r = comparison.getValue();
                    appliedSettings = requested;
                    showResult(r, oldRoot.getFileName() + " ⇄ " + newRoot.getFileName());
                    String reused = cache == null ? "" : cacheSummary(cache);
                    status.set("Compared in " + r.duration().toMillis() + " ms" + reused);
                    log.accept("Done: " + countsText(r));
                    log.accept("Time: " + r.timings().line() + " (phase times summed over worker threads)");
                }
                case CANCELLED -> {
                    status.set("Cancelled");
                    log.accept("Cancelled");
                }
                default -> {
                    status.set("Comparison failed");
                    log.accept("Failed: " + comparison.getException());
                    Dialogs.error(stage, "Comparison failed", comparison.getException());
                }
            }
            if (state != Worker.State.SUCCEEDED) {
                updateNotices();
            }
        });
        Thread thread = new Thread(comparison, "jartree-gui-compare");
        thread.setDaemon(true);
        thread.start();
    }

    void cancel() {
        if (task != null) {
            task.cancel(true);
        }
    }

    boolean isRunning() {
        return task != null;
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

    private void showResult(ComparisonResult r, String source) {
        result.set(r);
        Map<LibraryStatus, Integer> counts = r.countsByStatus();
        statusToggles.forEach((s, t) -> t.setText(s.name().toLowerCase(Locale.ROOT) + "  " + counts.get(s)));
        tree.setLibraries(r.libraries());
        applyFilter();
        title.set(source);
        r.warnings().forEach(w -> log.accept("Warning: " + w));
        r.limits().hints().forEach(h -> log.accept("Limit reached: " + h));
        detail.show(null);
        updateNotices();
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
            status.set("Opened " + file);
            log.accept("Opened report " + file);
            settings.put("lastReportDir", file.getParent() == null ? null : file.getParent().toString());
        } catch (IOException e) {
            Dialogs.error(stage, "Could not open report", e);
        }
    }

    // ------------------------------------------------------------------ result access

    /** The result reduced to the libraries the filters currently show. */
    ComparisonResult visibleResult() {
        ComparisonResult r = result.get();
        return new ComparisonResult(r.oldRoot(), r.newRoot(), r.oldLibraryCount(), r.newLibraryCount(),
                tree.visibleLibraries(), r.warnings(), r.started(), r.duration(), r.limits(), r.timings());
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
                status.set("Saved " + f);
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

    // ------------------------------------------------------------------ filters and notices

    void focusSearch() {
        search.requestFocus();
        search.selectAll();
    }

    CheckBox showNoiseBox() {
        return showNoise;
    }

    private void applyFilter() {
        Set<LibraryStatus> selected = EnumSet.noneOf(LibraryStatus.class);
        statusToggles.forEach((s, t) -> {
            if (t.isSelected()) {
                selected.add(s);
            }
        });
        tree.setFilter(selected, search.getText(), showNoise.isSelected());
        ComparisonResult r = result.get();
        if (r != null) {
            int shown = tree.visibleLibraries().size();
            info.set(shown + " of " + r.libraries().size() + " libraries shown · " + r.oldLibraryCount()
                    + " old / " + r.newLibraryCount() + " new");
        }
    }

    private Map<String, String> settingsSnapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Old path", oldPath.getText().trim());
        m.put("New path", newPath.getText().trim());
        m.putAll(options.toOptions().resultSettings());
        return m;
    }

    /** Shows which options changed since the displayed comparison and which limits it reached. */
    private void updateNotices() {
        notices.getChildren().clear();
        compareButton.getStyleClass().remove("needs-run");
        ComparisonResult r = result.get();
        if (r == null || task != null) {
            return;
        }
        List<String> changed = new ArrayList<>();
        if (appliedSettings != null) {
            settingsSnapshot().forEach((key, value) -> {
                String before = appliedSettings.get(key);
                if (!value.equals(before)) {
                    changed.add(key.endsWith("path") ? key
                            : key + ": " + display(before) + " → " + display(value));
                }
            });
        }
        if (!changed.isEmpty()) {
            compareButton.getStyleClass().add("needs-run");
            String list = String.join(" · ", changed.subList(0, Math.min(4, changed.size())))
                    + (changed.size() > 4 ? " · …" : "");
            notices.getChildren().add(notice("notice-info", "↻",
                    "Changed since the displayed comparison: " + list
                            + ". Press Compare (F5) to apply the changes to the result.",
                    button("Compare now", this::compare)));
        }
        if (!r.warnings().isEmpty()) {
            List<String> w = r.warnings();
            notices.getChildren().add(notice("notice-warn", "⚠",
                    w.get(0) + (w.size() > 1 ? "  (and " + (w.size() - 1) + " more warning(s))" : ""),
                    button("Show log", showLog)));
        }
        Limits limits = r.limits();
        if (limits.classLimitReached()) {
            int needed = limits.maxClassesNeeded();
            notices.getChildren().add(notice("notice-warn", "⚠",
                    limits.librariesOverClassLimit() + (limits.librariesOverClassLimit() == 1 ? " library" : " libraries")
                            + " reached the limit of " + limits.maxClasses() + " decompiled classes per library: "
                            + limits.skippedClasses() + " changed class(es) were not decompiled. Set "
                            + "“Max classes / library” to at least " + needed + " to decompile all of them.",
                    button("Set to " + needed + " and compare", () -> {
                        options.setMaxClasses(needed);
                        compare();
                    })));
        }
        if (limits.depthLimitReached()) {
            int needed = limits.nestedDepthNeeded();
            String example = limits.unopenedArchives().isEmpty() ? "" : " (e.g. " + limits.unopenedArchives().get(0) + ")";
            notices.getChildren().add(notice("notice-warn", "⚠",
                    limits.unopenedArchives().size() + " nested archive(s) were not opened because of the nested "
                            + "archive depth limit of " + limits.nestedDepth() + example + ". Set “Nested archive "
                            + "depth” to at least " + needed + " to open all of them.",
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
        Button close = new Button("✕");
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

    private static String countsText(ComparisonResult r) {
        List<String> parts = new ArrayList<>();
        r.countsByStatus().forEach((s, n) -> {
            if (n > 0) {
                parts.add(n + " " + s.name().toLowerCase(Locale.ROOT));
            }
        });
        return String.join(", ", parts);
    }

    private static String cacheSummary(ResultCache cache) {
        if (cache.libraryHits() == 0 && cache.sourceHits() == 0) {
            return "";
        }
        return " · from cache: " + cache.libraryHits() + " libraries, " + cache.sourceHits() + " classes";
    }

    // ------------------------------------------------------------------ settings

    private void loadSettings() {
        List<String> history = history();
        oldPath.setHistory(history);
        newPath.setHistory(history);
        oldPath.setText(settings.get("oldPath", ""));
        newPath.setText(settings.get("newPath", ""));
        showNoise.setSelected(settings.getBoolean("showNoise", false));
        options.load(settings);
    }

    /** Stores the paths, the options and the divider of this pane as the defaults for new tabs. */
    void saveSettings() {
        settings.put("oldPath", oldPath.getText());
        settings.put("newPath", newPath.getText());
        if (split.getDividers().size() > 0) {
            settings.putDouble("divider", split.getDividerPositions()[0]);
        }
        options.save(settings);
        settings.flush();
    }

    void setPaths(String oldText, String newText) {
        oldPath.setText(oldText);
        newPath.setText(newText);
    }

    String oldPathText() {
        return oldPath.getText();
    }

    String newPathText() {
        return newPath.getText();
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
}
