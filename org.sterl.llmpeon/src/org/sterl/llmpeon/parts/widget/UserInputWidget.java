package org.sterl.llmpeon.parts.widget;

import java.util.List;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.sterl.llmpeon.parts.shared.ImageUtil;
import org.sterl.llmpeon.parts.shared.SwtUtil;
import org.sterl.llmpeon.shared.model.SimplePromptFile;

/**
 * User input area: file chips bar (hidden until files attached), auto-resizing
 * StyledText (min 2 / max 7 rows), mic button, and Send/Stop button.
 *
 * <p>The icons float inside the white field's bottom-right corner rather than sitting in a column
 * beside it. Anything placed to the right of the StyledText leaves its scrollbar as a seam
 * splitting two white areas; inside the client area the scrollbar stays at the outer edge. The
 * paint-based Buttons from {@link SwtUtil#createIconButton} are given the field's white so they
 * disappear into it — see {@link TextInputWidget#addOverlay(org.eclipse.swt.widgets.Control)}.
 */
public class UserInputWidget extends Composite {

    public static void setDropActiveProjectSupplier(Supplier<IProject> supplier) {
        FileDropSupport.setActiveProjectSupplier(supplier);
    }

    private static final int TEXT_ROW_MARGIN = 2;

    private final TextInputWidget textInput;
    private final Color bgWhite;
    private final Button sendButton;
    private Button micButton;   // null until voice is configured

    /** Notified whenever the preferred height changes, so the owner can re-split the view. */
    private Runnable onHeightChange = () -> { };

    private final Image micImage;
    private final Image sendImage;  // shared registry — must NOT be disposed
    private final Image stopImage;
    private volatile boolean working = false;
    private final Runnable onMicClick;

    private final Color colorRecording;

    private SlashMenuPopup slashPopup;
    private Supplier<List<SimplePromptFile>> commandSupplier;
    private boolean slashPopupWasOpen = false;

    public UserInputWidget(Composite parent, int style, Runnable onSend, Runnable onStop, Runnable onMicClick) {
        super(parent, style);
        this.onMicClick = onMicClick;

        colorRecording = new Color(200, 0, 0);
        addDisposeListener(e -> colorRecording.dispose());

        micImage  = ImageUtil.loadImage(this, ImageUtil.MICROPHONE);
        sendImage = DebugUITools.getImage(IDebugUIConstants.IMG_ACT_RUN);
        stopImage = ImageUtil.loadImage(this, ImageUtil.STOP);

        bgWhite = getDisplay().getSystemColor(SWT.COLOR_WHITE);

        GridLayout outerLayout = new GridLayout(1, false);
        outerLayout.marginWidth = 0;
        outerLayout.marginHeight = 0;
        outerLayout.verticalSpacing = 2;
        setLayout(outerLayout);

        // --- Text row: the field, with the icons floating inside it ---
        Composite textRow = new Composite(this, SWT.NONE);
        GridLayout textRowLayout = new GridLayout(1, false);
        textRowLayout.marginWidth = TEXT_ROW_MARGIN;
        textRowLayout.marginHeight = TEXT_ROW_MARGIN;
        textRowLayout.horizontalSpacing = 0;
        textRow.setLayout(textRowLayout);
        textRow.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        textInput = new TextInputWidget(textRow, SWT.NONE, 7, this::requestReflow);
        textInput.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        textInput.setTextBackground(bgWhite);

        // Enter sends; Shift+Enter inserts newline. While the slash popup is open,
        // defer to the VerifyKeyListener below so Enter commits the selection instead.
        textInput.addKeyListener(KeyListener.keyPressedAdapter(e -> {
            if (e.keyCode == SWT.CR || e.keyCode == SWT.LF) {
                boolean isShiftPressed = (e.stateMask & SWT.SHIFT) != 0;
                if (!isShiftPressed && !slashPopupWasOpen) {
                    e.doit = false;
                    onSend.run();
                }
            }
            slashPopupWasOpen = false;  // reset for next key event
        }));

        // Refresh the slash popup on every modification so it tracks the current prefix.
        textInput.addModifyListener(e -> refreshSlashPopup());

        // Steal arrow / Enter / Escape ONLY while the slash popup is open. Plain Enter still
        // produces a newline in StyledText when the popup is closed.
        textInput.addVerifyKeyListener(e -> {
            if (slashPopup == null || !slashPopup.isOpen()) return;
            switch (e.keyCode) {
            case SWT.ARROW_DOWN:
                slashPopup.moveSelection(1);
                e.doit = false;
                break;
            case SWT.ARROW_UP:
                slashPopup.moveSelection(-1);
                e.doit = false;
                break;
            case SWT.ESC:
                slashPopup.hide();
                e.doit = false;
                break;
            case SWT.CR:
            case SWT.LF:
            case SWT.KEYPAD_CR:
                // Plain Enter commits the selection; Shift+Enter inserts newline.
                if ((e.stateMask & SWT.SHIFT) == 0) {
                    slashPopupWasOpen = true;  // mark before commitSelection closes the popup
                    if (slashPopup.commitSelection()) e.doit = false;
                }
                break;
            case SWT.TAB:
                if (slashPopup.commitSelection()) e.doit = false;
                break;
            default:
                break;
            }
        });

        // Send/stop floats inside the field's bottom-right corner; the mic joins to its left
        // once voice input is configured.
        sendButton = SwtUtil.createIconButton(textInput, sendImage, "Send (Enter)");
        sendButton.setBackground(bgWhite);
        textInput.addOverlay(sendButton);
        sendButton.addListener(SWT.Selection, e -> {
            if (working) onStop.run();
            else onSend.run();
        });
    }

