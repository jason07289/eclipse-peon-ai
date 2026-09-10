package org.sterl.llmpeon.parts.widget;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.RowData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.sterl.llmpeon.PeonMode;
import org.sterl.llmpeon.ai.model.AiModel;

/**
 * Action bar below the user input. RowLayout (wrapping) with mode selector,
 * model selector, Think toggle, Clear, and conditional controls.
 */
public class ActionsBarWidget extends Composite {

    private Button btnClear;
    private Button btnImplement;
    private Button chkAutonomous;
    private Button btnThink;
    private Combo agentCombo;
    private Combo modelCombo;

    private final AtomicBoolean working = new AtomicBoolean(false);
    private boolean agentModeAvailable = false;
    private boolean queryToSourceAvailable = true;
    /** Modes currently listed in the combo; the selection index refers to this list. */
    private List<PeonMode> offeredModes = List.of();
    private List<AiModel> availableModels = List.of();

    public ActionsBarWidget(Composite parent, int style,
            Runnable onClear,
            Runnable onImplement,
            Consumer<PeonMode> onModeChange,
            Consumer<AiModel> onModelChange,
            Consumer<Boolean> onAutonomousChange,
            Consumer<Boolean> onThinkToggle) {
        super(parent, style);

        setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

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

        buildAgentCombo(onModeChange);

        buildModelCombo(onModelChange);

        btnThink = new Button(this, SWT.TOGGLE);
        btnThink.setText("\uD83E\uDDE0 Think");
        btnThink.setToolTipText("Enable extended thinking for the next request");
        btnThink.addListener(SWT.Selection, e -> onThinkToggle.accept(btnThink.getSelection()));

        btnClear = new Button(this, SWT.PUSH);
        btnClear.setText("Clear");
        btnClear.setToolTipText("Clear conversation context");
        btnClear.addListener(SWT.Selection, e -> onClear.run());

        buildBtnImplement(onImplement);
        buildChkAutonomous(onAutonomousChange);
    }

	private void buildBtnImplement(Runnable onImplement) {
		btnImplement = new Button(this, SWT.PUSH);
        btnImplement.setText("Start Impl.");
        RowData rdImpl = new RowData();
        rdImpl.exclude = true;
        btnImplement.setLayoutData(rdImpl);
        btnImplement.setVisible(false);
        btnImplement.setEnabled(false);
        btnImplement.setToolTipText("Switch to Dev mode and start implementing the plan");
        btnImplement.addListener(SWT.Selection, e -> onImplement.run());
	}

	private void buildChkAutonomous(Consumer<Boolean> onAutonomousChange) {
		chkAutonomous = new Button(this, SWT.CHECK);
        chkAutonomous.setText("autonomous");
        chkAutonomous.setToolTipText("Automatically start implementation after the plan is saved");
        RowData rdAuto = new RowData();
        rdAuto.exclude = true;
        chkAutonomous.setLayoutData(rdAuto);
        chkAutonomous.setVisible(false);
        chkAutonomous.addListener(SWT.Selection, e -> onAutonomousChange.accept(chkAutonomous.getSelection()));
	}

	private void buildModelCombo(Consumer<AiModel> onModelChange) {
		modelCombo = new Combo(this, SWT.READ_ONLY);
        modelCombo.setLayoutData(new RowData(200, SWT.DEFAULT));
        modelCombo.setToolTipText("Select model (fetched from provider)");
        modelCombo.addListener(SWT.Selection, e -> {
            int idx = modelCombo.getSelectionIndex();
            if (idx >= 0 && idx < availableModels.size()) {
                onModelChange.accept(availableModels.get(idx));
            }
        });
    }

    private void buildAgentCombo(Consumer<PeonMode> onModeChange) {
        agentCombo = new Combo(this, SWT.READ_ONLY);
        agentCombo.setLayoutData(new RowData(180, SWT.DEFAULT));
        rebuildModeItems(PeonMode.DEV); // default: dev
        agentCombo.setToolTipText("Select agent mode");
        agentCombo.addListener(SWT.Selection, e -> {
            int idx = agentCombo.getSelectionIndex();
            if (idx < 0 || idx >= offeredModes.size()) return;
            PeonMode selected = offeredModes.get(idx);
            if (selected == PeonMode.AGENT && !agentModeAvailable) {
                selectMode(PeonMode.DEV);
                agentCombo.setToolTipText("Peon-Agent requires a project to be selected");
                return;
            }
            agentCombo.setToolTipText("Select agent mode");
            onModeChange.accept(selected);
        });
	}

    /**
     * Refill the combo with the modes currently on offer and keep {@code selected} picked.
     * A mode that is no longer offered falls back to Dev.
     */
    private void rebuildModeItems(PeonMode selected) {
        offeredModes = PeonMode.visibleValues().stream()
                .filter(m -> m != PeonMode.QUERY_TO_SOURCE || queryToSourceAvailable)
                .toList();
        agentCombo.setItems(offeredModes.stream()
                .map(PeonMode::getLabel)
                .toArray(String[]::new));
        selectMode(selected);
    }

