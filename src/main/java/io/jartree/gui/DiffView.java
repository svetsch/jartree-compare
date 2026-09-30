package io.jartree.gui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
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
import io.jartree.compare.PackedText;
import io.jartree.compare.TextSupport;

/**
 * Shows a unified diff either as a unified list or side by side, highlights the changed part of edited lines,
 * colors Java syntax, navigates between changes (buttons, N / P keys), can reveal the lines of a changed member,
 * and copies or saves the selected lines, the diff or the whole old and new file. When the diff with the whole file
 * as context is known, the view can switch between the changed lines and the whole file.
 */
final class DiffView extends VBox {

    enum Mode { UNIFIED, SIDE_BY_SIDE }

    /**
     * The whole files the diff was made from.
     *
     * @param fileName name to save them under
     * @param oldText  full old text, null when not available
     * @param newText  full new text, null when not available
     */
    record Sources(String fileName, PackedText oldText, PackedText newText) {
        static final Sources NONE = new Sources(null, null, null);
    }

    /** Directory of the last file saved from any diff, so that the old and new file land next to each other. */
    private static File lastDirectory;

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

    /** A parsed diff, in both presentations. */
    private record Model(String text, List<DiffModel.Line> lines, List<DiffModel.Row> rows) {
        static Model of(String text) {
            List<DiffModel.Line> lines = DiffModel.parse(text);
            return new Model(text, lines, DiffModel.sideBySide(lines));
        }
    }

    private final String suggestedFileName;
    private final Sources sources;
    private final StackPane body = new StackPane();
    /** The changed lines with some context. */
    private final Model changes;
    /** The diff with the whole file as context, or null if not known. */
    private final String wholeText;
    private Model whole;
    /** What is shown: {@link #changes} or {@link #whole}. */
    private Model model;
    private List<DiffModel.Line> lines;
    private List<DiffModel.Row> rows;
    private final boolean javaSyntax;
    /** Colored parts of each code line of the models shown so far; empty when the diff is not Java source. */
    private final Map<DiffModel.Line, List<JavaSyntax.Span>> syntax = new IdentityHashMap<>();
    /** Models whose lines are in {@link #syntax}. */
    private final Set<Model> colored = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Start state of each line of the whole old and new file (null when unknown), once computed. */
    private JavaSyntax.State[] oldStates;
    private JavaSyntax.State[] newStates;
    private boolean statesComputed;
    private final Label position = new Label();
    private ListView<DiffModel.Line> unified;
    private TableView<DiffModel.Row> sideBySide;
    private Mode currentMode;
    private Focus focus;
    private int currentChange = -1;
    private ChangeListener<Mode> modeListener;
    private ChangeListener<Boolean> wholeListener;
    /** Side of the side-by-side view last clicked; Ctrl+C copies the selected lines of that side. */
    private boolean newSideClicked = true;

    /** @param javaSyntax color the lines as Java source */
    DiffView(TextSupport.DiffText diff, String suggestedFileName, Sources sources, boolean javaSyntax,
             String emptyMessage, ObjectProperty<Mode> mode) {
        this(diff, null, null, suggestedFileName, sources, javaSyntax, emptyMessage, mode, null);
    }

