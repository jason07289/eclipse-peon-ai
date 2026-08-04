package org.sterl.llmpeon.parts;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFileState;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.e4.ui.di.Focus;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.ui.IWorkingSet;
import org.sterl.llmpeon.AbstractChatService;
import org.sterl.llmpeon.PeonMode;
import org.sterl.llmpeon.StandingOrdersBuilder;
import org.sterl.llmpeon.ai.LlmConfig;
import org.sterl.llmpeon.parts.config.LlmPreferenceInitializer;
import org.sterl.llmpeon.parts.config.McpPreferenceInitializer;
import org.sterl.llmpeon.parts.config.PeonUpdateService;
import org.sterl.llmpeon.parts.config.QueryToSourcePreferenceInitializer;
import org.sterl.llmpeon.parts.config.VoicePreferenceInitializer;
import org.sterl.llmpeon.parts.model.UserContext;
import org.sterl.llmpeon.parts.monitor.EclipseAiMonitor;
import org.sterl.llmpeon.parts.querytosource.QueryToSourceModeService;
import org.sterl.llmpeon.parts.querytosource.StepInputDialog;
import org.sterl.llmpeon.parts.shared.BuildDiagnosticsUtil;
import org.sterl.llmpeon.parts.shared.EclipseUtil;
import org.sterl.llmpeon.parts.shared.IoUtils;
import org.sterl.llmpeon.parts.shared.SimpleDiff;
import org.sterl.llmpeon.parts.tools.AskUserTool;
import org.sterl.llmpeon.parts.tools.EclipseCodeNavigationTool;
import org.sterl.llmpeon.parts.widget.ActionsBarWidget;
import org.sterl.llmpeon.parts.widget.ChatMarkdownWidget;
import org.sterl.llmpeon.parts.widget.FileChangeReviewWidget;
import org.sterl.llmpeon.parts.widget.FileChangeReviewWidget.FileChange;
import org.sterl.llmpeon.parts.widget.QueryToSourceBarWidget;
import org.sterl.llmpeon.parts.widget.StatusLineWidget;
import org.sterl.llmpeon.parts.widget.StatusLineWidget.SkillMenuSelection;
import org.sterl.llmpeon.parts.widget.UserInputWidget;
import org.sterl.llmpeon.parts.widget.UserQuestionWidget;
import org.sterl.llmpeon.querytosource.QueryToSourceConfig.QueryStep;
import org.sterl.llmpeon.querytosource.StepKind;
import org.sterl.llmpeon.shared.OnPartialAiResponse;
import org.sterl.llmpeon.shared.FileUtils;
import org.sterl.llmpeon.shared.StringUtil;
import org.sterl.llmpeon.shared.model.SimplePromptFile;
import org.sterl.llmpeon.tool.model.SimpleMessage;
import org.sterl.llmpeon.tool.model.SimpleMessage.Type;
import org.sterl.llmpeon.tool.tools.ShellTool;
import org.sterl.llmpeon.voice.VoiceConfig;
import org.sterl.llmpeon.voice.VoiceInputService;

import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named;

public class AIChatView implements EclipseAiMonitor {

    private static final ILog LOG = Platform.getLog(AIChatView.class);

    // Declared first so the aiService field initializer lambdas can capture them
    // without violating the Java forward-reference restriction.
    // All are null until @PostConstruct runs; the lambdas are only ever invoked after that.
    private Composite parent;
    private ActionsBarWidget actionsBar;
    private StatusLineWidget statusLine;

    private final PeonAiService aiService = new PeonAiService(
        this::doSendMessage,
        file -> EclipseUtil.runInUiThread(parent, () -> EclipseUtil.openInEditor(file)),
        enabled -> EclipseUtil.runInUiThread(parent, () -> statusLine.setMcpEnabled(enabled))
    );

    private final AtomicReference<IProgressMonitor> monitorRef = new AtomicReference<>(new NullProgressMonitor());
    private final VoiceInputService voiceService = new VoiceInputService();

    private volatile boolean recording = false;

    private AtomicReference<LlmConfig> lastListedConfig = new AtomicReference<>();
    private volatile LlmConfig lastAppliedConfig = null;

    /** Largest share of the view the input pane may claim while it is auto-sizing. */
    private static final double MAX_AUTO_INPUT_RATIO = 0.6;
    /** Chat history the divider always leaves visible, however far the input is dragged open. */
    private static final int MIN_HISTORY_HEIGHT = 60;
    /** Slack absorbing SashForm's ratio rounding when a corrected height is measured back. */
    private static final int SNAP_TOLERANCE = 3;
    private static final String SASH_HOOKED = "peon.sashHooked";

    private SashForm splitter;
    /** Set once the user drags the divider — auto-sizing stops until they double-click it. */
    private boolean inputManuallySized = false;

    private ChatMarkdownWidget chatHistory;
    private Composite inputBlock;
    private FileChangeReviewWidget fileChangeReview;
    private Composite hintComposite;
    private org.eclipse.swt.widgets.Label queryHintLabel;
    private UserInputWidget chatInput;
    private UserQuestionWidget questionWidget;
    private QueryToSourceBarWidget queryBar;

    private final UserContext userContext = new UserContext();

    private final IPreferenceChangeListener prefListener = event -> {
        EclipseUtil.runInUiThread(parent, this::applyConfig);
    };
    
    private final StandingOrdersBuilder standingOrders = new StandingOrdersBuilder()
            .add(aiService)
            .add(aiService.getAgentsMdService())
            .add(userContext);

    @PostConstruct
    public void createPartControl(Composite parent) {
        this.parent = parent;
        parent.setLayout(new GridLayout(1, false));

        // History and input are the two panes of a SashForm: SWT owns the divider, the drag
        // and the re-layout, so nothing here has to push heights around by hand.
        splitter = new SashForm(parent, SWT.VERTICAL);
        splitter.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        splitter.setSashWidth(6);

        createChatHistoryWidget(splitter);
        createInputArea(splitter);
        createActionBars();
        installSplitterBehavior();

        registerPreferenceListener();
        loadInitialConfig();
        initToolsAndContext();
    }

    private void createChatHistoryWidget(Composite parent) {
        chatHistory = new ChatMarkdownWidget(parent, SWT.BORDER);
    }

