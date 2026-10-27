package org.sterl.llmpeon.parts;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.sterl.llmpeon.AbstractChatService;
import org.sterl.llmpeon.AiDeveloperService;
import org.sterl.llmpeon.AiPlannerService;
import org.sterl.llmpeon.AiQueryToSourceService;
import org.sterl.llmpeon.PeonMode;
import org.sterl.llmpeon.StandingOrdersBuilder.MessageProvider;
import org.sterl.llmpeon.ai.ConfiguredModel;
import org.sterl.llmpeon.ai.LlmConfig;
import org.sterl.llmpeon.ai.model.AiModel;
import org.sterl.llmpeon.command.CommandService;
import org.sterl.llmpeon.parts.agent.AgentModeService;
import org.sterl.llmpeon.parts.agentsmd.AgentsMdService;
import org.sterl.llmpeon.parts.config.LlmPreferenceInitializer;
import org.sterl.llmpeon.parts.config.QueryToSourcePreferenceInitializer;
import org.sterl.llmpeon.parts.querytosource.QueryToSourceModeService;
import org.sterl.llmpeon.parts.config.McpConnectionService;
import org.sterl.llmpeon.parts.shared.EclipseUtil;
import org.sterl.llmpeon.parts.shared.JdtUtil;
import org.sterl.llmpeon.parts.tools.AgentModeTool;
import org.sterl.llmpeon.parts.tools.EclipseBuildTool;
import org.sterl.llmpeon.parts.tools.EclipseCodeNavigationTool;
import org.sterl.llmpeon.parts.tools.EclipseConsoleLogTool;
import org.sterl.llmpeon.parts.tools.EclipseGrepTool;
import org.sterl.llmpeon.parts.tools.EclipseRunTestTool;
import org.sterl.llmpeon.parts.tools.EclipseWorkspaceReadFileTool;
import org.sterl.llmpeon.parts.tools.EclipseWorkspaceWriteFileTool;
import org.sterl.llmpeon.parts.tools.UserProfileTool;
import org.sterl.llmpeon.session.SessionAggregate;
import org.sterl.llmpeon.session.SessionDirectory;
import org.sterl.llmpeon.session.SessionManager;
import org.sterl.llmpeon.session.SessionSource;
import org.sterl.llmpeon.skill.SkillService;
import org.sterl.llmpeon.tool.ToolService;
import org.sterl.llmpeon.tool.tools.DiskFileReadTool;
import org.sterl.llmpeon.tool.tools.DiskFileWriteTool;
import org.sterl.llmpeon.tool.tools.DiskGrepTool;
import org.sterl.llmpeon.tool.tools.SkillTool;

import dev.langchain4j.data.message.UserMessage;

/**
 * Bundles all AI services into a single eagerly-initialized object.
 *
 * <p>Declaring this as a {@code final} field in {@link AIChatView} guarantees that all
 * services are non-null from the moment the view object is constructed — before Eclipse DI
 * has a chance to call any {@code @Inject} methods.</p>
 *
 * <p>Mutable state ({@code currentProject}, {@code currentConfig}) is held in
 * {@link AtomicReference} so that reads on background threads always see the latest values
 * without requiring locks. The downstream setters on individual services are called from the
 * Eclipse DI thread and are inherently serialized by the single-threaded event dispatch.</p>
 */
public class PeonAiService implements MessageProvider, SessionSource {

    private final ConfiguredModel configuredModel;
    private final ToolService toolService;
    private final SkillService skillService;
    private final CommandService commandService;
    private final AgentsMdService agentsMdService;
    private final AiDeveloperService developerService;
    private final AiPlannerService plannerService;
    private final AiQueryToSourceService queryToSourceService;
    private final QueryToSourceModeService queryToSourceMode;
    private final AgentModeService agentMode;
    private final AgentModeTool agentModeTool;
    private final McpConnectionService mcpConnectionService;
    
    private final EclipseWorkspaceWriteFileTool workspaceWriteFilesTool;
    private final DiskFileWriteTool diskFileWriteTool;
    private final DiskFileReadTool diskFileReadTool;
    private final DiskGrepTool diskGrepTool;

