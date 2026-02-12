package com.eclipse.mcp.server.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import com.eclipse.mcp.server.Activator;

public class PreferenceInitializer extends AbstractPreferenceInitializer {
    
    public static final String PREF_SERVER_PORT = "mcp.server.port";
    public static final String PREF_SERVER_ENABLED = "mcp.server.enabled";
    public static final String PREF_AUTO_START = "mcp.server.autostart";

    @Override
    public void initializeDefaultPreferences() {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        store.setDefault(PREF_SERVER_PORT, 8099);
        store.setDefault(PREF_SERVER_ENABLED, true);
        store.setDefault(PREF_AUTO_START, true);
    }
}