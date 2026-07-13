package org.sterl.llmpeon.parts.widget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.core.resources.IFileState;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.sterl.llmpeon.parts.shared.EclipseUtil;
import org.sterl.llmpeon.shared.AiMonitor.AiFileUpdate;

public class FileChangeReviewWidget extends Composite {

    public record FileChange(String file, String oldContent, String newContent, IFileState restoreState) {
        public boolean created() {
            return oldContent == null;
        }

        public boolean deleted() {
            return newContent == null && oldContent != null;
        }

        public int addedLines() {
            return Math.max(0, lineCount(newContent) - lineCount(oldContent));
        }

        public int removedLines() {
            return Math.max(0, lineCount(oldContent) - lineCount(newContent));
        }

        public int changedLines() {
            return Math.max(lineCount(oldContent), lineCount(newContent));
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, FileChange> changes = new LinkedHashMap<>();
    private final Composite header;
    private final Button toggleButton;
    private final Label summaryLabel;
    private final Button undoButton;
    private final Button keepButton;
    private final Composite fileList;
    private final Runnable onUndo;
    private final Runnable onKeep;

    private boolean expanded;

    public FileChangeReviewWidget(Composite parent, int style, Runnable onUndo, Runnable onKeep) {
        super(parent, style);
        this.onUndo = onUndo;
        this.onKeep = onKeep;

        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 8;
        layout.marginHeight = 6;
        layout.verticalSpacing = 4;
        setLayout(layout);

        header = new Composite(this, SWT.NONE);
        GridLayout headerLayout = new GridLayout(4, false);
        headerLayout.marginWidth = 0;
        headerLayout.marginHeight = 0;
        headerLayout.horizontalSpacing = 8;
        header.setLayout(headerLayout);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        toggleButton = new Button(header, SWT.PUSH);
        toggleButton.setText(">");
        toggleButton.setToolTipText("Show changed files");
        toggleButton.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        toggleButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                setExpanded(!expanded);
            }
        });

