package org.sterl.llmpeon.parts.widget;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.sterl.llmpeon.session.SessionStatus;
import org.sterl.llmpeon.session.SessionStore;

/**
 * Session history (chat-session design §9.1): recent-first rows with title, bound project and
 * last-used time. Selecting a row changes nothing — only the Resume button activates a session,
 * Delete removes one after confirmation, and dismissing the dialog leaves the current
 * conversation untouched. In a read-only store (another instance holds the lock) rows can be
 * resumed for reading but not deleted.
 */
public class SessionHistoryDialog extends Dialog {

    private static final int RESUME_ID = IDialogConstants.CLIENT_ID + 1;
    private static final int DELETE_ID = IDialogConstants.CLIENT_ID + 2;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Supplier<List<SessionStore.IndexEntry>> sessions;
    /** Runs the delete off the UI thread; the dialog refreshes when the future completes. */
    private final Function<String, CompletableFuture<Void>> onDelete;
    private final boolean readOnly;

    private Table table;
    private Label emptyHint;
    private String selectedSessionId;
    /** UI thread only — true while a delete is still running on the session writer. */
    private boolean deleting;

    public SessionHistoryDialog(Shell parentShell,
            Supplier<List<SessionStore.IndexEntry>> sessions,
            Function<String, CompletableFuture<Void>> onDelete,
            boolean readOnly) {
        super(parentShell);
        this.sessions = sessions;
        this.onDelete = onDelete;
        this.readOnly = readOnly;
    }

    /** The session the user chose to resume, set only when the dialog ended with Resume. */
    public String getSelectedSessionId() {
        return selectedSessionId;
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText(readOnly ? "Chat Sessions (read-only)" : "Chat Sessions");
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        var area = (Composite) super.createDialogArea(parent);

        if (readOnly) {
            var hint = new Label(area, SWT.WRAP);
            hint.setText("Another Eclipse instance is using this session store — new conversations are not"
                    + " saved and sessions cannot be deleted here.");
            hint.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        }

        emptyHint = new Label(area, SWT.WRAP);
        emptyHint.setText("No saved conversations yet — a session appears here after its first message is sent.");
        emptyHint.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        table = new Table(area, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        var gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.widthHint = 560;
        gd.heightHint = 280;
        table.setLayoutData(gd);

        addColumn("Title", 260);
        addColumn("Project", 100);
        addColumn("Last used", 120);
        addColumn("Status", 90);

        table.addListener(SWT.Selection, e -> updateButtons());
        table.addListener(SWT.DefaultSelection, e -> buttonPressed(RESUME_ID)); // double-click = Resume

        refresh();
        return area;
    }

    private void addColumn(String name, int width) {
        var col = new TableColumn(table, SWT.LEFT);
        col.setText(name);
        col.setWidth(width);
    }

    private void refresh() {
        table.removeAll();
        for (var entry : sessions.get()) {
            var item = new TableItem(table, SWT.NONE);
            item.setData(entry);
            item.setText(0, entry.title() == null ? "(untitled)" : entry.title());
            item.setText(1, entry.projectName() == null ? "—" : entry.projectName());
            item.setText(2, TIME.format(Instant.ofEpochMilli(entry.updatedAt()).atZone(ZoneId.systemDefault())));
            item.setText(3, statusLabel(entry));
        }
        var hasRows = table.getItemCount() > 0;
        emptyHint.setVisible(!hasRows);
        ((GridData) emptyHint.getLayoutData()).exclude = hasRows;
        emptyHint.getParent().layout();
        updateButtons();
    }

    private static String statusLabel(SessionStore.IndexEntry entry) {
        if (!entry.compatible()) return "incompatible";
        if (entry.status() == SessionStatus.INTERRUPTED) return "⚠ interrupted";
        return "";
    }

    private SessionStore.IndexEntry selected() {
        var items = table.getSelection();
        return items.length == 0 ? null : (SessionStore.IndexEntry) items[0].getData();
    }

    private void updateButtons() {
        var entry = selected();
        var resume = getButton(RESUME_ID);
        var delete = getButton(DELETE_ID);
        if (resume != null) resume.setEnabled(!deleting && entry != null && entry.compatible());
        if (delete != null) delete.setEnabled(!deleting && !readOnly && entry != null);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, RESUME_ID, "Resume", true);
        createButton(parent, DELETE_ID, "Delete", false);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CLOSE_LABEL, false);
        updateButtons();
    }

    @Override
    protected void buttonPressed(int buttonId) {
        var entry = selected();
        if (buttonId == RESUME_ID) {
            if (entry == null || !entry.compatible()) return;
            selectedSessionId = entry.id();
            setReturnCode(OK);
            close();
        } else if (buttonId == DELETE_ID) {
            if (entry == null || readOnly || deleting) return;
            if (MessageDialog.openConfirm(getShell(), "Delete session",
                    "Delete '" + entry.title() + "'? This cannot be undone.")) {
                startDelete(entry);
            }
        } else {
            super.buttonPressed(buttonId);
        }
    }

    private void startDelete(SessionStore.IndexEntry entry) {
        deleting = true;
        updateButtons();
        var display = getShell().getDisplay();
        CompletableFuture<Void> future;
        try {
            future = onDelete.apply(entry.id());
        } catch (RuntimeException e) { // e.g. writer already shut down — still unlock the dialog
            future = CompletableFuture.failedFuture(e);
        }
        future.whenComplete((ok, error) -> display.asyncExec(() -> {
            if (table == null || table.isDisposed()) return; // dialog closed meanwhile
            deleting = false;
            if (error != null) {
                var cause = error.getCause() != null ? error.getCause() : error;
                MessageDialog.openError(getShell(), "Delete session",
                        "'" + entry.title() + "' could not be deleted: " + cause.getMessage());
            }
            refresh();
        }));
    }
}
