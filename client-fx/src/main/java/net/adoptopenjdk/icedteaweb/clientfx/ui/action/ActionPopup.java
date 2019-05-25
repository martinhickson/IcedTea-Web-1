package net.adoptopenjdk.icedteaweb.clientfx.ui.action;

import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.List;


public class ActionPopup extends Popup {

    public ActionPopup(List<Action> actions) {
        final VBox box = new VBox();
        box.setStyle("-fx-background-color: white; -fx-border-style: solid; -fx-border-color: black; -fx-padding: 4px");
        getContent().add(box);

        for (Action action : actions) {
            action.addActionTriggeredListener(a -> hide());
            box.getChildren().add(new ActionButton(action));
            setAutoHide(true);
        }
    }
}
