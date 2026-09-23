package io.jartree.gui;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import io.jartree.compare.CompareOptions;
import io.jartree.compare.ResultCache;

/** Collapsible form with the comparison options. */
final class OptionsPane extends TitledPane {

    private final TextField includes = new TextField();
    private final TextField excludes = new TextField();
    private final TextField packages = new TextField();
    private final TextField ignoredEntries = new TextField();
    private final TextField extensions = new TextField();
    private final CheckBox decompile = new CheckBox("Decompile changed classes");
    private final CheckBox decompileAdded = new CheckBox("Also decompile added / removed classes");
    private final CheckBox bytecode = new CheckBox("Always include bytecode diff");
    private final CheckBox cache = new CheckBox("Use cache (reuse earlier results and decompiled classes)");
    private final Spinner<Integer> maxClasses = new Spinner<>(1, 1_000_000, 2000, 100);
    private final Spinner<Integer> context = new Spinner<>(0, 50, 3);
    private final Spinner<Integer> threads = new Spinner<>(1, 256, Runtime.getRuntime().availableProcessors());
    private final Spinner<Integer> nestedDepth = new Spinner<>(0, 64, 8);

    OptionsPane() {
        setText("Options");
        setExpanded(false);
        setAnimated(false);

        includes.setPromptText("all libraries, e.g. **/acme-*.jar, lib/*.jar");
        excludes.setPromptText("no library, e.g. **/*-sources.jar");
        ignoredEntries.setPromptText("no entry, e.g. META-INF/MANIFEST.MF, META-INF/maven/**, *.SF");
        ignoredEntries.setTooltip(new Tooltip("Comma separated globs on paths inside libraries; a pattern without '/' "
                + "matches the file name in any directory. Differences in matching entries are ignored."));
        packages.setPromptText("all packages, e.g. com.acme, org.example.api");
        extensions.setText(String.join(",", CompareOptions.DEFAULT_EXTENSIONS.stream().sorted().toList()));
        includes.setTooltip(new Tooltip("Comma separated globs on library path or file name (not on entries inside "
                + "libraries; use \u201CIgnore entries\u201D for those)"));
        excludes.setTooltip(new Tooltip("Comma separated globs on library path or file name (not on entries inside "
                + "libraries; use \u201CIgnore entries\u201D for those)"));
        packages.setTooltip(new Tooltip("Comma separated package prefixes; other classes are ignored"));
        decompile.setSelected(true);
        decompileAdded.disableProperty().bind(decompile.selectedProperty().not());
        for (Spinner<Integer> s : List.of(maxClasses, context, threads, nestedDepth)) {
            s.setEditable(true);
            s.setPrefWidth(100);
        }

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);
        grid.setPadding(new Insets(8));
        ColumnConstraints label = new ColumnConstraints();
        ColumnConstraints field = new ColumnConstraints();
        field.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(label, field, new ColumnConstraints(), new ColumnConstraints());

        grid.addRow(0, new Label("Include libraries"), includes, new Label("Max classes / library"), maxClasses);
        grid.addRow(1, new Label("Exclude libraries"), excludes, new Label("Diff context lines"), context);
        grid.addRow(2, new Label("Ignore entries"), ignoredEntries, new Label("Threads"), threads);
        grid.addRow(3, new Label("Packages"), packages, new Label("Nested archive depth"), nestedDepth);
        grid.addRow(4, new Label("Archive types"), extensions);
        grid.add(decompile, 1, 5);
        grid.add(decompileAdded, 1, 6);
        grid.add(bytecode, 1, 7);
        grid.add(cache, 1, 8);
        cache.setSelected(true);
        cache.setTooltip(new Tooltip("Cache directory: " + ResultCache.defaultDirectory()));
        setContent(grid);
    }

    CompareOptions toOptions() {
        Set<String> ext = new LinkedHashSet<>();
        for (String e : split(extensions.getText())) {
            ext.add(e.replaceFirst("^\\.", "").toLowerCase(Locale.ROOT));
        }
        if (ext.isEmpty()) {
            ext.addAll(CompareOptions.DEFAULT_EXTENSIONS);
        }
        return new CompareOptions(ext, Set.of("classes"), nestedDepth.getValue(), split(includes.getText()),
                split(excludes.getText()), split(packages.getText()), decompile.isSelected(),
                decompileAdded.isSelected(), bytecode.isSelected(), maxClasses.getValue(), context.getValue(), 5000,
                threads.getValue(), 30, split(ignoredEntries.getText()));
    }

    /** Calls {@code listener} whenever an option that influences the result changes. */
    void onResultOptionChange(Runnable listener) {
        for (TextField f : List.of(includes, excludes, ignoredEntries, packages, extensions)) {
            f.textProperty().addListener((obs, o, n) -> listener.run());
        }
        for (CheckBox c : List.of(decompile, decompileAdded, bytecode)) {
            c.selectedProperty().addListener((obs, o, n) -> listener.run());
        }
        for (Spinner<Integer> s : List.of(maxClasses, context, nestedDepth)) {
            s.valueProperty().addListener((obs, o, n) -> listener.run());
        }
    }

    void setMaxClasses(int value) {
        maxClasses.getValueFactory().setValue(value);
    }

    void setNestedDepth(int value) {
        nestedDepth.getValueFactory().setValue(value);
    }

    boolean useCache() {
        return cache.isSelected();
    }

    void load(Settings s) {
        includes.setText(s.get("opt.includes", ""));
        excludes.setText(s.get("opt.excludes", ""));
        packages.setText(s.get("opt.packages", ""));
        ignoredEntries.setText(s.get("opt.ignoredEntries", ""));
        extensions.setText(s.get("opt.extensions", extensions.getText()));
        decompile.setSelected(s.getBoolean("opt.decompile", true));
        decompileAdded.setSelected(s.getBoolean("opt.decompileAdded", false));
        bytecode.setSelected(s.getBoolean("opt.bytecode", false));
        cache.setSelected(s.getBoolean("opt.cache", true));
        maxClasses.getValueFactory().setValue(s.getInt("opt.maxClasses", maxClasses.getValue()));
        context.getValueFactory().setValue(s.getInt("opt.context", context.getValue()));
        threads.getValueFactory().setValue(s.getInt("opt.threads", threads.getValue()));
        nestedDepth.getValueFactory().setValue(s.getInt("opt.nestedDepth", nestedDepth.getValue()));
    }

    void save(Settings s) {
        s.put("opt.includes", includes.getText());
        s.put("opt.excludes", excludes.getText());
        s.put("opt.packages", packages.getText());
        s.put("opt.ignoredEntries", ignoredEntries.getText());
        s.put("opt.extensions", extensions.getText());
        s.putBoolean("opt.decompile", decompile.isSelected());
        s.putBoolean("opt.decompileAdded", decompileAdded.isSelected());
        s.putBoolean("opt.bytecode", bytecode.isSelected());
        s.putBoolean("opt.cache", cache.isSelected());
        s.putInt("opt.maxClasses", maxClasses.getValue());
        s.putInt("opt.context", context.getValue());
        s.putInt("opt.threads", threads.getValue());
        s.putInt("opt.nestedDepth", nestedDepth.getValue());
    }

    private static List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
