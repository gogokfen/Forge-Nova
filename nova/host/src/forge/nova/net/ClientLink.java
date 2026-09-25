package forge.nova.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import forge.nova.util.JsonOut;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * One player's browser connection: the local app window, or a friend playing online.
 *
 * - All outbound messages are written under one lock so that a state flush always
 *   reaches the client before the prompt/dialog that depends on it.
 * - Blocking dialog requests (the engine asks "choose one of these cards" and waits)
 *   are remembered until answered, so a page reload (or a reconnect) re-displays them.
 */
public final class ClientLink {
    public interface Listener {
        /** Called (on a Netty thread) after a new client connected; must re-send full state. */
        void onClientConnected();
    }

    /** dialog ids are unique across all links, so a reply can never answer another player's question */
    private static final AtomicInteger NEXT_DIALOG_ID = new AtomicInteger(1);

    private final String label;
    private final Object sendLock = new Object();
    private volatile Channel channel;
    private volatile Listener listener;
    private volatile long lastSeen = System.currentTimeMillis();
    private volatile int rtt = -1;

    /** insertion ordered so re-sent dialogs keep their stacking order */
    private final Map<Integer, Pending> pending = new LinkedHashMap<>();

    private static final class Pending {
        final String message;
        final CompletableFuture<JsonElement> future = new CompletableFuture<>();

        Pending(String message) {
            this.message = message;
        }
    }

    public ClientLink() {
        this("local");
    }

    public ClientLink(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public boolean isConnected() {
        Channel ch = channel;
        return ch != null && ch.isActive();
    }

    public Channel channel() {
        return channel;
    }

    /** Records traffic from the client (for connection status). */
    public void touch() {
        lastSeen = System.currentTimeMillis();
    }

    public long lastSeen() {
        return lastSeen;
    }

    /** Round-trip time the client measured, in ms (-1: unknown). */
    public void setRtt(int ms) {
        rtt = ms;
    }

    public int rtt() {
        return rtt;
    }

    public void attach(Channel ch) {
        Channel old;
        synchronized (sendLock) {
            old = channel;
            channel = ch;
        }
        touch();
        if (old != null && old != ch && old.isActive()) {
            old.writeAndFlush(new CloseWebSocketFrame(4000, "replaced by a newer window"));
            old.close();
        }
        Listener l = listener;
        if (l != null) {
            l.onClientConnected();
        }
        resendPendingDialogs();
    }

    /** @return true if {@code ch} was this link's current connection */
    public boolean detach(Channel ch) {
        synchronized (sendLock) {
            if (channel == ch) {
                channel = null;
                touch();
                return true;
            }
        }
        return false;
    }

    /** Closes the current connection (if any) with a WebSocket close code the client understands. */
    public void disconnect(int code, String reason) {
        Channel ch;
        synchronized (sendLock) {
            ch = channel;
            channel = null;
        }
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(new CloseWebSocketFrame(code, reason)).addListener(ChannelFutureListener.CLOSE);
        }
    }

    /** Sends a JSON message; silently dropped while no client is connected (state is re-sent on connect). */
    public void send(String json) {
        synchronized (sendLock) {
            Channel ch = channel;
            if (ch != null && ch.isActive()) {
                ch.writeAndFlush(new TextWebSocketFrame(json));
            }
        }
    }

    /** Runs {@code body} while holding the send lock, so several messages go out back-to-back. */
    public void sendAtomically(Runnable body) {
        synchronized (sendLock) {
            body.run();
        }
    }

    /**
     * Shows a modal dialog in the client and blocks the calling thread until it is answered.
     *
     * @return the reply value, or JsonNull if the dialog was cancelled (e.g. game ended)
     */
    public JsonElement request(String kind, Consumer<JsonOut> fields) {
        final int id = NEXT_DIALOG_ID.getAndIncrement();
        JsonOut out = new JsonOut(512);
        out.beginObj().put("t", "dialog").put("id", id).put("kind", kind);
        if (fields != null) {
            fields.accept(out);
        }
        out.endObj();
        Pending p = new Pending(out.toString());
        synchronized (pending) {
            pending.put(id, p);
        }
        send(p.message);
        try {
            JsonElement result = p.future.get();
            return result == null ? JsonNull.INSTANCE : result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return JsonNull.INSTANCE;
        } catch (ExecutionException e) {
            return JsonNull.INSTANCE;
        } finally {
            synchronized (pending) {
                pending.remove(id);
            }
        }
    }

    /** Called from the network thread when the client answered a dialog. */
    public void onReply(int id, JsonElement value) {
        Pending p;
        synchronized (pending) {
            p = pending.get(id);
        }
        if (p != null) {
            p.future.complete(value);
            send("{\"t\":\"dialogClose\",\"id\":" + id + "}");
        }
    }

    public void cancelAllDialogs() {
        List<Integer> ids;
        List<Pending> ps;
        synchronized (pending) {
            ids = new ArrayList<>(pending.keySet());
            ps = new ArrayList<>(pending.values());
            pending.clear();
        }
        for (int i = 0; i < ps.size(); i++) {
            ps.get(i).future.complete(JsonNull.INSTANCE);
            send("{\"t\":\"dialogClose\",\"id\":" + ids.get(i) + "}");
        }
    }

    public boolean hasPendingDialogs() {
        synchronized (pending) {
            return !pending.isEmpty();
        }
    }

    private void resendPendingDialogs() {
        List<Pending> ps;
        synchronized (pending) {
            ps = new ArrayList<>(pending.values());
        }
        for (Pending p : ps) {
            send(p.message);
        }
    }

    @Override
    public String toString() {
        return "ClientLink[" + label + "]";
    }
}
