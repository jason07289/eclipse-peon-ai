package org.sterl.llmpeon.tool.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Verifies the path recorded in {@code AiFileUpdate} / editor links.
 *
 * <p>Regression: when the working directory is pinned to one project and a disk tool edits a file
 * in a <em>sibling</em> project, {@code workingDir.relativize()} produced a {@code ..\other\...}
 * path (backslashes + parent segments on Windows) that the Eclipse side could neither open nor
 * undo ("Cannot restore missing file"). Out-of-workingDir files must be recorded as an absolute,
 * forward-slash path so the workspace can map them back by location.
 */
class DiskFileWritePathTest {

    private final Path workingDir = Path.of("/projects/lco-svc");

    @Test
    void fileDirectlyInWorkingDir_isRelative() {
        var result = DiskFileWriteTool.recordPath(workingDir, Path.of("/projects/lco-svc/Foo.java"));
        assertThat(result).isEqualTo("Foo.java");
    }

    @Test
    void nestedFileInWorkingDir_isRelativeWithForwardSlashes() {
        var result = DiskFileWriteTool.recordPath(workingDir,
                Path.of("/projects/lco-svc/src/main/java/App.java"));
        assertThat(result).isEqualTo("src/main/java/App.java");
    }

    @Test
    void siblingProjectFile_isRecordedAbsolute() {
        var result = DiskFileWriteTool.recordPath(workingDir,
                Path.of("/projects/exm-svc/src/main/java/EcstMgmtCSC.java"));
        assertThat(result).isEqualTo("/projects/exm-svc/src/main/java/EcstMgmtCSC.java");
    }

    @Test
    void parentSegmentsAreNormalizedAway() {
        var result = DiskFileWriteTool.recordPath(workingDir,
                Path.of("/projects/lco-svc/../exm-svc/B.java"));
        assertThat(result).isEqualTo("/projects/exm-svc/B.java");
    }

    @Test
    void relativeResolvedPathIsMadeAbsoluteAndNeverEscapesWithDotDot() {
        // Whatever we record must be resolvable on the Eclipse side: never a ".."-relative path.
        var result = DiskFileWriteTool.recordPath(workingDir,
                Path.of("/projects/exm-svc/deep/nested/File.java"));
        assertThat(result).doesNotContain("..");
    }

    @Test
    void resultNeverContainsBackslash() {
        assertThat(DiskFileWriteTool.recordPath(workingDir, Path.of("/projects/lco-svc/a/b/c.java")))
                .doesNotContain("\\");
        assertThat(DiskFileWriteTool.recordPath(workingDir, Path.of("/projects/exm-svc/a/b/c.java")))
                .doesNotContain("\\");
    }
}
