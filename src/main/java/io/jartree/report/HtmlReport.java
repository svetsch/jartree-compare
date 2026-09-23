package io.jartree.report;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.jartree.bytecode.ClassAnalyzer;
import io.jartree.compare.ChangeType;
import io.jartree.compare.ClassChange;
import io.jartree.compare.ComparisonResult;
import io.jartree.compare.LibraryDiff;
import io.jartree.compare.LibraryStatus;
import io.jartree.compare.ResourceChange;
import io.jartree.scan.LibraryRef;

/** Self-contained HTML report with collapsible diffs and client-side filtering. */
public final class HtmlReport {

    private static final String CSS = """
            :root { color-scheme: light dark;
              --bg:#f7f7f8; --panel:#fff; --text:#1d1d21; --muted:#6b6b76; --border:#e2e2e7; --code:#fbfbfc;
              --add:#1a7f37; --add-bg:#e6f6ea; --del:#c0262d; --del-bg:#fdecec; --hunk:#5a5fcf; --hunk-bg:#eef0fd;
              --changed:#b35900; --rebuilt:#0b7a8a; --unchanged:#8a8a94; --error:#c0262d; --accent:#3b5bdb; }
            @media (prefers-color-scheme: dark) { :root {
              --bg:#141417; --panel:#1c1c21; --text:#e7e7ea; --muted:#9a9aa5; --border:#2e2e36; --code:#17171b;
              --add:#56d364; --add-bg:#12301b; --del:#ff7b72; --del-bg:#3a1618; --hunk:#a5a9ff; --hunk-bg:#23244a;
              --changed:#f0a24b; --rebuilt:#4cc3d3; --unchanged:#77777f; --error:#ff7b72; --accent:#7c93ff; } }
            * { box-sizing: border-box; }
            body { margin:0; background:var(--bg); color:var(--text);
              font:14px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif; }
            main { max-width:1280px; margin:0 auto; padding:24px 16px 64px; }
            h1 { font-size:22px; margin:0 0 4px; }
            .meta { color:var(--muted); font-size:13px; }
            .meta code { color:var(--text); }
            .roots { display:grid; grid-template-columns:auto 1fr; gap:2px 10px; margin:12px 0 18px; }
            .toolbar { position:sticky; top:0; z-index:5; background:var(--bg); padding:10px 0;
              display:flex; flex-wrap:wrap; gap:8px; align-items:center; border-bottom:1px solid var(--border); }
            .chip { border:1px solid var(--border); background:var(--panel); color:var(--text); border-radius:999px;
              padding:4px 12px; cursor:pointer; font:inherit; display:inline-flex; gap:6px; align-items:center; }
            .chip b { font-variant-numeric:tabular-nums; }
            .chip[aria-pressed=false] { opacity:.45; }
            .dot { width:8px; height:8px; border-radius:50%; display:inline-block; }
            input[type=search] { flex:1 1 240px; min-width:0; padding:6px 10px; border-radius:8px;
              border:1px solid var(--border); background:var(--panel); color:var(--text); font:inherit; }
            label.opt { color:var(--muted); display:inline-flex; gap:4px; align-items:center; }
            .lib { background:var(--panel); border:1px solid var(--border); border-radius:10px; margin:10px 0; }
            .lib > summary { list-style:none; cursor:pointer; padding:10px 14px; display:flex; flex-wrap:wrap;
              gap:4px 10px; align-items:baseline; }
            .lib > summary::-webkit-details-marker { display:none; }
            .lib > summary::before { content:"\\25B8"; color:var(--muted); width:10px; }
            .lib[open] > summary::before { content:"\\25BE"; }
            .path { font-family:ui-monospace,SFMono-Regular,Consolas,monospace; font-size:13px; overflow-wrap:anywhere; }
            .badge { font-size:11px; font-weight:600; letter-spacing:.04em; padding:2px 7px; border-radius:5px;
              color:#fff; text-transform:uppercase; }
            .s-ADDED { background:var(--add); } .s-REMOVED { background:var(--del); }
            .s-CHANGED { background:var(--changed); } .s-REBUILT { background:var(--rebuilt); }
            .s-UNCHANGED { background:var(--unchanged); } .s-ERROR { background:var(--error); }
            .version { color:var(--accent); font-size:13px; }
            .counts { color:var(--muted); font-size:12px; margin-left:auto; }
            .body { padding:0 14px 12px 34px; }
            .note { color:var(--muted); font-size:12px; margin:2px 0; }
            .err { color:var(--error); }
            .kv { display:grid; grid-template-columns:auto 1fr 1fr; gap:2px 14px; font-size:12px; margin:4px 0 10px;
              color:var(--muted); }
            .kv span { overflow-wrap:anywhere; }
            h3 { font-size:13px; margin:14px 0 4px; color:var(--muted); text-transform:uppercase; letter-spacing:.05em; }
            details.item { border-top:1px solid var(--border); }
            details.item > summary { cursor:pointer; padding:5px 0; display:flex; flex-wrap:wrap; gap:4px 8px;
              align-items:baseline; }
            .sym { font-family:ui-monospace,Consolas,monospace; font-weight:700; width:1ch; }
            .t-ADDED { color:var(--add); } .t-REMOVED { color:var(--del); } .t-MODIFIED { color:var(--changed); }
            .tag { font-size:11px; color:var(--muted); border:1px solid var(--border); border-radius:4px; padding:0 5px; }
            .tag.warn { color:var(--changed); border-color:var(--changed); }
            .stat { font-size:12px; font-variant-numeric:tabular-nums; }
            .stat .a { color:var(--add); } .stat .r { color:var(--del); }
            ul.api { margin:4px 0 8px; padding-left:18px; font-family:ui-monospace,Consolas,monospace; font-size:12px; }
            ul.api li { overflow-wrap:anywhere; }
            pre.diff { margin:6px 0 10px; background:var(--code); border:1px solid var(--border); border-radius:8px;
              padding:8px 0; overflow:auto; font:12px/1.5 ui-monospace,SFMono-Regular,Consolas,monospace; max-height:70vh; }
            pre.diff span { display:block; padding:0 12px; white-space:pre; min-width:max-content; }
            pre.diff .ad { background:var(--add-bg); color:var(--add); }
            pre.diff .de { background:var(--del-bg); color:var(--del); }
            pre.diff .hu { background:var(--hunk-bg); color:var(--hunk); }
            pre.diff .fi { color:var(--muted); font-weight:600; }
            .label { font-size:12px; color:var(--muted); margin-top:6px; }
            table.unchanged { width:100%; border-collapse:collapse; font-size:12px; }
            table.unchanged td { border-top:1px solid var(--border); padding:3px 6px; }
            .hidden { display:none !important; }
            .noise { opacity:.7; }
            body:not(.show-noise) .noise { display:none; }
            .empty { color:var(--muted); padding:24px 0; text-align:center; }
            """;

