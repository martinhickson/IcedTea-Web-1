package net.adoptopenjdk.icedteaweb.clientfx.model;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.image.Image;
import net.adoptopenjdk.icedteaweb.clientfx.ui.media.Media;

public class WebStartApplication implements Media {

    private final StringProperty title = new SimpleStringProperty();

    private final StringProperty description = new SimpleStringProperty();

    private final ObjectProperty<Image> image = new SimpleObjectProperty<>();

    public WebStartApplication() {
    }

    public WebStartApplication(final String title, final String description) {
        this.title.setValue(title);
        this.description.setValue(description);
    }

    @Override
    public StringProperty titleProperty() {
        return title;
    }

    @Override
    public StringProperty descriptionProperty() {
        return description;
    }

    @Override
    public ObjectProperty<Image> imageProperty() {
        return image;
    }
}
