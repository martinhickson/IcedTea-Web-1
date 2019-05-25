package net.adoptopenjdk.icedteaweb.clientfx;

import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.stage.Stage;
import net.adoptopenjdk.icedteaweb.clientfx.model.WebStartApplication;

public class SampleApp extends Application {

    @Override
    public void start(final Stage primaryStage) throws Exception {

        final WebStartApplication app1 = new WebStartApplication("Super Java Bros.", "Java based Jump and Run");
        final WebStartApplication app2 = new WebStartApplication("Java Office", "Java based Jump and Run");
        final WebStartApplication app3 = new WebStartApplication("Bla ", "Java based Jump and Run");
        final WebStartApplication app4 = new WebStartApplication(" 124", "Java based Jump and Run");

        final ObservableList<WebStartApplication> applications = FXCollections.observableArrayList(app1, app2, app3, app4);

        final ApplicationView applicationView = new ApplicationView(applications);
        final Scene scene = new Scene(applicationView);
        scene.getStylesheets().add(SampleApp.class.getResource("style.css").toExternalForm());
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
