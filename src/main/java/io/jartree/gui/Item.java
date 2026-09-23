package io.jartree.gui;

import io.jartree.bytecode.MemberChange;
import io.jartree.compare.ClassChange;
import io.jartree.compare.LibraryDiff;
import io.jartree.compare.ResourceChange;

/** A row of the result tree. */
sealed interface Item {

    LibraryDiff library();

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
