package org.sterl.llmpeon.parts.querytosource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.sterl.llmpeon.querytosource.QueryToSourceConfig.StepField;

/**
 * Dialog for collecting input fields before a Query-to-Source step executes.
 * Required fields must be filled; optional fields can be left blank.
 * Only filled fields are included in output.
 * Input order is preserved for output.
 */
public class StepInputDialog extends TitleAreaDialog {

    private final String stepLabel;
    private final List<StepField> fields;
    private final Map<String, Text> fieldInputs = new LinkedHashMap<>();
    private Map<String, String> result;

    public StepInputDialog(Shell parent, String stepLabel, List<StepField> fields) {
        super(parent);
        this.stepLabel = stepLabel;
        this.fields = fields == null ? List.of() : fields;
    }

    @Override
    public void create() {
        super.create();
        setTitle(stepLabel != null ? stepLabel : "Step Input");
        setMessage("Please enter the required information.");
        getShell().setMinimumSize(400, 200);
        getShell().setSize(400, 250);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        var area = (Composite) super.createDialogArea(parent);
        var container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        container.setLayout(new GridLayout(2, false));

        for (var field : fields) {
            var lbl = new Label(container, SWT.NONE);
            var labelText = field.required() ? field.label() + " *" : field.label() + " (optional)";
            lbl.setText(labelText + ":");
            lbl.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));

            var txt = new Text(container, SWT.BORDER);
            txt.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            fieldInputs.put(field.label(), txt);
        }

        return area;
    }

    @Override
    protected void okPressed() {
        for (var field : fields) {
            if (field.required()) {
                var txt = fieldInputs.get(field.label());
                var value = txt != null ? txt.getText().trim() : "";
                if (value.isEmpty()) {
                    setErrorMessage("Required field '" + field.label() + "' must be filled.");
                    return;
                }
            }
        }
        setErrorMessage(null);
        result = new LinkedHashMap<>();
        for (var entry : fieldInputs.entrySet()) {
            var value = entry.getValue().getText().trim();
            if (!value.isEmpty()) {
                result.put(entry.getKey(), value);
            }
        }
        super.okPressed();
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    public Map<String, String> getResult() {
        return result;
    }
}
