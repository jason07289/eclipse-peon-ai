package org.sterl.llmpeon.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.eclipse.core.resources.IFile;
import org.junit.Test;
import org.sterl.llmpeon.parts.shared.EclipseUtil;

/**
 * Regression coverage for cross-project / disk-tool paths in {@link EclipseUtil#resolveInEclipse(String)}.
 *
 * <p>When the working directory is pinned to one project and a disk tool edits a file in another
 * project, the recorded path is an absolute OS path (see {@code DiskFileWriteTool.recordPath}).
 * The Undo bar and "open in editor" links must resolve such paths — and must also tolerate
 * Windows-style backslashes — otherwise clicking the link does nothing and Undo fails with
 * "Cannot restore missing file".
 */
public class EclipseUtilResolveTest extends AbstractTest {

    /** A file guaranteed to exist at the project root. */
    private IFile projectFile() {
        assumeTrue("Eclipse workspace / project not available", isWorkspaceAvailable() && project != null);
        IFile file = project.getFile(".project");
        assumeTrue(".project file expected to exist", file.exists());
        return file;
    }

    @Test
    public void resolvesWorkspaceRelativePath() {
        IFile file = projectFile();
        String workspacePath = file.getFullPath().toPortableString(); // /<project>/.project

        var resolved = EclipseUtil.resolveInEclipse(workspacePath);

        assertIsPresent(resolved);
        assertEquals(file, resolved.get());
    }

    @Test
    public void resolvesWorkspacePathWithBackslashes() {
        IFile file = projectFile();
        String backslashed = file.getFullPath().toPortableString().replace('/', '\\');

        var resolved = EclipseUtil.resolveInEclipse(backslashed);

        assertIsPresent(resolved);
        assertEquals(file, resolved.get());
    }

    @Test
    public void resolvesAbsoluteOsPathViaLocation() {
        IFile file = projectFile();
        String absolute = file.getLocation().toOSString(); // real path on disk

        var resolved = EclipseUtil.resolveInEclipse(absolute);

        assertIsPresent(resolved);
        assertEquals(file, resolved.get());
    }

    @Test
    public void resolvesAbsoluteOsPathWithForwardSlashes() {
        IFile file = projectFile();
        String absolute = file.getLocation().toOSString().replace('\\', '/');

        var resolved = EclipseUtil.resolveInEclipse(absolute);

        assertIsPresent(resolved);
        assertEquals(file, resolved.get());
    }

    @Test
    public void unknownPathResolvesToEmpty() {
        assumeTrue("Eclipse workspace not available", isWorkspaceAvailable());

        assertIsEmpty(EclipseUtil.resolveInEclipse("/does-not-exist/nope.txt"));
        assertIsEmpty(EclipseUtil.resolveInEclipse("..\\other-project\\src\\Missing.java"));
    }

    @Test
    public void blankPathResolvesToEmpty() {
        assertIsEmpty(EclipseUtil.resolveInEclipse(null));
        assertIsEmpty(EclipseUtil.resolveInEclipse(""));
        assertTrue(EclipseUtil.resolveInEclipse("   ").isEmpty());
    }
}
