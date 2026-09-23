# jartree-compare

Compares two hierarchies of jar files, finds the libraries that changed, decompiles the changed classes and
shows what changed in the code — on the command line, as reports, or in a JavaFX desktop interface.

```bash
jartree-compare old-release/ new-release/ --html report.html
```

![The desktop interface: libraries, changed classes with their changed members, and the source diff](docs/images/gui-overview.png)

## Contents

- [What it does](#what-it-does)
- [Build](#build)
- [Command line](#command-line)
- [Desktop interface](#desktop-interface)
- [How it works](#how-it-works)
- [Cache](#cache)
- [Limits and timings](#limits-and-timings)
- [Reports](#reports)
- [Development](#development)
- [Troubleshooting](#troubleshooting)
- [License](#license)

## What it does

Given two directory trees (or two archives) containing jars, it answers three questions:

1. **Which libraries changed?** Libraries are paired even when their file name contains a different version,
   and a library whose bytes differ only because of a rebuild is reported as such.
2. **What changed inside a changed library?** Per class: the changed methods and fields, the API delta and a
   diff of the decompiled source. Per resource: a text diff or a size comparison.
3. **How much changed, and where?** Lines added and removed per library, class and member, plus where the
   comparison spent its time.

## Build

Requires JDK 17 or newer and Maven.

```bash
mvn package
```

This produces the self-contained `target/jartree-compare.jar` (command line and GUI). Launchers:

| Script | Purpose |
|---|---|
| `jartree-compare` / `jartree-compare.cmd` | Command line |
| `jartree-compare-gui.cmd` | Desktop interface |
| `mvn javafx:run` | Desktop interface from the sources |

`java -jar target/jartree-compare.jar --version` prints the version and the git commit the jar was built from.

## Command line

```
jartree-compare [options] OLD NEW
```

`OLD` and `NEW` are directories or archives.

| Option | Description |
|---|---|
| `-o, --html FILE` | Self-contained HTML report with filters and collapsible diffs |
| `--json FILE` | JSON report including all diffs (can be reopened in the GUI) |
| `--patch FILE` | All source and text diffs as one unified diff (`a/<lib>!/<class>.java`) |
| `-v, --verbose` | Print API changes and diffs to the console |
| `-q, --quiet` | Only print the summary line |
| `--show-noise` | Also list debug-info-only / bytecode-only classes and build noise |
| `--show-unchanged` | Also list unchanged libraries |
| `-i, --include GLOB` / `-x, --exclude GLOB` | Filter **libraries** by path or file name (`**/acme-*.jar`); a pattern that matches no library is reported |
| `-e, --ignore-entry GLOB` | Ignore **entries inside** libraries (`META-INF/MANIFEST.MF`, `META-INF/maven/**`, `*.SF`; without `/` the file name matches in any directory) |
| `-p, --package PREFIX` | Only analyze classes in these packages |
| `--no-decompile` | Skip decompilation, show bytecode diffs (faster) |
| `--decompile-added` | Also decompile added and removed classes |
| `--bytecode` | Always add bytecode diffs next to source diffs |
| `--max-classes N` | Decompile at most N classes per library (default 2000) |
| `--nested-depth N` | How deep to open nested archives (default 8) |
| `-U, --context N` | Diff context lines (default 3) |
| `--fail-on-change` | Exit code 1 when libraries were added, removed or changed (for CI) |
| `--no-cache` / `--cache-dir DIR` / `--clear-cache` | Control the cache (default `~/.jartree-compare/cache`) |
| `-t, --threads N` | Worker threads |
| `--gui [OLD NEW \| REPORT.json]` | Open the desktop interface |

Exit codes: `0` no differences (or `--fail-on-change` not set), `1` differences found with `--fail-on-change`,
`2` error.

Examples:

```bash
# two exploded distributions, only our own code, with a patch for code review
jartree-compare dist-1.4/ dist-1.5/ -i '**/acme-*.jar' -p com.acme --patch review.diff

# two wars, full detail on the console, ignoring manifests and Maven metadata
jartree-compare shop-2.0.war shop-2.1.war -v -e 'META-INF/MANIFEST.MF,META-INF/maven/**'

# CI gate: fail if anything but a rebuild happened
jartree-compare baseline/ build/libs/ -q --fail-on-change
```

Every run ends with a summary, the time breakdown and any limit that was reached:

```
Summary: 2 changed, 1 unchanged  (13393 ms)
Time: scan 214 ms | uncompress 90 ms | compare 907 ms | decompile 12.4 s | decompile wait 10.2 s | diff 569 ms | elapsed 13.4 s
Limit reached: 2 libraries reached the limit of 20 decompiled classes per library; 96 changed class(es) were
not decompiled. Use --max-classes 70 (or more) to decompile all of them.
```

## Desktop interface

```bash
jartree-compare-gui.cmd                                      # or: java -jar target/jartree-compare.jar
java -jar target/jartree-compare.jar --gui OLD NEW           # compare immediately
java -jar target/jartree-compare.jar --gui report.json       # open a saved report
```

**Run a comparison.** Choose the old and new tree (folder, archive, or drag and drop), adjust *Options* and
press **Compare** (F5). The comparison runs in the background with progress and can be cancelled.

**Browse the result.** The tree lists libraries, their changed classes, the changed members of each class and
the changed resources.

| Column | Shows |
|---|---|
| Library / entry | Path, class name or member declaration |
| Status | `changed`, `rebuilt`, `added`, `removed`, `error`; for classes `modified`, `debug info`, `same source`; for members what changed (`body`, `modifiers`, `constant`, `lambda`) |
| Version | Library version change, or a class file version change |
| Lines | `+added −removed` for the row (library totals, class diff, lines inside a member, resource diff) |
| Changes | Number of classes and resources, or members of a class |

Click a column header to sort; the first click on *Lines* or *Changes* puts the largest first. The status chips
filter by status, the search box (Ctrl+F) matches libraries, classes, members and resources, and *Show build
noise* reveals debug-info-only classes, manifest timestamps and signature files.

**Inspect a change.** The right pane shows library metadata, or for a class the decompiled source diff, its API
changes, the bytecode diff and the changed class files. The part that actually changed inside an edited line is
highlighted:

![In-line highlight of the changed part of a line](docs/images/gui-inline-highlight.png)

Diffs can be shown side by side:

![Side-by-side diff](docs/images/gui-side-by-side.png)

**Point at a change.** Clicking a member in the tree or in the *Changed members* list scrolls the source diff to
it and marks its lines. ▲ / ▼ (or N / P) step through the changes of a diff, F7 / Shift+F7 through the changed
classes of the whole result.

**Notices** below the filter bar report reached limits, patterns that matched nothing, and options that changed
since the displayed result:

![Notices for changed options, an unused pattern and a reached limit](docs/images/gui-notices.png)

**Manage the output.** *File ▸ Save report as JSON* (Ctrl+S) and *Open report* (Ctrl+O, or drop a `.json` on the
window) store and reopen results without re-running. *File ▸ Export* writes HTML, JSON or a patch, optionally
only the libraries the filter shows. *View ▸ Timings* (Ctrl+T) shows where the time went. *Help ▸ About* shows
the version and the git commit of the build.

Paths (with a drop-down of recent ones), options, diff mode and window layout are remembered between sessions.

## How it works

1. **Scan.** Both trees are walked. Archives (`jar, war, ear, rar, sar, aar, zip`) are libraries. Nested
   archives are opened recursively (`WEB-INF/lib/*.jar`, `BOOT-INF/lib/*.jar`, jars inside an ear or zip), and
   so are exploded `classes` directories. Either side can also be a single archive.
2. **Pair.** Libraries of the two trees are matched by, in order:
   - identical path;
   - same name ignoring versions: `lib/foo-1.2.jar` matches `lib/foo-1.3.jar`, and versioned parent
     directories such as `app-2.0.war!/WEB-INF/lib` are normalized too;
   - same Maven `groupId:artifactId` (from `META-INF/maven/**/pom.properties`);
   - identical content (moved or renamed files).
3. **Classify.** Identical SHA-256 → `UNCHANGED`. Otherwise entries are compared one by one; zip timestamps and
   entry order don't count.
   - **Classes** are grouped per top-level class, inner classes included.
     - Changed methods and fields are detected in the bytecode with ASM: a method whose body changed is listed
       even when its signature did not, and changes inside lambdas are attributed to the enclosing method.
     - If only debug info differs (line numbers, local variable names, frames), the class is marked
       `debug info only`.
     - Otherwise both versions are decompiled with [Vineflower](https://github.com/Vineflower/vineflower) and
       the sources are diffed. If the decompiled sources are identical, the class is marked `bytecode only` and
       you get a bytecode diff instead — as you do when decompilation fails.
   - **Resources:** text files get a unified diff, binaries a size comparison. Volatile manifest attributes
     (`Build-Time`, `Built-By`, `Bnd-LastModified`, …), `.properties` comment lines and signature files count
     as *build noise*.
   - A library whose bytes differ but which has only noise or debug-info/bytecode-only changes is `REBUILT`;
     anything else is `CHANGED`.
4. **Report.** Console output, plus optional HTML, JSON and unified-diff reports.

## Cache

Results are cached in `~/.jartree-compare/cache`:

- **Library results:** the complete diff of a library pair, keyed by both SHA-256 digests, both paths and the
  options that influence the result. Repeating a comparison is almost instant (17.7 s → 0.75 s in a test with
  commons-lang3 and spring-core).
- **Decompiled sources:** keyed by the class file bytes, so a library version already decompiled in an earlier
  comparison is not decompiled again.

Entries unused for 60 days are pruned. Clear the cache with *Tools ▸ Clear cache* or `--clear-cache`, and turn
it off with the *Use cache* option or `--no-cache`.

## Limits and timings

- When the class limit (`--max-classes`) or the nested archive depth (`--nested-depth`) is reached, the console,
  the HTML report and the GUI give the exact value needed, e.g. `Use --max-classes 70 (or more)`. For archives
  that were not opened, their own nesting is inspected, so the value covers every level.
- Every run reports where its time went: scan, uncompress, compare, decompile, waiting for the decompiler,
  source diffs and cache. Phases that run in parallel are summed over the worker threads, so they can add up to
  more than the elapsed time. *Decompile wait* is time libraries spent queued for the decompiler, which runs
  one library at a time. The JSON report contains the same numbers and the slowest libraries.

## Reports

| Report | Contents |
|---|---|
| HTML (`--html`) | Self-contained page: summary, status filters, search, collapsible colored diffs |
| JSON (`--json`) | Full result: libraries, classes, members, resources, diffs, limits, timings. Reopen it with `--gui report.json` or *File ▸ Open report* |
| Patch (`--patch`) | All decompiled source and text diffs as one unified diff, usable with `patch` or a diff viewer |

## Development

```bash
mvn test          # unit and integration tests
mvn package       # jar with tests
mvn javafx:run    # start the GUI from the sources
```

Source layout:

| Package | Responsibility |
|---|---|
| `io.jartree.scan` | Walking trees, reading archives, library metadata |
| `io.jartree.compare` | Pairing, entry comparison, member location, cache, limits, timings |
| `io.jartree.bytecode` | ASM based class analysis (API delta, member changes, bytecode text) |
| `io.jartree.decompile` | Vineflower integration (in-memory sources, no temporary files) |
| `io.jartree.report` | Console, HTML, JSON and patch output |
| `io.jartree.gui` | JavaFX interface |

The tests build their own jars by compiling small classes in memory
(`src/test/java/io/jartree/Fixtures.java`), so they need no fixtures on disk. `GuiSnapshot` (test sources)
renders the window to a PNG for visual checks:

```bash
mvn test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp "target/classes;target/test-classes;$(cat target/cp.txt)" io.jartree.gui.GuiSnapshot report.json out.png "com.acme.Foo"
```

The version and commit shown by `--version` and *Help ▸ About* come from `build.properties`, filled in by the
git-commit-id Maven plugin; a build without a git checkout reports `unknown`.

## Troubleshooting

| Symptom | Cause and remedy |
|---|---|
| `Unsupported JavaFX configuration: classes were loaded from 'unnamed module'` | The single jar loads JavaFX from the class path. Harmless, and the message is filtered out. |
| The GUI does not start on another operating system | The shaded jar contains the JavaFX binaries of the platform it was built on. Build it there, or use `mvn javafx:run`. |
| `OutOfMemoryError` on very large trees | Raise the heap: `JAVA_OPTS=-Xmx8g` (the launchers pass it on) or `java -Xmx8g -jar …`. |
| Many classes reported as changed with identical source | A different compiler or compiler version. They are marked `bytecode only` and do not count as source changes. |
| Excluding `META-INF/MANIFEST.MF` has no effect | `--exclude` filters libraries; use `--ignore-entry` for files inside libraries. |
| Stack traces wanted for errors | Set `JARTREE_DEBUG=1`. |

Decompiled output is not the original source: minor differences can come from the decompiler itself. Changes
are reported as source differences only when the decompiled text actually differs.

## License

[MIT](LICENSE) © 2026 Samuel Vetsch.

The tool uses [Vineflower](https://github.com/Vineflower/vineflower) (Apache-2.0),
[ASM](https://asm.ow2.io/) (BSD-3-Clause), [java-diff-utils](https://github.com/java-diff-utils/java-diff-utils)
(Apache-2.0), [picocli](https://picocli.info/) (Apache-2.0) and [JavaFX](https://openjfx.io/) (GPLv2 with
Classpath Exception); the shaded jar bundles them.
