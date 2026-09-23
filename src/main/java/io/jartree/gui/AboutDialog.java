package io.jartree.gui;

import java.nio.file.Files;

import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import io.jartree.BuildInfo;
import io.jartree.compare.ResultCache;
import io.jartree.report.ConsoleReport;

/** About box: version, git commit of this build, runtime, cache and the libraries used. */
final class AboutDialog {

    private AboutDialog() {
    }

    static void show(Window owner, HostServices hostServices) {
        VBox box = new VBox(10);
        box.setPadding(new Insets(18));

        Label title = new Label("jartree-compare");
        title.getStyleClass().add("detail-title");
        Label subtitle = new Label("Compares two hierarchies of jar files and shows the decompiled code changes.");
        subtitle.setWrapText(true);
        subtitle.getStyleClass().add("muted");

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(4);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, values);
        int row = 0;
        grid.addRow(row++, muted("Version"), mono(BuildInfo.VERSION));
        if (BuildInfo.hasCommit()) {
            Hyperlink commit = new Hyperlink(BuildInfo.COMMIT + (BuildInfo.DIRTY ? " (with uncommitted changes)" : ""));
            commit.getStyleClass().add("mono");
            commit.setOnAction(e -> open(hostServices, BuildInfo.commitUrl()));
            grid.addRow(row++, muted("Commit"), commit);
            grid.addRow(row++, muted("Branch"), mono(BuildInfo.BRANCH));
            grid.addRow(row++, muted("Commit date"), mono(BuildInfo.COMMIT_TIME));
        } else {
            grid.addRow(row++, muted("Commit"), mono("unknown (built without a git checkout)"));
        }
        grid.addRow(row++, muted("Built"), mono(BuildInfo.BUILD_TIME));
        grid.addRow(row++, muted("Java"), mono(System.getProperty("java.version") + "  ·  "
                + System.getProperty("java.vendor")));
        grid.addRow(row++, muted("JavaFX"), mono(System.getProperty("javafx.runtime.version", "unknown")));
        grid.addRow(row++, muted("Maximum heap"), mono(BuildInfo.maxHeap()));
        ResultCache cache = new ResultCache(ResultCache.defaultDirectory());
        grid.addRow(row++, muted("Cache size"), cacheSize(cache));
        if (Files.isDirectory(cache.directory())) {
            Hyperlink folder = new Hyperlink(cache.directory().toString());
            folder.getStyleClass().add("mono");
            folder.setOnAction(e -> open(hostServices, cache.directory().toUri().toString()));
            grid.addRow(row++, muted("Cache folder"), folder);
        } else {
            grid.addRow(row++, muted("Cache folder"), mono(cache.directory() + " (not created yet)"));
        }
        grid.addRow(row++, muted("License"), mono("MIT"));
        Hyperlink repository = new Hyperlink(BuildInfo.REPOSITORY);
        repository.setOnAction(e -> open(hostServices, BuildInfo.REPOSITORY));
        grid.addRow(row, muted("Project"), repository);

        Label libraries = new Label("Decompiler: Vineflower · Bytecode: ASM · Diff: java-diff-utils "
                + "· Command line: picocli");
        libraries.setWrapText(true);
        libraries.getStyleClass().add("muted");

        Button copy = new Button("Copy version details");
        copy.setOnAction(e -> DiffView.copyToClipboard(details()));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Stage stage = new Stage();
        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());
        close.setDefaultButton(true);
        HBox buttons = new HBox(8, spacer, copy, close);

        box.getChildren().addAll(title, subtitle, grid, libraries, buttons);
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("About jartree-compare");
        Scene scene = new Scene(box, 580, 440);
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        stage.setScene(scene);
        stage.showAndWait();
    }

    /** A label that shows the size of the cache once it is known; walking a large cache takes a moment. */
    private static Label cacheSize(ResultCache cache) {
        Label label = mono("calculating…");
        Thread thread = new Thread(() -> {
            long size = cache.size();
            Platform.runLater(() -> label.setText(size == 0 ? "empty" : ConsoleReport.size(size)
                    + "  ·  cleared with Tools ▸ Clear cache"));
        }, "jartree-about-cache-size");
        thread.setDaemon(true);
        thread.start();
        return label;
    }

    /** Version information in one block, for bug reports. */
    static String details() {
        return "jartree-compare " + BuildInfo.describe()
                + "\ncommit: " + (BuildInfo.hasCommit() ? BuildInfo.COMMIT_FULL : "unknown")
                + "\nbranch: " + BuildInfo.BRANCH
                + "\njava: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")"
                + "\njavafx: " + System.getProperty("javafx.runtime.version", "unknown")
                + "\nmax heap: " + BuildInfo.maxHeap()
                + "\nos: " + System.getProperty("os.name") + " " + System.getProperty("os.version");
    }

    private static void open(HostServices hostServices, String url) {
        if (hostServices != null) {
            hostServices.showDocument(url);
        } else {
            DiffView.copyToClipboard(url);
        }
    }

    private static Label muted(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("muted");
        return l;
    }

    private static Label mono(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("mono");
        l.setWrapText(true);
        return l;
    }
}
