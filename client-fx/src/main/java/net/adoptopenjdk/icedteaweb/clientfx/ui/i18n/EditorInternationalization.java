package net.adoptopenjdk.icedteaweb.clientfx.ui.i18n;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;

public final class EditorInternationalization {

    private final static EditorInternationalization INSTANCE = new EditorInternationalization();

    private final List<WeakReference<LocaleObserver>> localeObservers = new CopyOnWriteArrayList<>();

    private EditorInternationalization() {
    }

    public void addObserver(final LocaleObserver observer) {
        final WeakReference<LocaleObserver> observerWeakReference = new WeakReference<>(observer);
        localeObservers.add(observerWeakReference);
    }

    public void setLocale(final Locale locale) {
        Locale.setDefault(locale);
        update();
    }

    public void update() {
        ResourceBundle.clearCache();
        localeObservers.stream()
                .map(o -> o.get())
                .filter(o -> o != null)
                .forEach(o -> o.onLocaleChanged(Locale.getDefault()));
    }

    public static EditorInternationalization getInstance() {
        return INSTANCE;
    }
}
