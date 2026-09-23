package io.jartree.gui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import javax.imageio.ImageIO;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

/**
 * Development aid: opens a JSON report in the real main window, selects an item and writes a PNG of the scene.
 * Usage: GuiSnapshot REPORT.json OUT.png SELECT_SUBSTRING [unified|side] [SEARCH] [sort]
 * or:    GuiSnapshot --compare OLD NEW OUT.png SELECT_SUBSTRING (runs the comparison through the GUI)
 */
public final class GuiSnapshot {

    public static void main(String[] args) throws Exception {
        if (args[0].equals("--compare")) {
            compare(args);
            return;
        }
        Path report = Path.of(args[0]);
        File out = new File(args[1]);
        String select = args.length > 2 ? args[2] : "";
        DiffView.Mode mode = args.length > 3 && args[3].startsWith("side") ? DiffView.Mode.SIDE_BY_SIDE : DiffView.Mode.UNIFIED;
        String search = args.length > 4 ? args[4] : "";

        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();

        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            Stage stage = new Stage();
            MainWindow window = new MainWindow(stage, null);
            Scene scene = new Scene(window, 1360, 860);
            scene.getStylesheets().add(JarTreeGui.class.getResource("app.css").toExternalForm());
            stage.setScene(scene);
            stage.show();
            window.openReport(report);
            if (!search.isEmpty()) {
                ((javafx.scene.control.TextField) scene.lookup(".filter-bar .text-field")).setText(search);
            }
            if (args.length > 5 && args[5].equals("sort")) {
                window.tree().sortByChanges();
            }
            if (args.length > 5 && args[5].equals("stale")) {
                // simulate the user changing an option after the comparison
                try {
                    var f = MainWindow.class.getDeclaredField("options");
                    f.setAccessible(true);
                    ((OptionsPane) f.get(window)).setMaxClasses(5000);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            window.tree().select(i -> ResultTree.nameText(i).contains(select));
            // switch mode after selection so the detail view follows the property change
            javafx.beans.property.ObjectProperty<DiffView.Mode> modeProperty = diffModeOf(window);
            modeProperty.set(mode);
            // give layout a few pulses before taking the snapshot
            new Thread(() -> {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                }
                Platform.runLater(() -> {
                    try {
                        WritableImage img = scene.snapshot(null);
                        ImageIO.write(toBuffered(img), "png", out);
                        modeProperty.set(DiffView.Mode.UNIFIED);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    done.countDown();
                });
            }).start();
        });
        done.await();
        Platform.exit();
        System.out.println("wrote " + out.getAbsolutePath());
    }

    private static void compare(String[] args) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();
        MainWindow[] holder = new MainWindow[1];
        Scene[] sceneHolder = new Scene[1];
        Platform.runLater(() -> {
            Stage stage = new Stage();
            MainWindow window = new MainWindow(stage, null);
            Scene scene = new Scene(window, 1360, 860);
            scene.getStylesheets().add(JarTreeGui.class.getResource("app.css").toExternalForm());
            stage.setScene(scene);
            stage.show();
            window.setPaths(args[1], args[2]);
            window.compare();
            holder[0] = window;
            sceneHolder[0] = scene;
        });
        // snapshot once while running, then wait for the result
        Thread.sleep(2500);
        CountDownLatch mid = new CountDownLatch(1);
        Platform.runLater(() -> {
            write(sceneHolder[0], new File(args[3].replace(".png", "-running.png")));
            mid.countDown();
        });
        mid.await();
        for (int i = 0; i < 240; i++) {
            Thread.sleep(500);
            CountDownLatch check = new CountDownLatch(1);
            boolean[] ready = new boolean[1];
            Platform.runLater(() -> {
                ready[0] = !holder[0].tree().visibleLibraries().isEmpty();
                check.countDown();
            });
            check.await();
            if (ready[0]) {
                break;
            }
        }
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            holder[0].tree().select(item -> ResultTree.nameText(item).contains(args[4]));
            done.countDown();
        });
        done.await();

        // regression check: re-run the comparison while an item is selected and the tree is sorted
        int[] failures = new int[1];
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            failures[0]++;
            e.printStackTrace();
        });
        CountDownLatch rerun = new CountDownLatch(1);
        Platform.runLater(() -> {
            holder[0].tree().sortByChanges();
            holder[0].tree().select(item -> ResultTree.nameText(item).contains(args[4]));
            holder[0].compare();
            rerun.countDown();
        });
        rerun.await();
        Thread.sleep(8000);
        System.out.println("re-run with selection: " + (failures[0] == 0 ? "no exception" : failures[0] + " exception(s)"));
        CountDownLatch reselect = new CountDownLatch(1);
        Platform.runLater(() -> {
            holder[0].tree().select(item -> ResultTree.nameText(item).contains(args[4]));
            reselect.countDown();
        });
        reselect.await();
        Thread.sleep(1500);
        CountDownLatch shot = new CountDownLatch(1);
        Platform.runLater(() -> {
            write(sceneHolder[0], new File(args[3]));
            shot.countDown();
        });
        shot.await();
        Platform.exit();
        System.out.println("wrote " + args[3]);
    }

    private static void write(Scene scene, File out) {
        try {
            ImageIO.write(toBuffered(scene.snapshot(null)), "png", out);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    private static javafx.beans.property.ObjectProperty<DiffView.Mode> diffModeOf(MainWindow window) {
        try {
            var f = MainWindow.class.getDeclaredField("diffMode");
            f.setAccessible(true);
            return (javafx.beans.property.ObjectProperty<DiffView.Mode>) f.get(window);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BufferedImage toBuffered(WritableImage img) {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader r = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bi.setRGB(x, y, r.getArgb(x, y));
            }
        }
        return bi;
    }
}
