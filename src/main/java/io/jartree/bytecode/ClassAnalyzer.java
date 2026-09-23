package io.jartree.bytecode;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;
import org.objectweb.asm.util.TraceMethodVisitor;

/** Bytecode level inspection of class files using ASM. */
public final class ClassAnalyzer {

    private ClassAnalyzer() {
    }

    /** Structural summary of a class: header and declared (non synthetic) members. */
    public record ClassInfo(String internalName, int majorVersion, String header, Map<String, String> members) {

        public String javaName() {
            return internalName.replace('/', '.');
        }
    }

    public static ClassInfo read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        Map<String, String> members = new TreeMap<>();
        for (FieldNode f : node.fields) {
            if ((f.access & Opcodes.ACC_SYNTHETIC) != 0) {
                continue;
            }
            members.put("field " + f.name, fieldDecl(f));
        }
        for (MethodNode m : node.methods) {
            if ((m.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0 || m.name.startsWith("lambda$")) {
                continue;
            }
            members.put("method " + m.name + m.desc, methodDecl(node, m));
        }

        StringBuilder header = new StringBuilder(classModifiers(node.access));
        header.append(node.name.replace('/', '.'));
        if (node.superName != null && !node.superName.equals("java/lang/Object")
                && (node.access & (Opcodes.ACC_ENUM | Opcodes.ACC_RECORD)) == 0) {
            header.append(" extends ").append(node.superName.replace('/', '.'));
        }
        if (node.interfaces != null && !node.interfaces.isEmpty()) {
            header.append((node.access & Opcodes.ACC_INTERFACE) != 0 ? " extends " : " implements ");
            header.append(String.join(", ", node.interfaces.stream().map(i -> i.replace('/', '.')).toList()));
        }
        return new ClassInfo(node.name, node.version & 0xFFFF, header.toString(), members);
    }

    private static String fieldDecl(FieldNode f) {
        String decl = modifiers(f.access, false) + Type.getType(f.desc).getClassName() + " " + f.name;
        if (f.value != null) {
            decl += " = " + literal(f.value);
        }
        return decl.strip();
    }

    private static String methodDecl(ClassNode owner, MethodNode m) {
        if (m.name.equals("<clinit>")) {
            return "static {}";
        }
        StringJoiner params = new StringJoiner(", ", "(", ")");
        for (Type t : Type.getArgumentTypes(m.desc)) {
            params.add(t.getClassName());
        }
        String name = m.name.equals("<init>") ? simpleName(owner.name) : m.name;
        String decl = modifiers(m.access & ~Opcodes.ACC_VARARGS, true)
                + (m.name.equals("<init>") ? "" : Type.getReturnType(m.desc).getClassName() + " ")
                + name + params;
        if (m.exceptions != null && !m.exceptions.isEmpty()) {
            decl += " throws " + String.join(", ", m.exceptions.stream().map(e -> e.replace('/', '.')).toList());
        }
        return decl.strip();
    }

    private static final Pattern LAMBDA = Pattern.compile("^lambda\\$(.*)\\$\\d+$");
    private static final Pattern LAMBDA_REF = Pattern.compile("lambda\\$[\\w$]*?\\$\\d+");

