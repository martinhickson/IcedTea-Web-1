package net.adoptopenjdk.icedteaweb.clientfx.ui.action;

import javafx.beans.binding.Bindings;
import javafx.css.PseudoClass;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.Objects;

public class AbstractActionButton extends Button {
    private static final String  ICON_STYLE_CLASS          = "action-icon";
    private static final String  ACTION_BUTTON_STYLE_CLASS = "action-button";
    private        final boolean showText;


    public AbstractActionButton(final boolean showText) {
        this.showText = showText;
    }


    protected void init(final Action action) {
        if(showText) { textProperty().bind(action.nameProperty()); }
        tooltipProperty().bind(Bindings.createObjectBinding(()-> new Tooltip(action.getDescription()), action.descriptionProperty()));
        disableProperty().bind(action.activeProperty().not());

        graphicProperty().bind(Bindings.createObjectBinding(() -> {
            final Ikon icon = action.getIcon();
            if(icon != null) {
                FontIcon iconNode = FontIcon.of(icon);
                iconNode.getStyleClass().add(ICON_STYLE_CLASS);
                return iconNode;
            }
            return null;
        }, action.iconProperty()));

        setOnAction(e -> triggerAction(action));

        getStyleClass().add(ACTION_BUTTON_STYLE_CLASS);
        setMinWidth(Region.USE_PREF_SIZE);
        setMinHeight(Region.USE_PREF_SIZE);
        if(!showText) {
            pseudoClassStateChanged(PseudoClass.getPseudoClass("square"), true);
            setMaxHeight(Region.USE_PREF_SIZE);
            setMaxWidth(Region.USE_PREF_SIZE);
        }
    }

    protected void triggerAction(final Action action) {
        Objects.requireNonNull(action);
        action.run();
    }

    @Override
    protected double computePrefWidth(double height) {
        final double width = super.computePrefWidth(height);
        if(showText) {
            return width;
        } else {
            return Math.max(width, super.computePrefHeight(-1));
        }
    }

    @Override
    protected double computePrefHeight(double width) {
        final double height = super.computePrefHeight(width);
        if(showText) {
            return height;
        }
        else {
            return Math.max(super.computePrefWidth(-1), height);
        }
    }
}
