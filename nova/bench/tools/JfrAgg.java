import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Path;
import java.util.*;

/**
 * Aggregates jdk.ExecutionSample events of a JFR recording.
 *   java JfrAgg.java rec.jfr [top=N] [thread=substr] [focus=method-substr] [callers=method-substr] [line=1]
 * Prints self and inclusive sample counts per method; with focus=, only samples whose stack contains
 * that method are counted (and the callee breakdown below it is printed); callers= prints the direct
 * callers of a method.
 */
public class JfrAgg {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        int top = 60;
        String thread = null, focus = null, callers = null, callees = null;
        boolean lines = false;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("top=")) top = Integer.parseInt(a.substring(4));
            else if (a.startsWith("thread=")) thread = a.substring(7);
            else if (a.startsWith("focus=")) focus = a.substring(6);
            else if (a.startsWith("callers=")) callers = a.substring(8);
            else if (a.startsWith("callees=")) callees = a.substring(8);
            else if (a.equals("line=1")) lines = true;
        }
        Map<String, Integer> self = new HashMap<>(), incl = new HashMap<>(), callerMap = new HashMap<>(), calleeMap = new HashMap<>();
        Map<String, Integer> threads = new HashMap<>();
        int total = 0, truncated = 0;
        for (Path file1 : files(file)) try (RecordingFile rf = new RecordingFile(file1)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                if (!e.getEventType().getName().equals("jdk.ExecutionSample")) continue;
                String tn = e.getThread("sampledThread") == null ? "?" : e.getThread("sampledThread").getJavaName();
                if (tn == null) tn = "?";
                if (thread != null && !tn.contains(thread)) continue;
                RecordedStackTrace st = e.getStackTrace();
                if (st == null) continue;
                List<RecordedFrame> frames = st.getFrames();
                List<String> names = new ArrayList<>(frames.size());
                for (RecordedFrame f : frames) {
                    String n = f.getMethod().getType().getName() + "." + f.getMethod().getName();
                    if (lines) n += ":" + f.getLineNumber();
                    names.add(n);
                }
                if (focus != null) {
                    boolean has = false;
                    for (String n : names) if (n.contains(focus)) { has = true; break; }
                    if (!has) continue;
                }
                total++;
                if (st.isTruncated()) truncated++;
                threads.merge(tn.replaceAll("\\d+", "#"), 1, Integer::sum);
                if (!names.isEmpty()) self.merge(names.get(0), 1, Integer::sum);
                Set<String> seen = new HashSet<>();
                for (String n : names) if (seen.add(n)) incl.merge(n, 1, Integer::sum);
                if (callers != null) {
                    for (int i = 0; i < names.size() - 1; i++) {
                        if (names.get(i).contains(callers)) {
                            callerMap.merge(names.get(i + 1) + " -> " + names.get(i), 1, Integer::sum);
                            break;
                        }
                    }
                }
                if (callees != null) {
                    // outermost occurrence of the method; count its direct callee
                    for (int i = names.size() - 1; i > 0; i--) {
                        if (names.get(i).contains(callees)) {
                            calleeMap.merge(names.get(i - 1), 1, Integer::sum);
                            break;
                        }
                    }
                }
            }
        }
        System.out.println("samples: " + total + " (truncated stacks: " + truncated + ")");
        System.out.println("threads: " + sortDesc(threads, 10));
        System.out.println("\n== SELF ==");
        print(self, top, total);
        System.out.println("\n== INCLUSIVE ==");
        print(incl, top, total);
        if (callers != null) {
            System.out.println("\n== CALLERS of " + callers + " ==");
            print(callerMap, top, total);
        }
        if (callees != null) {
            System.out.println("\n== CALLEES of (outermost) " + callees + " ==");
            print(calleeMap, top, total);
        }
    }

    static String sortDesc(Map<String, Integer> m, int n) {
        StringBuilder sb = new StringBuilder();
        m.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(n)
                .forEach(en -> sb.append(en.getKey()).append('=').append(en.getValue()).append("  "));
        return sb.toString();
    }

    static void print(Map<String, Integer> m, int n, int total) {
        m.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(n)
                .forEach(en -> System.out.printf("%7d %5.1f%%  %s%n", en.getValue(), 100.0 * en.getValue() / Math.max(1, total), en.getKey()));
    }

    static java.util.List<Path> files(Path p) throws java.io.IOException {
        if (java.nio.file.Files.isDirectory(p)) {
            try (var s = java.nio.file.Files.list(p)) { return s.filter(x -> x.toString().endsWith(".jfr") && x.toFile().length() > 0).sorted().toList(); }
        }
        return java.util.List.of(p);
    }
}
