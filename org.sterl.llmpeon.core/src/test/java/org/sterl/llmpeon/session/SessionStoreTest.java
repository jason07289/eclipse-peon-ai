package org.sterl.llmpeon.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sterl.llmpeon.PeonMode;

import dev.langchain4j.data.message.UserMessage;

class SessionStoreTest {

    @TempDir
    Path base;
    @TempDir
    Path workspace;

    private SessionMeta meta(String title) {
        long now = System.currentTimeMillis();
        return SessionMeta.builder()
                .title(title).status(SessionStatus.COMPLETED)
                .createdAt(now).updatedAt(now).projectName("demo")
                .build();
    }

    private SessionAggregate aggregate() {
        return SessionAggregate.of(PeonMode.DEV, List.of(UserMessage.from("hi")), List.of());
    }

    @Test
    void test_storeDirectory_isReadableAndCollisionSafe() {
        var dir = SessionStore.storeDirectory(base, workspace);

        var name = dir.getFileName().toString();
        assertThat(dir.getParent().getFileName().toString()).isEqualTo("sessions");
        assertThat(name).startsWith(workspace.getFileName().toString() + "-");
        assertThat(name).matches(".+-[0-9a-f]{16}");
    }

    @Test
    void test_sanitize_windowsForbiddenChars() {
        assertThat(SessionStore.sanitize("my:ws*name?")).isEqualTo("my_ws_name_");
        assertThat(SessionStore.sanitize("  ")).isEqualTo("workspace");
    }

    @Test
    void test_identity_mismatch_isRefused() throws IOException {
        try (var store = SessionStore.open(base, workspace)) {
            assertThat(store.isWritable()).isTrue();
        }
        // another workspace whose directory would collide (simulated by copying the store dir)
        var otherWorkspace = Files.createDirectories(workspace.resolveSibling("other-ws"));
        var collidingDir = SessionStore.storeDirectory(base, otherWorkspace);
        Files.createDirectories(collidingDir.getParent());
        Files.move(SessionStore.storeDirectory(base, workspace), collidingDir);

        assertThatThrownBy(() -> SessionStore.open(base, otherWorkspace))
                .isInstanceOf(SessionStore.StoreOwnershipException.class)
                .hasMessageContaining("refusing");
    }

    @Test
    void test_secondInstance_opensReadOnly() {
        try (var first = SessionStore.open(base, workspace)) {
            assertThat(first.isWritable()).isTrue();

            try (var second = SessionStore.open(base, workspace)) {
                assertThat(second.isWritable()).isFalse();
                // reading works
                assertThat(second.listSessions()).isEmpty();
                // mutations are refused
                assertThatThrownBy(() -> second.createSession(meta("x"), aggregate()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("read-only");
            }
        }
        // after close the lock is free again
        try (var third = SessionStore.open(base, workspace)) {
            assertThat(third.isWritable()).isTrue();
        }
    }

    @Test
    void test_createListDelete_lifecycle() {
        try (var store = SessionStore.open(base, workspace)) {
            var s1 = store.createSession(meta("first"), aggregate());
            store.createSession(meta("second"), aggregate());

            var listed = store.listSessions();
            assertThat(listed).hasSize(2);
            assertThat(listed).extracting(SessionStore.IndexEntry::title).contains("first", "second");
            assertThat(listed.get(0).mode()).isEqualTo("DEV");
            assertThat(listed.get(0).compatible()).isTrue();

            store.deleteSession(s1.sessionId());
            assertThat(store.listSessions()).hasSize(1);
            assertThat(Files.exists(s1.dir())).isFalse();
        }
    }

    @Test
    void test_index_isOnlyACache_rebuiltAfterLoss() throws IOException {
        try (var store = SessionStore.open(base, workspace)) {
            store.createSession(meta("survivor"), aggregate());
            Files.delete(store.root().resolve(SessionStore.INDEX_JSON));

            assertThat(store.listSessions()).hasSize(1);
            assertThat(store.listSessions().get(0).title()).isEqualTo("survivor");
        }
    }

    @Test
    void test_corruptIndex_isRebuilt() throws IOException {
        try (var store = SessionStore.open(base, workspace)) {
            store.createSession(meta("survivor"), aggregate());
            Files.writeString(store.root().resolve(SessionStore.INDEX_JSON), "not json");

            assertThat(store.listSessions()).hasSize(1);
        }
    }

    @Test
    void test_sessionMeta_getsIdAndWorkspaceAssigned() {
        try (var store = SessionStore.open(base, workspace)) {
            var session = store.createSession(meta("auto-id"), aggregate());
            var loaded = session.load().orElseThrow();

            assertThat(loaded.checkpoint().meta().id()).isNotBlank();
            assertThat(loaded.checkpoint().meta().workspacePath()).isEqualTo(store.workspacePath());
            assertThat(session.sessionId()).isEqualTo(loaded.checkpoint().meta().id());
        }
    }

    @Test
    void test_updateIndex_reflectsCommit() {
        try (var store = SessionStore.open(base, workspace)) {
            var session = store.createSession(meta("before"), aggregate());

            var newMeta = session.load().orElseThrow().checkpoint().meta().toBuilder()
                    .title("after").updatedAt(System.currentTimeMillis() + 1000).build();
            session.commit(newMeta, aggregate(), 0);
            store.updateIndex(session);

            assertThat(store.listSessions().get(0).title()).isEqualTo("after");
        }
    }

    @Test
    void test_buildTitle_truncatesAndSingleLines() {
        assertThat(SessionMeta.buildTitle("  hello\nworld  ")).isEqualTo("hello");
        assertThat(SessionMeta.buildTitle("x".repeat(100))).hasSize(60).endsWith("…");
        assertThat(SessionMeta.buildTitle(null)).isEqualTo("New session");
    }
}
