package net.adoptopenjdk.icedteaweb.clientfx.ui.action;

import javafx.geometry.Point2D;
import net.adoptopenjdk.icedteaweb.clientfx.ui.i18n.Translator;
import org.kordamp.ikonli.Ikon;

import java.util.List;


public class MenuActionButton extends ActionButton {

    private final ActionPopup popup;

    public MenuActionButton(final String nameKey, final String descriptionKey, final Ikon icon, final Translator translator, List<Action> actions) {
        super(new SimpleAction(nameKey, descriptionKey, icon, a -> {}, translator));
        popup = new ActionPopup(actions);
        setOnAction(e -> showPopup());
    }

    public MenuActionButton(final String nameKey, final String descriptionKey, final Ikon icon, final Translator translator, List<Action> actions, final boolean showText) {
        super(new SimpleAction(nameKey, descriptionKey, icon, a -> {}, translator), showText);
        popup = new ActionPopup(actions);
        setOnAction(e -> showPopup());
    }

    private void showPopup() {
        final Point2D pos = localToScreen(getWidth(), getHeight() / 2);
        popup.setAutoHide(true);
        popup.show(this, pos.getX(), pos.getY());
    }
}
