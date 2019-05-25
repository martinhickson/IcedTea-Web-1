package net.adoptopenjdk.icedteaweb.clientfx.ui.i18n;

import java.util.Locale;

@FunctionalInterface
public interface LocaleObserver {

    void onLocaleChanged(Locale locale);
}
