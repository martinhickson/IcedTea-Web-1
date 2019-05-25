package net.adoptopenjdk.icedteaweb.clientfx;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.ListView;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import net.adoptopenjdk.icedteaweb.clientfx.model.WebStartApplication;
import net.adoptopenjdk.icedteaweb.clientfx.ui.action.Action;
import net.adoptopenjdk.icedteaweb.clientfx.ui.action.SimpleAction;
import net.adoptopenjdk.icedteaweb.clientfx.ui.i18n.ResourceBundleBasedTranslator;
import net.adoptopenjdk.icedteaweb.clientfx.ui.i18n.Translator;
import net.adoptopenjdk.icedteaweb.clientfx.ui.media.SimpleMediaListCell;
import org.kordamp.ikonli.materialdesign.MaterialDesign;

import java.util.Collections;

public class ApplicationView extends StackPane {

    private final ListView<WebStartApplication> list;

    public ApplicationView(final ObservableList<WebStartApplication> applications) {
        this.list = new ListView<>(applications);

        getStyleClass().add("webstart-view");

        final Translator translator = new ResourceBundleBasedTranslator(Collections.singletonList("webstart"));

        final Action startAction = new SimpleAction("ui.actions.startApplication.name", "ui.actions.startApplication.description", MaterialDesign.MDI_PLAY_CIRCLE_OUTLINE, a -> {}, translator);
        final Action addToDesktopAction = new SimpleAction("ui.actions.addApplicationToDesktop.name", "ui.actions.addApplicationToDesktop.description", MaterialDesign.MDI_LINK_VARIANT, a -> {}, translator);
        final Action removeApplication = new SimpleAction("ui.actions.uninstallApplication.name", "ui.actions.uninstallApplication.description", MaterialDesign.MDI_CLOSE_CIRCLE_OUTLINE, a -> {}, translator);
        final Action updateApplication = new SimpleAction("ui.actions.updateApplication.name", "ui.actions.updateApplication.description", MaterialDesign.MDI_CLOUD_SYNC, a -> {}, translator);


        list.setCellFactory(SimpleMediaListCell.createDefaultCallback(new Image(ApplicationView.class.getResourceAsStream("dummy-app-icon-1.png")), FXCollections.observableArrayList(startAction, addToDesktopAction, updateApplication, removeApplication)));
        getChildren().add(list);
    }
}
