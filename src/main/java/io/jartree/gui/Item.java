package io.jartree.gui;

import io.jartree.bytecode.MemberChange;
import io.jartree.compare.ClassChange;
import io.jartree.compare.LibraryDiff;
import io.jartree.compare.ResourceChange;
import io.jartree.scan.LibraryRef;

/** A row of the result tree. */
sealed interface Item {

    LibraryDiff library();

    /**
     * File name of the deepest archive the row belongs to: the library itself, or for a nested library the
     * innermost archive ({@code nested-1.1.jar} for {@code app.war!/WEB-INF/lib/nested-1.1.jar}). A paired library
     * shows its new name (the old one is in the name and version columns); exploded directories are not archives
     * and show nothing.
     */
    default String archiveName() {
        LibraryRef ref = library().primary();
        if (ref.kind() == LibraryRef.Kind.DIRECTORY) {
            return "";
        }
        return lastName(ref.path());
    }

    /**
     * File name of the deepest file the row stands for: the archive of a library row, the class file of a class
     * ({@code Calc.class}), the class file a member is declared in ({@code Calc$Helper.class}; for an added or
     * removed nested class its own class file), or the resource file ({@code app.properties}). Folder rows have
     * none.
     */
    default String fileName() {
        if (this instanceof LibraryItem) {
            return lastName(library().primary().path());
        }
        if (this instanceof ClassItem c) {
            return lastName(c.change().internalName()) + ".class";
        }
        if (this instanceof MemberItem m) {
            MemberChange member = m.member();
            String nested = member.kind() == MemberChange.Kind.NESTED_CLASS
                    ? (member.owner().isEmpty() ? "" : member.owner() + "$") + member.name() : member.owner();
            return lastName(m.change().internalName()) + (nested.isEmpty() ? "" : "$" + nested) + ".class";
        }
        if (this instanceof ResourceItem r) {
            return lastName(r.change().path());
        }
        return "";
    }

    private static String lastName(String path) {
        return path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
    }

    /** The library itself. */
    record LibraryItem(LibraryDiff library) implements Item {
    }

    /** "Classes" or "Resources" folder below a library. */
    record GroupItem(LibraryDiff library, String title, int count) implements Item {
    }

    record ClassItem(LibraryDiff library, ClassChange change) implements Item {
    }

    /** A changed field, method, constructor or nested class below its class. */
    record MemberItem(LibraryDiff library, ClassChange change, MemberChange member) implements Item {
    }

    record ResourceItem(LibraryDiff library, ResourceChange change) implements Item {
    }
}
