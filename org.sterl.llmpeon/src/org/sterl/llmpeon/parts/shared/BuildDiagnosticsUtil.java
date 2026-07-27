package org.sterl.llmpeon.parts.shared;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

/**
 * Small helper to trigger an Eclipse build and collect error markers. Extracted so both the
 * AI-callable build tool and the Query-to-Source wizard (post-generation compile check) can
 * reuse the same marker aggregation.
 */
public final class BuildDiagnosticsUtil {

    private BuildDiagnosticsUtil() {}

    /**
     * Runs a full build of the given project and returns its compile errors as formatted lines.
     *
     * @return one line per error ("message @ line N @ file path"); empty list when there are none
     */
    public static List<String> buildAndCollectErrors(IProject project, IProgressMonitor monitor) throws CoreException {
        if (project == null || !project.isOpen()) return List.of();
        project.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
        return collectErrors(project);
    }

    /** Collects existing error markers of the project without triggering a build. */
    public static List<String> collectErrors(IProject project) throws CoreException {
        if (project == null || !project.isOpen()) return List.of();
        var errors = new ArrayList<String>();
        IMarker[] markers = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
        for (IMarker marker : markers) {
            int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
            if (severity == IMarker.SEVERITY_ERROR) {
                errors.add(markerToString(marker));
            }
        }
        return errors;
    }

    private static String markerToString(IMarker marker) {
        String message = marker.getAttribute(IMarker.MESSAGE, "");
        var file = marker.getResource().getFullPath().toPortableString();
        int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
        return message + " @ line " + line + " @ file " + file;
    }
}
