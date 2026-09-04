package org.sterl.llmpeon.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sterl.llmpeon.StandingOrdersBuilder;
import org.sterl.llmpeon.parts.agentsmd.AgentsMdService;

public class AgentsMdServiceGlobalTest {

    private Path dir;
    private final AgentsMdService subject = new AgentsMdService();

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("peon-global-agents");
    }

    @After
    public void tearDown() throws Exception {
        if (dir == null) return;
        try (var files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    public void test_loads_file_path() throws Exception {
        // GIVEN
        var file = Files.writeString(dir.resolve("AGENTS.md"), "always answer in german");

        // WHEN
        boolean found = subject.loadGlobal(file.toString());

        // THEN
        assertTrue(found);
        assertEquals(file, subject.getGlobalAgentFile());
        var orders = new StandingOrdersBuilder().add(subject.globalProvider()).build();
        AbstractTest.assertHasMessageWith(orders, "always answer in german");
        AbstractTest.assertHasMessageWith(orders, file.toString());
    }

    @Test
    public void test_loads_directory_path() throws Exception {
        // GIVEN a directory like ~/.claude
        var file = Files.writeString(dir.resolve("agents.md"), "no emojis");

        // WHEN
        boolean found = subject.loadGlobal(dir.toString());

        // THEN the known agent file names are resolved inside the directory
        assertTrue(found);
        assertEquals(file.getFileName(), subject.getGlobalAgentFile().getFileName());
    }

    @Test
    public void test_missing_path_is_ignored() {
        // WHEN
        assertFalse(subject.loadGlobal(dir.resolve("nope.md").toString()));
        assertFalse(subject.loadGlobal(""));
        assertFalse(subject.loadGlobal(null));

        // THEN no orders and no agent file to show
        assertTrue(new StandingOrdersBuilder().add(subject.globalProvider()).build().isEmpty());
        assertNull(subject.getAgentFileName());
    }

    @Test
    public void test_disabled_sends_nothing() throws Exception {
        // GIVEN
        Files.writeString(dir.resolve("AGENTS.md"), "always answer in german");
        subject.loadGlobal(dir.toString());

        // WHEN the AGENTS.md toggle is off
        subject.setEnabled(false);

        // THEN
        assertTrue(new StandingOrdersBuilder().add(subject.globalProvider()).build().isEmpty());
    }

    @Test
    public void test_global_file_name_is_marked() throws Exception {
        // GIVEN no project file, only a global one
        Files.writeString(dir.resolve("AGENTS.md"), "no emojis");
        subject.loadGlobal(dir.toString());

        // THEN the status line can tell both apart
        assertEquals("AGENTS.md (global)", subject.getAgentFileName());
    }
}