    private void createInputArea(Composite parent) {
        inputBlock = new Composite(parent, SWT.BORDER);
        GridLayout inputBlockLayout = new GridLayout(1, false);
        inputBlockLayout.marginWidth = 0;
        inputBlockLayout.marginHeight = 0;
        inputBlockLayout.verticalSpacing = 0;
        inputBlock.setLayout(inputBlockLayout);

        UserInputWidget.setDropActiveProjectSupplier(userContext::getCurrentProject);

        fileChangeReview = new FileChangeReviewWidget(inputBlock, SWT.NONE, this::undoFileChanges, this::keepFileChanges);

        hintComposite = new Composite(inputBlock, SWT.NONE);
        GridData hintGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        hintGd.exclude = true;
        hintComposite.setLayoutData(hintGd);
        hintComposite.setVisible(false);
        var hintLayout = new GridLayout(1, false);
        hintLayout.marginLeft = 8;
        hintLayout.marginRight = 8;
        hintLayout.marginTop = 6;
        hintLayout.marginBottom = 6;
        hintLayout.verticalSpacing = 0;
        hintComposite.setLayout(hintLayout);
        hintComposite.setBackground(parent.getDisplay().getSystemColor(SWT.COLOR_INFO_BACKGROUND));

        queryHintLabel = new org.eclipse.swt.widgets.Label(hintComposite, SWT.WRAP);
        queryHintLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        queryHintLabel.setBackground(parent.getDisplay().getSystemColor(SWT.COLOR_INFO_BACKGROUND));
        queryHintLabel.setForeground(parent.getDisplay().getSystemColor(SWT.COLOR_INFO_FOREGROUND));

        chatInput = new UserInputWidget(inputBlock, SWT.NONE,
            this::doSendMessage,
            () -> getIProgressMonitor().setCanceled(true),
            this::onMicClick);
        // FILL + grab, never CENTER: the input is the row that absorbs whatever height the
        // splitter hands out. A CENTER-aligned child keeps its preferred height and overflows
        // the row instead, which clips the first text line.
        chatInput.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        chatInput.setOnHeightChange(this::applyAutoInputHeight);

        questionWidget = new UserQuestionWidget(inputBlock, SWT.NONE, this::hideQuestion);
        // Same FILL + grab as chatInput: while a question is up the input is excluded, so this is
        // the only child left to absorb the pane height the splitter hands out. Without the grab
        // a drag just leaves blank space below the question.
        GridData qgd = new GridData(SWT.FILL, SWT.FILL, true, true);
        qgd.exclude = true;
        questionWidget.setLayoutData(qgd);
        questionWidget.setVisible(false);
    }

    private void createActionBars() {
        actionsBar = new ActionsBarWidget(inputBlock, SWT.NONE,
            this::onClear,
            this::doStartImpl,
            this::onModeChange,
            aiService::setModel,
            autonomous -> aiService.getAgentMode().setAutonomous(autonomous),
            aiService::withThinking
        );

        queryBar = new QueryToSourceBarWidget(inputBlock, SWT.NONE,
            this::onRunStep
        );
        GridData qbgd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        qbgd.exclude = true;
        queryBar.setLayoutData(qbgd);
        queryBar.setVisible(false);

        statusLine = new StatusLineWidget(inputBlock, SWT.NONE,
            this::onPinChange,
            this::onSkillsToggle,
            enabled -> aiService.getMcpConnectionService().toggle(enabled),
            this::onAgentsMdToggle,
            this::doCompressContext
        );
    }

    /**
     * Wires the splitter: keep the input pane at its content height until the user drags the
     * divider, and let a double-click hand control back to auto-sizing. SashForm builds its
     * sashes during the first layout pass, so they are hooked lazily from the resize listener.
     */
    private void installSplitterBehavior() {
        splitter.addListener(SWT.Resize, e -> {
            applyAutoInputHeight();
            // SWT sends Resize before laying the composite out, so on the first pass the sashes
            // do not exist yet. Hooking after the setWeights above — which forces that layout —
            // is the earliest they can be found; hookSashes() is idempotent for later resizes.
            hookSashes();
        });
    }

    private void hookSashes() {
        for (Control child : splitter.getChildren()) {
            if (!(child instanceof Sash sash) || sash.getData(SASH_HOOKED) != null) continue;
            sash.setData(SASH_HOOKED, Boolean.TRUE);
            sash.setToolTipText("Drag to resize the input, double-click to auto-size");
            // Selection only fires on an actual drag, so a plain click keeps auto-sizing on.
            // SashForm's own listener ran first: for SWT.DRAG it only moves the ghost, and it
            // commits the weights on the closing event. Correcting the result is therefore only
            // valid once the gesture is over — touching the weights mid-drag moves the sash out
            // from under the mouse and kills the rest of the gesture.
            sash.addListener(SWT.Selection, e -> {
                inputManuallySized = true;
                if (e.detail == SWT.DRAG) return;
                // Re-laying the splitter out from inside this handler moves the sash while it is
                // still dispatching its own mouse-up, which leaves its drag state unfinished and
                // the divider dead for every later gesture. Correct the panes once the gesture
                // has fully unwound instead.
                sash.getDisplay().asyncExec(this::enforcePaneMinimums);
            });
            sash.addListener(SWT.MouseDoubleClick, e -> {
                inputManuallySized = false;
                applyAutoInputHeight();
            });
        }
    }

    /**
     * Gives the input pane exactly the height its content needs — the 2-to-7 row auto-grow of
     * {@code TextInputWidget} plus the action and status bars — and the rest to the history.
     * No-op once the user has sized the panes themselves.
     */
    private void applyAutoInputHeight() {
        if (inputManuallySized || splitter == null || splitter.isDisposed()) return;
        int total = splitter.getClientArea().height - splitter.getSashWidth();
        // On the very first resize the panes may not be laid out yet, so fall back to the
        // splitter width — otherwise this bails out and SashForm keeps its default 50/50 split.
        int width = inputBlock.getSize().x > 0 ? inputBlock.getSize().x : splitter.getClientArea().width;
        if (total <= 0 || width <= 0) return;

        int wanted = Math.min(inputBlock.computeSize(width, SWT.DEFAULT).y,
                              (int) (total * MAX_AUTO_INPUT_RATIO));
        setInputPaneHeight(clampInputHeight(wanted, total, width), total);
    }

    /**
     * Pulls a finished drag back inside the limits when a pane got too small to be usable.
     * SashForm stores weights as ratios, so the applied height lands a pixel or two off what
     * was asked for — {@value #SNAP_TOLERANCE}px of slack keeps that residue from looking like
     * a fresh violation and re-snapping the divider on every later drag.
     */
    private void enforcePaneMinimums() {
        // Runs one event loop turn after the drag, so the view may be gone by now.
        if (splitter == null || splitter.isDisposed() || inputBlock.isDisposed()) return;
        int total = splitter.getClientArea().height - splitter.getSashWidth();
        int width = inputBlock.getSize().x;
        int current = inputBlock.getSize().y;
        if (total <= 0 || width <= 0) return;

        int clamped = clampInputHeight(current, total, width);
        if (Math.abs(clamped - current) > SNAP_TOLERANCE) setInputPaneHeight(clamped, total);
    }

