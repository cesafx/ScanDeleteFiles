package it.scandeletefiles.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NavigationModelTest {

    @Test
    void startsOnScanAndNavigatesBetweenBothScreens() {
        NavigationModel navigation = new NavigationModel();
        List<AppScreen> visited = new ArrayList<>();
        navigation.addListener(visited::add);

        assertEquals(AppScreen.SCAN, navigation.currentScreen());

        navigation.navigateTo(AppScreen.DELETE);
        navigation.navigateTo(AppScreen.SCAN);

        assertEquals(List.of(AppScreen.DELETE, AppScreen.SCAN), visited);
        assertEquals(AppScreen.SCAN, navigation.currentScreen());
    }

    @Test
    void exposesItalianScreenCopy() {
        assertEquals("Scansione", AppScreen.SCAN.title());
        assertEquals("Move/Delete", AppScreen.DELETE.title());
        assertEquals("Avvia scansione", AppScreen.SCAN.actionLabel());
        assertEquals("Avvia operazione", AppScreen.DELETE.actionLabel());
    }
}