    private static final String JS = """
            (function(){
              const chips=[...document.querySelectorAll('.chip[data-status]')];
              const search=document.getElementById('q');
              const libs=[...document.querySelectorAll('.lib')];
              const empty=document.getElementById('empty');
              function apply(){
                const active=new Set(chips.filter(c=>c.getAttribute('aria-pressed')==='true').map(c=>c.dataset.status));
                const q=search.value.trim().toLowerCase();
                let shown=0;
                libs.forEach(l=>{
                  const ok=active.has(l.dataset.status)&&(!q||l.dataset.search.includes(q));
                  l.classList.toggle('hidden',!ok); if(ok) shown++;
                });
                empty.classList.toggle('hidden',shown>0);
              }
              chips.forEach(c=>c.addEventListener('click',()=>{
                c.setAttribute('aria-pressed',c.getAttribute('aria-pressed')==='true'?'false':'true'); apply();
              }));
              search.addEventListener('input',apply);
              document.getElementById('noise').addEventListener('change',e=>
                document.body.classList.toggle('show-noise',e.target.checked));
              document.getElementById('expand').addEventListener('click',()=>{
                const open=!libs.some(l=>l.open&&!l.classList.contains('hidden'));
                libs.forEach(l=>{ if(!l.classList.contains('hidden')) l.open=open; });
              });
              apply();
            })();
            """;

