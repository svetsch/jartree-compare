package io.jartree.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import io.jartree.bytecode.ClassAnalyzer;
import io.jartree.bytecode.MemberChange;
import io.jartree.compare.ChangeType;
import io.jartree.compare.ClassChange;
import io.jartree.compare.LibraryDiff;
import io.jartree.compare.LibraryStatus;
import io.jartree.compare.ResourceChange;
import io.jartree.compare.TextSupport;
import io.jartree.gui.Item.ClassItem;
import io.jartree.gui.Item.GroupItem;
import io.jartree.gui.Item.LibraryItem;
import io.jartree.gui.Item.MemberItem;
import io.jartree.gui.Item.ResourceItem;
import io.jartree.report.ConsoleReport;

/**
 * Tree of libraries with their changed classes (and the changed members of each class) and resources, filtered
 * by status, text and noise, and sortable by every column.
 */
final class ResultTree extends TreeTableView<Item> {

    private final TreeItem<Item> root = new TreeItem<>();
    private final TreeTableColumn<Item, Item> changesColumn;
    private List<LibraryDiff> libraries = List.of();
    private final Set<LibraryDiff> expanded = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<TreeItem<Item>, Integer> naturalOrder = new IdentityHashMap<>();
    private Set<LibraryStatus> statuses = EnumSet.complementOf(EnumSet.of(LibraryStatus.UNCHANGED));
    private String query = "";
    private boolean showNoise;

    ResultTree() {
        setRoot(root);
        setShowRoot(false);
        getStyleClass().add("result-tree");
        setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        setPlaceholder(new Label("Choose the old and new directories (or archives) and press Compare,\n"
                + "or open a JSON report with File ▸ Open report."));

        TreeTableColumn<Item, Item> name = column("Library / entry", 380);
        name.setCellFactory(c -> new NameCell());
        name.setComparator(Comparator.comparing(ResultTree::nameText, String.CASE_INSENSITIVE_ORDER));
        TreeTableColumn<Item, Item> status = column("Status", 100);
        status.setCellFactory(c -> new TextCell(ResultTree::statusText, ResultTree::statusStyle));
        status.setComparator(Comparator.comparingInt(ResultTree::statusRank).thenComparing(ResultTree::statusText));
        TreeTableColumn<Item, Item> version = column("Version", 110);
        version.setCellFactory(c -> new TextCell(ResultTree::versionText, i -> null));
        version.setComparator(Comparator.comparing(ResultTree::versionText));
        TreeTableColumn<Item, Item> lines = column("Lines", 110);
        lines.setCellFactory(c -> new LinesCell());
        // most modified lines first on the first click
        lines.setComparator(Comparator.comparingLong(ResultTree::lineTotal).reversed());
        changesColumn = column("Changes", 120);
        changesColumn.setCellFactory(c -> new TextCell(ResultTree::changesText, i -> "changes-cell"));
        // most changes first on the first click
        changesColumn.setComparator(Comparator.comparingLong(ResultTree::changeWeight).reversed());
        getColumns().setAll(List.of(name, status, version, lines, changesColumn));
        setTreeColumn(name);

        setSortPolicy(table -> {
            Item selected = selectedItem();
            // clear before moving rows: clearing a selection whose rows were moved or removed fails in JavaFX
            getSelectionModel().clearSelection();
            sortChildren(root, getSortOrder().isEmpty() ? null : getComparator());
            if (selected != null) {
                select(i -> sameItem(i, selected));
            }
            return true;
        });
    }

