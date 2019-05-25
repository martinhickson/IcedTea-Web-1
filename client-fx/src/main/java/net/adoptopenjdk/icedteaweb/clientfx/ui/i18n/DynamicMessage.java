package net.adoptopenjdk.icedteaweb.clientfx.ui.i18n;

import java.util.Objects;

public class DynamicMessage {

    private final String key;

    private final Object[] values;

    public DynamicMessage(final String key, final Object... values) {
        this.key = key;
        this.values = values;
    }

    public String getKey() {
        return key;
    }

    public Object[] getValues() {
        return values;
    }

    public String getText(final ResourceBundleBasedTranslator translator) {
        Objects.requireNonNull(translator);
        return translator.get(this);
    }
}