        summaryLabel = new Label(header, SWT.NONE);
        summaryLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        undoButton = new Button(header, SWT.PUSH);
        undoButton.setText("Undo");
        undoButton.setToolTipText("Revert AI file changes");
        undoButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onUndo.run();
            }
        });

        keepButton = new Button(header, SWT.PUSH);
        keepButton.setText("Keep");
        keepButton.setToolTipText("Keep AI file changes and hide this review");
        keepButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onKeep.run();
            }
        });

        fileList = new Composite(this, SWT.NONE);
        GridLayout fileListLayout = new GridLayout(1, false);
        fileListLayout.marginWidth = 20;
        fileListLayout.marginHeight = 0;
        fileListLayout.verticalSpacing = 2;
        fileList.setLayout(fileListLayout);
        GridData listData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        listData.exclude = true;
        fileList.setLayoutData(listData);
        fileList.setVisible(false);

        setVisible(false);
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.exclude = true;
        setLayoutData(data);
        refresh();
    }

    public void addChange(AiFileUpdate update, IFileState restoreState) {
        lock.lock();
        try {
            changes.compute(update.file(), (file, existing) -> mergeChange(file, existing, update, restoreState));
        } finally {
            lock.unlock();
        }
        refresh();
    }

    static FileChange mergeChange(String file, FileChange existing, AiFileUpdate update, IFileState restoreState) {
        if (existing == null) {
            return new FileChange(file, update.oldContent(), update.newContent(), restoreState);
        }
        if (!Objects.equals(existing.newContent(), update.oldContent())) {
            return new FileChange(file, update.oldContent(), update.newContent(), restoreState);
        }
        var retainedState = existing.restoreState() != null ? existing.restoreState() : restoreState;
        return new FileChange(file, existing.oldContent(), update.newContent(), retainedState);
    }

    public List<FileChange> snapshot() {
        lock.lock();
        try {
            return changes.values().stream()
                    .sorted(Comparator.comparing(FileChange::file))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    public void clearChanges() {
        lock.lock();
        try {
            changes.clear();
        } finally {
            lock.unlock();
        }
        setExpanded(false);
        refresh();
    }

    public boolean hasChanges() {
        lock.lock();
        try {
            return !changes.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    /** Enable/disable the Undo & Keep actions while a request is in flight. */
    public void setActionsEnabled(boolean enabled) {
        if (undoButton != null && !undoButton.isDisposed()) undoButton.setEnabled(enabled);
        if (keepButton != null && !keepButton.isDisposed()) keepButton.setEnabled(enabled);
    }

    private void setExpanded(boolean expanded) {
        this.expanded = expanded;
        GridData data = (GridData)fileList.getLayoutData();
        data.exclude = !expanded;
        fileList.setVisible(expanded);
        toggleButton.setText(expanded ? "v" : ">");
        toggleButton.setToolTipText(expanded ? "Hide changed files" : "Show changed files");
        relayout();
    }

    private void refresh() {
        var snapshot = snapshotMutable();
        boolean visible = !snapshot.isEmpty();
        GridData data = (GridData)getLayoutData();
        data.exclude = !visible;
        setVisible(visible);
        if (!visible) {
            summaryLabel.setText("");
            disposeFileRows();
            relayout();
            return;
        }

        int added = snapshot.stream().mapToInt(FileChange::addedLines).sum();
        int removed = snapshot.stream().mapToInt(FileChange::removedLines).sum();
        summaryLabel.setText(snapshot.size() + " File" + (snapshot.size() == 1 ? "" : "s")
                + "  +" + added + "  -" + removed);

        disposeFileRows();
        Color green = getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN);
        Color red = getDisplay().getSystemColor(SWT.COLOR_DARK_RED);
        for (var change : snapshot) {
            Composite row = new Composite(fileList, SWT.NONE);
            GridLayout rowLayout = new GridLayout(4, false);
            rowLayout.marginWidth = 0;
            rowLayout.marginHeight = 0;
            rowLayout.horizontalSpacing = 5;
            row.setLayout(rowLayout);
            row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

            Label statusLabel = new Label(row, SWT.NONE);
            if (change.created()) {
                statusLabel.setText("new");
                statusLabel.setForeground(green);
            } else if (change.deleted()) {
                statusLabel.setText("deleted");
                statusLabel.setForeground(red);
            }
            var statusData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
            statusData.widthHint = 52;
            statusLabel.setLayoutData(statusData);

            Label fileLabel = new Label(row, SWT.NONE);
            var path = change.file();
            fileLabel.setText(path);
            if (!change.deleted()) {
                fileLabel.setForeground(getDisplay().getSystemColor(SWT.COLOR_LINK_FOREGROUND));
                fileLabel.setCursor(getDisplay().getSystemCursor(SWT.CURSOR_HAND));
                fileLabel.setToolTipText("Open in editor");
                fileLabel.addListener(SWT.MouseUp, e -> EclipseUtil.openWorkspacePathInEditor(path));
            }
            fileLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

            Label addedLabel = new Label(row, SWT.NONE);
            addedLabel.setText("+" + change.addedLines());
            addedLabel.setForeground(green);

            Label removedLabel = new Label(row, SWT.NONE);
            removedLabel.setText("-" + change.removedLines());
            removedLabel.setForeground(red);
        }
        fileList.layout(true, true);
        relayout();
    }

    private List<FileChange> snapshotMutable() {
        lock.lock();
        try {
            var result = new ArrayList<>(changes.values());
            result.sort(Comparator.comparing(FileChange::file));
            return result;
        } finally {
            lock.unlock();
        }
    }

    private void disposeFileRows() {
        for (var child : fileList.getChildren()) {
            child.dispose();
        }
    }

    private void relayout() {
        layout(true, true);
        Composite p = getParent();
        if (p != null) {
            p.layout(true, true);
            Composite pp = p.getParent();
            if (pp != null) pp.layout(true, true);
        }
    }

    private static int lineCount(String value) {
        if (value == null || value.isEmpty()) return 0;
        return value.split("\\R", -1).length;
    }
}
