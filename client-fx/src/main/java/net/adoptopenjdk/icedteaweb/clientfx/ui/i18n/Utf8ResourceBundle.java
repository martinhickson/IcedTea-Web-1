package net.adoptopenjdk.icedteaweb.clientfx.ui.i18n;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * UTF-8 friendly ResourceBundle support
 */
public class Utf8ResourceBundle {

    private final static String ISO_8859_1 = "ISO-8859-1";

    private final static String UTF_8 = "UTF-8";

    /**
     * Gets the unicode friendly resource bundle
     *
     * @param baseNames
     * List of all properties files, which should be given hierarchically (root first)
     * @return Unicode friendly resource bundle
     * @see ResourceBundle#getBundle(String)
     */
    public static final ResourceBundle getBundle(final List<String> baseNames, final Locale locale) {
        List<PropertyResourceBundle> resourceBundles = new ArrayList<>();
        for (String baseName : baseNames) {
            ResourceBundle bundle = ResourceBundle.getBundle(baseName, locale);
            if (bundle instanceof PropertyResourceBundle) {
                resourceBundles.add((PropertyResourceBundle) bundle);
            } else if (baseNames.size() == 1) {
                return bundle;
            } else {
                throw new ClassCastException("Given base names are not properties base names");
            }
        }

        Utf8PropertyResourceBundle prevBundle = null;
        for (PropertyResourceBundle resourceBundle : resourceBundles) {
            Utf8PropertyResourceBundle currentUtf8ResourceBundle = new Utf8PropertyResourceBundle(resourceBundle, prevBundle);
            prevBundle = currentUtf8ResourceBundle;
        }

        return prevBundle;
    }

    /**
     * Resource Bundle that does the hard work
     */
    private static class Utf8PropertyResourceBundle extends ResourceBundle {

        /**
         * Bundle with unicode data
         */
        private final PropertyResourceBundle bundle;

        /**
         * Initializing constructor
         *
         * @param bundle
         */
        private Utf8PropertyResourceBundle(final PropertyResourceBundle bundle, ResourceBundle parent) {
            this.bundle = bundle;
            this.parent = parent;
        }

        @Override
        public String getBaseBundleName() {
            return bundle.getBaseBundleName();
        }

        @Override
        public Locale getLocale() {
            return bundle.getLocale();
        }

        @Override
        public boolean containsKey(String key) {
            return bundle.containsKey(key);
        }

        @Override
        public Set<String> keySet() {
            return bundle.keySet();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Enumeration getKeys() {
            return bundle.getKeys();
        }

        @Override
        protected Object handleGetObject(final String key) {
            try {
                final String value = bundle.getString(key);
                if (value == null)
                    return null;
                try {
                    return new String(value.getBytes(ISO_8859_1), UTF_8);
                } catch (final UnsupportedEncodingException e) {
                    throw new RuntimeException("Encoding not supported", e);
                }
            } catch (MissingResourceException e) {
                return null;
            }
        }
    }
}