    /** Written from Eclipse DI thread, read from background LLM job threads. */
    private final AtomicReference<IProject> currentProject = new AtomicReference<>();

    private volatile PeonMode mode = PeonMode.DEV;

    /** Null when persistence is disabled or the store could not be opened — chat still works. */
    private final SessionManager sessionManager;
    
    /**
     * Package-private constructor for unit tests — accepts pre-built services to avoid
     * hard Eclipse dependencies ({@code EclipseUtil.workspacePath()}, Eclipse tools, etc.).
     */
    PeonAiService(ConfiguredModel configuredModel, AiPlannerService plannerService, AiDeveloperService developerService) {
        this.configuredModel = configuredModel;
        this.plannerService = plannerService;
        this.developerService = developerService;
        this.sessionManager = null;
        this.queryToSourceService = null;
        this.queryToSourceMode = null;
        this.toolService = null;
        this.skillService = null;
        this.commandService = null;
        this.agentsMdService = null;
        this.agentMode = null;
        this.agentModeTool = null;
        this.mcpConnectionService = null;
        this.workspaceWriteFilesTool = null;
        this.diskFileWriteTool = null;
        this.diskFileReadTool = null;
        this.diskGrepTool = null;
    }

    /**
     * Creates all AI services with defaults from the current Eclipse preferences.
     *
     * @param sendTrigger         callback to re-trigger the send loop (agent autonomous mode)
     * @param openInEditorCallback callback to open a file in the Eclipse editor
     * @param mcpStateChange      callback notified when MCP connected/disconnected
     */
    public PeonAiService(Runnable sendTrigger,
                         Consumer<IFile> openInEditorCallback,
                         Consumer<Boolean> mcpStateChange) {

        configuredModel = LlmPreferenceInitializer.buildWithDefaults().build();

        var rootPath            = EclipseUtil.workspacePath();
        toolService             = new ToolService();
        skillService            = new SkillService();
        commandService          = new CommandService();
        agentsMdService         = new AgentsMdService();
        
        toolService.addTool(new SkillTool(skillService));

        workspaceWriteFilesTool = new EclipseWorkspaceWriteFileTool();
        toolService.addTool(workspaceWriteFilesTool);
        toolService.addTool(new EclipseWorkspaceReadFileTool());
        
        diskFileWriteTool = new DiskFileWriteTool(rootPath);
        diskFileReadTool  = new DiskFileReadTool(rootPath);
        diskGrepTool      = new DiskGrepTool(rootPath);
        
        toolService.addTool(new EclipseBuildTool());
        toolService.addTool(new EclipseGrepTool());
        toolService.addTool(new EclipseRunTestTool());
        toolService.addTool(new EclipseCodeNavigationTool());
        toolService.addTool(new EclipseConsoleLogTool());
        toolService.addTool(new UserProfileTool());

        developerService = new AiDeveloperService(configuredModel, toolService);
        plannerService   = new AiPlannerService(configuredModel, toolService);

        // Query-to-Source uses its own service (isolated memory) but the shared edit tools
        queryToSourceService = new AiQueryToSourceService(configuredModel, toolService);
        queryToSourceMode    = new QueryToSourceModeService(queryToSourceService);

        // Agent mode uses separate instances with isolated memory
        var agentDev  = new AiDeveloperService(configuredModel, toolService);
        var agentPlan = new AiPlannerService(configuredModel, toolService);
        agentMode     = new AgentModeService(agentPlan, agentDev, sendTrigger, openInEditorCallback);
        agentModeTool = new AgentModeTool(agentMode);

        mcpConnectionService = new McpConnectionService(toolService, mcpStateChange);

        updateConfig(configuredModel.getConfig());

        sessionManager = openSessionManager(rootPath);
        if (sessionManager != null) {
            developerService.setTurnRecorder(sessionManager.recorder(PeonMode.DEV));
            plannerService.setTurnRecorder(sessionManager.recorder(PeonMode.PLAN));
            sessionManager.startFresh(); // design §9: launching always starts an empty session
            sessionManager.applyRetentionAsync(retentionPolicyFromPreferences());
        }
    }

