package org.sterl.llmpeon.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sterl.llmpeon.parts.tools.EclipseWorkspaceWriteFileTool;
import org.sterl.llmpeon.shared.AiMonitor;
import org.sterl.llmpeon.shared.AiMonitor.AiFileUpdate;
import org.sterl.llmpeon.tool.model.SimpleMessage;

public class EclipseWorkspaceWriteFileToolSnapshotTest {

    private static final String PROJECT_NAME = "peon-snapshot-test";

    private IProject project;

    @Before
    public void before() throws CoreException {
        assumeTrue("Eclipse workspace not available", isWorkspaceAvailable());
        project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT_NAME);
        var monitor = new NullProgressMonitor();
        if (project.exists()) {
            project.delete(true, true, monitor);
        }
        project.create(monitor);
        project.open(monitor);
    }

    @After
    public void after() throws CoreException {
        if (project != null && project.exists()) {
            project.delete(true, true, new NullProgressMonitor());
        }
    }

    @Test
    public void test_editWorkspaceFile_reports_full_file_update_snapshot() {
        var captured = new AtomicReference<AiFileUpdate>();
        var tool = new CapturingWriteTool(update -> captured.set(update));
        var fileName = "/" + PROJECT_NAME + "/foo.txt";
        var original = """
                class Example {
                    void first() {
                        callOne();
                    }

                    void second() {
                        callTwo();
                    }
                }
                """;
        var replacement = """
                    void first() {
                        callReplacement();
                    }
                """;
        tool.writeWorkspaceFile(fileName, original);
        captured.set(null);

        tool.editWorkspaceFile(fileName, """
                    void first() {
                        callOne();
                    }
                """, replacement);

        var update = captured.get();
        assertNotNull(update);
        assertEquals(original, update.oldContent());
        assertTrue(update.newContent(), update.newContent().contains(replacement));
        assertTrue(update.newContent(), update.newContent().contains("void second()"));
    }

    private static boolean isWorkspaceAvailable() {
        try {
            ResourcesPlugin.getWorkspace();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static class CapturingWriteTool extends EclipseWorkspaceWriteFileTool {
        CapturingWriteTool(java.util.function.Consumer<AiFileUpdate> onUpdate) {
            this.monitor = new AiMonitor() {
                @Override
                public void onChatResponse(SimpleMessage message) {
                }

                @Override
                public void onFileUpdate(AiFileUpdate update) {
                    onUpdate.accept(update);
                }
            };
        }
    }
}
