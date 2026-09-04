package org.sterl.llmpeon.parts.agentsmd;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.templates.TemplateContext;
import org.sterl.llmpeon.StandingOrdersBuilder.MessageProvider;
import org.sterl.llmpeon.parts.shared.EclipseUtil;
import org.sterl.llmpeon.parts.shared.JdtUtil;
import org.sterl.llmpeon.shared.StringUtil;

import dev.langchain4j.data.message.AiMessage;

public class AgentsMdService implements MessageProvider {

    private static final ILog LOG = Platform.getLog(AgentsMdService.class);

    private static final String HEADER =
            "%s\n" +
            "Use this file for critical, non-obvious, always-needed project settings — like workspace memory, but scoped to this project. Edit it directly. Keep it very short, and update or clean up entries as work evolves so only current, relevant rules remain.\n" +
            "---\n";

    private static final String GLOBAL_HEADER =
            "%s\n" +
            "Global rules of this user, they apply to every project. Project rules win if both say something about the same topic.\n" +
            "---\n";

    private IProject project;
    private final AtomicReference<IFile> agentsMd = new AtomicReference<>();
    private final AtomicReference<Path> globalAgentsMd = new AtomicReference<>();
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    /**
     * Standing orders provider for the user global agents file. Kept separate from this service
     * so switching the project does not re-send the unchanged global rules again.
     */
    private final MessageProvider globalProvider = this::globalOrders;

    @Override
    public String get() {
        if (!enabled.get() || agentsMd.get() == null || !agentsMd.get().exists()) return null;
        var f = agentsMd.get();
        try {
            var text = f.readString();
            return String.format(HEADER, JdtUtil.pathOf(f)) + " full content:\n" + text;
        } catch (CoreException e) {
            throw new RuntimeException(e);
        }
    }

    /** Provider for the global agents file, register it next to this service in the standing orders. */
    public MessageProvider globalProvider() {
        return globalProvider;
    }

    private String globalOrders() {
        var file = globalAgentsMd.get();
        if (!enabled.get() || file == null || !Files.isReadable(file)) return null;
        try {
            return String.format(GLOBAL_HEADER, file) + " full content:\n" + Files.readString(file);
        } catch (IOException e) {
            // a broken global config must not stop the chat, the project rules are still fine
            LOG.warn("Failed to read global agents file " + file, e);
            return null;
        }
    }

    public void setEnabled(boolean value) {
        enabled.set(value);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    /** Loads the AGENTS.md / agents.md content for the given path. */
    public boolean load(IProject inProject) {
        if (inProject == null) {
            agentsMd.set(null);
            return false;
        }
        this.project = inProject;
        agentsMd.set(resolveFile().orElse(null));

        return hasAgentFile();
    }

    /**
     * Loads the user global agents file, e.g. <code>~/.claude/AGENTS.md</code>. A directory is
     * scanned for one of the known {@link #NAMES}, a file is taken as is. A missing or empty
     * path simply disables the global rules.
     */
    public boolean loadGlobal(String path) {
        globalAgentsMd.set(resolveGlobalFile(path).orElse(null));
        return hasGlobalAgentFile();
    }

    /** Returns a processed {@link AiMessage} with path header when enabled and file present, empty otherwise. */
    public Optional<AiMessage> agentMessage(TemplateContext context) {
        IFile file = agentsMd.get();
        if (file == null || !enabled.get()) return Optional.empty();
        String content;
        try {
            content = file.readString();
        } catch (CoreException e) {
            throw new RuntimeException("Failed to read " + file, e);
        }
        String fullText = String.format(HEADER, JdtUtil.pathOf(file)) + " content:\n" + content;
        return Optional.of(AiMessage.from(fullText));
    }

    /**
     * Returns the agent file name to show in the UI: the project file if there is one, otherwise
     * the global file marked as such, or <code>null</code> if no agent file was found at all.
     */
    public String getAgentFileName() {
        IFile file = agentsMd.get();
        if (file != null) return file.getName();
        Path global = globalAgentsMd.get();
        return global == null ? null : global.getFileName() + " (global)";
    }

    public boolean hasAgentFile() {
        return agentsMd.get() != null;
    }

    public boolean hasGlobalAgentFile() {
        return globalAgentsMd.get() != null;
    }

    /** Path of the global agents file in use, or <code>null</code> if none was found. */
    public Path getGlobalAgentFile() {
        return globalAgentsMd.get();
    }

    static final String NAMES[] = {
            "AGENTS.MD",
            "AGENTS.md",
            "Agents.md",
            "agents.md",
            "rules.md",
            "AGENT.md",
    };
    private Optional<IFile> resolveFile() {
        if (project == null) return Optional.empty();
        for (String n : NAMES) {
            var r = EclipseUtil.findMember(project, n);
            if (r.isPresent()) return r;
        }
        return Optional.empty();
    }

    static Optional<Path> resolveGlobalFile(String path) {
        if (StringUtil.hasNoValue(path)) return Optional.empty();
        Path p;
        try {
            p = Path.of(expandUserHome(path.trim()));
        } catch (Exception e) {
            LOG.warn("Invalid global agents file path " + path, e);
            return Optional.empty();
        }
        if (Files.isDirectory(p)) {
            for (String n : NAMES) {
                var f = p.resolve(n);
                if (Files.isRegularFile(f)) return Optional.of(realPath(f));
            }
            return Optional.empty();
        }
        return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }

    /**
     * Case insensitive file systems (macOS, Windows) match any of the {@link #NAMES} spellings,
     * so ask the file system for the name the file really has before showing it to the user.
     */
    private static Path realPath(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p;
        }
    }

    private static String expandUserHome(String path) {
        if (path.equals("~")) return System.getProperty("user.home");
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home"), path.substring(2)).toString();
        }
        return path;
    }
}
