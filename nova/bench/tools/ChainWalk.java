import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.util.*;

/**
 * Walks an if/else dispatch chain in a method for a given value of a String parameter and reports which code
 * it reaches, proving along the way that every condition evaluated is a pure test of that parameter.
 *
 *   java ChainWalk.java <File.java> <method> <param> <startLine> [--emit3] <value1> [value2 ...]
 *
 * Starting at the first statement of the method at or after startLine (skipping the prologue), statements
 * are followed like the JVM would for param == value:
 *  - if (cond): cond must be decidable (param.equals/startsWith/endsWith/contains/matches/isEmpty with constant
 *    arguments, "lit".equals(param), !, &&, || with Java's short-circuiting, parentheses); the taken branch is
 *    followed, and when the condition is false and there is no else, the walk continues with the next statement;
 *  - any other statement is "branch code": its line is reported as REACHED, with the statements that follow.
 * The first undecidable condition is reported as DYNAMIC, with its branches and the code that follows it.
 *
 * --emit3 prints, for the path of the value, Java code that decides for any other string whether it follows
 * the same path: one method per condition returning 0 (false), 1 (true) or 2 (evaluating it would reach
 * something that is not a pure test of the parameter), and a check that each result equals the value's.
 */
public class ChainWalk {
    static CompilationUnitTree cu;
    static SourcePositions pos;
    static String param;
    static String value;
    static boolean emit3 = false;
    static final List<Object[]> decisions = new ArrayList<>();
    static int tmp;

    public static void main(String[] a) throws Exception {
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fm = jc.getStandardFileManager(null, null, null);
        Iterable<? extends JavaFileObject> files = fm.getJavaFileObjects(new File(a[0]));
        JavacTask task = (JavacTask) jc.getTask(null, fm, null, List.of("-proc:none"), null, files);
        cu = task.parse().iterator().next();
        pos = com.sun.source.util.Trees.instance(task).getSourcePositions();
        String method = a[1];
        param = a[2];
        int startLine = Integer.parseInt(a[3]);
        MethodTree mt = findMethod(cu, method);
        if (mt == null) throw new IllegalStateException("method not found");
        for (int i = 4; i < a.length; i++) {
            if (a[i].equals("--emit3")) {
                emit3 = true;
                continue;
            }
            value = a[i];
            decisions.clear();
            walkMethod(mt, startLine);
            if (emit3) {
                emitChecker();
            }
        }
    }

