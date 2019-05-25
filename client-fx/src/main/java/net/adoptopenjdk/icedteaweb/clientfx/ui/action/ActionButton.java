package net.adoptopenjdk.icedteaweb.clientfx.ui.action;

import javafx.css.PseudoClass;

import java.util.Objects;

public class ActionButton extends AbstractActionButton {
    private static final PseudoClass SELECTED_PSEUDO_CLASS = PseudoClass.getPseudoClass("selected");
    public ActionButton(Action action) {
        this(action, true);
    }

    public ActionButton(final Action action, final boolean showText) {
        super(showText);
        Objects.requireNonNull(action);
        init(action);
    }
}
