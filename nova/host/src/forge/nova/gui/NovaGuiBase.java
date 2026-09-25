package forge.nova.gui;

import forge.ImageKeys;
import forge.gamemodes.match.HostedMatch;
import forge.gui.FThreads;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.BuildInfo;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;
import org.jupnp.UpnpServiceConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Platform services for a headless Forge whose UI lives in a browser.
 * Replaces forge.GuiDesktop (Swing) / forge.GuiMobile (libGDX).
 */
public final class NovaGuiBase implements IGuiBase {
    /** Minimal ISkinImage: the engine only passes these around, the browser draws its own art. */
    private static final ISkinImage NO_IMAGE = new ISkinImage() {
    };

    private final String assetsDir;
    private final NovaEdt edt;
    private final NovaImageFetcher imageFetcher;
    private final Dialogs dialogs;
    private NovaAudio audio;
    private Supplier<IGuiGame> guiGameFactory;
    /** picks the window a platform-level question goes to (online: the player who is deciding) */
    private volatile Supplier<Dialogs> dialogRouter;
    private final int avatarCount;
    private final int sleeveCount;

    public NovaGuiBase(String assetsDir, NovaEdt edt, Dialogs dialogs, int avatarCount, int sleeveCount) {
        this.assetsDir = assetsDir;
        this.edt = edt;
        this.dialogs = dialogs;
        this.imageFetcher = new NovaImageFetcher(edt);
        this.avatarCount = Math.max(1, avatarCount);
        this.sleeveCount = Math.max(1, sleeveCount);
    }

    public void setAudio(NovaAudio audio) {
        this.audio = audio;
    }

    /** Routes the engine's platform-level dialogs (SGuiChoose, SOptionPane); null answers use the local window. */
    public void setDialogRouter(Supplier<Dialogs> router) {
        this.dialogRouter = router;
    }

    private Dialogs dlg() {
        Supplier<Dialogs> r = dialogRouter;
        Dialogs d = r == null ? null : r.get();
        return d == null ? dialogs : d;
    }

    /** Factory for spectator GUIs (AI vs AI matches). */
    public void setGuiGameFactory(Supplier<IGuiGame> factory) {
        this.guiGameFactory = factory;
    }

    public NovaImageFetcher getNovaImageFetcher() {
        return imageFetcher;
    }

    public NovaEdt getEdt() {
        return edt;
    }

    // ------------------------------------------------------------------ identity & threading

    @Override public boolean isRunningOnDesktop() { return true; }
    @Override public boolean isLibgdxPort() { return false; }
    @Override public String getCurrentVersion() { return BuildInfo.getVersionString(); }
    @Override public String getAssetsDir() { return assetsDir; }
    @Override public ImageFetcher getImageFetcher() { return imageFetcher; }

    @Override public void invokeInEdtNow(Runnable proc) { proc.run(); }
    @Override public void invokeInEdtLater(Runnable proc) { edt.later(proc); }
    @Override public void invokeInEdtAndWait(Runnable proc) { edt.andWait(proc); }
    @Override public boolean isGuiThread() { return edt.isEdt(); }
    @Override public void runBackgroundTask(String message, Runnable task) { FThreads.invokeInBackgroundThread(task); }

    // ------------------------------------------------------------------ images & skin

    @Override public ISkinImage getSkinIcon(FSkinProp skinProp) { return skinProp == null ? null : NO_IMAGE; }
    @Override public ISkinImage getUnskinnedIcon(String path) { return NO_IMAGE; }
    @Override public ISkinImage getCardArt(PaperCard card, boolean backFace) { return null; }

    @Override
    public ISkinImage createLayeredImage(PaperCard paperCard, FSkinProp background, String overlayFilename, float opacity) {
        return NO_IMAGE;
    }

    @Override
    public void clearImageCache() {
        ImageKeys.clearMissingCards();
    }

    /** The browser renders mana symbols itself from the {X} notation, so text passes through unchanged. */
    @Override public String encodeSymbols(String str, boolean formatReminderText) { return str; }
    @Override public int getAvatarCount() { return avatarCount; }
    @Override public int getSleevesCount() { return sleeveCount; }
    @Override public float getScreenScale() { return 1f; }
    @Override public void preventSystemSleep(boolean preventSleep) { }

