package io.jartree.gui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import io.jartree.bytecode.MemberChange;
import io.jartree.compare.TextSupport;

/**
 * Shows a unified diff either as a unified list or side by side, highlights the changed part of edited lines,
 * navigates between changes (buttons, N / P keys) and can reveal the lines of a changed member.
 */
final class DiffView extends VBox {

    enum Mode { UNIFIED, SIDE_BY_SIDE }

    /** Lines of the old or new file that belong to the member currently pointed at. */
    private record Focus(boolean newSide, int from, int to) {
        boolean contains(DiffModel.Line line) {
            if (line == null || !(line.isChange() || line.kind() == DiffModel.Kind.CONTEXT)) {
                return false;
            }
            Integer no = newSide ? line.newNo() : line.oldNo();
            int pos = no != null ? no : newSide ? line.newPos() : line.oldPos();
            return pos >= from && pos <= to;
        }
    }

    private final String text;
    private final String suggestedFileName;
    private final StackPane body = new StackPane();
    private final List<DiffModel.Line> lines;
    private final List<DiffModel.Row> rows;
    private final Label position = new Label();
    private ListView<DiffModel.Line> unified;
    private TableView<DiffModel.Row> sideBySide;
    private Mode currentMode;
    private Focus focus;
    private int currentChange = -1;
    private ChangeListener<Mode> modeListener;

