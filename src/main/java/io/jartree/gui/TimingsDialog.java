package io.jartree.gui;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import io.jartree.compare.ComparisonResult;
import io.jartree.compare.Timings;

/** Shows where the time of a comparison was spent. */
final class TimingsDialog {

    private TimingsDialog() {
    }

    static void show(Window owner, ComparisonResult result) {
        Timings.Summary summary = result.timings();
        VBox box = new VBox(12);
        box.setPadding(new Insets(16));

        Label title = new Label("Elapsed " + Timings.format(summary.elapsedMillis()));
        title.getStyleClass().add("detail-title");
        Label note = new Label("Phases that run on several worker threads (comparison, uncompress, cache) are summed "
                + "over the threads, so they can add up to more than the elapsed time. Decompilation runs one "
                + "library at a time; “wait for the decompiler” is time other libraries spent queued for it.");
        note.setWrapText(true);
        note.getStyleClass().add("muted");
        box.getChildren().addAll(title, note);

        if (summary.phases().isEmpty()) {
            box.getChildren().add(new Label("No timing information (the report was written by an older version)."));
        } else {
            long max = Math.max(1, summary.phases().stream().mapToLong(Timings.Entry::millis).max().orElse(1));
            GridPane grid = new GridPane();
            grid.setHgap(12);
            grid.setVgap(6);
            ColumnConstraints bars = new ColumnConstraints();
            bars.setHgrow(Priority.ALWAYS);
            ColumnConstraints right = new ColumnConstraints();
            right.setHalignment(HPos.RIGHT);
            grid.getColumnConstraints().addAll(new ColumnConstraints(), right, bars, right);
            int row = 0;
            for (Timings.Entry e : summary.phases()) {
                ProgressBar bar = new ProgressBar((double) e.millis() / max);
                bar.setMaxWidth(Double.MAX_VALUE);
                bar.getStyleClass().add("timing-bar");
                Label count = new Label(e.count() == 0 ? "" : e.count() + "×");
                count.getStyleClass().add("muted");
                grid.addRow(row++, new Label(e.phase().label()), new Label(Timings.format(e.millis())), bar, count);
            }
            box.getChildren().add(grid);
        }

        if (!summary.slowestLibraries().isEmpty()) {
            Label heading = new Label("Slowest libraries");
            heading.getStyleClass().add("bold");
            GridPane libs = new GridPane();
            libs.setHgap(12);
            libs.setVgap(3);
            int row = 0;
            for (Timings.LibraryTime l : summary.slowestLibraries()) {
                Label path = new Label(l.library());
                path.getStyleClass().add("mono");
                Label cached = new Label(l.cached() ? "from cache" : "");
                cached.getStyleClass().add("muted");
                libs.addRow(row++, new Label(Timings.format(l.millis())), path, cached);
            }
            box.getChildren().addAll(heading, libs);
        }

        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("jartree-compare — where the time went");
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        Scene scene = new Scene(scroll, 720, 520);
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        stage.setScene(scene);
        stage.show();
    }
}