    static MethodTree findMethod(Tree t, String name) {
        final MethodTree[] found = {null};
        new com.sun.source.util.TreeScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree m, Void v) {
                if (m.getName().contentEquals(name) && found[0] == null) found[0] = m;
                return super.visitMethod(m, v);
            }
        }.scan(t, null);
        return found[0];
    }

    static long line(Tree t) {
        return cu.getLineMap().getLineNumber(pos.getStartPosition(cu, t));
    }

    /** a frame of the walk: a block's statements and the index of the next one */
    record Frame(List<? extends StatementTree> stmts, int idx) {
    }

    static void printRest(Frame f, Deque<Frame> stack, int from) {
        List<String> rest = new ArrayList<>();
        for (int k = from; k < f.stmts.size(); k++) rest.add("line " + line(f.stmts.get(k)) + ": " + oneLine(f.stmts.get(k)));
        for (Frame g : stack) {
            for (int k = g.idx; k < g.stmts.size(); k++) rest.add("line " + line(g.stmts.get(k)) + ": " + oneLine(g.stmts.get(k)));
        }
        for (String r : rest) System.out.println("         " + r);
    }

    static void walkMethod(MethodTree mt, int startLine) {
        List<? extends StatementTree> body = mt.getBody().getStatements();
        int i = 0;
        while (i < body.size() && line(body.get(i)) < startLine) i++;
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(body, i));
        while (!stack.isEmpty()) {
            Frame f = stack.pop();
            if (f.idx >= f.stmts.size()) continue; // block finished: continue with the enclosing block
            StatementTree s = f.stmts.get(f.idx);
            if (s instanceof BlockTree b) {
                stack.push(new Frame(f.stmts, f.idx + 1));
                stack.push(new Frame(b.getStatements(), 0));
                continue;
            }
            if (s instanceof IfTree ifs) {
                Boolean v = eval(ifs.getCondition());
                if (v == null) {
                    System.out.printf("%-28s DYNAMIC condition at line %d after %d decidable conditions: %s%n", value, line(ifs), decisions.size(), oneLine(ifs.getCondition()));
                    for (int k = Math.max(0, decisions.size() - 3); k < decisions.size(); k++) {
                        Object[] d = decisions.get(k);
                        System.out.println("      decided line " + d[0] + " " + d[2] + " " + oneLine((Tree) d[1]));
                    }
                    System.out.println("      then-branch: " + oneLine(ifs.getThenStatement()));
                    System.out.println("      else-branch: " + (ifs.getElseStatement() == null ? "-" : oneLine(ifs.getElseStatement())));
                    System.out.println("      continuation after this if statement:");
                    printRest(f, stack, f.idx + 1);
                    return;
                }
                decisions.add(new Object[]{line(ifs), ifs.getCondition(), v});
                stack.push(new Frame(f.stmts, f.idx + 1));
                StatementTree next = v ? ifs.getThenStatement() : ifs.getElseStatement();
                if (next != null) {
                    stack.push(new Frame(List.of(next), 0));
                }
                continue;
            }
            System.out.printf("%-28s REACHED after %d decidable conditions%n", value, decisions.size());
            printRest(f, stack, f.idx);
            return;
        }
        System.out.printf("%-28s fell off the end of the method%n", value);
    }

    static void emitChecker() {
        StringBuilder methods = new StringBuilder();
        System.out.println("   // path of \"" + value + "\": " + decisions.size() + " decisions");
        for (Object[] d : decisions) {
            long ln = (Long) d[0];
            String m = "c" + ln;
            System.out.println("      if (" + m + "(property) != " + (((Boolean) d[2]) ? 1 : 0) + ") return false; // line " + ln + ": " + oneLine((Tree) d[1]));
            tmp = 0;
            StringBuilder b = new StringBuilder();
            String r = gen((ExpressionTree) d[1], b, "        ");
            methods.append("    private static int ").append(m).append("(String property) { // line ").append(ln).append("\n")
                    .append(b).append("        return ").append(r).append(";\n    }\n\n");
        }
        System.out.println("METHODS");
        System.out.print(methods);
    }

    /**
     * Java statements computing a 3-valued result of a condition with Java's evaluation order and
     * short-circuiting: 0 = false, 1 = true, 2 = the evaluation would reach something that is not a pure test
     * of the parameter (the caller must then not use the fast path). Returns the variable holding it.
     */
    static String gen(ExpressionTree e, StringBuilder b, String ind) {
        if (e instanceof ParenthesizedTree p) return gen(p.getExpression(), b, ind);
        String v = "v" + (tmp++);
        if (e instanceof UnaryTree u && u.getKind() == Tree.Kind.LOGICAL_COMPLEMENT) {
            String x = gen(u.getExpression(), b, ind);
            b.append(ind).append("int ").append(v).append(" = ").append(x).append(" == 2 ? 2 : 1 - ").append(x).append(";\n");
            return v;
        }
        if (e instanceof BinaryTree bt && (bt.getKind() == Tree.Kind.CONDITIONAL_AND || bt.getKind() == Tree.Kind.CONDITIONAL_OR)) {
            boolean and = bt.getKind() == Tree.Kind.CONDITIONAL_AND;
            String l = gen(bt.getLeftOperand(), b, ind);
            b.append(ind).append("int ").append(v).append(" = ").append(l).append(";\n");
            b.append(ind).append("if (").append(l).append(and ? " == 1" : " == 0").append(") {\n");
            String r = gen(bt.getRightOperand(), b, ind + "    ");
            b.append(ind).append("    ").append(v).append(" = ").append(r).append(";\n");
            b.append(ind).append("}\n");
            return v;
        }
        if (isPureTest(e)) {
            b.append(ind).append("int ").append(v).append(" = (").append(e.toString().replaceAll("\\s+", " ")).append(") ? 1 : 0;\n");
        } else {
            b.append(ind).append("int ").append(v).append(" = 2; // not a pure test of the property: ")
                    .append(e.toString().replaceAll("\\s+", " ")).append("\n");
        }
        return v;
    }

    /** a single test that eval() can decide for any value of the parameter */
    static boolean isPureTest(ExpressionTree e) {
        if (e instanceof BinaryTree || e instanceof UnaryTree || e instanceof ParenthesizedTree) return false;
        String keep = value;
        try {
            value = "";
            if (eval(e) == null) return false;
            value = "x";
            return eval(e) != null;
        } finally {
            value = keep;
        }
    }

    static String oneLine(Tree t) {
        String s = t.toString().replaceAll("\\s+", " ");
        return s.length() > 150 ? s.substring(0, 150) + "..." : s;
    }

    static boolean isParam(ExpressionTree e) {
        while (e instanceof ParenthesizedTree p) e = p.getExpression();
        return e instanceof IdentifierTree id && id.getName().contentEquals(param);
    }

    static String lit(ExpressionTree e) {
        while (e instanceof ParenthesizedTree p) e = p.getExpression();
        if (e instanceof LiteralTree l && l.getValue() instanceof String str) return str;
        return null;
    }

    /** TRUE/FALSE if decidable for param == value, null otherwise */
    static Boolean eval(ExpressionTree e) {
        if (e instanceof ParenthesizedTree p) return eval(p.getExpression());
        if (e instanceof UnaryTree u && u.getKind() == Tree.Kind.LOGICAL_COMPLEMENT) {
            Boolean v = eval(u.getExpression());
            return v == null ? null : !v;
        }
        if (e instanceof BinaryTree b) {
            if (b.getKind() == Tree.Kind.CONDITIONAL_AND) {
                Boolean l = eval(b.getLeftOperand());
                if (l == null) return null;
                if (!l) return false;
                return eval(b.getRightOperand());
            }
            if (b.getKind() == Tree.Kind.CONDITIONAL_OR) {
                Boolean l = eval(b.getLeftOperand());
                if (l == null) return null;
                if (l) return true;
                return eval(b.getRightOperand());
            }
            return null;
        }
        if (e instanceof MethodInvocationTree mi && mi.getMethodSelect() instanceof MemberSelectTree ms) {
            String name = ms.getIdentifier().toString();
            List<? extends ExpressionTree> args = mi.getArguments();
            if (isParam(ms.getExpression())) {
                if (args.isEmpty() && name.equals("isEmpty")) return value.isEmpty();
                if (args.size() == 1) {
                    String arg = lit(args.get(0));
                    if (arg == null) return null;
                    switch (name) {
                        case "equals": return value.equals(arg);
                        case "startsWith": return value.startsWith(arg);
                        case "endsWith": return value.endsWith(arg);
                        case "contains": return value.contains(arg);
                        case "matches": return value.matches(arg);
                        case "equalsIgnoreCase": return value.equalsIgnoreCase(arg);
                        default: return null;
                    }
                }
                return null;
            }
            String recv = lit(ms.getExpression());
            if (recv != null && args.size() == 1 && isParam(args.get(0))) {
                if (name.equals("equals")) return recv.equals(value);
                if (name.equals("equalsIgnoreCase")) return recv.equalsIgnoreCase(value);
            }
        }
        return null;
    }
}
