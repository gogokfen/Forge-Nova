package forge.nova.gui;

import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.nova.net.ClientLink;
import forge.nova.util.JsonOut;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Forge's SoundSystem stays in charge of *which* sound plays for which game event;
 * these proxies just forward the request to the browser, which plays the same files
 * from res/sound and res/music through WebAudio.
 */
public final class NovaAudio {
    private static final AtomicInteger MUSIC_IDS = new AtomicInteger(1);

    private final ClientLink link;
    private final File resDir;
    /** the music track currently playing, so the client's "ended" notification can advance the playlist */
    private volatile Music current;
    /** everyone who hears the match (online: the host and the friends in it) */
    private volatile Supplier<List<ClientLink>> listeners;

    public NovaAudio(ClientLink link, File resDir) {
        this.link = link;
        this.resDir = resDir;
        this.listeners = () -> List.of(link);
    }

    public void setListeners(Supplier<List<ClientLink>> listeners) {
        this.listeners = listeners;
    }

    private void send(String json) {
        List<ClientLink> to;
        try {
            to = listeners.get();
        } catch (RuntimeException e) {
            to = List.of(link);
        }
        for (ClientLink l : to) {
            l.send(json);
        }
    }

    /**
     * Forge multiplies every clip and track by the saved volume setting. The browser applies the
     * user's (live) volume itself, so it only gets the part Forge adds on top, such as fades.
     */
    private static float relative(float volume, ForgePreferences.FPref pref) {
        float saved = FModel.getPreferences().getPrefInt(pref) / 100f;
        return saved > 0.001f ? volume / saved : 1f;
    }

    /** Converts an absolute Forge resource path to the /res/... URL served by the host. */
    String toUrl(String filename) {
        if (filename == null) {
            return null;
        }
        try {
            String abs = new File(filename).getCanonicalPath();
            String root = resDir.getCanonicalPath() + File.separator;
            if (!abs.startsWith(root)) {
                return null;
            }
            return "/res/" + abs.substring(root.length()).replace(File.separatorChar, '/');
        } catch (Exception e) {
            return null;
        }
    }

    public IAudioClip clip(String filename) {
        String url = toUrl(filename);
        return url == null ? null : new Clip(url);
    }

    public IAudioMusic music(String filename) {
        String url = toUrl(filename);
        return new Music(url);
    }

    /** Client reported that the music track with this id finished playing. */
    public void onMusicEnded(int id) {
        Music m = current;
        if (m != null && m.id == id && m.onComplete != null) {
            m.playing = false;
            Runnable r = m.onComplete;
            m.onComplete = null;
            r.run();
        }
    }

    private final class Clip implements IAudioClip {
        private final String url;
        private long lastPlay;

        Clip(String url) {
            this.url = url;
        }

        @Override
        public void play(float volume) {
            lastPlay = System.currentTimeMillis();
            float rel = relative(volume, ForgePreferences.FPref.UI_VOL_SOUNDS);
            send(new JsonOut(96).beginObj().put("t", "sound").put("url", url).put("vol", rel).endObj().toString());
        }

        @Override
        public boolean isDone() {
            // Clips are fire-and-forget in the browser; treat as done shortly after starting.
            return System.currentTimeMillis() - lastPlay > 150;
        }

        @Override
        public void stop() {
        }

        @Override
        public void loop() {
            play(1f);
        }

        @Override
        public void dispose() {
        }
    }

    private final class Music implements IAudioMusic {
        final int id = MUSIC_IDS.getAndIncrement();
        final String url;
        volatile Runnable onComplete;
        volatile boolean playing;
        float volume = 1f;

        Music(String url) {
            this.url = url;
        }

        @Override
        public void play(Runnable onComplete) {
            this.onComplete = onComplete;
            this.playing = url != null;
            current = this;
            if (url != null) {
                send(new JsonOut(128).beginObj().put("t", "music").put("op", "play").put("id", id)
                        .put("url", url).put("vol", volume).endObj().toString());
            }
        }

        @Override
        public void pause() {
            send("{\"t\":\"music\",\"op\":\"pause\",\"id\":" + id + "}");
        }

        @Override
        public void resume() {
            send("{\"t\":\"music\",\"op\":\"resume\",\"id\":" + id + "}");
        }

        @Override
        public void stop() {
            playing = false;
            send("{\"t\":\"music\",\"op\":\"stop\",\"id\":" + id + "}");
        }

        @Override
        public void dispose() {
            stop();
            if (current == this) {
                current = null;
            }
        }

        @Override
        public void setVolume(float v) {
            volume = relative(v, ForgePreferences.FPref.UI_VOL_MUSIC);
            send("{\"t\":\"music\",\"op\":\"volume\",\"id\":" + id + ",\"vol\":" + volume + "}");
        }

        @Override
        public boolean isPlaying() {
            return playing;
        }
    }
}
