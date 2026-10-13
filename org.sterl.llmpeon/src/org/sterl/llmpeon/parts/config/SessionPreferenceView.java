package org.sterl.llmpeon.parts.config;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.DirectoryFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.sterl.llmpeon.parts.PeonConstants;
import org.sterl.llmpeon.session.RetentionPolicy;

/**
 * Chat session storage preferences (chat-session design §9). Disabling persistence stops new
 * writes only — existing sessions are kept. A changed base path opens the other store on the
 * next Eclipse start; existing data stays where it is and reappears when the path is set back
 * (migration between stores is not offered yet).
 */
public class SessionPreferenceView extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    public SessionPreferenceView() {
        super(GRID);
        setPreferenceStore(new ScopedPreferenceStore(InstanceScope.INSTANCE, PeonConstants.PLUGIN_ID));
        setDescription("Where chat sessions of this workspace are stored, and how long they are kept.");
    }

    @Override
    public void init(IWorkbench workbench) {
        var store = getPreferenceStore();
        store.setDefault(PeonConstants.PREF_SESSION_PERSISTENCE_ENABLED, true);
        store.setDefault(PeonConstants.PREF_SESSION_BASE_PATH, defaultBasePath());
        store.setDefault(PeonConstants.PREF_SESSION_MAX_AGE_DAYS, RetentionPolicy.DEFAULT.maxAgeDays());
        store.setDefault(PeonConstants.PREF_SESSION_MAX_COUNT, RetentionPolicy.DEFAULT.maxCount());
    }

    public static String defaultBasePath() {
        return new File(System.getProperty("user.home"), ".peon-ai").getAbsolutePath();
    }

    @Override
    protected void createFieldEditors() {
        addField(new BooleanFieldEditor(PeonConstants.PREF_SESSION_PERSISTENCE_ENABLED,
                "Save chat sessions (turning this off keeps already saved sessions)",
                getFieldEditorParent()));

        addField(new DirectoryFieldEditor(PeonConstants.PREF_SESSION_BASE_PATH,
                "Storage location:", getFieldEditorParent()) {
            @Override
            protected boolean doCheckState() {
                var value = getTextControl().getText();
                if (value == null || value.isBlank()) return true; // blank = default ~/.peon-ai
                try {
                    var path = Path.of(value);
                    return !Files.exists(path) || (Files.isDirectory(path) && Files.isWritable(path));
                } catch (RuntimeException e) {
                    return false;
                }
            }
        });

        var ageField = new IntegerFieldEditor(PeonConstants.PREF_SESSION_MAX_AGE_DAYS,
                "Delete sessions older than (days):", getFieldEditorParent());
        ageField.setValidRange(1, 3650);
        addField(ageField);

        var countField = new IntegerFieldEditor(PeonConstants.PREF_SESSION_MAX_COUNT,
                "Keep at most (sessions):", getFieldEditorParent());
        countField.setValidRange(1, 10000);
        addField(countField);

        var note = new Label(getFieldEditorParent(), SWT.WRAP);
        note.setText("Changes to the storage location apply after restarting Eclipse. "
                + "Sessions at the old location are kept and show up again when the path is set back.");
        var gd = new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1);
        gd.widthHint = 420;
        note.setLayoutData(gd);
    }
}
