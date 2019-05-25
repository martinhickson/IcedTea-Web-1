package net.adoptopenjdk.icedteaweb.clientfx.ui.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

public class ResourceBundleBasedTranslator implements Translator {

    private final static Logger LOG = LoggerFactory.getLogger(ResourceBundleBasedTranslator.class);
    
    private final ResourceBundle resourceBundle;

    /**
     *
     * @param bundleNames
     * List of all properties file names, which should be given hierarchically (root first)
     */
    public ResourceBundleBasedTranslator(List<String> bundleNames) {
        // Add application resource name as root
        List<String> completeBundleNames = new ArrayList<>();
        completeBundleNames.add(BasicApplicationI18nKeys.RESOURCE_NAME);
        completeBundleNames.addAll(bundleNames);
        resourceBundle = Utf8ResourceBundle.getBundle(completeBundleNames, Locale.getDefault());
    }

    public String get(final String key) {
        try {
            return resourceBundle.getString(key);
        }catch(MissingResourceException e){
            LOG.warn("Can not find resouce for key '{}'", key);
            return key;
        }
    }

}