    /**
     * @param wholeText  the same diff with the whole file as context, or null
     * @param wholeLabel what the whole file is called on the button that shows it, e.g. "Whole class"
     * @param sources    the whole old and new file, for copying and saving
     * @param javaSyntax color the lines as Java source
     * @param showWhole  whether the whole file is shown, shared by the views; may be null without {@code wholeText}
     */
    DiffView(TextSupport.DiffText diff, String wholeText, String wholeLabel, String suggestedFileName,
             Sources sources, boolean javaSyntax, String emptyMessage, ObjectProperty<Mode> mode,
             BooleanProperty showWhole) {
        this.javaSyntax = javaSyntax;
        this.suggestedFileName = suggestedFileName;
        this.sources = sources;
        this.changes = Model.of(diff.text());
        this.wholeText = changes.lines().isEmpty() || wholeText == null || wholeText.isEmpty() ? null : wholeText;
        useModel(this.wholeText != null && showWhole.get() ? whole() : changes);
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

        ToggleButton wholeButton = null;
        if (this.wholeText != null) {
            wholeButton = new ToggleButton(wholeLabel);
            wholeButton.setTooltip(new Tooltip("Show the " + wholeLabel.toLowerCase(Locale.ROOT)
                    + " instead of only the changed lines and their context (W)"));
            wholeButton.setSelected(showWhole.get());
            ToggleButton button = wholeButton;
            wholeButton.setOnAction(e -> showWhole.set(button.isSelected()));
            // weak for the same reason as the mode listener
            wholeListener = (obs, o, n) -> {
                button.setSelected(n);
                showWhole(n);
            };
            showWhole.addListener(new WeakChangeListener<>(wholeListener));
        }

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
        MenuButton copy = new MenuButton("Copy");
        copy.setTooltip(new Tooltip("Copy the selected old or new lines, the unified diff as shown or a whole file.\n"
                + "Ctrl+C copies the selected lines of the side last clicked."));
        copy.setOnShowing(e -> copy.getItems().setAll(copyItems()));
        MenuButton save = new MenuButton("Save");
        save.setTooltip(new Tooltip("Save the unified diff as shown or the whole old or new file"));
        save.setOnShowing(e -> save.getItems().setAll(saveItems()));
        // the items are built when the menu opens; a placeholder lets it open at all
        copy.getItems().add(new MenuItem());
        save.getItems().add(new MenuItem());
        ToolBar bar = new ToolBar(unifiedButton, sideButton);
        if (wholeButton != null) {
            bar.getItems().add(wholeButton);
        }
        bar.getItems().addAll(stats, previous, next, position, spacer, copy, save);
        bar.getStyleClass().add("diff-toolbar");
        BooleanProperty toggleWhole = this.wholeText == null ? null : showWhole;

        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isShortcutDown() && e.getCode() == KeyCode.C) {
                copySelection();
                e.consume();
                return;
            }
            if (e.isControlDown() || e.isShortcutDown()) {
                return;
            }
            if (e.getCode() == KeyCode.N || e.isAltDown() && e.getCode() == KeyCode.DOWN) {
                navigate(1);
                e.consume();
            } else if (e.getCode() == KeyCode.P || e.isAltDown() && e.getCode() == KeyCode.UP) {
                navigate(-1);
                e.consume();
            } else if (e.getCode() == KeyCode.W && !e.isAltDown() && toggleWhole != null) {
                toggleWhole.set(!toggleWhole.get());
                e.consume();
            }
        });

        VBox.setVgrow(body, Priority.ALWAYS);
        getChildren().addAll(bar, body);
        showMode(mode.get());
    }

    boolean isEmpty() {
        return changes.lines().isEmpty();
    }

    // ------------------------------------------------------------------ changes / whole file

    private Model whole() {
        if (whole == null) {
            whole = Model.of(wholeText);
        }
        return whole;
    }

    private void useModel(Model m) {
        if (javaSyntax && colored.add(m)) {
            colorSyntax(m.lines());
        }
        model = m;
        lines = m.lines();
        rows = m.rows();
    }

    /** Switches between the changed lines and the whole file, staying at the selected line. */
    private void showWhole(boolean on) {
        Model next = on && wholeText != null ? whole() : changes;
        if (next == model) {
            return;
        }
        DiffModel.Line anchor = selectedLine();
        useModel(next);
        if (unified != null) {
            unified.setItems(FXCollections.observableList(lines));
        }
        if (sideBySide != null) {
            sideBySide.setItems(FXCollections.observableList(rows));
        }
        int index = anchor == null ? -1 : indexAt(anchor);
        currentChange = -1;
        updatePosition();
        if (index >= 0) {
            Platform.runLater(() -> select(index));
        } else if (focus != null) {
            Platform.runLater(this::scrollToFocus);
        }
    }

    /** The selected line; in side-by-side mode the line of the selected row that has a number on the new side. */
    private DiffModel.Line selectedLine() {
        int i = selectedIndex();
        if (i < 0) {
            return null;
        }
        if (currentMode == Mode.SIDE_BY_SIDE) {
            DiffModel.Row r = rows.get(i);
            return r.rightLine() != null ? r.rightLine() : r.leftLine();
        }
        return lines.get(i);
    }

    /**
     * Index (in the current mode's list) of the given line of the other model, or of the first line after it when
     * the line is not shown; -1 for lines without a place in the files (headers, notes).
     */
    private int indexAt(DiffModel.Line anchor) {
        if (!(anchor.isChange() || anchor.kind() == DiffModel.Kind.CONTEXT)) {
            return -1;
        }
        int size = currentMode == Mode.SIDE_BY_SIDE ? rows.size() : lines.size();
        int last = -1;
        for (int i = 0; i < size; i++) {
            int newPos;
            int oldPos;
            boolean placed;
            if (currentMode == Mode.SIDE_BY_SIDE) {
                DiffModel.Row r = rows.get(i);
                if (sameLine(r.leftLine(), anchor) || sameLine(r.rightLine(), anchor)) {
                    return i;
                }
                newPos = r.newPos();
                oldPos = r.oldPos();
                placed = r.isChange() || r.leftKind() == DiffModel.Kind.CONTEXT;
            } else {
                DiffModel.Line l = lines.get(i);
                if (sameLine(l, anchor)) {
                    return i;
                }
                newPos = l.newPos();
                oldPos = l.oldPos();
                placed = l.isChange() || l.kind() == DiffModel.Kind.CONTEXT;
            }
            if (placed) {
                if (newPos > anchor.newPos() || newPos == anchor.newPos() && oldPos > anchor.oldPos()) {
                    return i;
                }
                last = i;
            }
        }
        return last;
    }

    private static boolean sameLine(DiffModel.Line a, DiffModel.Line b) {
        return a != null && a.kind() == b.kind() && Objects.equals(a.oldNo(), b.oldNo())
                && Objects.equals(a.newNo(), b.newNo());
    }

    // ------------------------------------------------------------------ modes

    private void showMode(Mode m) {
        if (lines.isEmpty()) {
            return;
        }
        currentMode = m;
        body.getChildren().setAll(m == Mode.UNIFIED ? unified() : sideBySide());
        // lay the new list out now: scrolling a list that has never been laid out misses the target
        if (getScene() != null) {
            body.applyCss();
            body.layout();
        }
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
            unified.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            unified.setCellFactory(lv -> new UnifiedCell());
            unified.setContextMenu(contextMenu());
        }
        return unified;
    }

    private TableView<DiffModel.Row> sideBySide() {
        if (sideBySide == null) {
            sideBySide = new TableView<>(FXCollections.observableList(rows));
            sideBySide.getStyleClass().add("diff-table");
            sideBySide.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
            sideBySide.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            sideBySide.setContextMenu(contextMenu());
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

            {
                setOnMousePressed(e -> newSideClicked = !left);
            }

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
                    content.show("", line, syntax.get(line));
                    setGraphic(content);
                }
            }
        });
        return col;
    }

    static String styleOf(DiffModel.Kind kind) {
        return "diff-" + kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Tokenizes the code lines. A line's start state (inside a block comment or text block or not) comes from the
     * whole file when it is available, since a hunk may start inside a comment; otherwise it is carried from line
     * to line within the diff, starting each hunk in code.
     */
    private void colorSyntax(List<DiffModel.Line> lines) {
        if (!statesComputed) {
            oldStates = lineStates(sources.oldText());
            newStates = lineStates(sources.newText());
            statesComputed = true;
        }
        JavaSyntax.State oldState = JavaSyntax.State.CODE;
        JavaSyntax.State newState = JavaSyntax.State.CODE;
        for (DiffModel.Line line : lines) {
            switch (line.kind()) {
                case HUNK -> {
                    oldState = JavaSyntax.State.CODE;
                    newState = JavaSyntax.State.CODE;
                }
                case CONTEXT, ADDED, REMOVED -> {
                    boolean newSide = line.kind() != DiffModel.Kind.REMOVED;
                    Integer no = newSide ? line.newNo() : line.oldNo();
                    JavaSyntax.State[] states = newSide ? newStates : oldStates;
                    JavaSyntax.State start = states != null && no != null && no >= 1 && no <= states.length
                            ? states[no - 1] : newSide ? newState : oldState;
                    JavaSyntax.Result result = JavaSyntax.highlight(line.text(), start);
                    syntax.put(line, result.spans());
                    if (line.kind() != DiffModel.Kind.ADDED) {
                        oldState = result.end();
                    }
                    if (line.kind() != DiffModel.Kind.REMOVED) {
                        newState = result.end();
                    }
                }
                default -> {
                }
            }
        }
    }

    private static JavaSyntax.State[] lineStates(PackedText text) {
        if (text == null) {
            return null;
        }
        try {
            return JavaSyntax.lineStates(text.text());
        } catch (RuntimeException e) {
            // an unreadable full text only costs the exact start states
            return null;
        }
    }

    /**
     * Text of a line in pieces: syntax colored, with the changed part of an edited line shown in a stronger
     * color on top. The labels are reused from one line to the next.
     */
    private static final class HighlightedText extends HBox {
        private final List<Label> pool = new ArrayList<>();
        private final Label changed = new Label();
        private final List<Node> parts = new ArrayList<>();
        private int used;

        HighlightedText() {
            super(0);
            changed.getStyleClass().addAll("diff-text", "diff-hl");
            changed.setMinWidth(Region.USE_PREF_SIZE);
            setMinWidth(Region.USE_PREF_SIZE);
        }

        /** @param spans colored parts of the line, or null to show it plainly */
        void show(String marker, DiffModel.Line line, List<JavaSyntax.Span> spans) {
            parts.clear();
            used = 0;
            String t = line.text();
            if (!marker.isEmpty()) {
                piece(marker, null);
            }
            boolean highlight = line.hlStart() >= 0 && line.hlEnd() > line.hlStart() && line.hlEnd() <= t.length();
            if (highlight) {
                pieces(t, 0, line.hlStart(), spans);
                changed.setText(t.substring(line.hlStart(), line.hlEnd()));
                parts.add(changed);
                pieces(t, line.hlEnd(), t.length(), spans);
            } else {
                pieces(t, 0, t.length(), spans);
            }
            getChildren().setAll(parts);
        }

        /** Adds the text from {@code from} to {@code to}, split where its color changes. */
        private void pieces(String t, int from, int to, List<JavaSyntax.Span> spans) {
            int pos = from;
            if (spans != null) {
                for (JavaSyntax.Span span : spans) {
                    int start = Math.max(span.start(), pos);
                    int end = Math.min(span.end(), to);
                    if (start >= end) {
                        continue;
                    }
                    if (start > pos) {
                        piece(t.substring(pos, start), null);
                    }
                    piece(t.substring(start, end), span.style());
                    pos = end;
                }
            }
            if (pos < to) {
                piece(t.substring(pos, to), null);
            }
        }

        private void piece(String text, JavaSyntax.Style style) {
            Label label;
            if (used < pool.size()) {
                label = pool.get(used);
            } else {
                label = new Label();
                label.setMinWidth(Region.USE_PREF_SIZE);
                pool.add(label);
            }
            used++;
            label.setText(text);
            label.getStyleClass().setAll("label", "diff-text");
            if (style != null) {
                label.getStyleClass().add("syn-" + style.name().toLowerCase(Locale.ROOT));
            }
            parts.add(label);
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
            content.show(marker, line, syntax.get(line));
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

    private List<MenuItem> copyItems() {
        boolean selection = !selectedIndices().isEmpty();
        return List.of(
                item("Selected old lines", selection, () -> copyToClipboard(selectedText(false))),
                item("Selected new lines", selection, () -> copyToClipboard(selectedText(true))),
                new SeparatorMenuItem(),
                item("Unified diff", true, () -> copyToClipboard(model.text())),
                item("Whole old file", sources.oldText() != null, () -> copyFile(false)),
                item("Whole new file", sources.newText() != null, () -> copyFile(true)));
    }

    private List<MenuItem> saveItems() {
        return List.of(
                item("Unified diff…", true, this::saveDiff),
                item("Old file…", sources.oldText() != null, () -> saveFile(false)),
                item("New file…", sources.newText() != null, () -> saveFile(true)));
    }

    private ContextMenu contextMenu() {
        ContextMenu menu = new ContextMenu();
        menu.setOnShowing(e -> {
            boolean selection = !selectedIndices().isEmpty();
            menu.getItems().setAll(
                    item("Copy selected old lines", selection, () -> copyToClipboard(selectedText(false))),
                    item("Copy selected new lines", selection, () -> copyToClipboard(selectedText(true))),
                    new SeparatorMenuItem(),
                    item("Copy whole old file", sources.oldText() != null, () -> copyFile(false)),
                    item("Copy whole new file", sources.newText() != null, () -> copyFile(true)),
                    item("Save old file…", sources.oldText() != null, () -> saveFile(false)),
                    item("Save new file…", sources.newText() != null, () -> saveFile(true)));
        });
        menu.getItems().add(new MenuItem());
        return menu;
    }

    private static MenuItem item(String label, boolean enabled, Runnable action) {
        MenuItem item = new MenuItem(label);
        item.setDisable(!enabled);
        item.setOnAction(e -> action.run());
        return item;
    }

    /** Ctrl+C: side by side, the selected lines of the side last clicked; unified, the lines as shown. */
    private void copySelection() {
        List<Integer> selected = selectedIndices();
        if (selected.isEmpty()) {
            return;
        }
        if (currentMode == Mode.SIDE_BY_SIDE) {
            copyToClipboard(selectedText(newSideClicked));
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i : selected) {
            DiffModel.Line line = lines.get(i);
            String marker = switch (line.kind()) {
                case ADDED -> "+";
                case REMOVED -> "-";
                case CONTEXT -> " ";
                default -> "";
            };
            sb.append(marker).append(line.text()).append('\n');
        }
        copyToClipboard(sb.toString());
    }

    /** Selected indices of the current mode's list, in display order. */
    private List<Integer> selectedIndices() {
        Control control = currentMode == Mode.SIDE_BY_SIDE ? sideBySide : unified;
        List<Integer> selected = new ArrayList<>();
        if (control instanceof ListView<?> lv) {
            selected.addAll(lv.getSelectionModel().getSelectedIndices());
        } else if (control instanceof TableView<?> tv) {
            selected.addAll(tv.getSelectionModel().getSelectedIndices());
        }
        selected.removeIf(i -> i == null || i < 0);
        selected.sort(null);
        return selected;
    }

    /** The code of one side in the selected lines or rows. */
    private String selectedText(boolean newSide) {
        List<DiffModel.Line> selected = new ArrayList<>();
        for (int i : selectedIndices()) {
            if (currentMode == Mode.SIDE_BY_SIDE) {
                DiffModel.Row row = rows.get(i);
                selected.add(newSide ? row.rightLine() : row.leftLine());
            } else {
                selected.add(lines.get(i));
            }
        }
        return DiffModel.sideText(selected, newSide);
    }

    private void copyFile(boolean newSide) {
        String content = fileText(newSide);
        if (content != null) {
            copyToClipboard(content);
        }
    }

    /** The whole old or new file, or null (after showing an error) when it cannot be restored. */
    private String fileText(boolean newSide) {
        PackedText packed = newSide ? sources.newText() : sources.oldText();
        if (packed == null) {
            return null;
        }
        try {
            return packed.text();
        } catch (RuntimeException e) {
            Dialogs.error(getScene().getWindow(), "Could not restore the " + (newSide ? "new" : "old") + " file", e);
            return null;
        }
    }

    private void saveDiff() {
        save("Save diff", suggestedFileName,
                List.of(new FileChooser.ExtensionFilter("Diff files", "*.diff", "*.patch")), () -> model.text());
    }

    private void saveFile(boolean newSide) {
        String name = sources.fileName();
        int dot = name.lastIndexOf('.');
        List<FileChooser.ExtensionFilter> filters = new ArrayList<>();
        if (dot > 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1);
            filters.add(new FileChooser.ExtensionFilter(ext.toUpperCase(Locale.ROOT) + " files", "*." + ext));
        }
        filters.add(new FileChooser.ExtensionFilter("All files", "*.*"));
        save("Save " + (newSide ? "new" : "old") + " version of " + name, name, filters, () -> fileText(newSide));
    }

    private void save(String title, String fileName, List<FileChooser.ExtensionFilter> filters,
                      Supplier<String> content) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.setInitialFileName(fileName);
        chooser.getExtensionFilters().addAll(filters);
        if (lastDirectory != null && lastDirectory.isDirectory()) {
            chooser.setInitialDirectory(lastDirectory);
        }
        File file = chooser.showSaveDialog(getScene().getWindow());
        if (file == null) {
            return;
        }
        lastDirectory = file.getParentFile();
        String s = content.get();
        if (s == null) {
            return;
        }
        try {
            Files.writeString(file.toPath(), s, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Dialogs.error(getScene().getWindow(), "Could not save " + file, e);
        }
    }
}
