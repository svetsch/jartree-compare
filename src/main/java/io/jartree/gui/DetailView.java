package io.jartree.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.beans.property.ObjectProperty;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import io.jartree.bytecode.ClassAnalyzer;
import io.jartree.bytecode.MemberChange;
import io.jartree.compare.ChangeType;
import io.jartree.compare.ClassChange;
import io.jartree.compare.LibraryDiff;
import io.jartree.compare.LibraryStatus;
import io.jartree.compare.ResourceChange;
import io.jartree.gui.Item.ClassItem;
import io.jartree.gui.Item.GroupItem;
import io.jartree.gui.Item.LibraryItem;
import io.jartree.gui.Item.MemberItem;
import io.jartree.gui.Item.ResourceItem;
import io.jartree.report.ConsoleReport;
import io.jartree.scan.LibraryRef;

/** Right-hand pane: details of the selected library, class or resource. */
final class DetailView extends StackPane {

    private final ObjectProperty<DiffView.Mode> diffMode;

    DetailView(ObjectProperty<DiffView.Mode> diffMode) {
        this.diffMode = diffMode;
        getStyleClass().add("detail-view");
        show(null);
    }

    void show(Item item) {
        Node content;
        if (item == null) {
            Label hint = new Label("Select a library, class or resource to see its changes.");
            hint.getStyleClass().add("placeholder");
            content = hint;
        } else if (item instanceof ClassItem c) {
            content = classDetail(c.library(), c.change(), null);
        } else if (item instanceof MemberItem m) {
            content = classDetail(m.library(), m.change(), m.member());
        } else if (item instanceof ResourceItem r) {
            content = resourceDetail(r.library(), r.change());
        } else if (item instanceof LibraryItem l) {
            content = libraryDetail(l.library());
        } else {
            content = libraryDetail(((GroupItem) item).library());
        }
        getChildren().setAll(content);
    }

    // ------------------------------------------------------------------ library

    private Node libraryDetail(LibraryDiff lib) {
        VBox box = new VBox(10);
        box.setPadding(new Insets(14));
        box.getChildren().add(header(lib.status().name().toLowerCase(Locale.ROOT),
                "status-" + lib.status().name().toLowerCase(Locale.ROOT), lib.primary().path()));
        if (lib.pathChanged()) {
            box.getChildren().add(muted("was " + lib.oldLib().path()));
        }

        GridPane grid = new GridPane();
        grid.getStyleClass().add("kv-grid");
        grid.setHgap(18);
        grid.setVgap(4);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints values = new ColumnConstraints();
        values.setPercentWidth(42);
        values.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, values, values);
        grid.addRow(0, muted(""), bold("Old"), bold("New"));
        int row = 1;
        row = kv(grid, row, "Path", lib, LibraryRef::path);
        row = kv(grid, row, "Version", lib, r -> r.version() == null ? "–" : r.version());
        row = kv(grid, row, "Maven", lib, r -> r.mavenGa() == null ? "–" : r.mavenGa() + ":" + r.mavenVersion());
        row = kv(grid, row, "Kind", lib, r -> r.kind().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        row = kv(grid, row, "Size", lib, r -> ConsoleReport.size(r.size()));
        row = kv(grid, row, "Entries / classes", lib, r -> r.entryCount() + " / " + r.classCount());
        kv(grid, row, "SHA-256", lib, r -> r.sha256().substring(0, 16) + "…");
        box.getChildren().add(grid);

        if (lib.oldLib() != null && lib.newLib() != null) {
            box.getChildren().add(muted("Matched by " + lib.matchedBy().name().toLowerCase(Locale.ROOT).replace('_', ' ')));
        }
        if (lib.error() != null) {
            box.getChildren().add(error(lib.error()));
        }
        if ((lib.status() == LibraryStatus.CHANGED || lib.status() == LibraryStatus.REBUILT) && lib.error() == null) {
            Label summary = new Label(ConsoleReport.summary(lib));
            summary.setWrapText(true);
            box.getChildren().add(summary);
        }
        lib.notes().forEach(n -> box.getChildren().add(muted(n)));
        String explanation = switch (lib.status()) {
            case REBUILT -> "The archive differs, but decompiled code and resources are equivalent "
                    + "(timestamps, build metadata, debug information or compiler differences). "
                    + "Enable “show build noise” to list those entries.";
            case UNCHANGED -> "Both libraries are byte-identical.";
            case ADDED -> "This library only exists in the new tree.";
            case REMOVED -> "This library only exists in the old tree.";
            default -> null;
        };
        if (explanation != null) {
            Label l = new Label(explanation);
            l.setWrapText(true);
            l.getStyleClass().add("explanation");
            box.getChildren().add(l);
        }
        return scroll(box);
    }

    private static int kv(GridPane grid, int row, String label, LibraryDiff lib, Function<LibraryRef, String> f) {
        Label name = muted(label);
        name.setWrapText(false);
        grid.addRow(row, name, value(lib.oldLib(), f), value(lib.newLib(), f));
        return row + 1;
    }

