package forge.nova.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.ReferenceCountUtil;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * HTTP + WebSocket server (Netty, already bundled in the Forge jar).
 *
 * <ul>
 * <li>The <b>local</b> listener (127.0.0.1 only) serves the app window: the full API, guarded by a
 * per-run token and a Host header check.</li>
 * <li>The <b>online</b> listener (all interfaces, only while an online room is open) serves friends:
 * the client files, card images and the game WebSocket of their seat. It has no API at all, so a
 * guest can never reach the host's decks, preferences or files.</li>
 * </ul>
 */
public final class NovaServer {

    /** Result of an API or asset request. */
    public record Response(int status, String contentType, byte[] body, boolean cacheable) {
        public static Response json(String json) {
            return new Response(200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8), false);
        }

        public static Response error(int status, String message) {
            String j = "{\"error\":" + new com.google.gson.JsonPrimitive(message == null ? "" : message) + "}";
            return new Response(status, "application/json; charset=utf-8", j.getBytes(StandardCharsets.UTF_8), false);
        }
    }

    public interface Handler {
        /** JSON API: /api/... (token already verified). Runs on a worker thread. */
        Response api(String method, String path, Map<String, String> query, String body);

        /** Dynamic assets (/img, /achv, /avatar, /sleeve). Runs on a worker thread; may block (downloads). */
        Response asset(String path, Map<String, String> query);

        /** A message from the local WebSocket client (network thread). */
        void onClientMessage(JsonObject msg);
    }

    /** Friends connected through the online listener. */
    public interface GuestHandler {
        /** Whether an invite code is valid (checked before a WebSocket upgrade or an image request). */
        boolean acceptsRoom(String code);

        /** Card images for guests (/img); runs on a worker thread. */
        Response guestImage(Map<String, String> query);

        /** A guest's WebSocket is open; {@code query} holds the parameters of the /ws URL. */
        void onGuestOpen(Channel ch, Map<String, String> query);

        void onGuestMessage(Channel ch, JsonObject msg);

        void onGuestClose(Channel ch);
    }

    /** Close codes the client knows (4000 is "replaced by a newer window"). */
    public static final int CLOSE_ROOM_CLOSED = 4001;
    public static final int CLOSE_KICKED = 4002;
    public static final int CLOSE_ROOM_FULL = 4003;
    public static final int CLOSE_REJECTED = 4004;
    public static final int CLOSE_VERSION = 4010;
    public static final int CLOSE_FLOOD = 4020;

    private final int requestedPort;
    private final String token;
    private final File clientDir;
    private final File resDir;
    private final ClientLink link;
    private final Handler handler;
    private final ExecutorService workers = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "Nova-HTTP");
        t.setDaemon(true);
        return t;
    });

    private EventLoopGroup boss;
    private EventLoopGroup io;
    private Channel serverChannel;
    private int port;

    // online listener (guarded by this)
    private Channel onlineChannel;
    private int onlinePort;
    private volatile GuestHandler guests;

    public NovaServer(int port, String token, File clientDir, File resDir, ClientLink link, Handler handler) {
        this.requestedPort = port;
        this.token = token;
        this.clientDir = clientDir;
        this.resDir = resDir;
        this.link = link;
        this.handler = handler;
    }

    public int getPort() {
        return port;
    }

    public void start() throws InterruptedException {
        boss = new NioEventLoopGroup(1);
        io = new NioEventLoopGroup(3);
        ServerBootstrap b = new ServerBootstrap()
                .group(boss, io)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(8 * 1024 * 1024))
                                .addLast(new GuardHandler())
                                .addLast(new WebSocketServerProtocolHandler("/ws", null, true, 16 * 1024 * 1024, false, true))
                                .addLast(new HttpHandler())
                                .addLast(new FrameHandler());
                    }
                });
        int p = requestedPort;
        try {
            serverChannel = b.bind(new InetSocketAddress("127.0.0.1", p)).sync().channel();
        } catch (Exception bindFailure) {
            if (p == 0) {
                throw bindFailure;
            }
            System.out.println("[Nova] Port " + p + " busy, picking a free port");
            serverChannel = b.bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
        }
        port = ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public void stop() {
        stopOnline();
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (io != null) {
            io.shutdownGracefully();
        }
        if (boss != null) {
            boss.shutdownGracefully();
        }
        workers.shutdownNow();
    }

    // ------------------------------------------------------------------ online listener

    /**
     * Starts accepting friends on {@code port} (all network interfaces). Calling it again while it
     * runs on the same port just swaps the handler.
     *
     * @return the port actually used
     * @throws Exception when the port cannot be opened (in use, not allowed...)
     */
    public synchronized int startOnline(int port, GuestHandler gh) throws Exception {
        guests = gh;
        if (onlineChannel != null && onlineChannel.isActive()) {
            if (port == 0 || port == onlinePort) {
                return onlinePort;
            }
            stopOnline();
            guests = gh;
        }
        ServerBootstrap b = new ServerBootstrap()
                .group(boss, io)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new IdleStateHandler(75, 0, 0, TimeUnit.SECONDS))
                                .addLast(new HttpServerCodec(4096, 8192, 16384))
                                .addLast(new TextCompressor())
                                .addLast(new HttpObjectAggregator(64 * 1024))
                                .addLast(new OnlineGuard())
                                .addLast(new WebSocketServerCompressionHandler())
                                .addLast(new WebSocketServerProtocolHandler("/ws", null, true, 1024 * 1024, false, true))
                                .addLast(new OnlineHttpHandler())
                                .addLast(new OnlineFrameHandler());
                    }
                });
        onlineChannel = b.bind(new InetSocketAddress(port)).sync().channel();
        onlinePort = ((InetSocketAddress) onlineChannel.localAddress()).getPort();
        System.out.println("[Nova] Accepting online players on port " + onlinePort);
        return onlinePort;
    }

    /** Stops accepting friends; connected guests are closed by their room. */
    public synchronized void stopOnline() {
        guests = null;
        if (onlineChannel != null) {
            onlineChannel.close();
            onlineChannel = null;
            System.out.println("[Nova] Stopped accepting online players");
        }
    }

    public synchronized boolean isOnline() {
        return onlineChannel != null && onlineChannel.isActive();
    }

    public synchronized int getOnlinePort() {
        return onlinePort;
    }

    // ------------------------------------------------------------------ helpers

    private boolean hostAllowed(FullHttpRequest req) {
        String host = req.headers().get(HttpHeaderNames.HOST);
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("127.0.0.1:" + port) || host.equals("localhost:" + port);
    }

    private boolean tokenOk(FullHttpRequest req, QueryStringDecoder qs) {
        String t = req.headers().get("X-Nova-Token");
        if (t == null) {
            List<String> v = qs.parameters().get("token");
            t = v == null || v.isEmpty() ? null : v.get(0);
        }
        return token.equals(t);
    }

    private static Map<String, String> flatten(QueryStringDecoder qs) {
        Map<String, String> m = new HashMap<>();
        qs.parameters().forEach((k, v) -> m.put(k, v.isEmpty() ? "" : v.get(0)));
        return m;
    }

    private static void write(ChannelHandlerContext ctx, FullHttpRequest req, Response r) {
        ByteBuf content = Unpooled.wrappedBuffer(r.body() == null ? new byte[0] : r.body());
        FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(r.status()), content);
        res.headers().set(HttpHeaderNames.CONTENT_TYPE, r.contentType());
        res.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        res.headers().set(HttpHeaderNames.CACHE_CONTROL, r.cacheable() ? "public, max-age=604800" : "no-store");
        res.headers().set("X-Content-Type-Options", "nosniff");
        res.headers().set("Referrer-Policy", "no-referrer");
        boolean keepAlive = HttpUtil.isKeepAlive(req);
        if (keepAlive) {
            res.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(res);
        } else {
            ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".ico")) return "image/x-icon";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".ogg")) return "audio/ogg";
        if (n.endsWith(".ttf")) return "font/ttf";
        if (n.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    /** Serves a file only if it lies inside {@code root} (no path traversal). */
    private static Response file(File root, String relative, boolean cacheable) {
        try {
            File f = new File(root, relative).getCanonicalFile();
            if (!f.getPath().startsWith(root.getCanonicalPath() + File.separator) || !f.isFile()) {
                return notFound();
            }
            return new Response(200, mime(f.getName()), Files.readAllBytes(f.toPath()), cacheable);
        } catch (IOException e) {
            return notFound();
        }
    }

    private static Response notFound() {
        return new Response(404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), false);
    }

    /** Only harmless, read-only media folders of the Forge install are exposed. */
    private Response resFile(String path) {
        String rel = path.substring(5);
        if (rel.startsWith("sound/") || rel.startsWith("music/") || rel.startsWith("skins/")) {
            return file(resDir, rel.replace('/', File.separatorChar), true);
        }
        return new Response(404, "text/plain", new byte[0], false);
    }

    private void onWorker(ChannelHandlerContext ctx, FullHttpRequest req, java.util.function.Supplier<Response> work) {
        req.retain();
        workers.execute(() -> {
            try {
                Response r;
                try {
                    r = work.get();
                } catch (Throwable t) {
                    t.printStackTrace();
                    r = new Response(500, "text/plain", new byte[0], false);
                }
                write(ctx, req, r);
            } finally {
                req.release();
            }
        });
    }

    // ------------------------------------------------------------------ local handlers

    /** Rejects foreign hosts and unauthenticated WebSocket upgrades before anything else sees them. */
    private final class GuardHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        GuardHandler() {
            super(false);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            if (!hostAllowed(req)) {
                write(ctx, req, new Response(403, "text/plain", "forbidden host".getBytes(StandardCharsets.UTF_8), false));
                ReferenceCountUtil.release(req);
                return;
            }
            QueryStringDecoder qs = new QueryStringDecoder(req.uri());
            if (qs.path().equals("/ws") && !tokenOk(req, qs)) {
                write(ctx, req, new Response(403, "text/plain", "bad token".getBytes(StandardCharsets.UTF_8), false));
                ReferenceCountUtil.release(req);
                return;
            }
            ctx.fireChannelRead(req);
        }
    }

    private final class HttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            final QueryStringDecoder qs = new QueryStringDecoder(req.uri());
            final String path = qs.path();
            final HttpMethod method = req.method();

            if (path.startsWith("/api/")) {
                if (!tokenOk(req, qs)) {
                    write(ctx, req, Response.error(403, "bad token"));
                    return;
                }
                final String body = req.content().toString(StandardCharsets.UTF_8);
                final Map<String, String> query = flatten(qs);
                onWorker(ctx, req, () -> {
                    try {
                        return handler.api(method.name(), path.substring(5), query, body);
                    } catch (Throwable t) {
                        t.printStackTrace();
                        return Response.error(500, String.valueOf(t));
                    }
                });
                return;
            }

            if (path.equals("/img") || path.equals("/achv") || path.startsWith("/avatar/") || path.startsWith("/sleeve/")) {
                final Map<String, String> query = flatten(qs);
                onWorker(ctx, req, () -> handler.asset(path, query));
                return;
            }

            if (path.startsWith("/res/")) {
                write(ctx, req, resFile(path));
                return;
            }

            String rel = path.equals("/") ? "index.html" : path.substring(1);
            // The client is small and changes during development: let the browser revalidate.
            write(ctx, req, file(clientDir, rel.replace('/', File.separatorChar), false));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    private final class FrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                link.attach(ctx.channel());
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (frame instanceof TextWebSocketFrame text) {
                String s = text.text();
                JsonObject msg;
                try {
                    msg = JsonParser.parseString(s).getAsJsonObject();
                } catch (Exception e) {
                    System.err.println("[Nova] Bad client message: " + s);
                    return;
                }
                link.touch();
                try {
                    handler.onClientMessage(msg);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            } else if (frame instanceof PingWebSocketFrame) {
                ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
            } else if (frame instanceof CloseWebSocketFrame) {
                ctx.close();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            link.detach(ctx.channel());
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    // ------------------------------------------------------------------ online handlers

    /** gzip for text responses only (images and sounds are compressed already). */
    private static final class TextCompressor extends HttpContentCompressor {
        @Override
        protected Result beginEncode(HttpResponse res, String acceptEncoding) throws Exception {
            String ct = res.headers().get(HttpHeaderNames.CONTENT_TYPE);
            if (ct == null || !(ct.startsWith("text/") || ct.startsWith("application/json") || ct.startsWith("image/svg"))) {
                return null;
            }
            return super.beginEncode(res, acceptEncoding);
        }
    }

    private static String first(QueryStringDecoder qs, String key) {
        List<String> v = qs.parameters().get(key);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    /** Guests may only read; the game socket needs a valid invite code. */
    private final class OnlineGuard extends SimpleChannelInboundHandler<FullHttpRequest> {
        OnlineGuard() {
            super(false);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            QueryStringDecoder qs = new QueryStringDecoder(req.uri());
            GuestHandler gh = guests;
            String reject = null;
            if (gh == null) {
                reject = "This game is closed.";
            } else if (qs.path().equals("/ws")) {
                if (!gh.acceptsRoom(first(qs, "room"))) {
                    reject = "This invite link is no longer valid.";
                }
            } else if (req.method() != HttpMethod.GET && req.method() != HttpMethod.HEAD) {
                write(ctx, req, new Response(405, "text/plain", new byte[0], false));
                ReferenceCountUtil.release(req);
                return;
            }
            if (reject != null) {
                write(ctx, req, new Response(403, "text/plain; charset=utf-8", reject.getBytes(StandardCharsets.UTF_8), false));
                ReferenceCountUtil.release(req);
                return;
            }
            ctx.fireChannelRead(req);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    private final class OnlineHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            final QueryStringDecoder qs = new QueryStringDecoder(req.uri());
            final String path = qs.path();
            final GuestHandler gh = guests;
            if (gh == null) {
                write(ctx, req, notFound());
                return;
            }
            if (path.equals("/img")) {
                final Map<String, String> query = flatten(qs);
                if (!gh.acceptsRoom(query.get("k"))) {
                    write(ctx, req, new Response(403, "text/plain", new byte[0], false));
                    return;
                }
                onWorker(ctx, req, () -> gh.guestImage(query));
                return;
            }
            if (path.startsWith("/avatar/") || path.startsWith("/sleeve/")) {
                final Map<String, String> query = flatten(qs);
                onWorker(ctx, req, () -> handler.asset(path, query));
                return;
            }
            if (path.startsWith("/res/")) {
                write(ctx, req, resFile(path));
                return;
            }
            if (path.startsWith("/api/") || path.equals("/ws")) {
                write(ctx, req, notFound());
                return;
            }
            String rel = path.equals("/") ? "index.html" : path.substring(1);
            write(ctx, req, file(clientDir, rel.replace('/', File.separatorChar), false));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    private final class OnlineFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        /** token bucket: a burst of 60 messages, then 25 per second */
        private double tokens = 60;
        private long last = System.nanoTime();
        private int dropped;
        private boolean open;

        private boolean allow() {
            long now = System.nanoTime();
            tokens = Math.min(60, tokens + (now - last) / 1e9 * 25);
            last = now;
            if (tokens < 1) {
                return false;
            }
            tokens -= 1;
            return true;
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete hc) {
                GuestHandler gh = guests;
                if (gh == null) {
                    ctx.close();
                    return;
                }
                open = true;
                gh.onGuestOpen(ctx.channel(), flatten(new QueryStringDecoder(hc.requestUri())));
            } else if (evt instanceof IdleStateEvent) {
                ctx.close(); // no ping for a long time: the connection is dead
                return;
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (frame instanceof TextWebSocketFrame text) {
                GuestHandler gh = guests;
                if (gh == null) {
                    ctx.close();
                    return;
                }
                if (!allow()) {
                    if (++dropped > 300) {
                        ctx.writeAndFlush(new CloseWebSocketFrame(CLOSE_FLOOD, "too many messages")).addListener(ChannelFutureListener.CLOSE);
                    }
                    return;
                }
                JsonObject msg;
                try {
                    msg = JsonParser.parseString(text.text()).getAsJsonObject();
                } catch (Exception e) {
                    return;
                }
                try {
                    gh.onGuestMessage(ctx.channel(), msg);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            } else if (frame instanceof PingWebSocketFrame) {
                ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
            } else if (frame instanceof CloseWebSocketFrame) {
                ctx.close();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            GuestHandler gh = guests;
            if (open && gh != null) {
                try {
                    gh.onGuestClose(ctx.channel());
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