    /**
     * Lists the fields, methods, constructors and initializers that were added, removed or changed between two
     * versions of the same class file. Method bodies are compared instruction by instruction without debug
     * information; changes inside lambdas are attributed to the enclosing method.
     *
     * @param owner nested class path relative to the top-level class, "" for the top-level class itself
     */
    public static List<MemberChange> memberChanges(byte[] oldBytes, byte[] newBytes, String owner) {
        ClassNode a = code(oldBytes);
        ClassNode b = code(newBytes);
        List<MemberChange> result = new ArrayList<>();

        Map<String, FieldNode> oldFields = fields(a);
        Map<String, FieldNode> newFields = fields(b);
        for (String name : union(oldFields.keySet(), newFields.keySet())) {
            FieldNode f = oldFields.get(name);
            FieldNode g = newFields.get(name);
            if (f == null) {
                result.add(new MemberChange(MemberChange.Change.ADDED, MemberChange.Kind.FIELD, owner, name,
                        fieldDecl(g), "added", -1));
            } else if (g == null) {
                result.add(new MemberChange(MemberChange.Change.REMOVED, MemberChange.Kind.FIELD, owner, name,
                        fieldDecl(f), "removed", -1));
            } else if (!fieldDecl(f).equals(fieldDecl(g))) {
                String detail = !f.desc.equals(g.desc) ? "type"
                        : !Objects.equals(f.value, g.value) ? "constant" : "modifiers";
                result.add(new MemberChange(MemberChange.Change.MODIFIED, MemberChange.Kind.FIELD, owner, name,
                        fieldDecl(f) + "  →  " + fieldDecl(g), detail, -1));
            }
        }

        Map<String, MethodNode> oldMethods = methods(a);
        Map<String, MethodNode> newMethods = methods(b);
        Map<String, MemberChange> modified = new LinkedHashMap<>();
        for (String key : union(oldMethods.keySet(), newMethods.keySet())) {
            MethodNode m = oldMethods.get(key);
            MethodNode n = newMethods.get(key);
            if (m == null) {
                result.add(method(MemberChange.Change.ADDED, b, n, owner, "added"));
            } else if (n == null) {
                result.add(method(MemberChange.Change.REMOVED, a, m, owner, "removed"));
            } else {
                List<String> details = new ArrayList<>();
                if (!methodDecl(a, m).equals(methodDecl(b, n))) {
                    details.add("modifiers");
                }
                if (!body(m).equals(body(n))) {
                    details.add("body");
                }
                if (!details.isEmpty()) {
                    modified.put(key, method(MemberChange.Change.MODIFIED, b, n, owner, String.join(", ", details)));
                }
            }
        }

        // lambdas: compare the bodies per enclosing method name and attribute differences to that method
        Map<String, List<String>> oldLambdas = lambdas(a);
        Map<String, List<String>> newLambdas = lambdas(b);
        for (String enclosing : union(oldLambdas.keySet(), newLambdas.keySet())) {
            if (oldLambdas.getOrDefault(enclosing, List.of()).equals(newLambdas.getOrDefault(enclosing, List.of()))) {
                continue;
            }
            String methodName = enclosing.equals("new") ? "<init>" : enclosing.equals("static") ? "<clinit>" : enclosing;
            String key = newMethods.keySet().stream().filter(k -> k.startsWith(methodName + "(")).findFirst()
                    .orElse(null);
            if (key == null || !oldMethods.containsKey(key)) {
                continue;
            }
            MemberChange existing = modified.get(key);
            String detail = existing == null ? "lambda" : existing.detail() + ", lambda";
            modified.put(key, method(MemberChange.Change.MODIFIED, b, newMethods.get(key), owner, detail));
        }
        result.addAll(modified.values());
        return result;
    }

    private static MemberChange method(MemberChange.Change change, ClassNode owner, MethodNode m, String ownerPath,
                                       String detail) {
        MemberChange.Kind kind = m.name.equals("<init>") ? MemberChange.Kind.CONSTRUCTOR
                : m.name.equals("<clinit>") ? MemberChange.Kind.INITIALIZER : MemberChange.Kind.METHOD;
        String name = switch (kind) {
            case CONSTRUCTOR -> simpleName(owner.name);
            case INITIALIZER -> "static";
            default -> m.name;
        };
        return new MemberChange(change, kind, ownerPath, name, methodDecl(owner, m), detail,
                Type.getArgumentTypes(m.desc).length);
    }

