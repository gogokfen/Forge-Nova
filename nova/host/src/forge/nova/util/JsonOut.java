package forge.nova.util;

/**
 * Minimal, allocation-light streaming JSON writer.
 *
 * The state synchroniser serialises every visible card on every flush, so this
 * avoids building intermediate trees (Gson JsonObject) and writes straight into
 * a reusable StringBuilder. Commas are inserted automatically.
 */
public final class JsonOut {
    private final StringBuilder sb;
    /** true when the next value/key in the current container needs a leading comma */
    private boolean needComma;

    public JsonOut() {
        this(256);
    }

    public JsonOut(int capacity) {
        sb = new StringBuilder(capacity);
    }

    public JsonOut reset() {
        sb.setLength(0);
        needComma = false;
        return this;
    }

    private void sep() {
        if (needComma) {
            sb.append(',');
        }
    }

    public JsonOut beginObj() {
        sep();
        sb.append('{');
        needComma = false;
        return this;
    }

    public JsonOut endObj() {
        sb.append('}');
        needComma = true;
        return this;
    }

    public JsonOut beginArr() {
        sep();
        sb.append('[');
        needComma = false;
        return this;
    }

    public JsonOut endArr() {
        sb.append(']');
        needComma = true;
        return this;
    }

    /** Writes a key; the following call must write its value. */
    public JsonOut key(String k) {
        sep();
        quote(k);
        sb.append(':');
        needComma = false;
        return this;
    }

    public JsonOut beginObj(String k) {
        key(k);
        return beginObj();
    }

    public JsonOut beginArr(String k) {
        key(k);
        return beginArr();
    }

    public JsonOut val(String v) {
        sep();
        if (v == null) {
            sb.append("null");
        } else {
            quote(v);
        }
        needComma = true;
        return this;
    }

    public JsonOut val(int v) {
        sep();
        sb.append(v);
        needComma = true;
        return this;
    }

    public JsonOut val(long v) {
        sep();
        sb.append(v);
        needComma = true;
        return this;
    }

    public JsonOut val(double v) {
        sep();
        if (Double.isFinite(v)) {
            sb.append(v);
        } else {
            sb.append("null");
        }
        needComma = true;
        return this;
    }

    public JsonOut val(boolean v) {
        sep();
        sb.append(v);
        needComma = true;
        return this;
    }

    /** Appends pre-serialised JSON verbatim as a value. */
    public JsonOut raw(String json) {
        sep();
        sb.append(json);
        needComma = true;
        return this;
    }

    public JsonOut nul() {
        sep();
        sb.append("null");
        needComma = true;
        return this;
    }

    public JsonOut put(String k, String v) {
        return key(k).val(v);
    }

    public JsonOut put(String k, int v) {
        return key(k).val(v);
    }

    public JsonOut put(String k, long v) {
        return key(k).val(v);
    }

    public JsonOut put(String k, double v) {
        return key(k).val(v);
    }

    public JsonOut put(String k, boolean v) {
        return key(k).val(v);
    }

    /** Only writes the key when the value is non-null and non-empty (keeps payloads small). */
    public JsonOut putOpt(String k, String v) {
        if (v != null && !v.isEmpty()) {
            key(k).val(v);
        }
        return this;
    }

    /** Only writes the key when the flag is set (absent == false on the client). */
    public JsonOut flag(String k, boolean v) {
        if (v) {
            key(k).val(true);
        }
        return this;
    }

    /** Only writes the key when the value is non-zero. */
    public JsonOut putNz(String k, int v) {
        if (v != 0) {
            key(k).val(v);
        }
        return this;
    }

    public JsonOut rawPut(String k, String json) {
        key(k);
        return raw(json);
    }

    private void quote(String s) {
        sb.append('"');
        final int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    public int length() {
        return sb.length();
    }

    @Override
    public String toString() {
        return sb.toString();
    }
}
