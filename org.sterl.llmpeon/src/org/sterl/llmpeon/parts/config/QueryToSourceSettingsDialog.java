package org.sterl.llmpeon.parts.config;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
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
    /** Shown for a configured prompt whose skill/command is no longer loaded. */
    private static final String UNKNOWN_TAG = "[?]";

    private static final String[] KIND_LABELS = {
            "질의 변환 (Transform)",
            "코드 생성 (Generate)",
            "검토 (Review)"
    };

    private final QueryToSourceConfig initial;
    private final List<PromptOption> availablePrompts;

    /**
     * A selectable prompt plus where it comes from, so the UI can show whether a name is a skill,
     * a command, or both. Only {@link #name()} is stored in the config.
     */
    public record PromptOption(String name, boolean skill, boolean command) {
        String displayLabel() {
            return sourceTag() + " " + name;
        }

        private String sourceTag() {
            if (skill && command) return "[Skill+Command]";
            if (command) return "[Command]";
            if (skill) return "[Skill]";
            return UNKNOWN_TAG;
        }
    }

    private Table stepTable;
    private Button btnEdit;
    private Button btnRemove;
    private Button btnUp;
    private Button btnDown;
    private Button chkShowStepNumbers;

    private final List<QueryStep> steps = new ArrayList<>();
    private boolean showStepNumbers;
    private QueryToSourceConfig result;

    public QueryToSourceSettingsDialog(Shell parent, QueryToSourceConfig initial, List<PromptOption> availablePrompts) {
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
        colLabel.setWidth(120);
        var colKind = new TableColumn(stepTable, SWT.NONE);
        colKind.setText("Kind");
        colKind.setWidth(140);
        var colPrompt = new TableColumn(stepTable, SWT.NONE);
        colPrompt.setText("Prompt");
        colPrompt.setWidth(180);
        var colFields = new TableColumn(stepTable, SWT.NONE);
        colFields.setText("Fields");
        colFields.setWidth(150);

        // Share the available width instead of overflowing into a horizontal scrollbar.
        stepTable.addListener(SWT.Resize, e -> {
            int w = stepTable.getClientArea().width;
            if (w <= 0) return;
            colLabel.setWidth((int) (w * 0.22));
            colKind.setWidth((int) (w * 0.25));
            colPrompt.setWidth((int) (w * 0.30));
            colFields.setWidth(Math.max(0,
                    w - colLabel.getWidth() - colKind.getWidth() - colPrompt.getWidth()));
        });

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

        var optionsLabel = new Label(container, SWT.NONE);
        optionsLabel.setText("Display options:");
        var optionsGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        optionsGd.horizontalSpan = 2;
        optionsLabel.setLayoutData(optionsGd);

        chkShowStepNumbers = new Button(container, SWT.CHECK);
        chkShowStepNumbers.setText("Show step numbers (1, 2, 3, ...)");
        chkShowStepNumbers.setSelection(initial.showStepNumbers());
        var chkGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        chkGd.horizontalSpan = 2;
        chkShowStepNumbers.setLayoutData(chkGd);

        steps.addAll(initial.steps());
        showStepNumbers = initial.showStepNumbers();
        refreshStepTable();

        return area;
    }

    private void refreshStepTable() {
        stepTable.removeAll();
        for (var step : steps) {
            var item = new TableItem(stepTable, SWT.NONE);
            item.setText(0, step.label());
            item.setText(1, kindLabel(step.kind()));
            item.setText(2, promptDisplay(step.prompt()));
            var fieldsText = step.fields().isEmpty() ? "-" : step.fields().stream()
                    .map(f -> f.required() ? f.label() + "*" : f.label())
                    .collect(java.util.stream.Collectors.joining(", "));
            item.setText(3, fieldsText);
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
        result = new QueryToSourceConfig(new ArrayList<>(steps), chkShowStepNumbers.getSelection());
        super.okPressed();
    }

    public QueryToSourceConfig getResult() {
        return result;
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    /**
     * Items are tagged ({@code [Skill] foo}) while the plain prompt names are kept as widget data,
     * because only the name is stored in the config.
     */
    private Combo buildPromptCombo(Composite parent, String selected) {
        var cmb = new Combo(parent, SWT.READ_ONLY | SWT.DROP_DOWN);
        cmb.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        var items = new ArrayList<String>();
        var values = new ArrayList<String>();
        items.add(NONE);
        values.add("");
        for (var option : availablePrompts) {
            items.add(option.displayLabel());
            values.add(option.name());
        }
        if (selected != null && !selected.isBlank() && !values.contains(selected)) {
            items.add(UNKNOWN_TAG + " " + selected);
            values.add(selected);
        }
        cmb.setItems(items.toArray(String[]::new));
        cmb.setData(values.toArray(String[]::new));
        selectPrompt(cmb, selected);
        return cmb;
    }

    private void selectPrompt(Combo cmb, String value) {
        if (value == null || value.isBlank()) {
            cmb.select(0);
            return;
        }
        var values = (String[]) cmb.getData();
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                cmb.select(i);
                return;
            }
        }
        cmb.select(0);
    }

    private String promptValue(Combo cmb) {
        int idx = cmb.getSelectionIndex();
        if (idx <= 0) return "";
        return ((String[]) cmb.getData())[idx];
    }

    /** Tagged label for the steps table, e.g. {@code [Command] commit}. */
    private String promptDisplay(String name) {
        if (name == null || name.isBlank()) return NONE;
        for (var option : availablePrompts) {
            if (option.name().equals(name)) return option.displayLabel();
        }
        return UNKNOWN_TAG + " " + name;
    }

    private void addLabel(Composite parent, String text) {
        var lbl = new Label(parent, SWT.NONE);
        lbl.setText(text);
        lbl.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
    }

    private class StepDialog extends TitleAreaDialog {
        private static final int VISIBLE_FIELD_ROWS = 3;

        private final QueryStep initialStep;
        private Text txtLabel;
        private Combo cmbKind;
        private Combo cmbPrompt;
        private Table fieldsTable;
        private Text txtHint;
        private Text txtInstruction;
        private Button btnFieldAdd;
        private Button btnFieldEdit;
        private Button btnFieldRemove;
        private final java.util.List<QueryToSourceConfig.StepField> currentFields = new java.util.ArrayList<>();
        private QueryStep stepResult;

        StepDialog(Shell parent, QueryStep initialStep) {
            super(parent);
            this.initialStep = initialStep;
        }

        @Override
        public void create() {
            super.create();
            setTitle(initialStep == null ? "Add Step" : "Edit Step");
            setMessage("Label, step kind, prompt ([Skill] / [Command] tagged), required fields, hint, and instruction.");
            getShell().setMinimumSize(520, 480);
        }

        @Override
        protected boolean isResizable() {
            return true;
        }

        @Override
        protected Point getInitialSize() {
            var computed = super.getInitialSize();
            return new Point(Math.max(computed.x, 560), Math.max(computed.y, 540));
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

            var fieldsLabel = new Label(container, SWT.NONE);
            fieldsLabel.setText("Fields:");
            fieldsLabel.setLayoutData(new GridData(SWT.RIGHT, SWT.TOP, false, false));

            var fieldsComposite = new Composite(container, SWT.NONE);
            fieldsComposite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            fieldsComposite.setLayout(new GridLayout(2, false));

            fieldsTable = new Table(fieldsComposite, SWT.CHECK | SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
            fieldsTable.setHeaderVisible(true);
            fieldsTable.setLinesVisible(true);
            var fieldTableGd = new GridData(SWT.FILL, SWT.FILL, true, true);
            fieldTableGd.heightHint = fieldsTableHeightHint();
            fieldsTable.setLayoutData(fieldTableGd);

            var fieldCol = new TableColumn(fieldsTable, SWT.NONE);
            fieldCol.setText("Field Label (checked = required)");
            fieldCol.setWidth(200);
            // Fill the client width (excludes the vertical scrollbar) so nothing gets clipped.
            fieldsTable.addListener(SWT.Resize, e -> {
                int w = fieldsTable.getClientArea().width;
                if (w > 0) fieldCol.setWidth(w);
            });
            fieldsTable.addListener(SWT.Selection, e -> updateFieldButtonStates());

            var fieldBtnComposite = new Composite(fieldsComposite, SWT.NONE);
            fieldBtnComposite.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
            fieldBtnComposite.setLayout(new GridLayout(1, false));

            btnFieldAdd = new Button(fieldBtnComposite, SWT.PUSH);
            btnFieldAdd.setText("Add...");
            btnFieldAdd.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
            btnFieldAdd.addListener(SWT.Selection, e -> onAddField());

            btnFieldEdit = new Button(fieldBtnComposite, SWT.PUSH);
            btnFieldEdit.setText("Edit...");
            btnFieldEdit.setEnabled(false);
            btnFieldEdit.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
            btnFieldEdit.addListener(SWT.Selection, e -> onEditField());

            btnFieldRemove = new Button(fieldBtnComposite, SWT.PUSH);
            btnFieldRemove.setText("Remove");
            btnFieldRemove.setEnabled(false);
            btnFieldRemove.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
            btnFieldRemove.addListener(SWT.Selection, e -> onRemoveField());

            if (initialStep != null) {
                currentFields.addAll(initialStep.fields());
            }
            refreshFieldsTable();

            addLabel(container, "Hint:");
            txtHint = new Text(container, SWT.BORDER | SWT.MULTI | SWT.WRAP);
            txtHint.setText(initialStep != null ? initialStep.hint() : "");
            var hintGd = new GridData(SWT.FILL, SWT.FILL, true, false);
            hintGd.heightHint = 40;
            txtHint.setLayoutData(hintGd);

            addLabel(container, "Instruction:");
            txtInstruction = new Text(container, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
            txtInstruction.setText(initialStep != null ? initialStep.instruction() : "");
            var instrGd = new GridData(SWT.FILL, SWT.FILL, true, true);
            instrGd.heightHint = 80;
            txtInstruction.setLayoutData(instrGd);

            return area;
        }

        @Override
        protected void okPressed() {
            stepResult = new QueryStep(
                    txtLabel.getText().trim(),
                    kindFromIndex(cmbKind.getSelectionIndex()),
                    promptValue(cmbPrompt),
                    new java.util.ArrayList<>(currentFields),
                    txtInstruction.getText().trim(),
                    txtHint.getText().trim());
            super.okPressed();
        }

        QueryStep getResult() {
            return stepResult;
        }

        private void refreshFieldsTable() {
            fieldsTable.removeAll();
            for (var field : currentFields) {
                var item = new TableItem(fieldsTable, SWT.NONE);
                item.setText(0, field.label());
                item.setChecked(field.required());
            }
            updateFieldButtonStates();
        }

        /**
         * Room for {@value #VISIBLE_FIELD_ROWS} rows; more fields simply scroll. Acts as a minimum,
         * so the table still takes its share of the extra space when the dialog is enlarged.
         */
        private int fieldsTableHeightHint() {
            int rowHeight = fieldsTable.getItemHeight();
            if (rowHeight <= 0) rowHeight = 18;
            return rowHeight * VISIBLE_FIELD_ROWS + fieldsTable.getHeaderHeight() + 4;
        }

        private void updateFieldButtonStates() {
            int idx = fieldsTable.getSelectionIndex();
            boolean selected = idx >= 0;
            btnFieldEdit.setEnabled(selected);
            btnFieldRemove.setEnabled(selected);
        }

        private void onAddField() {
            var dlg = new org.eclipse.jface.dialogs.InputDialog(
                    getShell(), "Add Field", "Field label:", "", null);
            if (dlg.open() == org.eclipse.jface.dialogs.IDialogConstants.OK_ID) {
                var label = dlg.getValue().trim();
                if (!label.isEmpty()) {
                    currentFields.add(new QueryToSourceConfig.StepField(label, true));
                    refreshFieldsTable();
                }
            }
        }

        private void onEditField() {
            int idx = fieldsTable.getSelectionIndex();
            if (idx < 0) return;
            var field = currentFields.get(idx);
            var dlg = new org.eclipse.jface.dialogs.InputDialog(
                    getShell(), "Edit Field", "Field label:", field.label(), null);
            if (dlg.open() == org.eclipse.jface.dialogs.IDialogConstants.OK_ID) {
                var label = dlg.getValue().trim();
                if (!label.isEmpty()) {
                    currentFields.set(idx, new QueryToSourceConfig.StepField(label, field.required()));
                    refreshFieldsTable();
                    fieldsTable.select(idx);
                }
            }
        }

        private void onRemoveField() {
            int idx = fieldsTable.getSelectionIndex();
            if (idx < 0) return;
            currentFields.remove(idx);
            refreshFieldsTable();
        }
    }
}
