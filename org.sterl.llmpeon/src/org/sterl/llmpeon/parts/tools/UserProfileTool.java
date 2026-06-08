package org.sterl.llmpeon.parts.tools;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.sterl.llmpeon.shared.StringUtil;

import dev.langchain4j.agent.tool.Tool;

public class UserProfileTool extends AbstractEclipseTool {

    private static final String QUALIFIER = "com.hanwha.hone.core";
    private static final String PREF_KEY = "com.hanwha.hone.user.name";

    @Tool("Returns the current user's employee ID (사번) as stored in the Eclipse workspace preferences. "
        + "Use this when the user asks about their own employee ID / 사번.")
    public String getEmployeeId() {
        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(QUALIFIER);
        String value = prefs.get(PREF_KEY, null);

        onTool("Reading employee ID from workspace preferences");

        if (StringUtil.hasNoValue(value)) {
            return "Employee ID is not set in the workspace preferences (" + QUALIFIER + "/" + PREF_KEY + ").";
        }
        return value;
    }
}