    DiffView(TextSupport.DiffText diff, String suggestedFileName, String emptyMessage, ObjectProperty<Mode> mode) {
        this.text = diff.text();
        this.suggestedFileName = suggestedFileName;
        this.lines = DiffModel.parse(text);
        this.rows = DiffModel.sideBySide(lines);
        getStyleClass().add("diff-view");

        if (lines.isEmpty()) {
            Label empty = new Label(emptyMessage);
            empty.getStyleClass().add("placeholder");
            empty.setWrapText(true);
            getChildren().add(empty);
            return;
        }

        ToggleGroup group = new ToggleGroup();
        ToggleButton unifiedButton = new ToggleButton("Unified");
        ToggleButton sideButton = new ToggleButton("Side by side");
        unifiedButton.setToggleGroup(group);
        sideButton.setToggleGroup(group);
        unifiedButton.setUserData(Mode.UNIFIED);
        sideButton.setUserData(Mode.SIDE_BY_SIDE);
        group.selectToggle(mode.get() == Mode.UNIFIED ? unifiedButton : sideButton);
        group.selectedToggleProperty().addListener((obs, old, sel) -> {
            if (sel == null) {
                group.selectToggle(old);
            } else {
                mode.set((Mode) sel.getUserData());
            }
        });
        // weak, because the mode property outlives the views created for each selection
        modeListener = (obs, o, n) -> {
            group.selectToggle(n == Mode.UNIFIED ? unifiedButton : sideButton);
            showMode(n);
        };
        mode.addListener(new WeakChangeListener<>(modeListener));

        Button previous = new Button("▲");
        previous.setTooltip(new Tooltip("Previous change (P or Alt+Up)"));
        previous.setOnAction(e -> navigate(-1));
        Button next = new Button("▼");
        next.setTooltip(new Tooltip("Next change (N or Alt+Down)"));
        next.setOnAction(e -> navigate(1));
        position.getStyleClass().add("diff-stats");

        Label stats = new Label("+" + diff.added() + "  −" + diff.removed());
        stats.getStyleClass().add("diff-stats");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button copy = new Button("Copy");
        copy.setTooltip(new Tooltip("Copy the unified diff to the clipboard"));
        copy.setOnAction(e -> copyToClipboard(text));
        Button save = new Button("Save…");
        save.setTooltip(new Tooltip("Save the unified diff to a file"));
        save.setOnAction(e -> save());
        ToolBar bar = new ToolBar(unifiedButton, sideButton, stats, previous, next, position, spacer, copy, save);
        bar.getStyleClass().add("diff-toolbar");

        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isControlDown() || e.isShortcutDown()) {
                return;
            }
            if (e.getCode() == KeyCode.N || e.isAltDown() && e.getCode() == KeyCode.DOWN) {
                navigate(1);
                e.consume();
            } else if (e.getCode() == KeyCode.P || e.isAltDown() && e.getCode() == KeyCode.UP) {
                navigate(-1);
                e.consume();
            }
        });

        VBox.setVgrow(body, Priority.ALWAYS);
        getChildren().addAll(bar, body);
        showMode(mode.get());
    }

    boolean isEmpty() {
        return lines.isEmpty();
    }

    // ------------------------------------------------------------------ modes

    private void showMode(Mode m) {
        if (lines.isEmpty()) {
            return;
        }
        currentMode = m;
        body.getChildren().setAll(m == Mode.UNIFIED ? unified() : sideBySide());
        updatePosition();
        if (focus != null) {
            Platform.runLater(this::scrollToFocus);
        } else if (currentChange >= 0) {
            int change = currentChange;
            Platform.runLater(() -> showChange(change));
        }
    }

    private ListView<DiffModel.Line> unified() {
        if (unified == null) {
            unified = new ListView<>(FXCollections.observableList(lines));
            unified.getStyleClass().add("diff-list");
            unified.setCellFactory(lv -> new UnifiedCell());
        }
        return unified;
    }

    private TableView<DiffModel.Row> sideBySide() {
        if (sideBySide == null) {
            sideBySide = new TableView<>(FXCollections.observableList(rows));
            sideBySide.getStyleClass().add("diff-table");
            sideBySide.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
            TableColumn<DiffModel.Row, DiffModel.Row> oldNo = column("", 48, true, true);
            TableColumn<DiffModel.Row, DiffModel.Row> oldText = column("Old", 420, true, false);
            TableColumn<DiffModel.Row, DiffModel.Row> newNo = column("", 48, false, true);
            TableColumn<DiffModel.Row, DiffModel.Row> newText = column("New", 420, false, false);
            oldText.prefWidthProperty().bind(sideBySide.widthProperty().subtract(2 * 48 + 20).divide(2));
            newText.prefWidthProperty().bind(sideBySide.widthProperty().subtract(2 * 48 + 20).divide(2));
            sideBySide.getColumns().setAll(List.of(oldNo, oldText, newNo, newText));
        }
        return sideBySide;
    }

    private TableColumn<DiffModel.Row, DiffModel.Row> column(String title, double width, boolean left, boolean number) {
        TableColumn<DiffModel.Row, DiffModel.Row> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setSortable(false);
        col.setReorderable(false);
        col.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue()));
        col.setCellFactory(c -> new TableCell<>() {
            private final HighlightedText content = new HighlightedText();

            @Override
            protected void updateItem(DiffModel.Row row, boolean empty) {
                super.updateItem(row, empty);
                getStyleClass().removeIf(s -> s.startsWith("diff-"));
                setText(null);
                setGraphic(null);
                if (empty || row == null) {
                    return;
                }
                DiffModel.Kind kind = left ? row.leftKind() : row.rightKind();
                DiffModel.Line line = left ? row.leftLine() : row.rightLine();
                getStyleClass().add(styleOf(kind));
                if (number && focus != null && left != focus.newSide() && focus.contains(line)) {
                    getStyleClass().add("diff-focus");
                }
                if (number) {
                    Integer no = left ? row.leftNo() : row.rightNo();
                    setText(no == null ? "" : no.toString());
                    getStyleClass().add("diff-lineno");
                } else if (line != null && kind != DiffModel.Kind.EMPTY) {
                    content.show("", line);
                    setGraphic(content);
                }
            }
        });
        return col;
    }

    static String styleOf(DiffModel.Kind kind) {
        return "diff-" + kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Text with the changed part of an edited line shown in a stronger color. */
    private static final class HighlightedText extends HBox {
        private final Label before = new Label();
        private final Label changed = new Label();
        private final Label after = new Label();

        HighlightedText() {
            super(0);
            for (Label l : List.of(before, changed, after)) {
                l.getStyleClass().add("diff-text");
                l.setMinWidth(Region.USE_PREF_SIZE);
            }
            changed.getStyleClass().add("diff-hl");
            getChildren().addAll(before, changed, after);
            setMinWidth(Region.USE_PREF_SIZE);
        }

        void show(String marker, DiffModel.Line line) {
            String t = line.text();
            boolean highlight = line.hlStart() >= 0 && line.hlEnd() > line.hlStart() && line.hlEnd() <= t.length();
            if (highlight) {
                before.setText(marker + t.substring(0, line.hlStart()));
                changed.setText(t.substring(line.hlStart(), line.hlEnd()));
                after.setText(t.substring(line.hlEnd()));
            } else {
                before.setText(marker + t);
                changed.setText("");
                after.setText("");
            }
            changed.setVisible(highlight);
            changed.setManaged(highlight);
            after.setManaged(highlight);
        }
    }

    private final class UnifiedCell extends ListCell<DiffModel.Line> {
        private final Label oldNo = new Label();
        private final Label newNo = new Label();
        private final HighlightedText content = new HighlightedText();
        private final HBox box = new HBox(oldNo, newNo, content);

        UnifiedCell() {
            oldNo.getStyleClass().add("diff-lineno");
            newNo.getStyleClass().add("diff-lineno");
            oldNo.setMinWidth(44);
            newNo.setMinWidth(44);
            oldNo.setAlignment(Pos.CENTER_RIGHT);
            newNo.setAlignment(Pos.CENTER_RIGHT);
            box.setSpacing(8);
        }

        @Override
        protected void updateItem(DiffModel.Line line, boolean empty) {
            super.updateItem(line, empty);
            getStyleClass().removeIf(s -> s.startsWith("diff-"));
            if (empty || line == null) {
                setGraphic(null);
                return;
            }
            oldNo.setText(line.oldNo() == null ? "" : line.oldNo().toString());
            newNo.setText(line.newNo() == null ? "" : line.newNo().toString());
            String marker = switch (line.kind()) {
                case ADDED -> "+ ";
                case REMOVED -> "− ";
                case CONTEXT -> "  ";
                default -> "";
            };
            content.show(marker, line);
            getStyleClass().add(styleOf(line.kind()));
            if (focus != null && focus.contains(line)) {
                getStyleClass().add("diff-focus");
            }
            setGraphic(box);
        }
    }

    // ------------------------------------------------------------------ navigation

    /** Indices (in the current mode's list) where a block of changed lines starts. */
    private List<Integer> changeStarts() {
        List<Integer> starts = new ArrayList<>();
        if (currentMode == Mode.SIDE_BY_SIDE) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).isChange() && (i == 0 || !rows.get(i - 1).isChange())) {
                    starts.add(i);
                }
            }
        } else {
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).isChange() && (i == 0 || !lines.get(i - 1).isChange())) {
                    starts.add(i);
                }
            }
        }
        return starts;
    }

    private void navigate(int direction) {
        List<Integer> starts = changeStarts();
        if (starts.isEmpty()) {
            return;
        }
        int selected = selectedIndex();
        int target;
        if (direction > 0) {
            target = starts.size() - 1;
            for (int i = 0; i < starts.size(); i++) {
                if (starts.get(i) > selected) {
                    target = i;
                    break;
                }
            }
        } else {
            target = 0;
            for (int i = starts.size() - 1; i >= 0; i--) {
                if (starts.get(i) < selected) {
                    target = i;
                    break;
                }
            }
        }
        showChange(target);
    }

    private void showChange(int changeNumber) {
        List<Integer> starts = changeStarts();
        if (changeNumber >= 0 && changeNumber < starts.size()) {
            select(starts.get(changeNumber));
        }
    }

    private void updatePosition() {
        int total = changeStarts().size();
        position.setText(currentChange < 0 ? total + (total == 1 ? " change" : " changes")
                : "change " + (currentChange + 1) + " / " + total);
    }

    private int selectedIndex() {
        if (currentMode == Mode.SIDE_BY_SIDE && sideBySide != null) {
            return sideBySide.getSelectionModel().getSelectedIndex();
        }
        return unified == null ? -1 : unified.getSelectionModel().getSelectedIndex();
    }

    private void select(int index) {
        Control control = currentMode == Mode.SIDE_BY_SIDE ? sideBySide : unified;
        if (control instanceof ListView<?> lv) {
            lv.getSelectionModel().clearAndSelect(index);
            lv.scrollTo(Math.max(0, index - 3));
            lv.requestFocus();
        } else if (control instanceof TableView<?> tv) {
            tv.getSelectionModel().clearAndSelect(index);
            tv.scrollTo(Math.max(0, index - 3));
            tv.requestFocus();
        }
        // keep the change counter in sync with where we are
        List<Integer> starts = changeStarts();
        currentChange = -1;
        for (int i = 0; i < starts.size(); i++) {
            if (starts.get(i) <= index) {
                currentChange = i;
            }
        }
        updatePosition();
    }

    /**
     * Scrolls to the lines of a changed member and marks them. Falls back to searching the member name when
     * the member could not be located in the decompiled source.
     */
    void reveal(MemberChange member) {
        if (lines.isEmpty()) {
            return;
        }
        boolean newSide = member.change() != MemberChange.Change.REMOVED && member.newLine() != null;
        Integer from = newSide ? member.newLine() : member.oldLine();
        Integer to = newSide ? member.newEnd() : member.oldEnd();
        focus = from == null ? null : new Focus(newSide, from, to == null ? from : to);
        refreshCells();
        if (focus != null) {
            Platform.runLater(this::scrollToFocus);
            return;
        }
        String needle = member.kind() == MemberChange.Kind.FIELD ? member.name() : member.name() + "(";
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isChange() && lines.get(i).text().contains(needle)) {
                int index = currentMode == Mode.SIDE_BY_SIDE ? rowIndexOf(lines.get(i)) : i;
                Platform.runLater(() -> select(index));
                return;
            }
        }
    }

    private void scrollToFocus() {
        if (focus == null) {
            return;
        }
        int firstAfter = -1;
        int size = currentMode == Mode.SIDE_BY_SIDE ? rows.size() : lines.size();
        for (int i = 0; i < size; i++) {
            boolean change;
            boolean inFocus;
            int pos;
            if (currentMode == Mode.SIDE_BY_SIDE) {
                DiffModel.Row r = rows.get(i);
                change = r.isChange();
                inFocus = focus.contains(r.leftLine()) || focus.contains(r.rightLine());
                pos = focus.newSide() ? r.newPos() : r.oldPos();
            } else {
                DiffModel.Line l = lines.get(i);
                change = l.isChange();
                inFocus = focus.contains(l);
                pos = focus.newSide() ? l.newPos() : l.oldPos();
            }
            if (inFocus && change) {
                select(i);
                return;
            }
            if (firstAfter < 0 && pos >= focus.from() && (change || inFocus)) {
                firstAfter = i;
            }
        }
        select(firstAfter >= 0 ? firstAfter : size - 1);
    }

    private int rowIndexOf(DiffModel.Line line) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).leftLine() == line || rows.get(i).rightLine() == line) {
                return i;
            }
        }
        return 0;
    }

    private void refreshCells() {
        if (unified != null) {
            unified.refresh();
        }
        if (sideBySide != null) {
            sideBySide.refresh();
        }
    }

    // ------------------------------------------------------------------ copy / save

    static void copyToClipboard(String s) {
        ClipboardContent content = new ClipboardContent();
        content.putString(s);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void save() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save diff");
        chooser.setInitialFileName(suggestedFileName);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Diff files", "*.diff", "*.patch"));
        File file = chooser.showSaveDialog(getScene().getWindow());
        if (file != null) {
            try {
                Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
            } catch (IOException e) {
                Dialogs.error(getScene().getWindow(), "Could not save " + file, e);
            }
        }
    }
}
