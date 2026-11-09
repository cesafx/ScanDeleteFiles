package it.scandeletefiles.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class NavigationModel {
    private final List<Consumer<AppScreen>> listeners = new ArrayList<>();
    private AppScreen currentScreen = AppScreen.SCAN;

    public AppScreen currentScreen() {
        return currentScreen;
    }

    public void navigateTo(AppScreen screen) {
        AppScreen target = Objects.requireNonNull(screen, "screen");
        if (currentScreen == target) {
            return;
        }
        currentScreen = target;
        List.copyOf(listeners).forEach(listener -> listener.accept(target));
    }

    public void addListener(Consumer<AppScreen> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }
}
