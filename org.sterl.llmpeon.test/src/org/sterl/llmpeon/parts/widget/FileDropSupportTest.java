package org.sterl.llmpeon.parts.widget;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.eclipse.core.resources.ResourcesPlugin;
import org.junit.Test;

public class FileDropSupportTest {

    @Test
    public void formatsSingleDroppedWorkspacePath() {
        assertEquals("@/llmpeon-parent/pom.xml",
                FileDropSupport.formatDroppedPaths(List.of("/llmpeon-parent/pom.xml")));
    }

    @Test
    public void formatsMultipleDroppedPathsOnSeparateLines() {
        assertEquals("@/tmp/a.txt\n@/tmp/b.txt",
                FileDropSupport.formatDroppedPaths(List.of("/tmp/a.txt", "/tmp/b.txt")));
    }

    @Test
    public void skipsBlankDroppedPaths() {
        assertEquals("@/tmp/a.txt",
                FileDropSupport.formatDroppedPaths(List.of("", "  ", "/tmp/a.txt")));
    }

    @Test
    public void readsWorkspaceResourcePaths() {
        var resource = ResourcesPlugin.getWorkspace().getRoot()
                .getProject("Project")
                .getFile("pom.xml");

        assertEquals(List.of("/Project/pom.xml"),
                FileDropSupport.pathsFromDropData(resource));
    }

    @Test
    public void keepsExternalOsFileTransferPaths() {
        assertEquals(List.of("/tmp/external-file.txt"),
                FileDropSupport.pathsFromDropData(new String[] { "/tmp/external-file.txt" }));
    }
}
