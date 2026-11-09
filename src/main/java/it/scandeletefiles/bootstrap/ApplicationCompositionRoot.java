package it.scandeletefiles.bootstrap;

import it.scandeletefiles.application.ExtensionPreferences;
import it.scandeletefiles.navigation.NavigationModel;
import it.scandeletefiles.ui.ApplicationShell;
import it.scandeletefiles.ui.DeletionView;
import it.scandeletefiles.ui.ScanView;
import java.util.Objects;
import javafx.scene.Parent;

public record ApplicationCompositionRoot(
        NavigationModel navigation,
        ApplicationServices services,
        PlatformAdapters platform) {

    public ApplicationCompositionRoot {
        Objects.requireNonNull(navigation, "navigation");
        Objects.requireNonNull(services, "services");
        Objects.requireNonNull(platform, "platform");
    }

    public static ApplicationCompositionRoot create() {
        PlatformAdapters platform = PlatformAdapters.currentPlatform();
        return new ApplicationCompositionRoot(
                new NavigationModel(),
                ApplicationServices.create(platform),
                platform);
    }

    public Parent createView() {
        ExtensionPreferences extensions = new ExtensionPreferences(platform.settings());
        return new ApplicationShell(navigation, new ScanView(services.scan(), extensions),
                new DeletionView(services.deletion())).createView();
    }
}
