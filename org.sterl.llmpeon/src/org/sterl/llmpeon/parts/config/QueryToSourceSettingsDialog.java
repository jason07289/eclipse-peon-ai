package org.sterl.llmpeon.parts.config;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.sterl.llmpeon.querytosource.QueryToSourceConfig;
import org.sterl.llmpeon.querytosource.QueryToSourceConfig.QueryStep;
import org.sterl.llmpeon.querytosource.StepKind;

/**
 * Popup for configuring the Query-to-Source pipeline: ordered steps with label, kind and prompt.
 */
public class QueryToSourceSettingsDialog extends TitleAreaDialog {

    private static final String NONE = "(none)";

    private static final String[] KIND_LABELS = {
            "질의 변환 (Transform)",
            "코드 생성 (Generate)",
            "검토 (Review)"
    };

    private final QueryToSourceConfig initial;
    private final List<String> availablePrompts;

    private Table stepTable;
    private Button btnEdit;
    private Button btnRemove;
    private Button btnUp;
    private Button btnDown;

    private final List<QueryStep> steps = new ArrayList<>();
    private QueryToSourceConfig result;

    public QueryToSourceSettingsDialog(Shell parent, QueryToSourceConfig initial, List<String> availablePrompts) {
        super(parent);
        this.initial = initial == null ? QueryToSourceConfig.defaults() : initial;
        this.availablePrompts = availablePrompts == null ? List.of() : availablePrompts;
    }

