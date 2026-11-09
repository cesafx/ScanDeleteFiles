package it.scandeletefiles.ui;

import javafx.scene.Parent;

@FunctionalInterface
public interface ScreenView {
    Parent createView();
}
