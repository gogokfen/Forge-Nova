import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Path;
import java.util.*;

/**
 * Top-down merged call tree of jdk.ExecutionSample events below a root method.
 *   java JfrTree.java rec.jfr root=<method-substr> [min=0.5] [depth=30] [thread=substr]
 * The outermost frame matching root starts the tree; nodes below min% of all root samples are pruned.
 */
public class JfrTree {
    static final class Node {
        final String name;
        int count;
        final Map<String, Node> kids = new LinkedHashMap<>();
        Node(String n) { name = n; }
        Node kid(String n) { return kids.computeIfAbsent(n, Node::new); }
    }

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        String root = null, thread = null;
        double min = 0.5;
        int depth = 30;
        boolean lines = false;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("root=")) root = a.substring(5);
            else if (a.startsWith("min=")) min = Double.parseDouble(a.substring(4));
            else if (a.startsWith("depth=")) depth = Integer.parseInt(a.substring(6));
            else if (a.startsWith("thread=")) thread = a.substring(7);
            else if (a.equals("line=1")) lines = true;
        }
        Node top = new Node("ROOT");
        int total = 0;
        for (Path file1 : files(file)) try (RecordingFile rf = new RecordingFile(file1)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                if (!e.getEventType().getName().equals("jdk.ExecutionSample")) continue;
                if (thread != null) {
                    String tn = e.getThread("sampledThread") == null ? "?" : e.getThread("sampledThread").getJavaName();
                    if (tn == null || !tn.contains(thread)) continue;
                }
                RecordedStackTrace st = e.getStackTrace();
                if (st == null) continue;
                List<RecordedFrame> frames = st.getFrames();
                int start = -1;
                for (int i = frames.size() - 1; i >= 0; i--) {
                    String n = name(frames.get(i), false);
                    if (root == null || n.contains(root)) { start = i; break; }
                }
                if (start < 0) continue;
                total++;
                Node cur = top;
                cur.count++;
                for (int i = start, d = 0; i >= 0 && d < depth; i--, d++) {
                    cur = cur.kid(name(frames.get(i), lines));
                    cur.count++;
                }
            }
        }
        System.out.println("samples under root: " + total);
        print(top, 0, total, min);
    }

    static String name(RecordedFrame f, boolean line) {
        String n = f.getMethod().getType().getName() + "." + f.getMethod().getName();
        n = n.replace("forge.game.", "g.").replace("forge.ai.", "ai.");
        return line ? n + ":" + f.getLineNumber() : n;
    }

    static void print(Node n, int indent, int total, double min) {
        List<Node> kids = new ArrayList<>(n.kids.values());
        kids.sort((a, b) -> b.count - a.count);
        for (Node k : kids) {
            double pct = 100.0 * k.count / Math.max(1, total);
            if (pct < min) continue;
            // collapse single-child chains of trivial frames
            System.out.printf("%s%5.1f%% %s%n", "  ".repeat(indent), pct, k.name);
            print(k, indent + 1, total, min);
        }
    }

    static java.util.List<Path> files(Path p) throws java.io.IOException {
        if (java.nio.file.Files.isDirectory(p)) {
            try (var s = java.nio.file.Files.list(p)) { return s.filter(x -> x.toString().endsWith(".jfr") && x.toFile().length() > 0).sorted().toList(); }
        }
        return java.util.List.of(p);
    }
}
