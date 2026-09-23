package io.jartree.decompile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Manifest;

import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.extern.IContextSource;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

import io.jartree.compare.Timings;

/** Decompiles in-memory class files with Vineflower. */
public final class Decompiler {

    /**
     * Vineflower keeps global state; concurrent decompiler instances interfere with each other and can load
     * classes without bound. Decompilation is therefore serialized, each run using its own worker threads.
     */
    private static final Object LOCK = new Object();

    /** Marker Vineflower emits when a method or class could not be decompiled. */
    public static final String FAILURE_MARKER = "$VF: Couldn't be decompiled";

    private final int threads;
    private final int maxSecondsPerMethod;
    private final Timings timings;

    public Decompiler(int threads, int maxSecondsPerMethod) {
        this(threads, maxSecondsPerMethod, new Timings());
    }

    public Decompiler(int threads, int maxSecondsPerMethod, Timings timings) {
        this.threads = threads;
        this.maxSecondsPerMethod = maxSecondsPerMethod;
        this.timings = timings;
    }

    public record Result(Map<String, String> sources, List<String> errors) {
    }

    /** The classes of a library, read on demand so that a large library is not held in memory. */
    public interface ClassSource {
        /** Internal names ("com/acme/Foo") of the classes that can be resolved. */
        Collection<String> names();

        /** Class bytes, or null when absent. */
        byte[] bytes(String internalName);

        static ClassSource of(Map<String, byte[]> classes) {
            return new ClassSource() {
                @Override
                public Collection<String> names() {
                    return classes.keySet();
                }

                @Override
                public byte[] bytes(String internalName) {
                    return classes.get(internalName);
                }
            };
        }
    }

    /**
     * @param classes internal class name (without {@code .class}) to bytes; these are decompiled
     * @param context other classes of the same library, used to resolve types (may overlap {@code classes})
     * @return top-level internal class name to Java source. Inner classes are part of their outer class' source.
     */
    public Result decompile(String name, Map<String, byte[]> classes, ClassSource context) {
        Map<String, String> sources = new ConcurrentHashMap<>();
        List<String> errors = new ArrayList<>();
        if (classes.isEmpty()) {
            return new Result(sources, errors);
        }

        Map<String, Object> options = new HashMap<>();
        options.put(IFernflowerPreferences.THREADS, String.valueOf(threads));
        options.put(IFernflowerPreferences.MAX_PROCESSING_METHOD, String.valueOf(maxSecondsPerMethod));
        options.put(IFernflowerPreferences.INDENT_STRING, "    ");
        options.put(IFernflowerPreferences.SKIP_EXTRA_FILES, "1");
        options.put(IFernflowerPreferences.BYTECODE_SOURCE_MAPPING, "0");
        options.put(IFernflowerPreferences.DUMP_ORIGINAL_LINES, "0");

        long waitStart = System.nanoTime();
        synchronized (LOCK) {
            timings.since(Timings.Phase.DECOMPILE_WAIT, waitStart);
            long start = System.nanoTime();
            run(name, classes, context, options, sources, errors);
            timings.since(Timings.Phase.DECOMPILE, start);
        }
        return new Result(sources, errors);
    }

    private static void run(String name, Map<String, byte[]> classes, ClassSource context,
                            Map<String, Object> options, Map<String, String> sources, List<String> errors) {
        Fernflower fernflower = new Fernflower(NoopSaver.INSTANCE, options, new CollectingLogger(errors));
        try {
            fernflower.addSource(new MemorySource(name, ClassSource.of(classes), sources));
            fernflower.addLibrary(new MemorySource(name + " (context)", context, null));
            fernflower.decompileContext();
        } catch (RuntimeException | StackOverflowError e) {
            synchronized (errors) {
                errors.add("Decompilation of " + name + " failed: " + e);
            }
        } finally {
            fernflower.clearContext();
        }
    }

    private static final class MemorySource implements IContextSource {
        private final String name;
        private final ClassSource classes;
        private final Map<String, String> output;

        MemorySource(String name, ClassSource classes, Map<String, String> output) {
            this.name = name;
            this.classes = classes;
            this.output = output;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Entries getEntries() {
            List<Entry> entries = classes.names().stream().sorted().map(Entry::atBase).toList();
            return new Entries(entries, List.of(), List.of());
        }

        @Override
        public byte[] getClassBytes(String className) {
            return classes.bytes(className);
        }

        @Override
        public boolean hasClass(String className) {
            return classes.bytes(className) != null;
        }

        @Override
        public InputStream getInputStream(String resource) {
            if (!resource.endsWith(CLASS_SUFFIX)) {
                return null;
            }
            byte[] bytes = classes.bytes(resource.substring(0, resource.length() - CLASS_SUFFIX.length()));
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        }

        @Override
        public IOutputSink createOutputSink(IResultSaver saver) {
            if (output == null) {
                return null;
            }
            return new IOutputSink() {
                @Override
                public void begin() {
                }

                @Override
                public void acceptClass(String qualifiedName, String fileName, String content, int[] mapping) {
                    output.put(qualifiedName, content);
                }

                @Override
                public void acceptDirectory(String directory) {
                }

                @Override
                public void acceptOther(String path) {
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private static final class CollectingLogger extends IFernflowerLogger {
        private final List<String> errors;

        CollectingLogger(List<String> errors) {
            this.errors = errors;
        }

        @Override
        public void writeMessage(String message, Severity severity) {
            if (severity == Severity.ERROR) {
                synchronized (errors) {
                    errors.add(message);
                }
            }
        }

        @Override
        public void writeMessage(String message, Severity severity, Throwable t) {
            writeMessage(t == null ? message : message + ": " + t, severity);
        }
    }

    private enum NoopSaver implements IResultSaver {
        INSTANCE;

        @Override
        public void saveFolder(String path) {
        }

        @Override
        public void copyFile(String source, String path, String entryName) {
        }

        @Override
        public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping) {
        }

        @Override
        public void createArchive(String path, String archiveName, Manifest manifest) {
        }

        @Override
        public void saveDirEntry(String path, String archiveName, String entryName) {
        }

        @Override
        public void copyEntry(String source, String path, String archiveName, String entry) {
        }

        @Override
        public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName, String content) {
        }

        @Override
        public void closeArchive(String path, String archiveName) {
        }
    }
}