    private void requestReflow() {
        layout(true, true);
        Composite p = getParent();
        if (p == null) return;
        p.layout(new Control[]{ this });
        // The owner decides how much height the input actually gets — see AIChatView's SashForm.
        onHeightChange.run();
    }

    /**
     * Smallest height that still shows a usable widget: two rows of text (or the floating icons,
     * whichever needs more) plus the text row's margins. Used by the owner to limit its splitter.
     */
    public int getMinimumHeight() {
        return textInput.getMinimumHeight() + 2 * TEXT_ROW_MARGIN;
    }

    /** Callback for the owner to re-run its own sizing when the input's preferred height moves. */
    public void setOnHeightChange(Runnable callback) {
        this.onHeightChange = callback == null ? () -> { } : callback;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public String getText() {
        return textInput.getText();
    }

    public void clearText() {
        textInput.clearText();
        requestReflow();
    }

    public void setText(String text) {
        textInput.setText(text);
    }

    /** Show or hide the mic button. Created on first show, disposed on hide so the slot is truly empty. */
    public void setVoiceInputVisible(boolean visible) {
        if (visible && (micButton == null || micButton.isDisposed())) {
            micButton = SwtUtil.createIconButton(textInput,
                    micImage,
                    "Click to start recording — click again to stop and transcribe");
            micButton.setBackground(bgWhite);
            micButton.addListener(SWT.Selection, e -> onMicClick.run());
            textInput.addOverlay(micButton, 0);  // left of send
            requestReflow();
        } else if (!visible && micButton != null && !micButton.isDisposed()) {
            micButton.dispose();  // the overlay registration drops itself on dispose
            micButton = null;
            textInput.layout(true, true);
            requestReflow();
        }
    }

    public void setRecording(boolean recording) {
        if (micButton != null && !micButton.isDisposed()) {
            // Back to the field's white, not null — the parent is the text widget now.
            micButton.setBackground(recording ? colorRecording : bgWhite);
            micButton.setToolTipText(recording
                ? "Recording... click to stop and transcribe"
                : "Click to start recording — click again to stop and transcribe");
            micButton.redraw();
        }
    }

    /** Switch the Send/Stop button between idle and working state. */
    public void setWorking(boolean working) {
        this.working = working;
        if (working) {
            sendButton.setImage(stopImage);
            sendButton.setToolTipText("Cancel current request");
        } else {
            sendButton.setImage(sendImage);
            sendButton.setToolTipText("Send (Enter)");
        }
        sendButton.redraw();
    }

    @Override
    public boolean setFocus() {
        return textInput.setFocus();
    }

    /** Hides the slash-command popup if it is currently shown. Safe to call any time. */
    public void dismissSlashMenu() {
        if (slashPopup != null) slashPopup.hide();
    }

    /**
     * Enables the slash-command popup. {@code commandSupplier} returns the currently loaded
     * commands. Pass {@code null} to disable the popup.
     */
    public void enableSlashCommands(Supplier<List<SimplePromptFile>> commandSupplier) {
        this.commandSupplier = commandSupplier;
        if (commandSupplier == null) {
            disposeSlashPopup();
            return;
        }
        if (slashPopup == null) {  // only enters here once...
            slashPopup = new SlashMenuPopup(this, this::applyCommandSelection);
            addDisposeListener(e -> disposeSlashPopup()); // ...so this is safe
        }
    }

    private void refreshSlashPopup() {
        if (commandSupplier == null || slashPopup == null) return;
        var text = textInput.getText();
        if (text == null || !text.startsWith("/") || hasWhitespaceAfterSlash(text)) {
            slashPopup.hide();
            return;
        }
        var commands = commandSupplier.get();
        if (commands == null || commands.isEmpty()) { slashPopup.hide(); return; }
        slashPopup.show(commands, extractPrefix(text), textInput.getCaretDisplayLocation());
    }

    private static boolean hasWhitespaceAfterSlash(String text) {
        for (int i = 1; i < text.length(); i++)
            if (Character.isWhitespace(text.charAt(i))) return true;
        return false;
    }

    private static String extractPrefix(String text) {
        // Read characters after the leading '/' until whitespace.
        var sb = new StringBuilder();
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) break;
            sb.append(c);
        }
        return sb.toString();
    }

    private void applyCommandSelection(SimplePromptFile cmd) {
        String replacement = "/" + cmd.name() + " ";
        textInput.setText(replacement);
        textInput.setCaretOffset(replacement.length()); // cursor after the space
        textInput.setFocus();
    }

    private void disposeSlashPopup() {
        if (slashPopup != null) {
            slashPopup.dispose();
            slashPopup = null;
        }
    }

}