    private static ClassNode code(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    private static Map<String, FieldNode> fields(ClassNode node) {
        Map<String, FieldNode> map = new LinkedHashMap<>();
        for (FieldNode f : node.fields) {
            if ((f.access & Opcodes.ACC_SYNTHETIC) == 0) {
                map.put(f.name, f);
            }
        }
        return map;
    }

    private static Map<String, MethodNode> methods(ClassNode node) {
        Map<String, MethodNode> map = new LinkedHashMap<>();
        for (MethodNode m : node.methods) {
            if ((m.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == 0 && !m.name.startsWith("lambda$")) {
                map.put(m.name + m.desc, m);
            }
        }
        return map;
    }

    private static Map<String, List<String>> lambdas(ClassNode node) {
        Map<String, List<String>> map = new TreeMap<>();
        for (MethodNode m : node.methods) {
            Matcher matcher = LAMBDA.matcher(m.name);
            if (matcher.matches()) {
                map.computeIfAbsent(matcher.group(1), k -> new ArrayList<>()).add(m.desc + body(m));
            }
        }
        map.values().forEach(Collections::sort);
        return map;
    }

    private static String body(MethodNode m) {
        Textifier textifier = new Textifier();
        m.accept(new TraceMethodVisitor(textifier));
        StringWriter sw = new StringWriter();
        textifier.print(new PrintWriter(sw));
        // lambda numbering shifts when other lambdas are added; it does not change behavior
        return LAMBDA_REF.matcher(sw.toString()).replaceAll("lambda\\$");
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> set = new LinkedHashSet<>(a);
        set.addAll(b);
        return set;
    }

    /** Renders the class with ASM's Textifier; debug info (line numbers, local names) and frames are optional. */
    public static String textify(byte[] bytes, boolean includeDebug) {
        StringWriter sw = new StringWriter();
        int flags = includeDebug ? 0 : ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES;
        new ClassReader(bytes).accept(new TraceClassVisitor(null, new Textifier(), new PrintWriter(sw)), flags);
        return sw.toString();
    }

    /** Maps a class file major version to the Java release, e.g. 61 to "Java 17". */
    public static String javaRelease(int major) {
        return major >= 49 ? "Java " + (major - 44) : "Java 1." + (major - 44);
    }

    /** API level differences between two versions of the same class. */
    public record ApiDelta(List<String> added, List<String> removed, List<String> changed) {

        public static final ApiDelta EMPTY = new ApiDelta(List.of(), List.of(), List.of());

        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
        }

        public ApiDelta plus(ApiDelta other) {
            List<String> a = new ArrayList<>(added);
            List<String> r = new ArrayList<>(removed);
            List<String> c = new ArrayList<>(changed);
            a.addAll(other.added);
            r.addAll(other.removed);
            c.addAll(other.changed);
            return new ApiDelta(a, r, c);
        }
    }

    public static ApiDelta apiDelta(ClassInfo oldInfo, ClassInfo newInfo) {
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        if (!oldInfo.header().equals(newInfo.header())) {
            changed.add(oldInfo.header() + "  ->  " + newInfo.header());
        }
        for (var e : newInfo.members().entrySet()) {
            String old = oldInfo.members().get(e.getKey());
            if (old == null) {
                added.add(e.getValue());
            } else if (!old.equals(e.getValue())) {
                changed.add(old + "  ->  " + e.getValue());
            }
        }
        for (var e : oldInfo.members().entrySet()) {
            if (!newInfo.members().containsKey(e.getKey())) {
                removed.add(e.getValue());
            }
        }
        return new ApiDelta(added, removed, changed);
    }

    private static String simpleName(String internalName) {
        String s = internalName.substring(internalName.lastIndexOf('/') + 1);
        return s.substring(s.lastIndexOf('$') + 1);
    }

    private static String classModifiers(int access) {
        StringBuilder sb = new StringBuilder(visibility(access));
        if ((access & Opcodes.ACC_ANNOTATION) != 0) {
            return sb.append("@interface ").toString();
        }
        if ((access & Opcodes.ACC_INTERFACE) != 0) {
            return sb.append("interface ").toString();
        }
        if ((access & Opcodes.ACC_ABSTRACT) != 0) {
            sb.append("abstract ");
        }
        if ((access & Opcodes.ACC_FINAL) != 0 && (access & Opcodes.ACC_ENUM) == 0) {
            sb.append("final ");
        }
        if ((access & Opcodes.ACC_ENUM) != 0) {
            return sb.append("enum ").toString();
        }
        if ((access & Opcodes.ACC_RECORD) != 0) {
            return sb.append("record ").toString();
        }
        return sb.append("class ").toString();
    }

    private static String modifiers(int access, boolean method) {
        StringBuilder sb = new StringBuilder(visibility(access));
        if ((access & Opcodes.ACC_STATIC) != 0) {
            sb.append("static ");
        }
        if ((access & Opcodes.ACC_ABSTRACT) != 0) {
            sb.append("abstract ");
        }
        if ((access & Opcodes.ACC_FINAL) != 0) {
            sb.append("final ");
        }
        if (method) {
            if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) {
                sb.append("synchronized ");
            }
            if ((access & Opcodes.ACC_NATIVE) != 0) {
                sb.append("native ");
            }
        } else {
            if ((access & Opcodes.ACC_VOLATILE) != 0) {
                sb.append("volatile ");
            }
            if ((access & Opcodes.ACC_TRANSIENT) != 0) {
                sb.append("transient ");
            }
        }
        return sb.toString();
    }

    private static String visibility(int access) {
        if ((access & Opcodes.ACC_PUBLIC) != 0) {
            return "public ";
        }
        if ((access & Opcodes.ACC_PROTECTED) != 0) {
            return "protected ";
        }
        if ((access & Opcodes.ACC_PRIVATE) != 0) {
            return "private ";
        }
        return "";
    }

    private static String literal(Object value) {
        if (value instanceof String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        }
        if (value instanceof Long) {
            return value + "L";
        }
        if (value instanceof Float) {
            return value + "F";
        }
        return String.valueOf(value);
    }
}
