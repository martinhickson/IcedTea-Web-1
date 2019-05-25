package net.adoptopenjdk.icedteaweb.clientfx.ui.action;

import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.List;


public class ActionPopup extends Popup {

    public ActionPopup(List<Action> actions) {
        final VBox box = new VBox();
        box.getStyleClass().add("action-popup");
        getContent().add(box);

        for (Action action : actions) {
            action.addActionTriggeredListener(a -> hide());
            final ActionButton button = new ActionButton(action);
            button.getStyleClass().add("action-popup-action-button");
            box.getChildren().add(button);
            setAutoHide(true);
        }
    }
}
