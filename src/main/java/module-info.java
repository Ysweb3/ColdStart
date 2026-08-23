module com.example.resumeone {
    requires javafx.controls;
    requires javafx.fxml;
    requires com.google.gson;
    requires java.xml.crypto;
    requires io.javalin;
    requires java.desktop;


    opens com.example.resumeone to javafx.fxml;
    exports com.example.resumeone;
}