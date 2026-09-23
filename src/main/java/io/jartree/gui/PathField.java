package io.jartree.gui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.ComboBox;

/** Editable path input with a drop-down of recently used paths. */
final class PathField extends ComboBox<String> {

    static final int MAX_HISTORY = 15;

    PathField(String prompt) {
        setEditable(true);
        setMaxWidth(Double.MAX_VALUE);
        setVisibleRowCount(MAX_HISTORY);
        getEditor().setPromptText(prompt);
    }

    String getText() {
        String t = getEditor().getText();
        return t == null ? "" : t;
    }

    void setText(String text) {
        setValue(text);
        getEditor().setText(text);
    }

    void setHistory(List<String> history) {
        String current = getText();
        getItems().setAll(history);
        getEditor().setText(current);
    }

    /** Moves {@code path} to the front of the history list. */
    static List<String> remember(List<String> history, String path) {
        List<String> updated = new ArrayList<>();
        if (path != null && !path.isBlank()) {
            updated.add(path.trim());
        }
        for (String h : history) {
            if (updated.size() >= MAX_HISTORY) {
                break;
            }
            if (!h.isBlank() && !h.equals(path == null ? null : path.trim())) {
                updated.add(h);
            }
        }
        return updated;
    }
}
