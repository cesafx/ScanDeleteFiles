package it.scandeletefiles.bootstrap;

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
        return new ApplicationCompositionRoot(
                new NavigationModel(),
                ApplicationServices.pendingImplementation(),
                PlatformAdapters.currentPlatform());
    }

    public Parent createView() {
        return new ApplicationShell(navigation, new ScanView(), new DeletionView()).createView();
    }
}