    /** Select the given mode in the combo, falling back to Dev when it is not on offer. */
    private void selectMode(PeonMode mode) {
        int idx = offeredModes.indexOf(mode);
        if (idx < 0) idx = offeredModes.indexOf(PeonMode.DEV);
        if (idx >= 0) agentCombo.select(idx);
    }

    /** Enable/disable controls while a request is in flight. */
    public void lockWhileWorking(boolean value) {
        this.working.set(value);
        agentCombo.setEnabled(!value);
        modelCombo.setEnabled(!value);
        btnClear.setEnabled(!value);
        btnThink.setEnabled(!value);
        chkAutonomous.setEnabled(!value);
        if (value) btnImplement.setEnabled(false); // re-enable is handled by updateModeUI
    }

    public boolean isWorking() {
        return this.working.get();
    }

    /** Show/hide the "Start Impl." button based on mode and whether an AI reply exists. */
    public void updateModeUI(PeonMode mode, boolean implEnabled) {
        boolean isPlanLike = mode == PeonMode.PLAN || mode == PeonMode.AGENT;
        boolean isAgent = mode == PeonMode.AGENT;
        selectMode(mode);
        btnImplement.setEnabled(!this.working.get() && isPlanLike && implEnabled);
        boolean implVisibilityChanged = btnImplement.getVisible() != isPlanLike;
        if (implVisibilityChanged) {
            ((RowData) btnImplement.getLayoutData()).exclude = !isPlanLike;
            btnImplement.setVisible(isPlanLike);
        }
        boolean autoVisibilityChanged = chkAutonomous.getVisible() != isAgent;
        if (autoVisibilityChanged) {
            ((RowData) chkAutonomous.getLayoutData()).exclude = !isAgent;
            chkAutonomous.setVisible(isAgent);
        }
        if (implVisibilityChanged || autoVisibilityChanged) {
            layout(true, true);
            getParent().layout(new Control[]{this});
        }
    }

    /**
     * Show or hide Query-to-Source in the mode combo. It is hidden while no pipeline step is
     * configured, since the wizard would have nothing to run.
     */
    public void setQueryToSourceAvailable(boolean available) {
        if (this.queryToSourceAvailable == available) return;
        this.queryToSourceAvailable = available;
        int idx = agentCombo.getSelectionIndex();
        PeonMode current = idx >= 0 && idx < offeredModes.size() ? offeredModes.get(idx) : PeonMode.DEV;
        rebuildModeItems(current);
    }

    /** Allow or block selection of Peon-Agent mode. */
    public void setAgentModeAvailable(boolean available) {
        this.agentModeAvailable = available;
        agentCombo.setToolTipText(available ? "Select agent mode"
                : "Peon-Agent requires a project to be selected");
    }

    /** Set the autonomous checkbox state without firing the listener. */
    public void setAutonomous(boolean value) {
        chkAutonomous.setSelection(value);
    }
    
    public boolean getAutonomous() {
        return chkAutonomous.getSelection();
    }

    /** Set the Think toggle state without firing the listener. */
    public void setThinkEnabled(boolean value) {
        btnThink.setSelection(value);
    }

    /** Returns whether the Think toggle is currently on. */
    public boolean isThinkEnabled() {
        return btnThink.getSelection();
    }
    
    public void setModel(String model) {
        availableModels = List.of(AiModel.builder().id(model).name(model).build());
        modelCombo.setEnabled(true);
        modelCombo.setItems(new String[] { model });
        selectModel(model);
    }

    /** Populate the model combo with the available models. */
    public void applyModelList(List<AiModel> models, String selectedModelId) {
        availableModels = models;
        modelCombo.setEnabled(true);
        modelCombo.setItems(models.stream().map(AiModel::getName).toArray(String[]::new));
        selectModel(selectedModelId);
    }

    /** Select the model by its ID. Falls back to index 0 if not found. */
    public void selectModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            modelCombo.select(0);
            return;
        }
        for (int i = 0; i < availableModels.size(); i++) {
            if (availableModels.get(i).getId().equals(modelId)) {
                modelCombo.select(i);
                return;
            }
        }
        modelCombo.select(0);
    }

    /** Returns true if the given model ID is in the current list. */
    public boolean containsModelId(String modelId) {
        return availableModels.stream().anyMatch(m -> m.getId().equals(modelId));
    }

    /** Returns the ID of the currently selected model, or null if nothing is selected. */
    public String getSelectedModel() {
        if (availableModels.isEmpty()) return null;
        int idx = modelCombo.getSelectionIndex();
        if (idx < 0 || idx >= availableModels.size()) return null;
        return availableModels.get(idx).getId();
    }
}
