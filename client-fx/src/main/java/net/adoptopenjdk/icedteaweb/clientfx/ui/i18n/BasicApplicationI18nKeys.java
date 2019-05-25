package net.adoptopenjdk.icedteaweb.clientfx.ui.i18n;

import java.util.Locale;

public interface BasicApplicationI18nKeys {

    String RESOURCE_NAME= "BasicApplication";

    String DOT = ".";

    String NAME = "name";

    String DESCRIPTION = "description";

    String ACTION_LOCALE = "action.locale";


    static String getActionLocaleName(Locale locale) {
        return ACTION_LOCALE + DOT + locale.getDisplayLanguage(Locale.ENGLISH).toLowerCase() + DOT + NAME;
    }

    static String getActionLocaleDescription(Locale locale){
        return ACTION_LOCALE + DOT + locale.getDisplayLanguage(Locale.ENGLISH).toLowerCase() + DOT + DESCRIPTION;
    }
}
