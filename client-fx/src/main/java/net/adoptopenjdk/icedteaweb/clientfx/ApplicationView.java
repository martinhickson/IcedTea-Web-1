package net.adoptopenjdk.icedteaweb.clientfx;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.ListView;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import net.adoptopenjdk.icedteaweb.clientfx.model.WebStartApplication;
import net.adoptopenjdk.icedteaweb.clientfx.ui.action.Action;
import net.adoptopenjdk.icedteaweb.clientfx.ui.action.SimpleAction;
import net.adoptopenjdk.icedteaweb.clientfx.ui.i18n.EchoTranslator;
import net.adoptopenjdk.icedteaweb.clientfx.ui.media.SimpleMediaListCell;
import org.kordamp.ikonli.materialdesign.MaterialDesign;

public class ApplicationView extends StackPane {

    private final ListView<WebStartApplication> list;

    public ApplicationView(final ObservableList<WebStartApplication> applications) {
        this.list = new ListView<>(applications);

        final Action startAction = new SimpleAction("ui.actions.startApplication.name", "ui.actions.startApplication.name", MaterialDesign.MDI_PLAY_CIRCLE_OUTLINE, a -> {}, new EchoTranslator());
        final Action addToDesktopAction = new SimpleAction("ui.actions.addApplicationToDesktop.name", "ui.actions.addApplicationToDesktop.name", MaterialDesign.MDI_LINK_VARIANT, a -> {}, new EchoTranslator());
        final Action removeApplication = new SimpleAction("ui.actions.uninstallApplication.name", "ui.actions.uninstallApplication.name", MaterialDesign.MDI_CLOSE_CIRCLE_OUTLINE, a -> {}, new EchoTranslator());
        final Action updateApplication = new SimpleAction("ui.actions.updateApplication.name", "ui.actions.updateApplication.name", MaterialDesign.MDI_CLOUD_SYNC, a -> {}, new EchoTranslator());


        list.setCellFactory(SimpleMediaListCell.createDefaultCallback(new Image(ApplicationView.class.getResourceAsStream("dummy-app-icon-1.png")), FXCollections.observableArrayList(startAction, addToDesktopAction, updateApplication, removeApplication)));
        getChildren().add(list);
    }
}