    private static Label value(LibraryRef ref, Function<LibraryRef, String> f) {
        if (ref == null) {
            return selectable("–");
        }
        Label l = selectable(f.apply(ref));
        // the full SHA-256 is available as tooltip and via the copy menu
        String full = f.apply(ref).endsWith("…") ? ref.sha256() : l.getText();
        l.setTooltip(new Tooltip(full));
        l.getContextMenu().getItems().get(0).setOnAction(e -> DiffView.copyToClipboard(full));
        return l;
    }

    // ------------------------------------------------------------------ class

    private Node classDetail(LibraryDiff lib, ClassChange c, MemberChange focus) {
        VBox top = new VBox(8);
        top.setPadding(new Insets(14, 14, 6, 14));
        String type = c.type().name().toLowerCase(Locale.ROOT);
        top.getChildren().add(header(type, "change-" + type, c.displayName()));
        top.getChildren().add(muted(lib.primary().path()));

        FlowPane tags = new FlowPane(6, 6);
        switch (c.nature()) {
            case DEBUG_INFO_ONLY -> tags.getChildren().add(tag("debug info only", false));
            case BYTECODE_ONLY -> tags.getChildren().add(tag("bytecode only – same decompiled source", false));
            case NOT_DECOMPILED -> tags.getChildren().add(tag("not decompiled", false));
            default -> {
            }
        }
        if (c.oldMajor() != null && c.newMajor() != null && !c.oldMajor().equals(c.newMajor())) {
            tags.getChildren().add(tag(ClassAnalyzer.javaRelease(c.oldMajor()) + " → "
                    + ClassAnalyzer.javaRelease(c.newMajor()), true));
        }
        if (c.decompileProblem() != null) {
            tags.getChildren().add(tag("decompilation incomplete", true));
        }
        tags.getChildren().add(tag(c.changedFiles().size() + " class file(s)", false));
        top.getChildren().add(tags);
        if (c.decompileProblem() != null) {
            top.getChildren().add(error(c.decompileProblem()));
        }

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        String base = c.internalName().substring(c.internalName().lastIndexOf('/') + 1);
        DiffView sourceView = new DiffView(c.sourceDiff(), base + ".java.diff", emptySourceMessage(c), diffMode);
        Tab source = new Tab("Decompiled source", sourceView);
        Tab api = new Tab("API (" + (c.api().added().size() + c.api().removed().size() + c.api().changed().size()) + ")",
                apiView(c));
        Tab bytecode = new Tab("Bytecode", new DiffView(c.bytecodeDiff(), base + ".class.diff",
                "No bytecode diff was produced for this class (enable “Always include bytecode diff”).", diffMode));
        Tab files = new Tab("Files", filesView(c));
        tabs.getTabs().addAll(source, api, bytecode, files);
        if (c.sourceDiff().isEmpty() && !c.bytecodeDiff().isEmpty()) {
            tabs.getSelectionModel().select(bytecode);
        } else if (c.sourceDiff().isEmpty() && !c.api().isEmpty()) {
            tabs.getSelectionModel().select(api);
        }
        if (!c.members().isEmpty()) {
            top.getChildren().add(membersView(c, focus, m -> {
                tabs.getSelectionModel().select(source);
                sourceView.reveal(m);
            }));
        }
        if (focus != null && !sourceView.isEmpty()) {
            tabs.getSelectionModel().select(source);
            sourceView.reveal(focus);
        }

        VBox box = new VBox(top, tabs);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        return box;
    }

    /** Clickable list of the changed members; clicking one points at it in the source diff. */
    private static Node membersView(ClassChange c, MemberChange focus, Consumer<MemberChange> onPick) {
        VBox list = new VBox(1);
        list.getStyleClass().add("members-view");
        for (MemberChange m : c.members()) {
            String symbol = switch (m.change()) {
                case ADDED -> "+";
                case REMOVED -> "\u2212";
                case MODIFIED -> "~";
            };
            Label sym = new Label(symbol);
            sym.getStyleClass().addAll("member-symbol", "change-" + m.change().name().toLowerCase(Locale.ROOT));
            Label decl = new Label(m.label());
            decl.getStyleClass().add("mono");
            Label detail = new Label(m.change() == MemberChange.Change.MODIFIED ? m.detail() : "");
            detail.getStyleClass().add("muted");
            Integer line = m.newLine() != null ? m.newLine() : m.oldLine();
            Label where = new Label(line == null ? "" : "line " + line);
            where.getStyleClass().add("muted");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = new HBox(8, sym, decl, detail, spacer, where);
            row.getStyleClass().add("member-row");
            if (m.equals(focus)) {
                row.getStyleClass().add("member-row-focus");
            }
            row.setOnMouseClicked(e -> {
                list.getChildren().forEach(n -> n.getStyleClass().remove("member-row-focus"));
                row.getStyleClass().add("member-row-focus");
                onPick.accept(m);
            });
            Tooltip.install(row, new Tooltip("Show " + m.name() + " in the decompiled source"));
            list.getChildren().add(row);
        }
        ScrollPane sp = new ScrollPane(list);
        sp.setFitToWidth(true);
        sp.setMaxHeight(Math.min(150, 26 + 22.0 * c.members().size()));
        sp.setPrefViewportHeight(Math.min(128, 22.0 * c.members().size()));
        sp.getStyleClass().add("members-scroll");
        Label title = new Label("Changed members (" + c.members().size() + ") \u2013 click to show in source");
        title.getStyleClass().add("muted");
        return new VBox(4, title, sp);
    }

