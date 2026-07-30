package org.sterl.llmpeon.parts.widget;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.VerifyKeyListener;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

/**
 * Reusable auto-growing StyledText widget. The text area grows from a minimum of
 * 2 rows up to {@code maxRows}, then scrolls. Height changes are propagated by
 * calling the {@code onReflow} callback so the parent controls layout propagation.
 */
public class TextInputWidget extends Composite {

    /** Gap between the overlay row and the field edges / between two overlays. */
    private static final int OVERLAY_MARGIN = 3;
    private static final int OVERLAY_SPACING = 2;

    private final StyledText styledText;
    private final int maxRows;
    private final Runnable onReflow;
    private final List<Control> overlays = new ArrayList<>();
    /** Height {@link #refreshHeight()} settled on; 0 until the first measurement. */
    private int heightHint;

    private static final int MAX_STACK_SIZE = 25;
    private List<UndoRedoStack> undoStack;
    private List<UndoRedoStack> redoStack;

    private final Menu popupMenu;
    private boolean fullSelection = false;

    public TextInputWidget(Composite parent, int style, int maxRows, Runnable onReflow) {
        super(parent, style);
        this.maxRows = maxRows;
        this.onReflow = onReflow;

        undoStack = new LinkedList<>();
        redoStack = new LinkedList<>();

        setLayout(new OverlayLayout());

        styledText = new StyledText(this, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        styledText.addModifyListener(e -> refreshHeight());

        popupMenu = new Menu(parent.getShell(), SWT.POP_UP);
        addUndoRedoSupport(popupMenu);
        FileDropSupport.install(this, styledText);
        FileDropSupport.install(styledText, styledText);
    }

    private void refreshHeight() {
        if (styledText.isDisposed()) return;
        int width = styledText.getSize().x;
        if (width <= 0) return;
        Point size = styledText.computeSize(width, SWT.DEFAULT);
        int lineH = styledText.getLineHeight();
        int minHeight = lineH * 2;
        int maxHeight = lineH * maxRows;
        int newHint = Math.max(minHeight, Math.min(maxHeight, size.y));
        if (heightHint != newHint) {
            heightHint = newHint;
            onReflow.run();
        }
    }

    /**
     * Registers a control that floats on top of the text area, anchored bottom-right and laid out
     * left to right in registration order. Overlays live inside the StyledText's client area — to
     * the left of its scrollbar — so the field stays one uninterrupted surface; a sibling column
     * beside the StyledText would leave the scrollbar as a seam splitting it.
     *
     * <p>Text never flows under an overlay: {@link #applyOverlayMargin()} reserves the strip as a
     * right margin on the StyledText.
     */
    public void addOverlay(Control control) {
        addOverlay(control, overlays.size());
    }

    /** As {@link #addOverlay(Control)}, but places the control at {@code index} in the row. */
    public void addOverlay(Control control, int index) {
        if (control == null || control.isDisposed()) return;
        overlays.add(Math.max(0, Math.min(index, overlays.size())), control);
        control.moveAbove(styledText);
        control.addDisposeListener(e -> {
            overlays.remove(control);
            if (!isDisposed()) applyOverlayMargin();
        });
        applyOverlayMargin();
    }

    /** Width the overlay row needs, including the gaps around it; 0 when there are no overlays. */
    private int overlayStripWidth() {
        int width = 0;
        for (var overlay : overlays) {
            if (overlay.isDisposed()) continue;
            if (width > 0) width += OVERLAY_SPACING;
            width += overlay.computeSize(SWT.DEFAULT, SWT.DEFAULT).x;
        }
        return width == 0 ? 0 : width + 2 * OVERLAY_MARGIN;
    }

    /** Keeps wrapped text clear of the overlay strip. */
    private void applyOverlayMargin() {
        if (styledText.isDisposed()) return;
        int right = overlayStripWidth();
        if (styledText.getRightMargin() == right) return;
        styledText.setMargins(styledText.getLeftMargin(), styledText.getTopMargin(),
                right, styledText.getBottomMargin());
        layout(true, true);
        refreshHeight();
    }

    /**
     * Stacks the overlays on top of the StyledText instead of beside it. Sizing follows the
     * {@link #refreshHeight()} hint so the field still grows from 2 rows to {@code maxRows}.
     */
    private class OverlayLayout extends Layout {
        @Override
        protected Point computeSize(Composite composite, int wHint, int hHint, boolean flushCache) {
            int width = wHint == SWT.DEFAULT ? styledText.computeSize(wHint, SWT.DEFAULT).x : wHint;
            int height = hHint != SWT.DEFAULT ? hHint
                    : heightHint > 0 ? heightHint
                    : styledText.computeSize(width, SWT.DEFAULT).y;
            return new Point(Math.max(width, 0), Math.max(height, minimumOverlayHeight()));
        }

        @Override
        protected void layout(Composite composite, boolean flushCache) {
            var area = composite.getClientArea();
            styledText.setBounds(area);
            if (overlays.isEmpty()) return;
            // Client area excludes the scrollbar where it takes space, so the row cannot cover it.
            var inner = styledText.getClientArea();
            // Overlays are siblings of the StyledText, so translate its client area to our own.
            int right = area.x + inner.x + inner.width;
            int bottom = area.y + inner.y + inner.height;
            int x = right - OVERLAY_MARGIN;
            for (int i = overlays.size() - 1; i >= 0; i--) {
                var overlay = overlays.get(i);
                if (overlay.isDisposed()) continue;
                var size = overlay.computeSize(SWT.DEFAULT, SWT.DEFAULT);
                x -= size.x;
                overlay.setBounds(x, Math.max(area.y, bottom - size.y - OVERLAY_MARGIN),
                        size.x, size.y);
                x -= OVERLAY_SPACING;
            }
        }
    }

    /** Enough room for the overlay row to sit fully inside the field. */
    private int minimumOverlayHeight() {
        int tallest = 0;
        for (var overlay : overlays) {
            if (overlay.isDisposed()) continue;
            tallest = Math.max(tallest, overlay.computeSize(SWT.DEFAULT, SWT.DEFAULT).y);
        }
        return tallest == 0 ? 0 : tallest + 2 * OVERLAY_MARGIN;
    }

    // Add support functions for Undo/Redo with Popup Menu on text widget
    // https://fossies.org/linux/apache-hop/ui/src/main/java/org/apache/hop/ui/core/widget/StyledTextVar.java
    protected void addUndoRedoSupport(Menu popupMenu) {
        final MenuItem undoItem = new MenuItem(popupMenu, SWT.PUSH);
        undoItem.setText("Undo");
        undoItem.addListener(SWT.Selection, event -> undo());

        final MenuItem redoItem = new MenuItem(popupMenu, SWT.PUSH);
        redoItem.setText("Redo");
        redoItem.addListener(SWT.Selection, event -> redo());

        new MenuItem(popupMenu, SWT.SEPARATOR);

        final MenuItem cutItem = new MenuItem(popupMenu, SWT.PUSH);
        cutItem.setText("Cut");
        cutItem.addListener(SWT.Selection, event -> styledText.cut());

        final MenuItem copyItem = new MenuItem(popupMenu, SWT.PUSH);
        copyItem.setText("Copy");
        copyItem.addListener(SWT.Selection, event -> styledText.copy());

        final MenuItem pasteItem = new MenuItem(popupMenu, SWT.PUSH);
        pasteItem.setText("Paste");
        pasteItem.addListener(SWT.Selection, event -> styledText.paste());

        new MenuItem(popupMenu, SWT.SEPARATOR);

        final MenuItem selectAllItem = new MenuItem(popupMenu, SWT.PUSH);
        selectAllItem.setText("Select All");
        selectAllItem.addListener(SWT.Selection, event -> styledText.selectAll());

        styledText.setMenu(popupMenu);

        styledText.addListener(
            SWT.Selection,
            event -> {
                if (styledText.getSelectionCount() == styledText.getCharCount()) {
                    fullSelection = true;
                }
            });

		styledText.addListener(
            SWT.KeyDown,
            event -> {
                if (event.keyCode == 'z'
                    && (event.stateMask & SWT.MOD1) != 0
                    && (event.stateMask & SWT.MOD2) != 0) {
                    redo();
                } else if (event.keyCode == 'z'
                    && (event.stateMask & SWT.MOD1) != 0) {
                    undo();
                } else if (event.keyCode == 'a' && (event.stateMask & SWT.MOD1) != 0) {
                    styledText.selectAll();
                }
            });

        styledText.addExtendedModifyListener(
            event -> {
                int eventLength = event.length;
                int eventStartPostition = event.start;

                String newText = getText();
                String repText = event.replacedText;
                String oldText = "";
                int eventType = -1;

                if ((event.length != newText.length()) || (fullSelection)) {
                    if (repText != null && !repText.isEmpty()) {
                        oldText =
                              newText.substring(0, event.start)
                              + repText
                              + newText.substring(event.start + event.length);
                        eventType = UndoRedoStack.DELETE;
                        eventLength = repText.length();
                    } else {
                        oldText =
                              newText.substring(0, event.start) + newText.substring(event.start + event.length);
                        eventType = UndoRedoStack.INSERT;
                    }

                    if ((oldText != null && !oldText.isEmpty()) || (eventStartPostition == event.length)) {
                        UndoRedoStack urs =
                            new UndoRedoStack(eventStartPostition, newText, oldText, eventLength, eventType);

                        // Stack is full
                        if (undoStack.size() == MAX_STACK_SIZE) {
                            undoStack.remove(undoStack.size() - 1);
                        }
                        undoStack.add(0, urs);
                    }
                }
                fullSelection = false;
            });
    }

    protected void undo() {
        if (!undoStack.isEmpty()) {
            UndoRedoStack undo = undoStack.remove(0);
            if (redoStack.size() == MAX_STACK_SIZE) {
                redoStack.remove(redoStack.size() - 1);
            }
            UndoRedoStack redo =
                new UndoRedoStack(
                    undo.cursorPosition(),
                    undo.replacedText(),
                    getText(),
                    undo.eventLength(),
                    undo.type());
            fullSelection = false;
            setText(undo.replacedText());
            if (undo.type() == UndoRedoStack.INSERT) {
                styledText.setCaretOffset(undo.cursorPosition());
            } else if (undo.type() == UndoRedoStack.DELETE) {
                styledText.setCaretOffset(undo.cursorPosition() + undo.eventLength());
                styledText.setSelection(undo.cursorPosition(), undo.cursorPosition() + undo.eventLength());
                if (styledText.getSelectionCount() == styledText.getCharCount()) {
                    fullSelection = true;
                }
            }
            redoStack.add(0, redo);
        }
    }

    protected void redo() {
        if (!redoStack.isEmpty()) {
            UndoRedoStack redo = redoStack.remove(0);
            if (undoStack.size() == MAX_STACK_SIZE) {
                undoStack.remove(undoStack.size() - 1);
            }
            UndoRedoStack undo =
                new UndoRedoStack(
                    redo.cursorPosition(),
                    redo.replacedText(),
                    getText(),
                    redo.eventLength(),
                    redo.type());
            fullSelection = false;
            setText(redo.replacedText());
            if (redo.type() == UndoRedoStack.INSERT) {
                styledText.setCaretOffset(redo.cursorPosition());
            } else if (redo.type() == UndoRedoStack.DELETE) {
                styledText.setCaretOffset(redo.cursorPosition() + redo.eventLength());
                styledText.setSelection(redo.cursorPosition(), redo.cursorPosition() + redo.eventLength());
                if (styledText.getSelectionCount() == styledText.getCharCount()) {
                    fullSelection = true;
                }
            }
            undoStack.add(0, undo);
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public String getText() {
        return styledText.getText();
    }

    public void setText(String text) {
        styledText.setText(text != null ? text : "");
    }
    
    public void setEditable(boolean editable) {
        styledText.setEditable(editable);
    }

    public void clearText() {
        styledText.setText("");
    }

    @Override
    public boolean setFocus() {
        if (styledText.isDisposed()) return false;
        return styledText.setFocus();
    }

    public void addModifyListener(ModifyListener listener) {
        styledText.addModifyListener(listener);
    }

    public void removeModifyListener(ModifyListener listener) {
        styledText.removeModifyListener(listener);
    }

    public void addKeyListener(KeyListener listener) {
        styledText.addKeyListener(listener);
    }

    /**
     * Adds a verify-key listener that runs BEFORE the StyledText consumes the key. Setting
     * {@code event.doit = false} suppresses the default behavior (e.g. arrow navigation). This is
     * the only reliable hook for stealing arrow / Enter keys to drive an external popup.
     */
    public void addVerifyKeyListener(VerifyKeyListener listener) {
        styledText.addVerifyKeyListener(listener);
    }

    /**
     * Height of the smallest useful text area: the two rows {@link #refreshHeight()} never goes
     * below, or the overlay row if that needs more.
     */
    public int getMinimumHeight() {
        if (styledText.isDisposed()) return 0;
        return Math.max(styledText.getLineHeight() * 2, minimumOverlayHeight());
    }

    /** Sets the background on the underlying StyledText (safe — not a Composite). */
    public void setTextBackground(Color color) {
        styledText.setBackground(color);
    }

    /** Display coordinates of the current caret, suitable for anchoring an external popup. */
    public Point getCaretDisplayLocation() {
        if (styledText.isDisposed()) return null;
        var local = styledText.getLocationAtOffset(styledText.getCaretOffset());
        return styledText.toDisplay(local.x, local.y);
    }
    
    public void setCaretOffset(int offset) {
        if (styledText.isDisposed()) return;
        int clamped = Math.max(0, Math.min(offset, styledText.getCharCount()));
        styledText.setCaretOffset(clamped);
    }

    public static record UndoRedoStack (int cursorPosition, String newText, String replacedText, int eventLength, int type) {
        public static final int DELETE = 0;
        public static final int INSERT = 1;

    }
}
