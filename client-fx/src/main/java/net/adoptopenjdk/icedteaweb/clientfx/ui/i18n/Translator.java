package net.adoptopenjdk.icedteaweb.clientfx.ui.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.MessageFormat;

public interface Translator {

    Logger LOG = LoggerFactory.getLogger(Translator.class);
    
    String get(String key);

    default String get(final DynamicMessage message) {
        if(message == null) {
            LOG.warn("Can not translate 'null' message");
            return null;
        }
        return MessageFormat.format(get(message.getKey()), message.getValues());
    }
}
