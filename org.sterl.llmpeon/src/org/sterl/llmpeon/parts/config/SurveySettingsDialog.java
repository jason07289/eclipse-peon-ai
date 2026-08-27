package org.sterl.llmpeon.parts.config;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.sterl.llmpeon.survey.SurveyConfig;

/**
 * Popup for the satisfaction survey settings, kept out of the already long configuration page
 * the same way {@link QueryToSourceSettingsDialog} is.
 */
public class SurveySettingsDialog extends TitleAreaDialog {

    private final SurveyConfig initial;

    private Button chkEnabled;
    private Text txtUrl;
    private Text txtAuth;
    private Text txtCooldown;

    private SurveyConfig result;

    public SurveySettingsDialog(Shell parent, SurveyConfig initial) {
        super(parent);
        this.initial = initial;
    }

    @Override
    public void create() {
        super.create();
        setTitle("Survey Settings");
        setMessage("After a slash command with a slug finishes, the chat offers a one-click "
                + "satisfaction rating. Ignoring it does nothing.");
        getShell().setMinimumSize(560, 300);
        getShell().setSize(560, 300);
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        var area = (Composite) super.createDialogArea(parent);
        var container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        container.setLayout(new GridLayout(2, false));

        chkEnabled = new Button(container, SWT.CHECK);
        chkEnabled.setText("Enable satisfaction survey");
        chkEnabled.setSelection(initial.enabled());
        var chkGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        chkGd.horizontalSpan = 2;
        chkEnabled.setLayoutData(chkGd);

        addLabel(container, "URL:");
        txtUrl = new Text(container, SWT.BORDER);
        txtUrl.setText(nullSafe(initial.url()));
        txtUrl.setMessage("http://host:port/api/public/scores");
        txtUrl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        addLabel(container, "Auth (publicKey:secretKey):");
        txtAuth = new Text(container, SWT.BORDER);
        txtAuth.setText(nullSafe(initial.auth()));
        txtAuth.setMessage("pk-...:sk-...");
        txtAuth.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        addLabel(container, "Cooldown (minutes):");
        txtCooldown = new Text(container, SWT.BORDER);
        txtCooldown.setText(String.valueOf(initial.effectiveCooldownMinutes()));
        txtCooldown.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        ((GridData) txtCooldown.getLayoutData()).widthHint = 60;
        txtCooldown.addListener(SWT.Modify, e -> validate());

        var hint = new Label(container, SWT.WRAP);
        hint.setText("The cooldown is per command and stored in this workspace only — it is never "
                + "sent to the server. The command's frontmatter slug is submitted as the score's "
                + "comment; commands without a slug are never surveyed.");
        var hintGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        hintGd.horizontalSpan = 2;
        hintGd.widthHint = 480;
        hintGd.verticalIndent = 8;
        hint.setLayoutData(hintGd);

        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        super.createButtonsForButtonBar(parent);
        validate();
    }

    private void validate() {
        var ok = getButton(IDialogConstants.OK_ID);
        if (ok == null) return;

        if (parseCooldown() < 0) {
            setErrorMessage("Cooldown must be a whole number of minutes (0 = use default).");
            ok.setEnabled(false);
        } else {
            setErrorMessage(null);
            ok.setEnabled(true);
        }
    }

    /** Returns the entered minutes, or {@code -1} when the text is not a valid number. */
    private int parseCooldown() {
        if (txtCooldown == null) return SurveyConfig.DEFAULT_COOLDOWN_MINUTES;
        try {
            int value = Integer.parseInt(txtCooldown.getText().trim());
            return value < 0 ? -1 : value;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    protected void okPressed() {
        result = new SurveyConfig(
                chkEnabled.getSelection(),
                txtUrl.getText().trim(),
                txtAuth.getText().trim(),
                Math.max(parseCooldown(), 0));
        super.okPressed();
    }

    public SurveyConfig getResult() {
        return result;
    }

    private static void addLabel(Composite parent, String text) {
        var lbl = new Label(parent, SWT.NONE);
        lbl.setText(text);
        lbl.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
