package com.eclipse.mcp.server.preferences;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.eclipse.mcp.server.Activator;

public class MCPPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    public MCPPreferencePage() {
        super(GRID);
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("MCP Server Configuration");
    }

    @Override
    public void createFieldEditors() {
        addField(new BooleanFieldEditor(
            PreferenceInitializer.PREF_SERVER_ENABLED,
            "&Enable MCP Server",
            getFieldEditorParent()
        ));
        
        addField(new IntegerFieldEditor(
            PreferenceInitializer.PREF_SERVER_PORT,
            "&Server Port:",
            getFieldEditorParent()
        ));
        
        addField(new BooleanFieldEditor(
            PreferenceInitializer.PREF_AUTO_START,
            "&Auto-start server on Eclipse startup",
            getFieldEditorParent()
        ));
    }

    @Override
    public void init(IWorkbench workbench) {
        // Nothing to initialize
    }
}