    @Override
    public void create() {
        super.create();
        setTitle("Query-to-Source Settings");
        setMessage("Define the pipeline as an ordered list of steps. Each step runs a command/skill prompt you select.");
        getShell().setMinimumSize(620, 480);
        getShell().setSize(620, 480);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        var area = (Composite) super.createDialogArea(parent);
        var container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        container.setLayout(new GridLayout(2, false));

        var hint = new Label(container, SWT.NONE);
        hint.setText("Steps (order = wizard button order, top to bottom):");
        var hintGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        hintGd.horizontalSpan = 2;
        hint.setLayoutData(hintGd);

        stepTable = new Table(container, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        stepTable.setHeaderVisible(true);
        stepTable.setLinesVisible(true);
        var tableGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableGd.heightHint = 220;
        stepTable.setLayoutData(tableGd);

        var colLabel = new TableColumn(stepTable, SWT.NONE);
        colLabel.setText("Label");
        colLabel.setWidth(140);
        var colKind = new TableColumn(stepTable, SWT.NONE);
        colKind.setText("Kind");
        colKind.setWidth(160);
        var colPrompt = new TableColumn(stepTable, SWT.NONE);
        colPrompt.setText("Prompt");
        colPrompt.setWidth(280);

        stepTable.addListener(SWT.Selection, e -> updateButtonStates());
        stepTable.addListener(SWT.MouseDoubleClick, e -> onEditStep());

        var buttons = new Composite(container, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
        buttons.setLayout(new GridLayout(1, false));

        var btnAdd = new Button(buttons, SWT.PUSH);
        btnAdd.setText("Add...");
        btnAdd.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
        btnAdd.addListener(SWT.Selection, e -> onAddStep());

        btnEdit = new Button(buttons, SWT.PUSH);
        btnEdit.setText("Edit...");
        btnEdit.setEnabled(false);
        btnEdit.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
        btnEdit.addListener(SWT.Selection, e -> onEditStep());

        btnRemove = new Button(buttons, SWT.PUSH);
        btnRemove.setText("Remove");
        btnRemove.setEnabled(false);
        btnRemove.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
        btnRemove.addListener(SWT.Selection, e -> onRemoveStep());

        btnUp = new Button(buttons, SWT.PUSH);
        btnUp.setText("Up");
        btnUp.setEnabled(false);
        btnUp.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
        btnUp.addListener(SWT.Selection, e -> moveSelected(-1));

        btnDown = new Button(buttons, SWT.PUSH);
        btnDown.setText("Down");
        btnDown.setEnabled(false);
        btnDown.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
        btnDown.addListener(SWT.Selection, e -> moveSelected(1));

        steps.addAll(initial.steps());
        refreshStepTable();

        return area;
    }

    private void refreshStepTable() {
        stepTable.removeAll();
        for (var step : steps) {
            var item = new TableItem(stepTable, SWT.NONE);
            item.setText(0, step.label());
            item.setText(1, kindLabel(step.kind()));
            item.setText(2, step.prompt().isBlank() ? NONE : step.prompt());
        }
        updateButtonStates();
    }

    private static String kindLabel(StepKind kind) {
        return switch (kind) {
            case TRANSFORM -> KIND_LABELS[0];
            case GENERATE  -> KIND_LABELS[1];
            case REVIEW    -> KIND_LABELS[2];
        };
    }

    private static StepKind kindFromIndex(int idx) {
        return switch (idx) {
            case 1 -> StepKind.GENERATE;
            case 2 -> StepKind.REVIEW;
            default -> StepKind.TRANSFORM;
        };
    }

    private void updateButtonStates() {
        int idx = stepTable.getSelectionIndex();
        boolean selected = idx >= 0;
        btnEdit.setEnabled(selected);
        btnRemove.setEnabled(selected);
        btnUp.setEnabled(selected && idx > 0);
        btnDown.setEnabled(selected && idx >= 0 && idx < steps.size() - 1);
    }

    private void onAddStep() {
        var dialog = new StepDialog(getShell(), null);
        if (dialog.open() == IDialogConstants.OK_ID) {
            steps.add(dialog.getResult());
            refreshStepTable();
            stepTable.select(steps.size() - 1);
        }
    }

    private void onEditStep() {
        int idx = stepTable.getSelectionIndex();
        if (idx < 0) return;
        var dialog = new StepDialog(getShell(), steps.get(idx));
        if (dialog.open() == IDialogConstants.OK_ID) {
            steps.set(idx, dialog.getResult());
            refreshStepTable();
            stepTable.select(idx);
        }
    }

    private void onRemoveStep() {
        int idx = stepTable.getSelectionIndex();
        if (idx < 0) return;
        steps.remove(idx);
        refreshStepTable();
    }

    private void moveSelected(int delta) {
        int idx = stepTable.getSelectionIndex();
        if (idx < 0) return;
        int newIdx = idx + delta;
        if (newIdx < 0 || newIdx >= steps.size()) return;
        var step = steps.remove(idx);
        steps.add(newIdx, step);
        refreshStepTable();
        stepTable.select(newIdx);
    }

    @Override
    protected void okPressed() {
        result = new QueryToSourceConfig(new ArrayList<>(steps));
        super.okPressed();
    }

    public QueryToSourceConfig getResult() {
        return result;
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    private Combo buildPromptCombo(Composite parent, String selected) {
        var cmb = new Combo(parent, SWT.READ_ONLY | SWT.DROP_DOWN);
        cmb.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        var items = new ArrayList<String>();
        items.add(NONE);
        items.addAll(availablePrompts);
        if (selected != null && !selected.isBlank() && !items.contains(selected)) {
            items.add(selected);
        }
        cmb.setItems(items.toArray(String[]::new));
        selectPrompt(cmb, selected);
        return cmb;
    }

    private void selectPrompt(Combo cmb, String value) {
        if (value == null || value.isBlank()) {
            cmb.select(0);
            return;
        }
        var items = cmb.getItems();
        for (int i = 0; i < items.length; i++) {
            if (items[i].equals(value)) {
                cmb.select(i);
                return;
            }
        }
        cmb.select(0);
    }

    private String promptValue(Combo cmb) {
        int idx = cmb.getSelectionIndex();
        if (idx <= 0) return "";
        return cmb.getItem(idx);
    }

    private void addLabel(Composite parent, String text) {
        var lbl = new Label(parent, SWT.NONE);
        lbl.setText(text);
        lbl.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
    }

    private class StepDialog extends TitleAreaDialog {
        private final QueryStep initialStep;
        private Text txtLabel;
        private Combo cmbKind;
        private Combo cmbPrompt;
        private QueryStep stepResult;

        StepDialog(Shell parent, QueryStep initialStep) {
            super(parent);
            this.initialStep = initialStep;
        }

        @Override
        public void create() {
            super.create();
            setTitle(initialStep == null ? "Add Step" : "Edit Step");
            setMessage("Label, step kind, and the command/skill prompt to run.");
            getShell().setSize(480, 280);
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            var area = (Composite) super.createDialogArea(parent);
            var container = new Composite(area, SWT.NONE);
            container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            container.setLayout(new GridLayout(2, false));

            addLabel(container, "Label:");
            txtLabel = new Text(container, SWT.BORDER);
            txtLabel.setText(initialStep != null ? initialStep.label() : "");
            txtLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

            addLabel(container, "Kind:");
            cmbKind = new Combo(container, SWT.READ_ONLY | SWT.DROP_DOWN);
            cmbKind.setItems(KIND_LABELS);
            cmbKind.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            if (initialStep != null) {
                cmbKind.select(switch (initialStep.kind()) {
                    case TRANSFORM -> 0;
                    case GENERATE  -> 1;
                    case REVIEW    -> 2;
                });
            } else {
                cmbKind.select(0);
            }

            addLabel(container, "Prompt:");
            cmbPrompt = buildPromptCombo(container, initialStep != null ? initialStep.prompt() : "");

            return area;
        }

        @Override
        protected void okPressed() {
            stepResult = new QueryStep(
                    txtLabel.getText().trim(),
                    kindFromIndex(cmbKind.getSelectionIndex()),
                    promptValue(cmbPrompt));
            super.okPressed();
        }

        QueryStep getResult() {
            return stepResult;
        }
    }
}
