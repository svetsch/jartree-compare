package io.jartree.gui;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/** Persists paths and view options between sessions. */
final class Settings {

    private final Preferences prefs = Preferences.userRoot().node("io/jartree/gui");

    String get(String key, String fallback) {
        return prefs.get(key, fallback);
    }

    void put(String key, String value) {
        if (value == null) {
            prefs.remove(key);
        } else {
            prefs.put(key, value);
        }
    }

    boolean getBoolean(String key, boolean fallback) {
        return prefs.getBoolean(key, fallback);
    }

    void putBoolean(String key, boolean value) {
        prefs.putBoolean(key, value);
    }

    int getInt(String key, int fallback) {
        return prefs.getInt(key, fallback);
    }

    void putInt(String key, int value) {
        prefs.putInt(key, value);
    }

    double getDouble(String key, double fallback) {
        return prefs.getDouble(key, fallback);
    }

    void putDouble(String key, double value) {
        prefs.putDouble(key, value);
    }

    void flush() {
        try {
            prefs.flush();
        } catch (BackingStoreException e) {
            // settings are a convenience; losing them is not an error
        }
    }
}
