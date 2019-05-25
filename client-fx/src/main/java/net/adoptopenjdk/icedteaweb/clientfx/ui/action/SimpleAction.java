package net.adoptopenjdk.icedteaweb.clientfx.ui.action;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import net.adoptopenjdk.icedteaweb.clientfx.ui.i18n.EditorInternationalization;
import net.adoptopenjdk.icedteaweb.clientfx.ui.i18n.LocaleObserver;
import net.adoptopenjdk.icedteaweb.clientfx.ui.i18n.Translator;
import org.kordamp.ikonli.Ikon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class SimpleAction implements Action, LocaleObserver {

    private static final Logger LOG = LoggerFactory.getLogger(Action.class);
    private final Translator translator;
    private final StringProperty name = new SimpleStringProperty();
    private final StringProperty description = new SimpleStringProperty();
    private final BooleanProperty active = new SimpleBooleanProperty(true);
    private final ObjectProperty<Ikon> icon = new SimpleObjectProperty<>();
    private final List<Consumer<Action>> actionTriggeredListener = new CopyOnWriteArrayList<>();

    private String nameKey;
    private String descriptionKey;
    private Consumer<SimpleAction> actionTask;

    public SimpleAction(final String nameKey, final String descriptionKey, final Ikon icon, final Consumer<SimpleAction> actionTask, final Translator translator) {
        this.nameKey = Objects.requireNonNull(nameKey);
        this.descriptionKey = Objects.requireNonNull(descriptionKey);
        this.icon.setValue(icon);
        this.actionTask = actionTask;
        this.translator = Objects.requireNonNull(translator);
        updateTranslation();
        EditorInternationalization.getInstance().addObserver(this);
    }

    public String getNameKey() {
        return nameKey;
    }

    public void setNameKey(String nameKey) {
        this.nameKey = nameKey;
        this.name.setValue(translator.get(Optional.ofNullable(nameKey).orElse("")));
    }

    public String getDescriptionKey() {
        return descriptionKey;
    }

    public void setDescriptionKey(String descriptionKey) {
        this.descriptionKey = descriptionKey;
        this.description.setValue(translator.get(Optional.ofNullable(descriptionKey).orElse("")));
    }

    public Ikon getIcon() {
        return icon.get();
    }

    public void setIcon(Ikon icon) {
        this.icon.set(icon);
    }

    public ObjectProperty<Ikon> iconProperty() {
        return icon;
    }

    public String getName() {
        return name.get();
    }

    public StringProperty nameProperty() {
        return name;
    }

    public String getDescription() {
        return description.get();
    }

    public StringProperty descriptionProperty() {
        return description;
    }

    public boolean isActive() {
        return active.get();
    }

    public void setActive(boolean active) {
        this.active.set(active);
    }

    public BooleanProperty activeProperty() {
        return active;
    }

    public Consumer<SimpleAction> getActionTask() {
        return actionTask;
    }

    public void setActionTask(Consumer<SimpleAction> actionTask) {
        this.actionTask = actionTask;
    }

    @Override
    public void onLocaleChanged(Locale locale) {
        updateTranslation();
    }

    public Translator getTranslator() {
        return translator;
    }

    private void updateTranslation() {
        this.name.setValue(translator.get(Optional.ofNullable(nameKey).orElse("")));
        this.description.setValue(translator.get(Optional.ofNullable(descriptionKey).orElse("")));
    }

    public void run() {
        try {
            LOG.debug("SimpleAction {} called", getName());
            if (this.actionTask != null) {
                try {
                    actionTask.accept(this);
                } catch (RuntimeException e) {
                    LOG.error("Error while calling action " + getName(), e);
                    throw e;
                }
            } else {
                LOG.debug("No action task defined for action {}", getName());
            }
        } finally {
            actionTriggeredListener.forEach(l -> l.accept(this));
        }
    }

    @Override
    public void addActionTriggeredListener(final Consumer<Action> listener) {
        actionTriggeredListener.add(listener);
    }
}
