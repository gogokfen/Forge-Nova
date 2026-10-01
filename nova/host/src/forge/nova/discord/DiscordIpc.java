package forge.nova.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The local connection to the Discord app that Rich Presence uses (Discord's RPC over IPC): a named pipe on Windows
 * (\\.\pipe\discord-ipc-N), a Unix socket elsewhere. Frames are an opcode and a length (little-endian ints) followed by
 * JSON. Used from a single thread; reads never block (see {@link #poll()}), because Windows serializes a blocking read
 * and a write on the same pipe.
 */
final class DiscordIpc implements Closeable {
    static final int OP_HANDSHAKE = 0, OP_FRAME = 1, OP_CLOSE = 2, OP_PING = 3, OP_PONG = 4;

    private interface Pipe extends Closeable {
        void write(byte[] data) throws IOException;

        /** Bytes that can be read without blocking (a lower bound). */
        int available() throws IOException;

        void readFully(byte[] buf) throws IOException;
    }

    private final Pipe pipe;
    /** the Discord user name from the READY event */
    private String user = "";

    private DiscordIpc(Pipe pipe) {
        this.pipe = pipe;
    }

    /** Connects to the running Discord app and logs in with the application id; null when Discord isn't running. */
    static DiscordIpc open(String appId) throws IOException {
        IOException last = null;
        for (int i = 0; i < 10; i++) {
            Pipe p;
            try {
                p = openPipe(i);
            } catch (IOException e) {
                last = e;
                continue;
            }
            if (p == null) {
                continue;
            }
            DiscordIpc ipc = new DiscordIpc(p);
            try {
                JsonObject hello = new JsonObject();
                hello.addProperty("v", 1);
                hello.addProperty("client_id", appId);
                ipc.send(OP_HANDSHAKE, hello);
                long until = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < until) {
                    Frame f = ipc.poll();
                    if (f == null) {
                        sleep(40);
                        continue;
                    }
                    if (f.op == OP_CLOSE) {
                        String msg = f.json != null && f.json.has("message") ? f.json.get("message").getAsString() : "closed";
                        ipc.close();
                        throw new IOException("Discord refused the application id: " + msg);
                    }
                    if (f.op == OP_FRAME && f.json != null && "READY".equals(str(f.json, "evt"))) {
                        JsonObject data = f.json.has("data") && f.json.get("data").isJsonObject() ? f.json.getAsJsonObject("data") : null;
                        JsonObject u = data != null && data.has("user") && data.get("user").isJsonObject() ? data.getAsJsonObject("user") : null;
                        if (u != null) {
                            String gn = str(u, "global_name");
                            ipc.user = gn.isEmpty() ? str(u, "username") : gn;
                        }
                        return ipc;
                    }
                }
                ipc.close();
                last = new IOException("Discord didn't answer");
            } catch (IOException e) {
                ipc.close();
                last = e;
            }
        }
        if (last != null && last.getMessage() != null && last.getMessage().startsWith("Discord refused")) {
            throw last;
        }
        return null;
    }

    String user() {
        return user;
    }

    void send(int op, JsonObject json) throws IOException {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(op).putInt(body.length).put(body);
        pipe.write(b.array());
    }

    record Frame(int op, JsonObject json) {
    }

    /** The next frame if one has arrived (PINGs are answered here), else null. Never blocks on an empty pipe. */
    Frame poll() throws IOException {
        while (pipe.available() >= 8) {
            byte[] head = new byte[8];
            pipe.readFully(head);
            ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            int op = h.getInt();
            int len = h.getInt();
            if (len < 0 || len > (1 << 20)) {
                throw new IOException("bad frame from Discord");
            }
            byte[] body = new byte[len];
            pipe.readFully(body);
            JsonObject json = null;
            try {
                JsonElement e = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
                json = e.isJsonObject() ? e.getAsJsonObject() : null;
            } catch (RuntimeException ignored) {
                // not JSON
            }
            if (op == OP_PING) {
                send(OP_PONG, json == null ? new JsonObject() : json);
                continue;
            }
            return new Frame(op, json);
        }
        return null;
    }

    @Override
    public void close() {
        try {
            pipe.close();
        } catch (IOException ignored) {
            // already gone
        }
    }

    // ------------------------------------------------------------------ transports

    private static Pipe openPipe(int i) throws IOException {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            File f = new File("\\\\.\\pipe\\discord-ipc-" + i);
            RandomAccessFile raf;
            try {
                raf = new RandomAccessFile(f, "rw");
            } catch (IOException e) {
                return null; // no Discord on this pipe number
            }
            return new Pipe() {
                @Override
                public void write(byte[] data) throws IOException {
                    raf.write(data);
                }

                @Override
                public int available() throws IOException {
                    // for a pipe, the length is what can be read right now
                    return (int) Math.min(Integer.MAX_VALUE, raf.length());
                }

                @Override
                public void readFully(byte[] buf) throws IOException {
                    raf.readFully(buf);
                }

                @Override
                public void close() throws IOException {
                    raf.close();
                }
            };
        }
        for (String dir : unixDirs()) {
            File sock = new File(dir, "discord-ipc-" + i);
            if (!sock.exists()) {
                continue;
            }
            SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                ch.connect(UnixDomainSocketAddress.of(sock.toPath()));
                ch.configureBlocking(false);
            } catch (IOException e) {
                ch.close();
                continue;
            }
            return new Pipe() {
                private final ByteBuffer pending = ByteBuffer.allocate(1 << 16);

                private void fill() throws IOException {
                    if (pending.position() < pending.capacity() && ch.read(pending) < 0) {
                        throw new IOException("Discord closed the connection");
                    }
                }

                @Override
                public void write(byte[] data) throws IOException {
                    ByteBuffer b = ByteBuffer.wrap(data);
                    long until = System.currentTimeMillis() + 3000;
                    while (b.hasRemaining()) {
                        if (ch.write(b) == 0) {
                            if (System.currentTimeMillis() > until) throw new IOException("Discord isn't reading");
                            sleep(5);
                        }
                    }
                }

                @Override
                public int available() throws IOException {
                    fill();
                    return pending.position();
                }

                @Override
                public void readFully(byte[] buf) throws IOException {
                    long until = System.currentTimeMillis() + 3000;
                    while (pending.position() < buf.length) {
                        fill();
                        if (pending.position() < buf.length) {
                            if (System.currentTimeMillis() > until) throw new IOException("incomplete frame from Discord");
                            sleep(5);
                        }
                    }
                    pending.flip();
                    pending.get(buf);
                    pending.compact();
                }

                @Override
                public void close() throws IOException {
                    ch.close();
                }
            };
        }
        return null;
    }

    /** Where Discord (or its Flatpak / Snap builds) puts its sockets. */
    private static List<String> unixDirs() {
        List<String> bases = new ArrayList<>();
        for (String env : new String[]{"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
            String v = System.getenv(env);
            if (v != null && !v.isBlank()) bases.add(v);
        }
        bases.add("/tmp");
        List<String> out = new ArrayList<>();
        for (String b : bases) {
            out.add(b);
            out.add(b + "/app/com.discordapp.Discord");
            out.add(b + "/snap.discord");
        }
        return out;
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