    private static org.sterl.llmpeon.session.RetentionPolicy retentionPolicyFromPreferences() {
        var prefs = org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID);
        var defaults = org.sterl.llmpeon.session.RetentionPolicy.DEFAULT;
        return new org.sterl.llmpeon.session.RetentionPolicy(
                prefs.getInt(PeonConstants.PREF_SESSION_MAX_AGE_DAYS, defaults.maxAgeDays()),
                prefs.getInt(PeonConstants.PREF_SESSION_MAX_COUNT, defaults.maxCount()),
                defaults.minRetentionDays(),
                defaults.maxTotalBytes());
    }

    /** Persistence failures never break the chat view — they only disable session saving. */
    private SessionManager openSessionManager(java.nio.file.Path workspacePath) {
        var prefs = org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID);
        if (!prefs.getBoolean(PeonConstants.PREF_SESSION_PERSISTENCE_ENABLED, true)) return null;
        try {
            var base = prefs.get(PeonConstants.PREF_SESSION_BASE_PATH, "");
            var basePath = base == null || base.isBlank()
                    ? java.nio.file.Path.of(System.getProperty("user.home"), ".peon-ai")
                    : java.nio.file.Path.of(base);
            return new SessionManager(basePath, workspacePath, this);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(PeonAiService.class)
                    .warn("Session persistence disabled — store could not be opened", e);
            return null;
        }
    }
    
    /**
     * Propagates a new {@link LlmConfig} to all chat services and refreshes skills.
     * Safe to call from any thread.
     */
    public void updateConfig(LlmConfig config) {
        configuredModel.updateConfig(config);

        updateActiveDiskTools(config);
        if (config.getSkillDirectory() != null && !config.getSkillDirectory().isBlank()) {
            try {
                skillService.refresh(config.getSkillDirectory());
            } catch (IOException e) {
                throw new RuntimeException("Failed to load skills from " + config.getSkillDirectory(), e);
            }
        }
        try {
            commandService.refresh(config.getCommandDirectory());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load commands from " + config.getCommandDirectory(), e);
        }
    }

    private void updateActiveDiskTools(LlmConfig config) {
        if (config.isDiskToolsEnabled()) {
            if (toolService.getTool(DiskFileWriteTool.class).isEmpty()) {
                toolService.addTool(diskFileWriteTool);
                toolService.addTool(diskFileReadTool);
                toolService.addTool(diskGrepTool);
            }
        } else {
            if (toolService.getTool(DiskFileWriteTool.class).isPresent()) {
                toolService.removeTool(diskFileWriteTool);
                toolService.removeTool(diskFileReadTool);
                toolService.removeTool(diskGrepTool);
            }
        }
    }
    
    public AbstractChatService getActiveService() {
        return switch (getPeonMode()) {
            case DEV             -> getDeveloperService();
            case PLAN            -> getPlannerService();
            case QUERY_TO_SOURCE -> getQueryToSourceService();
            case AGENT           -> getAgentMode().getActiveService();
        };
    }

    /**
     * Updates the active project across all project-aware services.
     * Safe to call from any thread — each downstream setter manages its own state.
     */
    public void setProject(IProject project) {
        currentProject.set(project);
        agentsMdService.load(project);
        agentMode.setProject(project);  // volatile write inside AgentModeService

        var projectPath = JdtUtil.pathOf(project);

        workspaceWriteFilesTool.setCurrentProject(project);

        // disk tools work with the disk path not eclipse path
        projectPath = JdtUtil.diskPathOf(project);
        if (projectPath != null) {
            diskFileWriteTool.setWorkingDir(projectPath);
            diskFileReadTool.setWorkingDir(projectPath);
            diskGrepTool.setWorkingDir(projectPath);
        }
    }

    // -------------------------------------------------------------------------
    // Business logic
    // -------------------------------------------------------------------------

    /**
     * Performs the PLAN → DEV handoff: clears the developer service, adds the
     * "Start Implementation" trigger, and appends the last planner message (the
     * self-contained implementation plan) if one exists.
     * @return <code>true</code> if plan is found
     */
    public boolean startImplementation() {
        // LM Studio is sometimes bugged, if the first message is no user message ... :-/
        var plan = plannerService.extractLastPlan();
        if (plan.isPresent()) {
            developerService.clear();
            developerService.addMessage(UserMessage.from("Reading this plan:"));
            plan.ifPresent(developerService::addMessage);
        }
        return plan.isPresent();
    }

    // -------------------------------------------------------------------------
    // Sessions (chat-session design §5/§6/§9) — Phase 1: DEV + PLAN only
    // -------------------------------------------------------------------------

    /** Null when persistence is off; callers degrade to the previous no-session behavior. */
    public SessionManager getSessionManager() {
        return sessionManager;
    }

    /**
     * New empty session; keeps the saved one in history. The caller must have canceled the
     * running job first (UI protocol) — stale turns are dropped by the manager either way.
     */
    public void startNewSession() {
        if (sessionManager != null) sessionManager.startFresh();
        developerService.replaceMessages(List.of(), 0);
        plannerService.replaceMessages(List.of(), 0);
    }

    /**
     * Dedicated restore path (NOT {@link #setPeonMode(PeonMode)}): injects both memories and the
     * active mode verbatim — no reset, no plan re-injection, no auto-run.
     */
    public java.util.Optional<SessionDirectory.LoadedSession> restoreSession(String sessionId) {
        if (sessionManager == null) return java.util.Optional.empty();
        var loaded = sessionManager.resume(sessionId);
        if (loaded.isEmpty() || !loaded.get().compatible()) return loaded;

        var checkpoint = loaded.get().checkpoint();
        var aggregate = checkpoint.aggregate();
        var tokens = checkpoint.meta().contextTokens();
        developerService.replaceMessages(aggregate.devMessages(), tokens);
        plannerService.replaceMessages(aggregate.planMessages(), tokens);
        // safety net — the UI only offers resume in DEV/PLAN. Leaving AGENT must still detach its
        // tools (savePlan/reportPlanProblems), which setPeonMode would do; the rest of the mode
        // switch (overview re-injection, QUERY reset) stays skipped on purpose.
        if (!isSessionMode()) {
            getToolService().removeTool(getAgentModeTools());
            getAgentMode().reset();
        }
        this.mode = aggregate.activeMode() == PeonMode.PLAN ? PeonMode.PLAN : PeonMode.DEV;
        return loaded;
    }

    /** True in the modes whose memories are session-managed (Phase 1: DEV and PLAN, design §5). */
    public boolean isSessionMode() {
        return mode == PeonMode.DEV || mode == PeonMode.PLAN;
    }

    /**
     * Deleting the active session switches to a fresh one first (design §9.1) — synchronously,
     * so the view can reset right away. The returned future tracks the removal on disk.
     */
    public java.util.concurrent.CompletableFuture<Void> deleteSession(String sessionId) {
        if (sessionManager == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
        boolean wasActive = sessionId.equals(sessionManager.currentSessionId());
        var deleted = sessionManager.deleteSession(sessionId);
        if (wasActive) {
            developerService.replaceMessages(List.of(), 0);
            plannerService.replaceMessages(List.of(), 0);
        }
        return deleted;
    }

    /** Verifies the restored session's project binding against the workspace (design §7). */
    public boolean isProjectBindingValid(SessionDirectory.LoadedSession loaded) {
        var boundName = loaded.checkpoint().meta().projectName();
        if (boundName == null || boundName.isBlank()) return true; // unbound sessions open freely
        var project = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getProject(boundName);
        return project != null && project.exists();
    }

    // SessionSource — called on the turn's executing thread at commit points
    @Override
    public SessionAggregate snapshotAggregate() {
        var activeMode = mode == PeonMode.PLAN ? PeonMode.PLAN : PeonMode.DEV;
        return SessionAggregate.of(activeMode, developerService.getMessages(), plannerService.getMessages());
    }

    @Override
    public String projectName() {
        var p = currentProject.get();
        return p == null ? null : p.getName();
    }

    @Override
    public String projectLocation() {
        var p = currentProject.get();
        var path = p == null ? null : JdtUtil.diskPathOf(p);
        return path == null ? null : path.toString();
    }

    @Override
    public int contextTokens() {
        return (mode == PeonMode.PLAN ? plannerService : developerService).getContextSize();
    }

    /**
     * Shutdown: never blocks the UI thread (design §6). Every turn already committed at its
     * boundary; an in-flight one is recovered as interrupted through the WAL on the next start.
     */
    public void closeSessions() {
        if (sessionManager == null) return;
        var t = new Thread(sessionManager::close, "peon-session-close");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void applyMcpConfig() {
        mcpConnectionService.applyConfig();
    }

    public void disconnectMcp() {
        mcpConnectionService.disconnect();
    }

    // -------------------------------------------------------------------------
    // Accessors — all non-null after construction
    // -------------------------------------------------------------------------

    public IProject getProject() {
        return currentProject.get();
    }

    public LlmConfig getConfig() {
        return configuredModel.getConfig();
    }

    public AiDeveloperService getDeveloperService() {
        return developerService;
    }

    public AiPlannerService getPlannerService() {
        return plannerService;
    }

    public AgentModeService getAgentMode() {
        return agentMode;
    }

    public AgentModeTool getAgentModeTools() {
        return agentModeTool;
    }

    public ToolService getToolService() {
        return toolService;
    }

    public SkillService getSkillService() {
        return skillService;
    }

    public CommandService getCommandService() {
        return commandService;
    }

    public AgentsMdService getAgentsMdService() {
        return agentsMdService;
    }

    public McpConnectionService getMcpConnectionService() {
        return mcpConnectionService;
    }

    public void setModel(AiModel model) {
        if (this.configuredModel.withModel(model)) {
            LlmPreferenceInitializer.setModel(model.getId());
        }
    }

    public ConfiguredModel resolveModel(List<AiModel> models) {
        this.configuredModel.resolveModel(models);
        return configuredModel;
    }

    public String getModel() {
        return configuredModel.getModel();
    }

    public void withThinking(Boolean enabled) {
        if (enabled == null) enabled = Boolean.FALSE;
        configuredModel.withThinking(enabled);
    }

    public void setPeonMode(PeonMode mode) {
        this.mode = mode;
        if (mode == PeonMode.AGENT) {
            getToolService().addTool(getAgentModeTools());
            getAgentMode().reset();
            if (getAgentMode().overviewExists()) {
                getAgentMode().getActiveService().addMessage(UserMessage.from(
                        "Existing plan found:\n\n" + getAgentMode().readOverview()));
                getAgentMode().openOverviewInEditor();
            }
        } else {
            getToolService().removeTool(getAgentModeTools());
            getAgentMode().reset();
        }
        if (mode == PeonMode.QUERY_TO_SOURCE) {
            queryToSourceMode.setConfig(QueryToSourcePreferenceInitializer.load());
            queryToSourceMode.reset();
        }
    }

    public AiQueryToSourceService getQueryToSourceService() {
        return queryToSourceService;
    }

    public QueryToSourceModeService getQueryToSourceMode() {
        return queryToSourceMode;
    }

    /**
     * Resolves the body of a prompt referenced by name, searching commands first then skills.
     * Returns {@code null} when the name is blank or no matching prompt is loaded.
     */
    public String resolvePromptBody(String name) {
        if (name == null || name.isBlank()) return null;
        var cmd = commandService.get(name);
        if (cmd.isPresent()) return cmd.get().readBody();
        var skill = skillService.get(name);
        if (skill.isPresent()) return skill.get().readBody();
        return null;
    }

    /** Names of all loaded commands and skills, de-duplicated and sorted case-insensitively. */
    public java.util.List<String> availablePromptNames() {
        var set = new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        commandService.getAllLoadedCommands().forEach(c -> set.add(c.name()));
        skillService.getAllLoadedSkills().forEach(s -> set.add(s.name()));
        return new java.util.ArrayList<>(set);
    }
    
    public PeonMode getPeonMode() {
        return mode;
    }

    // TODO do we provide this twice?
    @Override
    public String get() {
        var agentMode = getAgentMode();
        if (getPeonMode() == PeonMode.AGENT && agentMode.hasPlan()) {
            return agentMode.planPathHint();
        }
        return null;
    }
}
