package io.jartree.gui;

import javafx.application.HostServices;
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

/** About box: version, git commit of this build, runtime and the libraries used. */
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
        Scene scene = new Scene(box, 560, 330);
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        stage.setScene(scene);
        stage.showAndWait();
    }

    /** Version information in one block, for bug reports. */
    static String details() {
        return "jartree-compare " + BuildInfo.describe()
                + "\ncommit: " + (BuildInfo.hasCommit() ? BuildInfo.COMMIT_FULL : "unknown")
                + "\nbranch: " + BuildInfo.BRANCH
                + "\njava: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")"
                + "\njavafx: " + System.getProperty("javafx.runtime.version", "unknown")
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
