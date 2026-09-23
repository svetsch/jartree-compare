package io.jartree;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

import io.jartree.compare.CompareOptions;
import io.jartree.compare.ComparisonResult;
import io.jartree.compare.JarTreeComparer;
import io.jartree.compare.ResultCache;
import io.jartree.gui.JarTreeGui;
import io.jartree.report.ConsoleReport;
import io.jartree.report.HtmlReport;
import io.jartree.report.JsonReport;
import io.jartree.report.PatchReport;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "jartree-compare", mixinStandardHelpOptions = true, versionProvider = Main.Version.class,
        sortOptions = false,
        description = {
                "Compares two hierarchies of jar files (directories or archives such as war/ear/zip). "
                        + "Libraries are paired by path, by name ignoring versions, by Maven coordinates or by "
                        + "content. Changed libraries are compared entry by entry; changed classes are decompiled "
                        + "with Vineflower and their source is diffed.",
                "",
                "Run without arguments, or with --gui [OLD NEW | REPORT.json], to open the graphical interface.",
                ""},
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
                "0:no differences (or --fail-on-change not set)",
                "1:differences found and --fail-on-change set",
                "2:error"})
public final class Main implements Callable<Integer> {

    /** Prints the version with the git commit this build was made from. */
    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {"jartree-compare " + BuildInfo.describe(), BuildInfo.REPOSITORY};
        }
    }

    @Parameters(index = "0", paramLabel = "OLD", description = "Old directory or archive.")
    Path oldRoot;

    @Parameters(index = "1", paramLabel = "NEW", description = "New directory or archive.")
    Path newRoot;

    @Option(names = {"-o", "--html"}, paramLabel = "FILE", description = "Write an HTML report.")
    Path html;

    @Option(names = "--json", paramLabel = "FILE", description = "Write a JSON report (includes diffs).")
    Path json;

    @Option(names = "--patch", paramLabel = "FILE",
            description = "Write all decompiled source and text resource diffs as one unified diff.")
    Path patch;

    @Option(names = {"-v", "--verbose"}, description = "Print API changes and diffs to the console.")
    boolean verbose;

    @Option(names = {"-q", "--quiet"}, description = "Print only the summary line and progress errors.")
    boolean quiet;

    @Option(names = "--show-unchanged", description = "List unchanged libraries too.")
    boolean showUnchanged;

    @Option(names = "--show-noise",
            description = "Show build noise: debug-info-only / bytecode-only class changes, manifest timestamps, signatures.")
    boolean showNoise;

    @Option(names = "--no-decompile", negatable = false,
            description = "Do not decompile; show bytecode diffs instead.")
    boolean noDecompile;

    @Option(names = "--decompile-added", description = "Also decompile added and removed classes.")
    boolean decompileAdded;

    @Option(names = "--bytecode", description = "Always include bytecode diffs next to source diffs.")
    boolean bytecode;

    @Option(names = {"-i", "--include"}, paramLabel = "GLOB", split = ",",
            description = "Only compare libraries whose path or file name matches (e.g. '**/mycompany-*.jar'). "
                    + "Applies to libraries, not to entries inside them (see --ignore-entry).")
    List<String> includes = new ArrayList<>();

    @Option(names = {"-x", "--exclude"}, paramLabel = "GLOB", split = ",",
            description = "Skip libraries whose path or file name matches (see --ignore-entry for entries).")
    List<String> excludes = new ArrayList<>();

    @Option(names = {"-e", "--ignore-entry"}, paramLabel = "GLOB", split = ",",
            description = "Ignore entries inside libraries whose path (or, without '/', file name) matches, "
                    + "e.g. META-INF/MANIFEST.MF, META-INF/maven/**, *.SF.")
    List<String> ignoredEntries = new ArrayList<>();

    @Option(names = {"-p", "--package"}, paramLabel = "PREFIX", split = ",",
            description = "Only analyze classes in these packages (e.g. com.mycompany).")
    List<String> packages = new ArrayList<>();

    @Option(names = "--extensions", paramLabel = "EXT", split = ",",
            description = "Archive extensions treated as libraries (default: ${DEFAULT-VALUE}).")
    List<String> extensions = new ArrayList<>(List.of("jar", "war", "ear", "rar", "sar", "aar", "zip"));

    @Option(names = "--class-dirs", paramLabel = "NAME", split = ",",
            description = "Directory names treated as exploded class libraries (default: ${DEFAULT-VALUE}).")
    List<String> classDirs = new ArrayList<>(List.of("classes"));

    @Option(names = "--nested-depth", paramLabel = "N",
            description = "How deep to open nested archives (default: ${DEFAULT-VALUE}).")
    int nestedDepth = 8;

    @Option(names = "--max-classes", paramLabel = "N",
            description = "Maximum classes decompiled per library (default: ${DEFAULT-VALUE}).")
    int maxClasses = 2000;

    @Option(names = {"-U", "--context"}, paramLabel = "N", description = "Diff context lines (default: ${DEFAULT-VALUE}).")
    int context = 3;

    @Option(names = "--max-diff-lines", paramLabel = "N",
            description = "Truncate each diff after N lines, 0 = unlimited (default: ${DEFAULT-VALUE}).")
    int maxDiffLines = 5000;

    @Option(names = {"-t", "--threads"}, paramLabel = "N", description = "Worker threads (default: number of CPUs).")
    int threads = Runtime.getRuntime().availableProcessors();

    @Option(names = "--method-timeout", paramLabel = "SECONDS",
            description = "Decompiler time limit per method (default: ${DEFAULT-VALUE}).")
    int methodTimeout = 30;

    @Option(names = "--color", paramLabel = "WHEN", description = "auto, always or never (default: ${DEFAULT-VALUE}).")
    String color = "auto";

    @Option(names = "--fail-on-change", description = "Exit with code 1 if differences were found.")
    boolean failOnChange;

    @Option(names = "--no-cache", description = "Do not use the result / decompiled-source cache.")
    boolean noCache;

    @Option(names = "--cache-dir", paramLabel = "DIR",
            description = "Cache directory (default: ~/.jartree-compare/cache).")
    Path cacheDir;

    @Option(names = "--clear-cache", description = "Empty the cache before comparing.")
    boolean clearCache;

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("--gui")) {
            JarTreeGui.launchGui(args.length == 0 ? args : Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        PrintStream utf8Out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        CommandLine cl = new CommandLine(new Main());
        cl.setOut(new PrintWriter(utf8Out, true));
        cl.setExecutionExceptionHandler((ex, cmd, parsed) -> {
            cmd.getErr().println("error: " + ex.getMessage());
            if (System.getenv("JARTREE_DEBUG") != null) {
                ex.printStackTrace(cmd.getErr());
            }
            return 2;
        });
        System.exit(cl.execute(args));
    }

    @Override
    public Integer call() throws Exception {
        for (Path p : List.of(oldRoot, newRoot)) {
            if (!Files.exists(p)) {
                System.err.println("error: " + p + " does not exist");
                return 2;
            }
        }
        Set<String> ext = new LinkedHashSet<>();
        extensions.forEach(e -> ext.add(e.replaceFirst("^\\.", "").toLowerCase(Locale.ROOT)));
        CompareOptions options = new CompareOptions(ext, Set.copyOf(classDirs), nestedDepth, includes, excludes,
                packages, !noDecompile, decompileAdded, bytecode, maxClasses, context, maxDiffLines,
                Math.max(1, threads), methodTimeout, ignoredEntries);

        ResultCache cache = noCache ? null : new ResultCache(cacheDir != null ? cacheDir : ResultCache.defaultDirectory());
        if (cache != null && clearCache) {
            cache.clear();
        }
        JarTreeComparer comparer = new JarTreeComparer(options, cache, msg -> {
            if (!quiet) {
                System.err.println(msg);
            }
        });
        ComparisonResult result = comparer.compare(oldRoot.toAbsolutePath().normalize(),
                newRoot.toAbsolutePath().normalize());

        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        boolean useColor = switch (color.toLowerCase(Locale.ROOT)) {
            case "always" -> true;
            case "never" -> false;
            default -> System.console() != null && System.getenv("NO_COLOR") == null;
        };
        if (quiet) {
            ConsoleReport.summaryLine(out, result);
        } else {
            new ConsoleReport(out, useColor, verbose, showUnchanged, showNoise).print(result);
        }

        if (!quiet) {
            System.err.println("Time: " + result.timings().line() + "  (phase times are summed over worker threads)");
        }
        for (String hint : result.limits().hints()) {
            System.err.println("Limit reached: " + hint);
        }
        if (cache != null) {
            if (!quiet && (cache.libraryHits() > 0 || cache.sourceHits() > 0)) {
                System.err.println("Cache: " + cache.libraryHits() + " library result(s), " + cache.sourceHits()
                        + " decompiled class(es) reused from " + cache.directory());
            }
            cache.prune(Duration.ofDays(60));
        }
        if (html != null) {
            new HtmlReport(true).write(result, html);
            out.println("HTML report:  " + html.toAbsolutePath());
        }
        if (json != null) {
            new JsonReport(true).write(result, json);
            out.println("JSON report:  " + json.toAbsolutePath());
        }
        if (patch != null) {
            new PatchReport(showNoise, bytecode).write(result, patch);
            out.println("Patch file:   " + patch.toAbsolutePath());
        }
        return failOnChange && result.hasDifferences() ? 1 : 0;
    }
}
