package net.sourceforge.jnlp.util;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Window;
import java.awt.event.InvocationEvent;
import java.lang.reflect.Field;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.AbstractButton;
import javax.swing.JDialog;
import sun.awt.AppContext;

/**
 * Privileged helper used by the autodetect IT javaagent to press
 * Apply / Autodetect on missing-JRE dialogs via {@link AbstractButton#doClick()}.
 *
 * <p>Lives in the icedtea-web codebase (shell {@link java.security.CodeSource}) so
 * {@link AccessController#doPrivileged} elevates past {@code JNLPSecurityManager}
 * restrictions that apply to an external agent jar.
 */
public final class AutodetectDialogClicker {

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean ARMED = new AtomicBoolean();
    private static final AtomicBoolean CLICKED_OK = new AtomicBoolean();
    private static final Map<Window, Boolean> CLICKED = new IdentityHashMap<>();

    private AutodetectDialogClicker() {
    }

    /** Start the background EDT poster (idempotent). Safe before the security manager exists. */
    public static void start() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        // Create the worker inside doPrivileged so Thread.inheritedAccessControlContext
        // is the shell AllPermission context — not the javaagent's restricted context.
        AccessController.doPrivileged(new StartWorker());
    }

    private static final class StartWorker implements PrivilegedAction<Void> {
        @Override
        public Void run() {
            Thread retry = new Thread(new RetryLoop(), "itw-autodetect-dialog-clicker");
            retry.setDaemon(true);
            retry.start();
            return null;
        }
    }

    /** Arm posting once {@code Launcher.resolveMissingSuitableJre} is entered. */
    public static void arm() {
        start();
        ARMED.set(true);
        System.err.println("[itw-autodetect-dialog-clicker] armed");
    }

    private static final class RetryLoop implements Runnable {
        @Override
        public void run() {
            for (int i = 0; i < 300 && !CLICKED_OK.get(); i++) {
                if (ARMED.get()) {
                    postClickPasses();
                }
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!CLICKED_OK.get()) {
                System.err.println("[itw-autodetect-dialog-clicker] EDT doClick gave up");
            }
        }
    }

    private static void postClickPasses() {
        AccessController.doPrivileged(new PostClicks());
    }

    private static final class PostClicks implements PrivilegedAction<Void> {
        @Override
        public Void run() {
            try {
                for (Object ctx : getAppContexts()) {
                    EventQueue queue = eventQueueOf(ctx);
                    if (queue == null) {
                        continue;
                    }
                    AWTEvent event = new InvocationEvent(new Object(), new EdtClick());
                    queue.postEvent(event);
                }
            } catch (Throwable ex) {
                System.err.println("[itw-autodetect-dialog-clicker] post: " + ex);
            }
            return null;
        }
    }

    private static final class EdtClick implements Runnable {
        @Override
        public void run() {
            AccessController.doPrivileged(new EdtClickAction());
        }
    }

    private static final class EdtClickAction implements PrivilegedAction<Void> {
        @Override
        public Void run() {
            try {
                clickMatchingWindows(Window.getWindows());
            } catch (Throwable ex) {
                System.err.println("[itw-autodetect-dialog-clicker] edt: " + ex);
            }
            return null;
        }
    }

    private static void clickMatchingWindows(Window[] windows) {
        if (windows == null || CLICKED_OK.get()) {
            return;
        }
        for (Window window : windows) {
            if (window == null || !window.isShowing()) {
                continue;
            }
            synchronized (CLICKED) {
                if (CLICKED.containsKey(window)) {
                    continue;
                }
            }
            String title = titleOf(window);
            if (title == null) {
                continue;
            }
            String titleLower = title.toLowerCase(Locale.ROOT);
            String buttonText;
            if (titleLower.contains("apply detected jdk")) {
                buttonText = "Apply";
            } else if (titleLower.contains("jdk required")) {
                buttonText = "Autodetect";
            } else {
                continue;
            }
            AbstractButton button = findButton(window, buttonText);
            if (button == null) {
                continue;
            }
            button.doClick();
            synchronized (CLICKED) {
                CLICKED.put(window, Boolean.TRUE);
            }
            CLICKED_OK.set(true);
            System.err.println("[itw-autodetect-dialog-clicker] Clicked '" + buttonText
                    + "' on '" + title + "' via AbstractButton.doClick()");
            return;
        }
    }

    private static Collection<?> getAppContexts() {
        try {
            Field mapField = AppContext.class.getDeclaredField("threadGroup2appContext");
            mapField.setAccessible(true);
            Object map = mapField.get(null);
            if (map instanceof Map) {
                return new ArrayList<>(((Map<?, ?>) map).values());
            }
        } catch (ReflectiveOperationException ex) {
            AppContext ctx = AppContext.getAppContext();
            if (ctx != null) {
                return Collections.singletonList(ctx);
            }
        }
        return Collections.emptyList();
    }

    private static EventQueue eventQueueOf(Object appContext) {
        try {
            Object key = AppContext.EVENT_QUEUE_KEY;
            Object queue = ((AppContext) appContext).get(key);
            return queue instanceof EventQueue ? (EventQueue) queue : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String titleOf(Window window) {
        if (window instanceof JDialog) {
            return ((JDialog) window).getTitle();
        }
        if (window instanceof java.awt.Frame) {
            return ((java.awt.Frame) window).getTitle();
        }
        return window.getName();
    }

    private static AbstractButton findButton(Container root, String buttonText) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton) {
                AbstractButton button = (AbstractButton) child;
                String text = button.getText();
                if (text != null) {
                    String plain = text.replaceAll("<[^>]*>", "").replace("&", "").trim();
                    if (plain.equalsIgnoreCase(buttonText) && button.isEnabled() && button.isShowing()) {
                        return button;
                    }
                }
            }
            if (child instanceof Container) {
                AbstractButton nested = findButton((Container) child, buttonText);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}
