package io.jartree.gui;

import java.io.PrintWriter;
import java.io.StringWriter;

import javafx.scene.control.Alert;
import javafx.scene.control.TextArea;
import javafx.stage.Window;

/** Error and information dialogs. */
final class Dialogs {

    private Dialogs() {
    }

    static void error(Window owner, String message, Throwable t) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(owner);
        alert.setTitle("jartree-compare");
        alert.setHeaderText(message);
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        alert.setContentText(root.getMessage() != null ? root.getMessage() : root.toString());
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        TextArea details = new TextArea(sw.toString());
        details.setEditable(false);
        details.setPrefRowCount(14);
        alert.getDialogPane().setExpandableContent(details);
        alert.showAndWait();
    }

    static void info(Window owner, String header, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle("jartree-compare");
        alert.setHeaderText(header);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
