package it.scandeletefiles.bootstrap;

import it.scandeletefiles.navigation.NavigationModel;
import java.util.Objects;

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
}