    private static TreeTableColumn<Item, Item> column(String title, double width) {
        TreeTableColumn<Item, Item> col = new TreeTableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().getValue()));
        return col;
    }

    /** Sorts every level except the fixed "Classes" / "Resources" folders; without a comparator the original order is restored. */
    private void sortChildren(TreeItem<Item> parent, Comparator<TreeItem<Item>> comparator) {
        List<TreeItem<Item>> children = new ArrayList<>(parent.getChildren());
        boolean folders = !children.isEmpty() && children.get(0).getValue() instanceof GroupItem;
        if (!folders && children.size() > 1) {
            children.sort(comparator != null ? comparator
                    : Comparator.comparingInt(t -> naturalOrder.getOrDefault(t, 0)));
            parent.getChildren().setAll(children);
        }
        for (TreeItem<Item> child : children) {
            sortChildren(child, comparator);
        }
    }

    /** Sorts by number of changes, largest first. */
    void sortByChanges() {
        changesColumn.setSortType(TreeTableColumn.SortType.ASCENDING);
        getSortOrder().setAll(List.of(changesColumn));
    }

    void setLibraries(List<LibraryDiff> libraries) {
        this.libraries = List.copyOf(libraries);
        getSelectionModel().clearSelection();
        root.getChildren().clear();
        expanded.clear();
        refresh(null);
    }

    void setFilter(Set<LibraryStatus> statuses, String query, boolean showNoise) {
        this.statuses = statuses.isEmpty() ? EnumSet.noneOf(LibraryStatus.class) : EnumSet.copyOf(statuses);
        this.query = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        this.showNoise = showNoise;
        refresh(selectedItem());
    }

    Item selectedItem() {
        TreeItem<Item> sel = getSelectionModel().getSelectedItem();
        return sel == null ? null : sel.getValue();
    }

    /** Libraries currently shown (respecting all filters). */
    List<LibraryDiff> visibleLibraries() {
        return root.getChildren().stream().map(t -> t.getValue().library()).toList();
    }

    void expandAll(boolean expand) {
        for (TreeItem<Item> lib : root.getChildren()) {
            setExpandedRecursively(lib, expand);
        }
    }

    private void setExpandedRecursively(TreeItem<Item> item, boolean expand) {
        item.setExpanded(expand);
        item.getChildren().forEach(c -> setExpandedRecursively(c, expand));
    }

    /** Selects the first visible item matching the predicate, expanding its parents. */
    boolean select(Predicate<Item> predicate) {
        TreeItem<Item> found = find(root, predicate);
        if (found == null) {
            return false;
        }
        reveal(found);
        return true;
    }

    private void reveal(TreeItem<Item> item) {
        for (TreeItem<Item> p = item.getParent(); p != null; p = p.getParent()) {
            p.setExpanded(true);
        }
        getSelectionModel().select(item);
        int row = getRow(item);
        if (row >= 0) {
            scrollTo(Math.max(0, row - 3));
        }
    }

    /**
     * Moves the selection to the next (or previous) class with a significant change, in display order and
     * across libraries.
     */
    boolean selectNextClass(boolean forward) {
        List<TreeItem<Item>> order = new ArrayList<>();
        flatten(root, order);
        TreeItem<Item> current = getSelectionModel().getSelectedItem();
        // a selected member counts as its class
        if (current != null && current.getValue() instanceof MemberItem) {
            current = current.getParent();
        }
        int index = current == null ? -1 : order.indexOf(current);
        int step = forward ? 1 : -1;
        for (int i = index + step; i >= 0 && i < order.size(); i += step) {
            if (order.get(i).getValue() instanceof ClassItem c && c.change().isSignificant()) {
                reveal(order.get(i));
                return true;
            }
        }
        return false;
    }

    private static void flatten(TreeItem<Item> item, List<TreeItem<Item>> out) {
        for (TreeItem<Item> c : item.getChildren()) {
            out.add(c);
            flatten(c, out);
        }
    }

    private static TreeItem<Item> find(TreeItem<Item> item, Predicate<Item> predicate) {
        if (item.getValue() != null && predicate.test(item.getValue())) {
            return item;
        }
        for (TreeItem<Item> c : item.getChildren()) {
            TreeItem<Item> f = find(c, predicate);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    private void refresh(Item reselect) {
        // remember expansion of the current tree
        for (TreeItem<Item> t : root.getChildren()) {
            if (t.isExpanded()) {
                expanded.add(t.getValue().library());
            } else {
                expanded.remove(t.getValue().library());
            }
        }
        naturalOrder.clear();
        List<TreeItem<Item>> items = new ArrayList<>();
        for (LibraryDiff lib : libraries) {
            if (!statuses.contains(lib.status())) {
                continue;
            }
            boolean libraryMatches = query.isEmpty() || matches(lib.primary().path())
                    || lib.oldLib() != null && matches(lib.oldLib().path());
            List<ClassChange> classes = lib.classes().stream()
                    .filter(c -> showNoise || c.isSignificant())
                    .filter(c -> libraryMatches || matches(c.displayName())
                            || c.members().stream().anyMatch(m -> matches(m.label())))
                    .toList();
            List<ResourceChange> resources = lib.resources().stream()
                    .filter(r -> showNoise || !r.noise())
                    .filter(r -> libraryMatches || matches(r.path()))
                    .toList();
            if (!libraryMatches && classes.isEmpty() && resources.isEmpty()) {
                continue;
            }
            TreeItem<Item> libItem = node(new LibraryItem(lib), items.size());
            if (!classes.isEmpty()) {
                TreeItem<Item> group = node(new GroupItem(lib, "Classes", classes.size()), 0);
                for (ClassChange c : classes) {
                    TreeItem<Item> classItem = node(new ClassItem(lib, c), group.getChildren().size());
                    for (MemberChange m : c.members()) {
                        classItem.getChildren().add(node(new MemberItem(lib, c, m), classItem.getChildren().size()));
                    }
                    group.getChildren().add(classItem);
                }
                group.setExpanded(true);
                libItem.getChildren().add(group);
            }
            if (!resources.isEmpty()) {
                TreeItem<Item> group = node(new GroupItem(lib, "Resources", resources.size()), 1);
                resources.forEach(r -> group.getChildren().add(node(new ResourceItem(lib, r), group.getChildren().size())));
                group.setExpanded(true);
                libItem.getChildren().add(group);
            }
            libItem.setExpanded(expanded.contains(lib) || !query.isEmpty() && !libraryMatches);
            items.add(libItem);
        }
        // clear before replacing rows: clearing a selection whose rows were removed fails inside JavaFX
        getSelectionModel().clearSelection();
        root.getChildren().setAll(items);
        if (!getSortOrder().isEmpty()) {
            sortChildren(root, getComparator());
        }
        if (reselect != null) {
            select(i -> sameItem(i, reselect));
        }
    }

    private TreeItem<Item> node(Item item, int order) {
        TreeItem<Item> t = new TreeItem<>(item);
        naturalOrder.put(t, order);
        return t;
    }

    private static boolean sameItem(Item a, Item b) {
        if (a.getClass() != b.getClass() || a.library() != b.library()) {
            return false;
        }
        if (a instanceof ClassItem ca) {
            return ca.change() == ((ClassItem) b).change();
        }
        if (a instanceof MemberItem ma) {
            return ma.change() == ((MemberItem) b).change() && ma.member().equals(((MemberItem) b).member());
        }
        if (a instanceof ResourceItem ra) {
            return ra.change() == ((ResourceItem) b).change();
        }
        if (a instanceof GroupItem ga) {
            return ga.title().equals(((GroupItem) b).title());
        }
        return true;
    }

    private boolean matches(String s) {
        return s.toLowerCase(Locale.ROOT).contains(query);
    }

    // ------------------------------------------------------------------ cell content

    static String nameText(Item item) {
        if (item instanceof LibraryItem li) {
            LibraryDiff lib = li.library();
            return lib.pathChanged() ? lib.oldLib().path() + "  →  " + lib.newLib().path() : lib.primary().path();
        }
        if (item instanceof GroupItem g) {
            return g.title() + " (" + g.count() + ")";
        }
        if (item instanceof ClassItem c) {
            return c.change().displayName();
        }
        if (item instanceof MemberItem m) {
            return m.member().label();
        }
        return ((ResourceItem) item).change().path();
    }

    static String statusText(Item item) {
        if (item instanceof LibraryItem li) {
            return li.library().status().name().toLowerCase(Locale.ROOT);
        }
        if (item instanceof ClassItem c) {
            ClassChange cc = c.change();
            String s = switch (cc.nature()) {
                case SOURCE -> "modified";
                case DEBUG_INFO_ONLY -> "debug info";
                case BYTECODE_ONLY -> "same source";
                case NOT_DECOMPILED -> "bytecode";
                case PRESENCE -> cc.type() == ChangeType.ADDED ? "added" : "removed";
            };
            return cc.decompileProblem() != null ? s + " ⚠" : s;
        }
        if (item instanceof MemberItem m) {
            return m.member().change() == MemberChange.Change.MODIFIED ? m.member().detail()
                    : m.member().change().name().toLowerCase(Locale.ROOT);
        }
        if (item instanceof ResourceItem r) {
            ResourceChange rc = r.change();
            String s = rc.type().name().toLowerCase(Locale.ROOT);
            return rc.noise() ? s + " (noise)" : s;
        }
        return "";
    }

    private static int statusRank(Item item) {
        if (item instanceof LibraryItem li) {
            return switch (li.library().status()) {
                case ERROR -> 0;
                case CHANGED -> 1;
                case ADDED -> 2;
                case REMOVED -> 3;
                case REBUILT -> 4;
                case UNCHANGED -> 5;
            };
        }
        if (item instanceof ClassItem c) {
            return c.change().nature().ordinal();
        }
        if (item instanceof MemberItem m) {
            return m.member().change().ordinal();
        }
        if (item instanceof ResourceItem r) {
            return r.change().noise() ? 9 : r.change().type().ordinal();
        }
        return 0;
    }

    static String statusStyle(Item item) {
        if (item instanceof LibraryItem li) {
            return "status-" + li.library().status().name().toLowerCase(Locale.ROOT);
        }
        if (item instanceof ClassItem c) {
            return c.change().isSignificant() ? "change-" + c.change().type().name().toLowerCase(Locale.ROOT) : "noise";
        }
        if (item instanceof MemberItem m) {
            return "change-" + m.member().change().name().toLowerCase(Locale.ROOT);
        }
        if (item instanceof ResourceItem r) {
            return r.change().noise() ? "noise" : "change-" + r.change().type().name().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    static String versionText(Item item) {
        if (item instanceof LibraryItem li) {
            return li.library().versionLabel().replace("->", "→");
        }
        if (item instanceof ClassItem c) {
            ClassChange cc = c.change();
            if (cc.oldMajor() != null && cc.newMajor() != null && !cc.oldMajor().equals(cc.newMajor())) {
                return ClassAnalyzer.javaRelease(cc.oldMajor()) + " → " + ClassAnalyzer.javaRelease(cc.newMajor());
            }
        }
        return "";
    }

    static String changesText(Item item) {
        if (item instanceof LibraryItem li) {
            LibraryDiff lib = li.library();
            if (lib.status() != LibraryStatus.CHANGED && lib.status() != LibraryStatus.REBUILT) {
                return "";
            }
            long significant = lib.classes().stream().filter(ClassChange::isSignificant).count();
            long resources = lib.resources().stream().filter(r -> !r.noise()).count();
            return significant + " cls · " + resources + " res";
        }
        if (item instanceof ClassItem c) {
            int members = c.change().members().size();
            return members == 0 ? "" : members + (members == 1 ? " member" : " members");
        }
        if (item instanceof MemberItem m) {
            MemberChange mc = m.member();
            Integer from = mc.newLine() != null ? mc.newLine() : mc.oldLine();
            return from == null ? "" : "line " + from;
        }
        if (item instanceof ResourceItem r) {
            ResourceChange rc = r.change();
            return rc.text() ? "" : ConsoleReport.size(rc.oldSize()) + " → " + ConsoleReport.size(rc.newSize());
        }
        return "";
    }

    /** Parsed source diffs of classes, to count the changed lines of each member. */
    private static final Map<ClassChange, List<DiffModel.Line>> PARSED =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Lines added and removed: for a library the sum over its (significant) classes and resources, for a class its
     * decompiled source diff (bytecode diff if it was not decompiled), for a member the changed lines inside the
     * member, for a resource its text diff. Null when there is nothing to count.
     */
    static int[] lines(Item item) {
        if (item instanceof LibraryItem li) {
            LibraryDiff lib = li.library();
            if (lib.status() != LibraryStatus.CHANGED && lib.status() != LibraryStatus.REBUILT) {
                return null;
            }
            int[] sum = new int[2];
            for (ClassChange c : lib.classes()) {
                if (c.isSignificant()) {
                    TextSupport.DiffText d = classDiff(c);
                    sum[0] += d.added();
                    sum[1] += d.removed();
                }
            }
            for (ResourceChange r : lib.resources()) {
                if (!r.noise()) {
                    sum[0] += r.diff().added();
                    sum[1] += r.diff().removed();
                }
            }
            return sum;
        }
        if (item instanceof ClassItem c) {
            TextSupport.DiffText d = classDiff(c.change());
            return d.isEmpty() ? null : new int[] {d.added(), d.removed()};
        }
        if (item instanceof MemberItem m) {
            return memberLines(m.change(), m.member());
        }
        if (item instanceof ResourceItem r) {
            TextSupport.DiffText d = r.change().diff();
            return d.isEmpty() ? null : new int[] {d.added(), d.removed()};
        }
        return null;
    }

    private static TextSupport.DiffText classDiff(ClassChange c) {
        return c.sourceDiff().isEmpty() ? c.bytecodeDiff() : c.sourceDiff();
    }

    private static int[] memberLines(ClassChange c, MemberChange m) {
        if (!m.located() || c.sourceDiff().isEmpty()) {
            return null;
        }
        List<DiffModel.Line> parsed = PARSED.computeIfAbsent(c, k -> DiffModel.parse(k.sourceDiff().text()));
        int added = 0;
        int removed = 0;
        for (DiffModel.Line line : parsed) {
            if (line.kind() == DiffModel.Kind.ADDED && within(line.newNo(), m.newLine(), m.newEnd())) {
                added++;
            } else if (line.kind() == DiffModel.Kind.REMOVED && within(line.oldNo(), m.oldLine(), m.oldEnd())) {
                removed++;
            }
        }
        return new int[] {added, removed};
    }

    private static boolean within(Integer no, Integer from, Integer to) {
        return no != null && from != null && no >= from && no <= (to == null ? from : to);
    }

    private static long lineTotal(Item item) {
        int[] l = lines(item);
        return l == null ? -1 : (long) l[0] + l[1];
    }

    /** "+12 −3" with the two numbers colored. */
    private static final class LinesCell extends TreeTableCell<Item, Item> {
        private final Label added = new Label();
        private final Label removed = new Label();
        private final HBox box = new HBox(6, added, removed);

        LinesCell() {
            added.getStyleClass().add("lines-added");
            removed.getStyleClass().add("lines-removed");
            box.setAlignment(Pos.CENTER_RIGHT);
        }

        @Override
        protected void updateItem(Item item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            int[] l = empty || item == null ? null : lines(item);
            if (l == null) {
                setGraphic(null);
                return;
            }
            added.setText("+" + l[0]);
            removed.setText("−" + l[1]);
            setGraphic(box);
        }
    }

    /** Size of a change, used to sort by the Changes column. */
    static long changeWeight(Item item) {
        if (item instanceof LibraryItem li) {
            LibraryDiff lib = li.library();
            long lines = 0;
            for (ClassChange c : lib.classes()) {
                if (c.isSignificant()) {
                    lines += 1_000 + c.sourceDiff().added() + c.sourceDiff().removed();
                }
            }
            for (ResourceChange r : lib.resources()) {
                if (!r.noise()) {
                    lines += 1_000 + r.diff().added() + r.diff().removed();
                }
            }
            return lines;
        }
        if (item instanceof ClassItem c) {
            ClassChange cc = c.change();
            long lines = cc.sourceDiff().added() + cc.sourceDiff().removed();
            if (lines == 0) {
                lines = cc.bytecodeDiff().added() + cc.bytecodeDiff().removed();
            }
            return lines * 100 + cc.members().size() + (cc.isSignificant() ? 0 : -1_000_000_000L);
        }
        if (item instanceof MemberItem m) {
            MemberChange mc = m.member();
            if (mc.newLine() != null && mc.newEnd() != null) {
                return mc.newEnd() - mc.newLine() + 1;
            }
            return mc.oldLine() != null && mc.oldEnd() != null ? mc.oldEnd() - mc.oldLine() + 1 : 0;
        }
        if (item instanceof ResourceItem r) {
            ResourceChange rc = r.change();
            return rc.text() ? rc.diff().added() + rc.diff().removed() : Math.abs(rc.newSize() - rc.oldSize());
        }
        return 0;
    }

    private static final class NameCell extends TreeTableCell<Item, Item> {
        private final Region dot = new Region();

        NameCell() {
            dot.getStyleClass().add("dot");
        }

        @Override
        protected void updateItem(Item item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("group-cell", "library-cell", "member-cell");
            dot.getStyleClass().setAll("dot");
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(nameText(item));
            if (item instanceof GroupItem) {
                getStyleClass().add("group-cell");
                setGraphic(null);
                return;
            }
            if (item instanceof LibraryItem) {
                getStyleClass().add("library-cell");
            }
            if (item instanceof MemberItem) {
                getStyleClass().add("member-cell");
                dot.getStyleClass().add("small");
            }
            String style = statusStyle(item);
            if (style != null) {
                dot.getStyleClass().add(style);
            }
            setGraphic(dot);
        }
    }

    private static final class TextCell extends TreeTableCell<Item, Item> {
        private final Function<Item, String> text;
        private final Function<Item, String> style;
        private String applied;

        TextCell(Function<Item, String> text, Function<Item, String> style) {
            this.text = text;
            this.style = style;
        }

        @Override
        protected void updateItem(Item item, boolean empty) {
            super.updateItem(item, empty);
            if (applied != null) {
                getStyleClass().remove(applied);
                applied = null;
            }
            if (empty || item == null) {
                setText(null);
                return;
            }
            setText(text.apply(item));
            applied = style.apply(item);
            if (applied != null) {
                getStyleClass().add(applied);
            }
        }
    }
}
