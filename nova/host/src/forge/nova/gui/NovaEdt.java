package forge.nova.gui;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The host's single "GUI thread".
 *
 * Forge's shared GUI layer (inputs, PlayerControllerHuman, AbstractGuiGame...) was written
 * against Swing's Event Dispatch Thread semantics: some code asserts it runs on the EDT,
 * other code asserts it does not (the game thread blocks on input latches). This class
 * reproduces those semantics without AWT: one daemon thread draining a FIFO queue.
 *
 * Player actions arriving from the browser are executed here, exactly like mouse clicks
 * in the Swing client.
 */
public final class NovaEdt {
    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final Thread thread;

    public NovaEdt() {
        thread = new Thread(this::loop, "Nova-UI");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (true) {
            Runnable r;
            try {
                r = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                r.run();
            } catch (Throwable t) {
                System.err.println("[Nova-UI] Uncaught exception in UI task:");
                t.printStackTrace();
            }
        }
    }

    public boolean isEdt() {
        return Thread.currentThread() == thread;
    }

    public void later(Runnable r) {
        queue.add(r);
    }

    public void nowOrLater(Runnable r) {
        if (isEdt()) {
            r.run();
        } else {
            later(r);
        }
    }

    public void andWait(Runnable r) {
        if (isEdt()) {
            r.run();
            return;
        }
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        queue.add(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        Throwable t = failure.get();
        if (t instanceof RuntimeException re) {
            throw re;
        } else if (t instanceof Error err) {
            throw err;
        } else if (t != null) {
            throw new RuntimeException(t);
        }
    }
}
