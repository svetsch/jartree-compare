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
        if (args[0].equals("--launch")) {
            launch(args.length > 1 ? new File(args[1]) : null);
            return;
        }
        if (args[0].equals("--tabs")) {
            tabs(args);
            return;
        }
        if (args[0].equals("--about")) {
            about(new File(args[1]));
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
            window.open(report);
            if (!search.isEmpty()) {
                ((javafx.scene.control.TextField) scene.lookup(".filter-bar .text-field")).setText(search);
            }
            if (args.length > 5 && args[5].equals("sort")) {
                window.tree().sortByChanges();
            }
            if (args.length > 5 && args[5].equals("stale")) {
                // simulate the user changing an option after the comparison
                window.activePane().options().setMaxClasses(5000);
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

    /**
     * Starts the application through the real launcher (JarTreeGui.start), optionally writes a PNG of the
     * window and exits. Fails when starting the application throws, which a directly built MainWindow misses.
     */
    private static void launch(File out) throws Exception {
        new Thread(() -> {
            try {
                Thread.sleep(6000);
            } catch (InterruptedException ignored) {
            }
            Platform.runLater(() -> {
                Stage stage = (Stage) javafx.stage.Window.getWindows().stream()
                        .filter(w -> w instanceof Stage).findFirst().orElse(null);
                if (stage != null && out != null) {
                    write(stage.getScene(), out);
                }
                System.out.println("window title: " + (stage == null ? "none" : stage.getTitle()));
                Platform.exit();
            });
        }).start();
        JarTreeGui.launchGui(new String[0]);
        System.out.println("application started and closed cleanly");
    }

    /** Opens one tab per report and renders the window. Usage: --tabs OUT.png REPORT.json... */
    private static void tabs(String[] args) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();
        Scene[] sceneHolder = new Scene[1];
        CountDownLatch opened = new CountDownLatch(1);
        Platform.runLater(() -> {
            Stage stage = new Stage();
            MainWindow window = new MainWindow(stage, null);
            Scene scene = new Scene(window, 1360, 860);
            scene.getStylesheets().add(JarTreeGui.class.getResource("app.css").toExternalForm());
            stage.setScene(scene);
            stage.show();
            for (int i = 2; i < args.length; i++) {
                window.addTab(null);
                window.open(Path.of(args[i]));
            }
            sceneHolder[0] = scene;
            opened.countDown();
        });
        opened.await();
        Thread.sleep(2500);
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            write(sceneHolder[0], new File(args[1]));
            done.countDown();
        });
        done.await();
        Platform.exit();
        System.out.println("wrote " + args[1]);
    }

    /** Renders the modal About dialog; it is found by title because it runs its own event loop. */
    private static void about(File out) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();
        Platform.runLater(() -> {
            Stage owner = new Stage();
            owner.setScene(new Scene(new MainWindow(owner, null), 900, 600));
            owner.getScene().getStylesheets().add(JarTreeGui.class.getResource("app.css").toExternalForm());
            owner.show();
            AboutDialog.show(owner, null);
        });
        Thread.sleep(2500);
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            javafx.stage.Window dialog = javafx.stage.Window.getWindows().stream()
                    .filter(w -> w instanceof Stage s && "About jartree-compare".equals(s.getTitle()))
                    .findFirst().orElse(null);
            if (dialog != null) {
                write(dialog.getScene(), out);
                ((Stage) dialog).close();
            } else {
                System.out.println("about dialog not found");
            }
            done.countDown();
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