    private void setInputPaneHeight(int inputHeight, int total) {
        splitter.setWeights(new int[]{ Math.max(1, total - inputHeight), inputHeight });
    }

    /**
     * Keeps the input pane between the height its own mandatory rows need — a two-line text
     * area, the send button, the action and status bars — and whatever leaves
     * {@value #MIN_HISTORY_HEIGHT}px of chat history. The history's share wins when the view is
     * too short for both: letting the input claim everything collapses the history to nothing
     * and parks the sash off the top edge, where it can no longer be grabbed.
     */
    private int clampInputHeight(int desired, int total, int width) {
        int max = Math.max(1, total - MIN_HISTORY_HEIGHT);
        int min = Math.min(minimumInputHeight(width), max);
        return Math.max(min, Math.min(desired, max));
    }

    /**
     * Height the input block cannot go below: every visible row at its preferred height, except
     * the text area which only has to show its two-line minimum. inputBlock's GridLayout has no
     * margins or spacing, so the rows sum exactly.
     */
    private int minimumInputHeight(int width) {
        int min = 0;
        for (Control child : inputBlock.getChildren()) {
            if (child.getLayoutData() instanceof GridData gd && gd.exclude) continue;
            min += child == chatInput
                    ? chatInput.getMinimumHeight()
                    : child.computeSize(width, SWT.DEFAULT).y;
        }
        return min;
    }