    private static String emptySourceMessage(ClassChange c) {
        return switch (c.nature()) {
            case DEBUG_INFO_ONLY -> "Only debug information (line numbers, local variable names) differs; "
                    + "the class was not decompiled.";
            case BYTECODE_ONLY -> "The decompiled source is identical. See the Bytecode tab for the difference.";
            case NOT_DECOMPILED -> "The class was not decompiled (decompilation disabled or limit reached).";
            case PRESENCE -> "The class was " + c.type().name().toLowerCase(Locale.ROOT)
                    + ". Enable “Also decompile added / removed classes” to see its source.";
            case SOURCE -> "No source difference.";
        };
    }

    private static Node apiView(ClassChange c) {
        VBox box = new VBox(3);
        box.setPadding(new Insets(10));
        box.getStyleClass().add("api-view");
        if (c.api().isEmpty()) {
            Label none = new Label(c.type() == ChangeType.MODIFIED
                    ? "No change in class declaration, fields or method signatures."
                    : "API deltas are computed for modified classes only.");
            none.getStyleClass().add("placeholder");
            box.getChildren().add(none);
        }
        c.api().removed().forEach(m -> box.getChildren().add(apiLine("− ", m, "api-removed")));
        c.api().added().forEach(m -> box.getChildren().add(apiLine("+ ", m, "api-added")));
        c.api().changed().forEach(m -> box.getChildren().add(apiLine("~ ", m, "api-changed")));
        return scroll(box);
    }

    private static Node apiLine(String marker, String text, String style) {
        Label l = selectable(marker + text);
        l.getStyleClass().addAll("mono", style);
        return l;
    }

    private static Node filesView(ClassChange c) {
        TextArea area = new TextArea(String.join("\n", c.changedFiles()));
        area.setEditable(false);
        area.getStyleClass().add("mono");
        return area;
    }

    // ------------------------------------------------------------------ resource

    private Node resourceDetail(LibraryDiff lib, ResourceChange r) {
        VBox top = new VBox(8);
        top.setPadding(new Insets(14, 14, 6, 14));
        String type = r.type().name().toLowerCase(Locale.ROOT);
        top.getChildren().add(header(type, r.noise() ? "noise" : "change-" + type, r.path()));
        top.getChildren().add(muted(lib.primary().path()));
        List<Node> tags = new ArrayList<>();
        tags.add(tag(r.text() ? "text" : "binary", false));
        tags.add(tag(ConsoleReport.size(r.oldSize()) + " → " + ConsoleReport.size(r.newSize()), false));
        if (r.noise()) {
            tags.add(tag("build noise", false));
        }
        top.getChildren().add(new FlowPane(6, 6, tags.toArray(Node[]::new)));

        String name = r.path().substring(r.path().lastIndexOf('/') + 1);
        Node body = r.text()
                ? new DiffView(r.diff(), name + ".diff", "No textual difference.", diffMode)
                : placeholder("Binary content differs; no diff is available.");
        VBox box = new VBox(top, body);
        VBox.setVgrow(body, Priority.ALWAYS);
        return box;
    }

    // ------------------------------------------------------------------ helpers

    private static Node header(String badge, String badgeStyle, String title) {
        Label b = new Label(badge);
        b.getStyleClass().addAll("badge", badgeStyle);
        Label t = selectable(title);
        t.getStyleClass().addAll("detail-title", "mono");
        t.setWrapText(true);
        HBox h = new HBox(10, b, t);
        h.setFillHeight(false);
        return h;
    }

    private static Label tag(String text, boolean warn) {
        Label l = new Label(text);
        l.getStyleClass().add(warn ? "tag-warn" : "tag");
        return l;
    }

    private static Label muted(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("muted");
        l.setWrapText(true);
        return l;
    }

    private static Label bold(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("bold");
        return l;
    }

    private static Label error(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("error-text");
        l.setWrapText(true);
        return l;
    }

    /** Label whose text can be copied via its context menu. */
    private static Label selectable(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(e -> DiffView.copyToClipboard(text));
        l.setContextMenu(new ContextMenu(copy));
        return l;
    }

    private static Node placeholder(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("placeholder");
        StackPane p = new StackPane(l);
        p.setPadding(new Insets(20));
        return p;
    }

    private static ScrollPane scroll(Node content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("edge-to-edge");
        return sp;
    }
}