    private final boolean includeUnchanged;

    public HtmlReport(boolean includeUnchanged) {
        this.includeUnchanged = includeUnchanged;
    }

    public void write(ComparisonResult result, Path file) throws IOException {
        StringBuilder h = new StringBuilder(64 * 1024);
        h.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Jar Tree Comparison</title><style>").append(CSS).append("</style></head><body><main>");

        h.append("<h1>Jar tree comparison</h1>");
        h.append("<div class=\"meta\">Generated ")
                .append(esc(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                        .format(result.started().atZone(ZoneId.systemDefault()))))
                .append(" in ").append(result.duration().toMillis()).append(" ms</div>");
        if (!result.timings().phases().isEmpty()) {
            h.append("<div class=\"meta\">Time: ").append(esc(result.timings().line()))
                    .append(" <span title=\"phases running on several threads are summed over the threads\">(summed over threads)</span></div>");
        }
        h.append("<div class=\"roots meta\"><b>Old</b><span><code>").append(esc(result.oldRoot().toString()))
                .append("</code> &middot; ").append(result.oldLibraryCount()).append(" libraries</span>")
                .append("<b>New</b><span><code>").append(esc(result.newRoot().toString()))
                .append("</code> &middot; ").append(result.newLibraryCount()).append(" libraries</span></div>");

        Map<LibraryStatus, Integer> counts = result.countsByStatus();
        h.append("<div class=\"toolbar\">");
        for (LibraryStatus s : LibraryStatus.values()) {
            boolean pressed = s != LibraryStatus.UNCHANGED;
            if (s == LibraryStatus.UNCHANGED && !includeUnchanged) {
                continue;
            }
            h.append("<button class=\"chip\" data-status=\"").append(s).append("\" aria-pressed=\"")
                    .append(pressed).append("\"><span class=\"dot s-").append(s).append("\"></span>")
                    .append(s.name().toLowerCase(Locale.ROOT)).append(" <b>").append(counts.get(s))
                    .append("</b></button>");
        }
        h.append("<input type=\"search\" id=\"q\" placeholder=\"Filter by library, class or resource…\">")
                .append("<label class=\"opt\"><input type=\"checkbox\" id=\"noise\"> show build noise</label>")
                .append("<button class=\"chip\" id=\"expand\">expand / collapse</button></div>");

        for (String hint : result.limits().hints()) {
            h.append("<div class=\"note err\">\u26A0 ").append(esc(hint)).append("</div>");
        }
        if (!result.warnings().isEmpty()) {
            h.append("<h3>Warnings</h3>");
            result.warnings().forEach(w -> h.append("<div class=\"note err\">").append(esc(w)).append("</div>"));
        }

        for (LibraryDiff lib : result.libraries()) {
            if (lib.status() == LibraryStatus.UNCHANGED && !includeUnchanged) {
                continue;
            }
            library(h, lib);
        }
        h.append("<div id=\"empty\" class=\"empty hidden\">No library matches the current filter.</div>");
        h.append("<script>").append(JS).append("</script></main></body></html>\n");

        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(h.toString());
        }
    }

    private void library(StringBuilder h, LibraryDiff lib) {
        StringBuilder search = new StringBuilder(lib.primary().path());
        if (lib.oldLib() != null) {
            search.append(' ').append(lib.oldLib().path());
        }
        lib.classes().forEach(c -> search.append(' ').append(c.displayName()));
        lib.resources().forEach(r -> search.append(' ').append(r.path()));

        boolean expandable = lib.status() == LibraryStatus.CHANGED || lib.status() == LibraryStatus.REBUILT
                || lib.status() == LibraryStatus.ERROR;
        h.append("<details class=\"lib\" data-status=\"").append(lib.status()).append("\" data-search=\"")
                .append(esc(search.toString().toLowerCase(Locale.ROOT))).append("\">");
        h.append("<summary><span class=\"badge s-").append(lib.status()).append("\">")
                .append(lib.status().name().toLowerCase(Locale.ROOT)).append("</span><span class=\"path\">");
        if (lib.pathChanged()) {
            h.append(esc(lib.oldLib().path())).append(" &rarr; ").append(esc(lib.newLib().path()));
        } else {
            h.append(esc(lib.primary().path()));
        }
        h.append("</span>");
        if (!lib.versionLabel().isEmpty()) {
            h.append("<span class=\"version\">").append(esc(lib.versionLabel().replace("->", "→"))).append("</span>");
        }
        if (expandable && lib.error() == null) {
            h.append("<span class=\"counts\">").append(lib.countClasses(ChangeType.MODIFIED)).append(" modified &middot; ")
                    .append(lib.countClasses(ChangeType.ADDED)).append(" added &middot; ")
                    .append(lib.countClasses(ChangeType.REMOVED)).append(" removed classes &middot; ")
                    .append(lib.resources().size()).append(" resources</span>");
        }
        h.append("</summary><div class=\"body\">");

        h.append("<div class=\"kv\"><span></span><span><b>old</b></span><span><b>new</b></span>");
        kv(h, "size", lib.oldLib(), lib.newLib(), r -> ConsoleReport.size(r.size()));
        kv(h, "entries / classes", lib.oldLib(), lib.newLib(), r -> r.entryCount() + " / " + r.classCount());
        kv(h, "maven", lib.oldLib(), lib.newLib(), r -> r.mavenGa() == null ? "–" : r.mavenGa() + ":" + r.mavenVersion());
        kv(h, "sha-256", lib.oldLib(), lib.newLib(), LibraryRef::shortSha);
        h.append("</div>");
        if (lib.matchedBy() != null && lib.oldLib() != null && lib.newLib() != null) {
            h.append("<div class=\"note\">matched by ").append(lib.matchedBy().name().toLowerCase(Locale.ROOT).replace('_', ' '))
                    .append("</div>");
        }
        if (lib.error() != null) {
            h.append("<div class=\"note err\">").append(esc(lib.error())).append("</div>");
        }
        if ((lib.status() == LibraryStatus.CHANGED || lib.status() == LibraryStatus.REBUILT) && lib.error() == null) {
            h.append("<div class=\"note\">").append(esc(ConsoleReport.summary(lib))).append("</div>");
        }
        lib.notes().forEach(n -> h.append("<div class=\"note\">").append(esc(n)).append("</div>"));

        if (!lib.classes().isEmpty()) {
            h.append("<h3>Classes</h3>");
            lib.classes().forEach(c -> classChange(h, c));
        }
        if (!lib.resources().isEmpty()) {
            h.append("<h3>Resources</h3>");
            lib.resources().forEach(r -> resource(h, r));
        }
        h.append("</div></details>");
    }

    private interface Field {
        String get(LibraryRef ref);
    }

    private static void kv(StringBuilder h, String label, LibraryRef a, LibraryRef b, Field f) {
        h.append("<span>").append(label).append("</span><span>").append(a == null ? "–" : esc(f.get(a)))
                .append("</span><span>").append(b == null ? "–" : esc(f.get(b))).append("</span>");
    }

    private void classChange(StringBuilder h, ClassChange c) {
        boolean hasBody = !c.api().isEmpty() || !c.sourceDiff().isEmpty() || !c.bytecodeDiff().isEmpty()
                || c.decompileProblem() != null;
        h.append("<details class=\"item").append(c.isSignificant() ? "" : " noise").append("\"><summary>")
                .append("<span class=\"sym t-").append(c.type()).append("\">").append(c.type().symbol())
                .append("</span><span class=\"path\">").append(esc(c.displayName())).append("</span>");
        List<String> tags = new ArrayList<>();
        switch (c.nature()) {
            case DEBUG_INFO_ONLY -> tags.add("debug info only");
            case BYTECODE_ONLY -> tags.add("bytecode only – same source");
            case NOT_DECOMPILED -> tags.add("not decompiled");
            default -> {
            }
        }
        if (c.oldMajor() != null && c.newMajor() != null && !c.oldMajor().equals(c.newMajor())) {
            tags.add(ClassAnalyzer.javaRelease(c.oldMajor()) + " → " + ClassAnalyzer.javaRelease(c.newMajor()));
        }
        tags.forEach(t -> h.append("<span class=\"tag\">").append(esc(t)).append("</span>"));
        if (c.decompileProblem() != null) {
            h.append("<span class=\"tag warn\">decompilation incomplete</span>");
        }
        if (!c.api().isEmpty()) {
            h.append("<span class=\"tag\">API +").append(c.api().added().size()).append(" −")
                    .append(c.api().removed().size()).append(" ~").append(c.api().changed().size()).append("</span>");
        }
        if (!c.sourceDiff().isEmpty()) {
            h.append("<span class=\"stat\"><span class=\"a\">+").append(c.sourceDiff().added())
                    .append("</span> <span class=\"r\">−").append(c.sourceDiff().removed()).append("</span></span>");
        }
        h.append("</summary>");
        if (hasBody) {
            if (c.decompileProblem() != null) {
                h.append("<div class=\"note err\">").append(esc(c.decompileProblem())).append("</div>");
            }
            if (!c.api().isEmpty()) {
                h.append("<ul class=\"api\">");
                c.api().removed().forEach(m -> h.append("<li class=\"t-REMOVED\">− ").append(esc(m)).append("</li>"));
                c.api().added().forEach(m -> h.append("<li class=\"t-ADDED\">+ ").append(esc(m)).append("</li>"));
                c.api().changed().forEach(m -> h.append("<li class=\"t-MODIFIED\">~ ").append(esc(m)).append("</li>"));
                h.append("</ul>");
            }
            if (!c.sourceDiff().isEmpty()) {
                h.append("<div class=\"label\">Decompiled source</div>");
                diff(h, c.sourceDiff().text());
            }
            if (!c.bytecodeDiff().isEmpty()) {
                h.append("<div class=\"label\">Bytecode (without debug info)</div>");
                diff(h, c.bytecodeDiff().text());
            }
        } else {
            h.append("<div class=\"note\">Changed files: ").append(esc(String.join(", ", c.changedFiles()))).append("</div>");
        }
        h.append("</details>");
    }

    private void resource(StringBuilder h, ResourceChange r) {
        h.append("<details class=\"item").append(r.noise() ? " noise" : "").append("\"><summary>")
                .append("<span class=\"sym t-").append(r.type()).append("\">").append(r.type().symbol())
                .append("</span><span class=\"path\">").append(esc(r.path())).append("</span>");
        if (r.noise()) {
            h.append("<span class=\"tag\">build noise</span>");
        }
        if (!r.text()) {
            h.append("<span class=\"tag\">binary ").append(esc(ConsoleReport.size(r.oldSize()))).append(" → ")
                    .append(esc(ConsoleReport.size(r.newSize()))).append("</span>");
        } else if (!r.diff().isEmpty()) {
            h.append("<span class=\"stat\"><span class=\"a\">+").append(r.diff().added())
                    .append("</span> <span class=\"r\">−").append(r.diff().removed()).append("</span></span>");
        }
        h.append("</summary>");
        if (!r.diff().isEmpty()) {
            diff(h, r.diff().text());
        }
        h.append("</details>");
    }

    private static void diff(StringBuilder h, String text) {
        h.append("<pre class=\"diff\">");
        for (String line : text.split("\n")) {
            String cls;
            if (line.startsWith("+++") || line.startsWith("---")) {
                cls = "fi";
            } else if (line.startsWith("@@")) {
                cls = "hu";
            } else if (line.startsWith("+")) {
                cls = "ad";
            } else if (line.startsWith("-")) {
                cls = "de";
            } else {
                cls = null;
            }
            h.append(cls == null ? "<span>" : "<span class=\"" + cls + "\">")
                    .append(line.isEmpty() ? " " : esc(line)).append("</span>");
        }
        h.append("</pre>");
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
