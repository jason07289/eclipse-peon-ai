package org.sterl.llmpeon.parts.widget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.RowData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.sterl.llmpeon.querytosource.QueryToSourceConfig.QueryStep;
import org.sterl.llmpeon.querytosource.StepKind;

/**
 * Wizard action bar for {@link org.sterl.llmpeon.PeonMode#QUERY_TO_SOURCE}: one button per
 * configured pipeline step plus a settings gear.
 *
 * <p>Once a step completes successfully in the current session, its button is marked done
 * (checkmark) and disabled — see {@link #updateState(boolean, boolean, int)}. Progress resets
 * whenever the underlying {@code QueryToSourceModeService} session resets (mode switch, Clear,
 * or a pipeline configuration change).</p>
 */
public class QueryToSourceBarWidget extends Composite {

    private final Composite buttonRow;
    private final Button btnSettings;
    private final BiConsumer<Integer, QueryStep> onRunStep;

    private final List<QueryStep> steps = new ArrayList<>();
    private final List<Button> stepButtons = new ArrayList<>();

    public QueryToSourceBarWidget(Composite parent, int style,
            BiConsumer<Integer, QueryStep> onRunStep,
            Runnable onOpenSettings) {
        super(parent, style);
        this.onRunStep = onRunStep;

        RowLayout rowLayout = new RowLayout(SWT.HORIZONTAL);
        rowLayout.wrap = true;
        rowLayout.pack = true;
        rowLayout.center = true;
        rowLayout.marginTop = 2;
        rowLayout.marginBottom = 2;
        rowLayout.marginLeft = 4;
        rowLayout.marginRight = 4;
        rowLayout.spacing = 4;
        setLayout(rowLayout);

        buttonRow = new Composite(this, SWT.NONE);
        buttonRow.setLayout(new RowLayout(SWT.HORIZONTAL));
        buttonRow.setLayoutData(new RowData(SWT.DEFAULT, SWT.DEFAULT));

        btnSettings = new Button(this, SWT.PUSH);
        btnSettings.setText("\u2699");
        btnSettings.setToolTipText("Query-to-Source settings");
        btnSettings.addListener(SWT.Selection, e -> onOpenSettings.run());
    }

    /** Rebuilds step buttons from the configured pipeline (preserves settings button). */
    public void setSteps(List<QueryStep> newSteps) {
        steps.clear();
        if (newSteps != null) steps.addAll(newSteps);
        disposeStepButtons();
        for (int i = 0; i < steps.size(); i++) {
            var index = i;
            var step = steps.get(i);
            var btn = new Button(buttonRow, SWT.PUSH);
            btn.setText(labelFor(step, false));
            btn.setToolTipText(tooltipFor(step, false));
            btn.addListener(SWT.Selection, e -> onRunStep.accept(index, step));
            stepButtons.add(btn);
        }
        buttonRow.layout(true, true);
        layout(true, true);
        Composite p = getParent();
        if (p != null) p.layout(new Control[]{ this });
    }

    private static String labelFor(QueryStep step, boolean done) {
        var text = step.label().isBlank() ? step.kind().name() : step.label();
        return done ? "\u2713 " + text : text;
    }

    private static String tooltipFor(QueryStep step, boolean done) {
        var kind = switch (step.kind()) {
            case TRANSFORM -> "Transform input (SQL, XML, notes, …)";
            case GENERATE  -> "Generate source files";
            case REVIEW    -> "Review inputs and generated sources";
        };
        var prompt = step.prompt().isBlank() ? "(no prompt configured)" : step.prompt();
        var status = done ? " — completed this session (Clear to re-run)" : "";
        return kind + " — prompt: " + prompt + status;
    }

    private void disposeStepButtons() {
        for (var btn : stepButtons) {
            if (!btn.isDisposed()) btn.dispose();
        }
        stepButtons.clear();
    }

    /**
     * @param working            whether a request is in flight
     * @param projectAvailable   whether an open project is selected
     * @param completedStepIndex highest pipeline step index completed this session ({@code -1} =
     *                           none); steps at or before this index are shown as done and disabled
     */
    public void updateState(boolean working, boolean projectAvailable, int completedStepIndex) {
        btnSettings.setEnabled(!working);
        for (int i = 0; i < stepButtons.size(); i++) {
            var btn = stepButtons.get(i);
            var step = steps.get(i);
            boolean done = i <= completedStepIndex;
            boolean needsProject = step.kind() != StepKind.TRANSFORM;
            btn.setEnabled(!working && !done && (!needsProject || projectAvailable));
            btn.setText(labelFor(step, done));
            btn.setToolTipText(tooltipFor(step, done));
        }
        buttonRow.layout(true, true);
    }

    @Override
    public void dispose() {
        disposeStepButtons();
        super.dispose();
    }
}
