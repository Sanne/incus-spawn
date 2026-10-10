package dev.incusspawn.tui;

import dev.tamboui.tui.event.KeyEvent;

/**
 * The TUI's delete confirmation (F8, or Shift+F8 for all templates or all instances): what is
 * about to be destroyed, with the CoW caveat note worked out once when it opened. It owns its
 * keys and its rendering; the {@link Tui} runs the destroy when it is confirmed.
 */
final class DeleteConfirm {

    /** What a key did: confirm the destroy, or close the dialog without it. */
    enum Outcome { CLOSE, CONFIRM }

    private final ModalRenderer modal;
    /** An instance or template name, or "--all" / "--all-instances". */
    private final String pendingDeleteName;
    private final String pendingDeleteNote;      // computed once when the confirm dialog opens

    DeleteConfirm(ModalRenderer modal, String name, String note) {
        this.modal = modal;
        this.pendingDeleteName = name;
        this.pendingDeleteNote = note;
    }

    /** What is to be destroyed: an instance or template name, or "--all" / "--all-instances". */
    String name() {
        return pendingDeleteName;
    }

    /** Any key closes the dialog; only y confirms. */
    Outcome handleKey(KeyEvent key) {
        if (key.isChar('y') || key.isChar('Y')) {
            return Outcome.CONFIRM;
        }
        return Outcome.CLOSE;
    }

    void render(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        var isAllTemplates = "--all".equals(pendingDeleteName);
        var isAllInstances = "--all-instances".equals(pendingDeleteName);
        var isAll = isAllTemplates || isAllInstances;
        var title = isAllTemplates ? " Destroy all templates "
                : isAllInstances ? " Destroy all instances "
                : " Destroy '" + pendingDeleteName + "' ";
        var message = isAllTemplates ? "This will destroy all built templates."
                : isAllInstances ? "This will destroy all instances."
                : "This action cannot be undone." + pendingDeleteNote;
        modal.renderConfirmModal(frame, screen, title, message, modal.warn());
    }
}
