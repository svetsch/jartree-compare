package io.jartree.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

/**
 * JavaFX front end. Arguments: none, {@code OLD NEW} (compared on start), or a JSON report to open.
 */
public final class JarTreeGui extends Application {

    private MainWindow window;

    /** Kept referenced so that the filter below is not lost when the logger is garbage collected. */
    private static final Logger JAVAFX_LOGGER = Logger.getLogger("javafx");

    /** Launches the GUI; called by {@link io.jartree.Main} so that the shaded jar works without a module path. */
    public static void launchGui(String[] args) {
        // The single jar loads JavaFX from the class path. JavaFX works fine that way but logs
        // "Unsupported JavaFX configuration: classes were loaded from 'unnamed module ...'" at startup;
        // drop exactly that message and keep every other JavaFX warning.
        JAVAFX_LOGGER.setFilter(record -> record.getMessage() == null
                || !record.getMessage().startsWith("Unsupported JavaFX configuration"));
        Application.launch(JarTreeGui.class, args);
    }

    @Override
    public void start(Stage stage) {
        window = new MainWindow(stage, getHostServices());
        Settings settings = window.settings();
        Scene scene = new Scene(window, settings.getDouble("width", 1360), settings.getDouble("height", 860));
        scene.getStylesheets().add(JarTreeGui.class.getResource("app.css").toExternalForm());
        stage.setTitle("jartree-compare");
        for (int size : new int[] {32, 64, 128, 256}) {
            stage.getIcons().add(new Image(JarTreeGui.class.getResourceAsStream("icon-" + size + ".png")));
        }
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(560);
        if (settings.getBoolean("maximized", false)) {
            stage.setMaximized(true);
        }
        stage.show();

        List<String> args = getParameters().getUnnamed();
        if (args.size() == 1 && args.get(0).toLowerCase().endsWith(".json") && Files.isRegularFile(Path.of(args.get(0)))) {
            window.openReport(Path.of(args.get(0)));
        } else if (args.size() == 2) {
            window.setPaths(args.get(0), args.get(1));
            window.compare();
        }
    }

    @Override
    public void stop() {
        if (window != null && window.getScene() != null) {
            Stage stage = (Stage) window.getScene().getWindow();
            Settings settings = window.settings();
            settings.putBoolean("maximized", stage.isMaximized());
            if (!stage.isMaximized()) {
                settings.putDouble("width", stage.getWidth());
                settings.putDouble("height", stage.getHeight());
            }
            window.saveSettings();
        }
    }
}
