package net.adoptopenjdk.icedteaweb.clientfx.ui.action;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import org.kordamp.ikonli.Ikon;

import java.util.function.Consumer;

public interface Action {

    Ikon getIcon();

    ObjectProperty<Ikon> iconProperty();

    String getName();

    StringProperty nameProperty();

    String getDescription();

    StringProperty descriptionProperty();

    boolean isActive();

    BooleanProperty activeProperty();

    void run();

    void addActionTriggeredListener(Consumer<Action> listener);
}