    // ------------------------------------------------------------------ dialogs (platform-level)

    /** Achievements ("You earned…") are the host's; a toast, so the UI thread never waits on it. */
    @Override
    public void showImageDialog(ISkinImage image, String message, String title) {
        dialogs.notify(title, message, false);
    }

    @Override
    public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) {
        return dlg().option(message, title, options, defaultOption, null);
    }

    @Override
    public String showInputDialog(String message, String title, FSkinProp icon, String initialInput, List<String> inputOptions, boolean isNumeric) {
        return dlg().input(message, title, initialInput, inputOptions, isNumeric);
    }

    @Override
    public <T> List<T> getChoices(String message, int min, int max, Collection<T> choices, Collection<T> selected, FSerializableFunction<T, String> display) {
        return dlg().choose(message, min, max, choices == null ? new ArrayList<>() : new ArrayList<>(choices),
                selected == null ? null : new ArrayList<>(selected), display == null ? null : display::apply, null);
    }

    @Override
    public <T> List<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax, List<T> sourceChoices, List<T> destChoices) {
        return dlg().order(title, top, remainingObjectsMin, remainingObjectsMax, sourceChoices, destChoices, null, false, false).items();
    }

    @Override
    public void showCardList(String title, String message, List<PaperCard> list) {
        dlg().choose(title + (message == null || message.isEmpty() ? "" : " - " + message), -1, -1, list, null, null, null);
    }

    @Override
    public boolean showBoxedProduct(String title, String message, List<PaperCard> list) {
        showCardList(title, message, list);
        return false;
    }

    @Override
    public PaperCard chooseCard(String title, String message, List<PaperCard> list) {
        List<PaperCard> r = dlg().choose(title, 1, 1, list, null, null, null);
        return r.isEmpty() ? null : r.get(0);
    }

    @Override
    public void showBugReportDialog(String title, String text, boolean showExitAppBtn) {
        System.err.println("[Nova] " + title + "\n" + text);
        // engine errors can come from any thread (even the UI thread); don't block the game on a click
        String first = text == null ? "" : text.strip().split("\n", 2)[0];
        dialogs.notify(title, first + " (details in nova\\logs\\host.log)", true);
    }

    @Override public String showFileDialog(String title, String defaultDir) { return null; }
    @Override public File getSaveFile(File defaultFile) { return defaultFile; }

    @Override
    public void download(GuiDownloadService service, Consumer<Boolean> callback) {
        if (callback != null) {
            callback.accept(false);
        }
    }

    @Override public void copyToClipboard(String text) { }

    @Override
    public void browseToUrl(String url) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (Exception e) {
            System.err.println("[Nova] cannot open " + url);
        }
    }

    // ------------------------------------------------------------------ audio

    @Override
    public boolean isSupportedAudioFormat(File file) {
        String n = file.getName().toLowerCase(Locale.ROOT);
        return n.endsWith(".wav") || n.endsWith(".mp3") || n.endsWith(".ogg");
    }

    @Override
    public IAudioClip createAudioClip(String filename) {
        if (audio == null || filename == null) {
            return null;
        }
        // Forge names effects without folder or extension ("add_counter"); scripted ones may be full paths
        File f = new File(filename);
        if (!f.isFile()) {
            f = forge.sound.SoundSystem.instance.getSoundResource(filename);
        }
        return f == null || !f.isFile() ? null : audio.clip(f.getAbsolutePath());
    }

    @Override
    public IAudioMusic createAudioMusic(String filename) {
        return audio == null ? null : audio.music(filename);
    }

    @Override public void startAltSoundSystem(String filename, boolean isSynchronized) { }

    // ------------------------------------------------------------------ screens we don't have

    @Override public void showSpellShop() { }
    @Override public void showBazaar() { }

    @Override
    public IGuiGame getNewGuiGame() {
        return guiGameFactory == null ? null : guiGameFactory.get();
    }

    @Override
    public HostedMatch hostMatch() {
        return new HostedMatch();
    }

    @Override public UpnpServiceConfiguration getUpnpPlatformService() { return null; }
    @Override public boolean hasNetGame() { return false; }
}
