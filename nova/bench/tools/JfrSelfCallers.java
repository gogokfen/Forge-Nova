import jdk.jfr.consumer.*;
import java.nio.file.Path;
import java.util.*;

/** For samples whose top frame matches self= (regex), attribute them to the first frames NOT matching skip= (regex). */
public class JfrSelfCallers {
    public static void main(String[] a) throws Exception {
        String self = null;
        String skip = "^(java[.]|jdk[.]|com[.]google[.]|forge[.]util[.]collect[.]|forge[.]game[.]card[.]CardCollection|forge[.]util[.])";
        int top = 40;
        int depthOut = 1;
        for (int i = 1; i < a.length; i++) {
            if (a[i].startsWith("self=")) self = a[i].substring(5);
            else if (a[i].startsWith("skip=")) skip = a[i].substring(5);
            else if (a[i].startsWith("top=")) top = Integer.parseInt(a[i].substring(4));
            else if (a[i].startsWith("depth=")) depthOut = Integer.parseInt(a[i].substring(6));
        }
        java.util.regex.Pattern sk = java.util.regex.Pattern.compile(skip);
        Map<String, Integer> m = new HashMap<>();
        int total = 0;
        for (Path file1 : files(Path.of(a[0]))) try (RecordingFile rf = new RecordingFile(file1)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                if (!e.getEventType().getName().equals("jdk.ExecutionSample")) continue;
                RecordedStackTrace st = e.getStackTrace();
                if (st == null) continue;
                List<RecordedFrame> fr = st.getFrames();
                if (fr.isEmpty()) continue;
                String t = fr.get(0).getMethod().getType().getName() + "." + fr.get(0).getMethod().getName();
                if (self != null && !t.matches(self)) continue;
                total++;
                StringBuilder key = new StringBuilder();
                int got = 0;
                for (RecordedFrame f : fr) {
                    String n = f.getMethod().getType().getName() + "." + f.getMethod().getName();
                    if (sk.matcher(n).find()) continue;
                    key.append(got == 0 ? "" : " <- ").append(n.replace("forge.game.", "g.").replace("forge.ai.", "ai.")).append(":").append(f.getLineNumber());
                    if (++got >= depthOut) break;
                }
                m.merge(key.toString(), 1, Integer::sum);
            }
        }
        System.out.println("samples: " + total);
        final int tot = total;
        m.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(top)
                .forEach(en -> System.out.printf("%6d %5.1f%%  %s%n", en.getValue(), 100.0 * en.getValue() / tot, en.getKey()));
    }

    static java.util.List<Path> files(Path p) throws java.io.IOException {
        if (java.nio.file.Files.isDirectory(p)) {
            try (var s = java.nio.file.Files.list(p)) { return s.filter(x -> x.toString().endsWith(".jfr") && x.toFile().length() > 0).sorted().toList(); }
        }
        return java.util.List.of(p);
    }
}