    private void registerPreferenceListener() {
        var prefs = InstanceScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID);
        prefs.addPreferenceChangeListener(prefListener);
    }

    private void loadInitialConfig() {
        checkForUpdates();
        applyConfig();
        statusLine.setSkillsMenuHandler(
            () -> aiService.getSkillService().getAllLoadedSkills(),
            this::onSkillMenuSelection
        );
    }

    private void initToolsAndContext() {
        updateSelectedProject(EclipseUtil.firstOpenOrSelectedProject());

        aiService.getToolService().addTool(new AskUserTool(
            (question, answers, onAnswer) -> showQuestion(question, answers, onAnswer)
        ));

        var dateInfo = buildDateInfo();
        aiService.getDeveloperService().setStaticContext(Arrays.asList(SystemMessage.from(dateInfo)));
        aiService.getPlannerService().setStaticContext(Arrays.asList(SystemMessage.from(dateInfo)));
        aiService.getQueryToSourceService().setStaticContext(Arrays.asList(SystemMessage.from(dateInfo)));

        chatInput.enableSlashCommands(() -> aiService.getCommandService().getCommands());
    }

    private String buildDateInfo() {
        return "Today: " + LocalDate.now()
                + " — APIs and libraries may have changed since your training cutoff. "
                + "Don't rely only on internal API knowledge — explore base classes and libs if possible with e.g. using "
                + EclipseCodeNavigationTool.GET_TYPE_SOURCE + " for java."
                + "\nos.name: " + System.getProperty("os.name")
                + "\nos file.separator: '" + System.getProperty("file.separator") + "'"
                + "\nos line.separator: '" + System.lineSeparator() + "'";
    }

    private void onClear() {
        var s = aiService.getActiveService();
        if (aiService.getPeonMode() == PeonMode.QUERY_TO_SOURCE) {
            // also drops the remembered step progress, not just the chat memory
            aiService.getQueryToSourceMode().reset();
            updateQueryBarState();
        } else {
            s.clear();
        }
        chatHistory.clear();
        keepFileChanges();
        statusLine.updateCompact(s.getContextSize(), s.getAutoCompactAfter());
    }

    @PreDestroy
    public void dispose() {
        if (questionWidget != null) questionWidget.cancelSilently();
        InstanceScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID).removePreferenceChangeListener(prefListener);
        aiService.disconnectMcp();
        voiceService.close();
    }

    private void checkForUpdates() {
        try {
            var result = PeonUpdateService.checkForUpdate();
            var updateUrl = InstanceScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID)
                    .get(PeonConstants.PREF_UPDATE_URL, "");
            if (result == PeonUpdateService.Result.UNREACHABLE) {
                MessageDialog.openWarning(parent.getShell(),
                        "Peon AI Update",
                        "Update URL에 연결할 수 없습니다. Preferences에서 Update URL 설정을 확인해주세요.\n" + updateUrl);
            } else if (result == PeonUpdateService.Result.INVALID_URL) {
                MessageDialog.openWarning(parent.getShell(),
                        "Peon AI Update",
                        "Update URL 형식이 올바르지 않습니다. Preferences에서 Update URL 설정을 확인해주세요.\n" + updateUrl);
            }
        } catch (Exception e) {
            LOG.warn("Error checking for Peon AI updates", e);
        }
    }

    @Focus
    public void setFocus() {
        if (questionWidget != null && questionWidget.isVisible()) questionWidget.setFocus();
        else if (chatInput != null) chatInput.setFocus();
    }

    // -------------------------------------------------------------------------
    // Eclipse selection injection
    // -------------------------------------------------------------------------

    @Inject
    @org.eclipse.e4.core.di.annotations.Optional
    public void setSelection(@Named(IServiceConstants.ACTIVE_SELECTION) ISelection s) {
        if (s == null || s.isEmpty()) return;
        if (s instanceof IStructuredSelection iss) {
            if (iss.size() == 1) setSelection(iss.getFirstElement());
            else setSelection(iss.toArray());
        }
    }

    @Inject
    @org.eclipse.e4.core.di.annotations.Optional
    public void setTextSelection(@Named(IServiceConstants.ACTIVE_SELECTION) ITextSelection ts) {
        userContext.setTextSelection(ts);
    }

    @Inject
    @org.eclipse.e4.core.di.annotations.Optional
    public void setSelection(@Named(IServiceConstants.ACTIVE_SELECTION) Object o) {
        if (o instanceof ISelection) return;
        userContext.setTextSelection(null);
        final IResource selection;
        if (o instanceof ICompilationUnit cu) {
            selection = cu.getResource();
        } else if (o instanceof IFile f) {
            selection = f;
        } else if (o instanceof IResource r) {
            selection = r;
        } else if (o instanceof IProject p) {
            selection = p;
        } else if (o instanceof IFolder f) {
            selection = f;
        } else if (o instanceof IJavaProject jp) {
            selection = jp.getResource();
        } else if (o instanceof IWorkingSet) {
            selection = null;
        } else if (o != null) {
            LOG.info("Unknown resource type selected " + o.getClass());
            selection = null;
        } else {
            selection = null;
        }
        userContext.setSelectedResource(selection);
        updateSelectedProject(EclipseUtil.resolveProject(selection));
    }

    private void updateSelectedProject(IProject project) {
        if (project != null && !userContext.isProjectPinned()) {
            userContext.setCurrentProject(project);
            aiService.setProject(project);
        }
        // TODO add check of project really changed
        if (actionsBar != null) {
            EclipseUtil.runInUiThread(parent, () -> {
                var currentProject = userContext.getCurrentProject();
                actionsBar.setAgentModeAvailable(currentProject != null && currentProject.isOpen());
                if (currentProject == null && aiService.getPeonMode() == PeonMode.AGENT) {
                    onModeChange(PeonMode.DEV);
                }
                updateQueryBarState();
                refreshStatusLine();
            });
        }
    }

    @Inject
    @org.eclipse.e4.core.di.annotations.Optional
    public void setSelection(@Named(IServiceConstants.ACTIVE_SELECTION) Object[] selectedObjects) {
        if (selectedObjects != null && selectedObjects.length > 0) {
            setSelection(selectedObjects[0]);
        }
    }

    private void onAgentsMdToggle(boolean enabled) {
        try {
            var prefs = InstanceScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID);
            prefs.putBoolean(PeonConstants.PREF_AGENTS_MD_ENABLED, enabled);
            prefs.flush();
        } catch (Exception e) {
            LOG.warn("Failed to save agents.md preference", e);
        }
        aiService.getAgentsMdService().setEnabled(enabled);
    }

    // -------------------------------------------------------------------------
    // EclipseAiMonitor
    // -------------------------------------------------------------------------

    @Override
    public void onChatResponse(SimpleMessage m) {
        EclipseUtil.runInUiThread(parent, () -> {
            var ai = aiService.getActiveService();
            chatHistory.hideLiveStatus();
            chatHistory.appendMessage(m);
            statusLine.updateCompact(ai.getContextSize(), ai.getAutoCompactAfter());
        });
    }

    @Override
    public void onCallCompleted(dev.langchain4j.model.chat.response.ChatResponse response, Duration duration) {
        EclipseUtil.runInUiThread(parent, () -> {
            lockWhileWorking(false);
            if (aiService.getAgentMode().consumeImplementationRequest()) {
                aiService.getAgentMode().startImplementation();
            }
            handleQueryToSourceCompletion();
            refreshStatusLine();
            actionsBar.updateModeUI(aiService.getPeonMode(), isImplEnabled());
            updateQueryBarState();
        });
    }

    @Override
    public void onStreamingChunk(OnPartialAiResponse r) {
        chatHistory.onStreamingChunk(r);
    }

    @Override
    public void onFileUpdate(AiFileUpdate update) {
        if (parent.isDisposed()) return;
        var diff = SimpleDiff.unifiedDiff(update.file(), update.oldContent(), update.newContent());
        var restoreState = findRestoreState(update);
        EclipseUtil.runInUiThread(parent, () -> {
            fileChangeReview.addChange(update, restoreState);
            chatHistory.showDiff(diff);
        });
    }

    private IFileState findRestoreState(AiFileUpdate update) {
        if (update.oldContent() == null) return null;
        var resource = EclipseUtil.resolveInEclipse(update.file());
        if (resource.isEmpty() || !(resource.get() instanceof IFile file)) return null;
        try {
            for (var state : file.getHistory(new NullProgressMonitor())) {
                if (update.oldContent().equals(readFileState(file, state))) {
                    return state;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to read local history for " + update.file(), e);
        }
        return null;
    }

    private String readFileState(IFile file, IFileState state) throws CoreException, IOException {
        try (var in = state.getContents()) {
            return IoUtils.toString(in, file.getCharset());
        }
    }

    private void keepFileChanges() {
        if (fileChangeReview == null || fileChangeReview.isDisposed()) return;
        fileChangeReview.clearChanges();
    }

    private void undoFileChanges() {
        if (fileChangeReview == null || fileChangeReview.isDisposed() || !fileChangeReview.hasChanges()) return;
        var changes = fileChangeReview.snapshot();
        lockWhileWorking(true);
        Job.create("Undo AI file changes", monitor -> {
            monitor.beginTask("Undo AI file changes", changes.size());
            monitorRef.set(monitor);
            AtomicReference<Exception> ex = new AtomicReference<>();
            AtomicInteger restored = new AtomicInteger();
            AtomicInteger skipped = new AtomicInteger();
            try {
                for (var change : changes.reversed()) {
                    if (monitor.isCanceled()) throw new CancellationException("Undo canceled");
                    if (undoFileChange(change, monitor)) {
                        restored.incrementAndGet();
                    } else {
                        skipped.incrementAndGet();
                    }
                    monitor.worked(1);
                }
            } catch (Exception e) {
                ex.set(e);
                if (!(e instanceof CancellationException)) {
                    onChatResponse(new SimpleMessage(Type.PROBLEM, "Undo failed: " + e.getMessage()));
                }
            } finally {
                monitor.done();
                monitorRef.set(new NullProgressMonitor());
                EclipseUtil.runInUiThread(parent, () -> {
                    lockWhileWorking(false);
                    if (ex.get() == null && skipped.get() == 0) {
                        fileChangeReview.clearChanges();
                        chatHistory.appendMessage(new SimpleMessage(Type.TOOL,
                                "Undid AI file changes in " + restored.get() + " file(s)."));
                    } else if (ex.get() == null) {
                        chatHistory.appendMessage(new SimpleMessage(Type.PROBLEM,
                                "Undo skipped " + skipped.get()
                                + " file(s) because they changed after the AI edit. "
                                + restored.get() + " file(s) were restored."));
                    }
                    refreshStatusLine();
                });
            }
            return PeonConstants.status("Undo AI file changes", ex.get());
        }).schedule();
    }

    private boolean undoFileChange(FileChange change, IProgressMonitor monitor) {
        var resource = EclipseUtil.resolveInEclipse(change.file());
        if (change.deleted()) {
            if (resource.isPresent()) {
                onChatResponse(new SimpleMessage(Type.PROBLEM,
                        "Skipped undo for " + change.file() + ": a file already exists at this path."));
                return false;
            }
            var ifile = ResourcesPlugin.getWorkspace().getRoot()
                    .getFile(IPath.fromPortableString(FileUtils.normalizePath(change.file())));
            try {
                var charset = Charset.forName(ifile.getCharset());
                try (var in = new ByteArrayInputStream(change.oldContent().getBytes(charset))) {
                    ifile.create(in, IResource.FORCE | IResource.KEEP_HISTORY, monitor);
                }
                return true;
            } catch (Exception e) {
                throw new RuntimeException("Failed to recreate deleted file " + change.file(), e);
            }
        }
        if (change.created()) {
            if (resource.isEmpty()) return true;
            if (resource.get() instanceof IFile file && hasConflict(file, change)) {
                onChatResponse(new SimpleMessage(Type.PROBLEM,
                        "Skipped undo for " + change.file() + ": file changed after AI creation."));
                return false;
            }
            try {
                resource.get().delete(IResource.KEEP_HISTORY, monitor);
                return true;
            } catch (Exception e) {
                throw new RuntimeException("Failed to delete created file " + change.file(), e);
            }
        }
        if (resource.isEmpty() || !(resource.get() instanceof IFile file)) {
            throw new IllegalArgumentException("Cannot restore missing file " + change.file());
        }
        if (hasConflict(file, change)) {
            onChatResponse(new SimpleMessage(Type.PROBLEM,
                    "Skipped undo for " + change.file() + ": file changed after AI edit."));
            return false;
        }
        try {
            if (change.restoreState() != null) {
                try (var in = change.restoreState().getContents()) {
                    file.setContents(in, IResource.FORCE | IResource.KEEP_HISTORY, monitor);
                }
            } else {
                var charset = Charset.forName(file.getCharset());
                try (var in = new ByteArrayInputStream(change.oldContent().getBytes(charset))) {
                    file.setContents(in, IResource.FORCE | IResource.KEEP_HISTORY, monitor);
                }
            }
            file.refreshLocal(IResource.DEPTH_ZERO, monitor);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to restore " + change.file(), e);
        }
    }

    private boolean hasConflict(IFile file, FileChange change) {
        try {
            return !file.readString().equals(change.newContent());
        } catch (CoreException e) {
            throw new RuntimeException("Failed to read " + change.file() + " before undo", e);
        }
    }

    @Override
    public IProgressMonitor getIProgressMonitor() {
        return IProgressMonitor.nullSafe(monitorRef.get());
    }

    @Override
    public boolean isCanceled() {
        return getIProgressMonitor().isCanceled();
    }

    // -------------------------------------------------------------------------
    // UI refresh
    // -------------------------------------------------------------------------

    public void refreshStatusLine() {
        statusLine.update(
            aiService.getSkillService().getSkills().size(),
            aiService.getAgentsMdService().getAgentFileName(),
            aiService.getAgentsMdService().isEnabled(),
            userContext.getCurrentProject(),
            userContext.getSelectedFile()
        );
        var ai = aiService.getActiveService();
        statusLine.updateCompact(ai.getContextSize(), ai.getAutoCompactAfter());
    }

    private void syncAgentsMdToggle() {
        var prefs = InstanceScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID);
        boolean enabled = prefs.getBoolean(PeonConstants.PREF_AGENTS_MD_ENABLED, true);
        statusLine.setAgentsMdEnabled(enabled);
        aiService.getAgentsMdService().setEnabled(enabled);
    }

    private void refreshChat() {
        chatHistory.clearMessages();
        aiService.getActiveService().getMessages().forEach(chatHistory::appendMessage);
        refreshStatusLine();
        actionsBar.updateModeUI(aiService.getPeonMode(), isImplEnabled());
    }

    private boolean isImplEnabled() {
        return switch (aiService.getPeonMode()) {
            case PLAN  -> aiService.getPlannerService().getMessages().stream().anyMatch(m -> m.type() == ChatMessageType.AI);
            case AGENT -> aiService.getAgentMode().overviewExists();
            default    -> false;
        };
    }

    // -------------------------------------------------------------------------
    // Config / model loading
    // -------------------------------------------------------------------------

    private void applyConfig() {
        var config = LlmPreferenceInitializer.buildWithDefaults();
        if (lastAppliedConfig != null && lastAppliedConfig.equals(config)) return;
        lastAppliedConfig = config;
        LOG.info("Set new config " + config);
        try {
            aiService.getSkillService().refresh(config.getSkillDirectory());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load " + config.getSkillDirectory());
        }
        try {
            aiService.getCommandService().refresh(config.getCommandDirectory());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load " + config.getCommandDirectory());
        }
        aiService.updateConfig(config);
        // Sync the Think toggle to the config default. The user can override this per-session
        // via the button; that override is stored in-memory only and not written to preferences.
        actionsBar.setThinkEnabled(config.isThinkingEnabled());
        applyMcpConfig();
        chatInput.setVoiceInputVisible(VoicePreferenceInitializer.buildWithDefaults().enabled());
        syncAgentsMdToggle();
        refreshStatusLine();
        reloadModelsIfNeeded();
        applyShellCommandConfirmation();
    }

    private void applyMcpConfig() {
        var servers = McpPreferenceInitializer.loadServers();
        statusLine.setMcpAvailable(!servers.isEmpty());
        statusLine.setMcpEnabled(!servers.isEmpty() && McpPreferenceInitializer.isMcpEnabled());
        aiService.applyMcpConfig();
    }

    private void applyShellCommandConfirmation() {
        var prefs = InstanceScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID);
        var autonomous = aiService.getAgentMode().getAutonomous();

        // TODO move into own class?
        if ("true".equalsIgnoreCase(prefs.get(PeonConstants.PREF_SHELL_CONFIRMATION_ENABLED, "")) ||
                "always".equalsIgnoreCase(prefs.get(PeonConstants.PREF_SHELL_CONFIRMATION_ENABLED, "")) ||
                (!autonomous && "not-autonomous".equalsIgnoreCase(prefs.get(PeonConstants.PREF_SHELL_CONFIRMATION_ENABLED, "")))) {
            aiService.getToolService().getTool(ShellTool.class).ifPresent(shellTool -> {
                shellTool.setConfirmationProvider((command, workingDirectory) -> {
                    var latch = new java.util.concurrent.CountDownLatch(1);
                    var answer = new AtomicReference<>("No");
                    showQuestion("Allow executing shell command in the \"" + workingDirectory + "\" directory? " +
                            "(or you can enter a new command to execute below)\n\n" + command,
                            List.of("Yes", "No"),
                            a -> { answer.set(a); latch.countDown(); });
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (UserQuestionWidget.CANCEL.equals(answer.get())) {
                        throw new CancellationException("Canceled tool execution " + workingDirectory + " " + command);
                    }
                    return answer.get();
                });
            });

        } else {
            aiService.getToolService().getTool(ShellTool.class).ifPresent(shellTool -> {
                shellTool.setConfirmationProvider(null);
            });
        }
    }

    private void reloadModelsIfNeeded() {
        var config = aiService.getConfig();
        if (StringUtil.hasNoValue(actionsBar.getSelectedModel())
                && StringUtil.hasValue(config.getModel())) {
            actionsBar.setModel(config.getModel());
        }
        if (lastListedConfig.get() == null
                || config.getProviderType() != lastListedConfig.get().getProviderType()
                || !java.util.Objects.equals(config.getUrl(), lastListedConfig.get().getUrl())
                || !java.util.Objects.equals(config.getApiKey(), lastListedConfig.get().getApiKey())) {
            loadModelsInBackground();
        } else {
            EclipseUtil.runInUiThread(parent, () -> {
                if (!actionsBar.containsModelId(config.getModel())) {
                    loadModelsInBackground();
                } else {
                    actionsBar.selectModel(config.getModel());
                }
            });
        }
        lastListedConfig.set(config);
    }

    private void loadModelsInBackground() {
        Job.create("Fetching available models", monitor -> {
            var config = aiService.getConfig();
            try {
                var models = config.listAiModels();
                if (models.isEmpty()) {
                    onChatResponse(new SimpleMessage(Type.PROBLEM, "No models returned by " + config.getUrl()));
                } else {
                    EclipseUtil.runInUiThread(parent, () -> {
                        aiService.resolveModel(models);
                        actionsBar.applyModelList(models, aiService.getConfig().getModel());
                    });
                }
                return Status.OK_STATUS;
            } catch (Exception e) {
                onChatResponse(new SimpleMessage(Type.PROBLEM, e.getMessage()));
                if (StringUtil.hasValue(aiService.getConfig().getModel())) {
                    return new Status(IStatus.WARNING, PeonConstants.PLUGIN_ID, IStatus.OK, 
                            "Failed to load models fallback to " + aiService.getConfig().getModel(), e);
                } else {
                    return new Status(IStatus.ERROR, PeonConstants.PLUGIN_ID, IStatus.OK, 
                            "Failed to load models. " + e.getMessage() + " config:\n" + aiService.getConfig(), e);
                }
            }
        }).schedule();
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private void onModeChange(PeonMode mode) {
        aiService.getAgentMode().setAutonomous(actionsBar.getAutonomous());
        aiService.setPeonMode(mode);
        refreshChat();
        updateInputForMode();
        applyShellCommandConfirmation();
    }

    // -------------------------------------------------------------------------
    // Query-to-Source wizard
    // -------------------------------------------------------------------------

    /** Shows the wizard step bar in QUERY_TO_SOURCE mode; chat input stays visible in all modes. */
    private void updateInputForMode() {
        boolean qs = aiService.getPeonMode() == PeonMode.QUERY_TO_SOURCE;
        setControlExcluded(queryBar, !qs);
        setControlExcluded(hintComposite, !qs);
        if (qs) {
            var config = aiService.getQueryToSourceMode().getConfig();
            queryBar.setSteps(config.steps());
            queryBar.setShowStepNumbers(config.showStepNumbers());
            updateQueryBarState();
        }
        inputBlock.layout(true, true);
        applyAutoInputHeight();   // the hint row and step bar change the input's content height
    }

    private void setControlExcluded(Control control, boolean excluded) {
        if (control == null || control.isDisposed()) return;
        ((GridData) control.getLayoutData()).exclude = excluded;
        control.setVisible(!excluded);
    }

    private void updateQueryBarState() {
        if (queryBar == null || queryBar.isDisposed()) return;
        // Wizard-only state. Project selection, sending a prompt and call completion all funnel
        // through here, and in DEV/PLAN the step bar and hint are excluded by
        // updateInputForMode() — without this guard those events resurrect the hint.
        if (aiService.getPeonMode() != PeonMode.QUERY_TO_SOURCE) return;

        var project = userContext.getCurrentProject();
        boolean projectAvailable = project != null && project.isOpen();
        int completedStepIndex = aiService.getQueryToSourceMode().getCompletedStepIndex();
        queryBar.updateState(actionsBar.isWorking(), projectAvailable, completedStepIndex);

        // Update query hint
        var mode = aiService.getQueryToSourceMode();
        var next = mode.getNextStep();
        boolean showHint = next.isPresent() && StringUtil.hasValue(next.get().hint());
        boolean hintChanged = ((GridData) hintComposite.getLayoutData()).exclude == showHint;
        setControlExcluded(hintComposite, !showHint);
        if (showHint) {
            queryHintLabel.setText("💡 " + next.get().label() + ": " + next.get().hint());
        }
        // The hint is a row of the input block, so showing or hiding it resizes the pane.
        if (hintChanged) {
            inputBlock.layout(true, true);
            applyAutoInputHeight();
        }
    }

    private void onRunStep(int stepIndex, QueryStep step) {
        if (actionsBar.isWorking()) return;
        var mode = aiService.getQueryToSourceMode();
        if (mode.isStepCompleted(stepIndex)) {
            return;
        }
        if (StringUtil.hasNoValue(aiService.getModel())) {
            chatHistory.appendMessage(new SimpleMessage(Type.PROBLEM, "No model configured — open Window > Preferences > Peon AI"));
            return;
        }
        var active = mode.getService();
        final var selection = userContext.getUserSelection();
        final var needsSelection = !active.hasUserText(selection);
        var userText = StringUtil.strip(chatInput.getText().trim()) + (needsSelection ? selection : "");
        if (QueryToSourceModeService.requiresProject(step)) {
            var project = userContext.getCurrentProject();
            if (project == null || !project.isOpen()) {
                chatHistory.appendMessage(new SimpleMessage(Type.PROBLEM,
                        "Select an open project — this step needs workspace access."));
                return;
            }
        }
        var promptName = step.prompt();
        var body = aiService.resolvePromptBody(promptName);
        if (body == null) {
            chatHistory.appendMessage(new SimpleMessage(Type.PROBLEM,
                    "No prompt configured or loaded for step \"" + step.label()
                    + "\". Configure it in Query-to-Source settings (\u2699)."));
            return;
        }

        // Collect input fields if step defines any
        java.util.Map<String, String> fieldValues = java.util.Map.of();
        if (!step.fields().isEmpty()) {
            var dialog = new StepInputDialog(parent.getShell(), step.label(), step.fields());
            if (dialog.open() != org.eclipse.jface.dialogs.IDialogConstants.OK_ID) {
                return;  // User canceled
            }
            fieldValues = dialog.getResult();
        }

        active.setOneShotSystemPrompt(body);
        var message = mode.messageFor(step, userText, fieldValues);
        mode.markPending(stepIndex, step);
        chatHistory.appendMessage(new SimpleMessage(Type.USER, message));
        chatInput.clearText();
        lockWhileWorking(true);
        scheduleQueryCall(active, message);
    }

    private void scheduleQueryCall(AbstractChatService active, String text) {
        Job.create("Peon Query-to-Source", monitor -> {
            monitor.beginTask("Arbeit, Arbeit!", 100);
            monitorRef.set(monitor);
            Exception ex = null;
            try {
                active.setUserContextInformations(this.standingOrders.build());
                active.call(text, this);
            } catch (ToolExecutionException e) {
                if (!isCanceled() && !(e.getCause() instanceof CancellationException)) throw e;
            } catch (Exception e) {
                if (!isCanceled() || !(e instanceof CancellationException)) {
                    ex = e;
                    LOG.warn("Failed to call LLM " + aiService.getConfig(), e);
                    onChatResponse(new SimpleMessage(Type.PROBLEM, e.getMessage()));
                }
            } finally {
                monitor.done();
                monitorRef.set(new NullProgressMonitor());
                EclipseUtil.runInUiThread(parent, () -> lockWhileWorking(false));
            }
            return PeonConstants.status("Peon Query-to-Source", ex);
        }).schedule();
    }

    /** After a pipeline step finishes: reflect transform output, or run a compile check. */
    private void handleQueryToSourceCompletion() {
        if (aiService.getPeonMode() != PeonMode.QUERY_TO_SOURCE) return;
        var mode = aiService.getQueryToSourceMode();
        int stepIndex = mode.getPendingStepIndex();
        var step = mode.consumePendingStep();
        if (step == null) return;
        mode.markStepCompleted(stepIndex);
        if (step.kind() == StepKind.GENERATE) {
            runCompileCheck();
        }
    }

    private void runCompileCheck() {
        var project = userContext.getCurrentProject();
        if (project == null || !project.isOpen()) return;
        Job.create("Query-to-Source compile check", monitor -> {
            try {
                var errors = BuildDiagnosticsUtil.buildAndCollectErrors(project, monitor);
                EclipseUtil.runInUiThread(parent, () -> {
                    if (errors.isEmpty()) {
                        chatHistory.appendMessage(new SimpleMessage(Type.TOOL,
                                "Compile check: no errors in " + project.getName() + "."));
                    } else {
                        var msg = new StringBuilder("Compile check found " + errors.size() + " error(s):\n");
                        errors.stream().limit(50).forEach(e -> msg.append("- ").append(e).append("\n"));
                        chatHistory.appendMessage(new SimpleMessage(Type.PROBLEM, msg.toString()));
                    }
                });
                return PeonConstants.okStatus("Compile check done");
            } catch (Exception e) {
                return PeonConstants.errorStatus("Compile check failed", e);
            }
        }).schedule();
    }


    // TODO 29.03.2026 
    // currentMode should be moved to the aiService
    // so this can all happen in aiService.startImplementation(); returning us the currentMode for the UI
    // maybe we should even name the aiService AIChatViewController
    // refreshChat(); here
    // and sendTrigger.run(); from the AgentModeService can be maybe even be removed? not sure ...
    // at least be moved to the AIChatViewController
    // so doSendMessage(); here can be removed or better be reused? as we use this also as button action
    private void doStartImpl() {
        if (aiService.getPeonMode() == PeonMode.AGENT) {
            aiService.getAgentMode().startImplementation();
            refreshChat();
        } else {
            // PLAN -> DEV: hand off the plan to the developer service
            aiService.setPeonMode(PeonMode.DEV);
            actionsBar.updateModeUI(PeonMode.DEV, true);
            if (aiService.startImplementation()) {
                refreshChat();
                if (StringUtil.hasNoValue(chatInput.getText())) {
                    // some models e.g. Qwen need a use message as last message
                    chatInput.setText("""
                            Start implementing this plan. Save larger plans in the peon-plan/ directory using a sensible filename (for example, based on the title or main goal). 
                            Treat that plan file as your long-term memory when needed. 
                            Keep token usage low: when you switch to a different piece of work, 
                            use the compressor tool to summarize this session and echo the key next steps plus the plan file path in the preserved instructions.
                            """);
                }
                doSendMessage();
            } else {
                onChatResponse(new SimpleMessage(Type.PROBLEM, "Plan missing ..."));
            }
        }
    }

    private void doCompressContext() {
        chatHistory.clear();

        var active = aiService.getActiveService();
        if (active.getMessages().isEmpty()) return;
        lockWhileWorking(true);
        Job.create("Compressing context", monitor -> {
            monitor.beginTask("Compressing chat", 1);
            monitorRef.set(monitor);
            Exception ex = null;
            try {
                active.compressContext(this);
                Display.getDefault().asyncExec(this::refreshChat);
            } catch (Exception e) {
                ex = e;
            } finally {
                monitor.done();
                monitorRef.set(new NullProgressMonitor());
                EclipseUtil.runInUiThread(parent, () -> lockWhileWorking(false));
            }
            return PeonConstants.status("Compressed", ex);
        }).schedule();
    }

    private void doSendMessage() {
        if (StringUtil.hasNoValue(aiService.getModel())) {
            chatHistory.appendMessage(new SimpleMessage(Type.PROBLEM, "No model configured — open Window > Preferences > Peon AI"));
            return;
        }

        var active = aiService.getActiveService();

        final var selection = userContext.getUserSelection();
        final var needsSelection = !active.hasUserText(selection);

        final var text = StringUtil.strip(chatInput.getText().trim()) + (needsSelection ? selection : "");
        if (StringUtil.hasNoValue(text) && active.getMessages().isEmpty()) return;

        if (StringUtil.hasValue(text)) {
            chatHistory.appendMessage(new SimpleMessage(Type.USER, text));
            applySlashCommandIfPresent(active);
            chatInput.clearText();
            
            // already working -> we only append the current history ...
            if (actionsBar.isWorking()) {
                active.addMessage(UserMessage.from(text));
                return;
            }
        } else if (actionsBar.isWorking()) { // no text and already working ...
            return;
        }

        lockWhileWorking(true);
        Job.create("Peon AI request", monitor -> {
            monitor.beginTask("Arbeit, Arbeit!", 100);
            monitorRef.set(monitor);
            Exception ex = null;
            ChatResponse cr = null;
            try {
                active.setUserContextInformations(this.standingOrders.build());
                cr = active.call(text.isEmpty() ? null : text, this);
            } catch (ToolExecutionException e) {
                if (!isCanceled()) {
                    if (e.getCause() instanceof CancellationException) {
                        // yes this is fine
                    } else {
                        throw e;
                    }
                }
            } catch (Exception e) {
                if (!isCanceled() || !(e instanceof CancellationException)) {
                    ex = e;
                    LOG.warn("Failed to call LLM " + aiService.getConfig(), e);
                    onChatResponse(new SimpleMessage(Type.PROBLEM, e.getMessage()));
                }
            } finally {
                if (lastAppliedConfig != null && lastAppliedConfig.isDebugMode()) {
                    LOG.info("Chatreponse: " + (cr == null ? "null" : cr.aiMessage()));
                }
                monitor.done();
                monitorRef.set(new NullProgressMonitor());
                EclipseUtil.runInUiThread(parent, () -> lockWhileWorking(false));
            }
            return PeonConstants.status("Peon AI\n" + aiService.getConfig(), ex);
        }).schedule();
    }

    private void onPinChange(boolean pinned) {
        this.userContext.setProjectPinned(pinned);
        if (!pinned && userContext.getSelectedResource() != null) {
            var project = EclipseUtil.resolveProject(userContext.getSelectedResource());
            if (project != null) {
                userContext.setCurrentProject(project);
                aiService.setProject(project);
                actionsBar.setAgentModeAvailable(project.isOpen());
            }
        }
        statusLine.setPinned(pinned);
        refreshStatusLine();
    }

    private void lockWhileWorking(boolean value) {
        if (parent == null || parent.isDisposed()) return;
        actionsBar.lockWhileWorking(value);
        chatInput.setWorking(value);
        if (fileChangeReview != null && !fileChangeReview.isDisposed()) {
            fileChangeReview.setActionsEnabled(!value);
        }
        updateQueryBarState();
        if (!value) chatHistory.hideLiveStatus();
        if (!value && questionWidget != null && questionWidget.isVisible()) {
            questionWidget.cancel();
        }
    }

    private void showQuestion(String question, java.util.List<String> answers,
            java.util.function.Consumer<String> onAnswer) {
        chatHistory.updateLiveResponseInUIThread("Wating for User...", 0, null);
        EclipseUtil.runInUiThread(parent, () -> {
            setControlExcluded(chatInput, true);
            setControlExcluded(queryBar, true);
            ((GridData) questionWidget.getLayoutData()).exclude = false;
            questionWidget.setVisible(true);
            questionWidget.showQuestion(question, answers, a -> {
                chatHistory.appendMessage(new SimpleMessage(Type.USER, a));
                onAnswer.accept(a);
            });
            inputBlock.layout(true, true);
            applyAutoInputHeight();
        });
    }

    private void hideQuestion() {
        ((GridData) questionWidget.getLayoutData()).exclude = true;
        questionWidget.setVisible(false);
        questionWidget.hideQuestion();
        // Restore the correct input area for the active mode.
        updateInputForMode();
    }

    private void onSkillsToggle(boolean enabled) {
        aiService.getSkillService().setEnabled(enabled);
    }

    private void onSkillMenuSelection(SkillMenuSelection selection) {
        if (selection.isAllSkills) {
            aiService.getSkillService().setAllSkillsEnabled(selection.enabled);
        } else {
            aiService.getSkillService().setSkillEnabled(selection.skillName, selection.enabled);
        }
        EclipseUtil.runInUiThread(parent, this::refreshStatusLine);
    }

    /**
     * If the chat input starts with {@code /name}, looks up the command and installs its body as
     * the one-shot system prompt on the active chat service. The slash token is stripped from the
     * input so only the trailing user text is sent. Returns {@code false} and reports a problem
     * when the name is unknown so the caller can abort the send.
     */
    private void applySlashCommandIfPresent(AbstractChatService active) {
        var raw = chatInput.getText();
        if (raw == null) return;
        var trimmed = raw.stripLeading();
        if (!trimmed.startsWith("/")) return;

        int wsIdx = -1;
        for (int i = 1; i < trimmed.length(); i++) {
            if (Character.isWhitespace(trimmed.charAt(i))) { wsIdx = i; break; }
        }
        var name = wsIdx < 0 ? trimmed.substring(1) : trimmed.substring(1, wsIdx);
        var rest = wsIdx < 0 ? "" : trimmed.substring(wsIdx).stripLeading();
        if (name.isBlank()) return;

        var commandService = aiService.getCommandService();
        var command = commandService.get(name);
        if (command.isPresent()) {
            var prompt = command.get().readBody();
            active.setOneShotSystemPrompt(prompt);
            active.setOneShotCommandSlug(command.get().slug());
        } else {
            if (!commandService.hasCommands()) return;
            var available = commandService.commandNames();
            chatHistory.appendMessage(new SimpleMessage(Type.PROBLEM,
                    "Unknown command /" + name + ". Available: " + available));
            return;
        }
        // If only the slash token was entered, keep it visible as the user turn so the chat
        // history clearly shows which command was invoked AND the LLM receives a non-empty turn.
        chatInput.setText(rest.isEmpty() ? "/" + name : rest);
        chatInput.dismissSlashMenu();
    }

    private void onMicClick() {
        if (!recording) {
            recording = true;
            chatInput.setRecording(true);
            try {
                VoiceConfig voice = VoicePreferenceInitializer.buildWithDefaults()
                        .resolve(aiService.getConfig());
                voiceService.startRecording(voice);
            } catch (Exception e) {
                recording = false;
                chatInput.setRecording(false);
                onChatResponse(new SimpleMessage(Type.PROBLEM, "Cannot open microphone: " + e.getMessage()));
            }
        } else {
            recording = false;
            chatInput.setRecording(false);
            Job.create("Transcribing audio", monitor -> {
                try {
                    String text = voiceService.stopAndTranscribe();
                    EclipseUtil.runInUiThread(parent, () -> {
                        chatInput.setText(text);
                        doSendMessage();
                    });
                } catch (Exception e) {
                    return PeonConstants.errorStatus("Transcription failed", e);
                }
                return PeonConstants.okStatus("Transcription finished.");
            }).schedule();
        }
    }
}
