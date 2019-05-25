package net.adoptopenjdk.icedteaweb.clientfx.ui.media;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.util.Callback;
import net.adoptopenjdk.icedteaweb.clientfx.ui.action.Action;
import net.adoptopenjdk.icedteaweb.clientfx.ui.action.MenuActionButton;
import net.adoptopenjdk.icedteaweb.clientfx.ui.i18n.EchoTranslator;
import org.kordamp.ikonli.materialdesign.MaterialDesign;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class SimpleMediaListCell<T extends Media> extends MediaListCell<T> {

    private final ImageView imageView;

    private final ObjectProperty<Image> fallbackImage = new SimpleObjectProperty<>();

    private ObjectBinding<Image> imageBinding;

    private DoubleProperty defaultImageSize = new SimpleDoubleProperty(64.0d);

    private MenuActionButton actionButton;

    public SimpleMediaListCell() {
        this(null,Collections.emptyList());
    }

    public SimpleMediaListCell(final Image fallbackImage, final List<Action> actions) {
        this.fallbackImage.setValue(fallbackImage);

        actionButton = new MenuActionButton("mediaCell.actions.name", "mediaCell.actions.description", MaterialDesign.MDI_DOTS_HORIZONTAL, new EchoTranslator(), actions, false);
        actionButton.getStyleClass().add("simple-media-cell-action-button");
        setRightContent(actionButton);

        imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.fitHeightProperty().bindBidirectional(defaultImageSize);
        setLeftContent(imageView);
        getStyleClass().add("simple-media-cell");
        itemProperty().addListener(e -> {
            final T media = getItem();
            titleProperty().unbind();
            descriptionProperty().unbind();
            imageView.imageProperty().unbind();
            if(media != null) {
                titleProperty().bind(media.titleProperty());
                descriptionProperty().bind(media.descriptionProperty());
                if(imageBinding != null) {
                    imageBinding.dispose();
                }
                imageBinding = Bindings.createObjectBinding(() -> getMediaImage(), media.imageProperty(), this.fallbackImage);
                imageView.imageProperty().bind(imageBinding);
                imageBinding.dispose();
            }
        });
    }

    private Image getMediaImage() {
        return  Optional.ofNullable(getItem())
                .map(i -> i.imageProperty())
                .map(p -> p.get())
                .orElse(fallbackImage.get());
    }


    public ObjectProperty<Image> fallbackImageProperty() {
        return fallbackImage;
    }

    public static <T extends Media> Callback<ListView<T>, ListCell<T>> createDefaultCallback() {
        return v -> new SimpleMediaListCell<>();
    }

    public static <T extends Media> Callback<ListView<T>, ListCell<T>> createDefaultCallback(final Image fallbackImage, final List<Action> actions) {
        return v -> new SimpleMediaListCell<>(fallbackImage, actions);
    }
}
