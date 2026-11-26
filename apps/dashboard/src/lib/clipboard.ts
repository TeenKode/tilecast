import { toast } from "../components/ui/toast";

/** The async Clipboard API is unavailable on plain HTTP LAN origins. */
function copyWithSelection(value: string): boolean {
  if (
    typeof document === "undefined" ||
    !document.body ||
    typeof document.execCommand !== "function"
  ) {
    return false;
  }

  const focusedElement = document.activeElement;
  const selection = document.getSelection();
  const savedRanges: Range[] = [];
  if (selection) {
    for (let i = 0; i < selection.rangeCount; i++) {
      savedRanges.push(selection.getRangeAt(i).cloneRange());
    }
  }
  const input = document.createElement("textarea");
  input.value = value;
  input.readOnly = true;
  input.setAttribute("aria-hidden", "true");
  input.style.cssText = "position:fixed;left:-9999px;top:0;opacity:0";
  document.body.appendChild(input);
  try {
    input.focus();
    input.select();
    input.setSelectionRange(0, value.length);
    return document.execCommand("copy");
  } catch {
    return false;
  } finally {
    input.remove();
    if (focusedElement instanceof HTMLElement) {
      focusedElement.focus({ preventScroll: true });
    }
    if (selection) {
      selection.removeAllRanges();
      for (const range of savedRanges) selection.addRange(range);
    }
  }
}

/** Use the secure Clipboard API or copy synchronously on plain HTTP. */
export async function writeTextToClipboard(value: string): Promise<boolean> {
  const clipboard = globalThis.navigator?.clipboard;
  if (typeof clipboard?.writeText === "function") {
    try {
      await clipboard.writeText(value);
      return true;
    } catch {
      // The legacy command can still work when the async API denies access.
    }
  }
  return copyWithSelection(value);
}

/** Copy in the browser or Studio shell; callers supply localized feedback. */
export async function copyText(
  value: string,
  feedback: { success: string; failure: string },
): Promise<boolean> {
  const copied = await writeTextToClipboard(value);
  toast.add({
    title: copied ? feedback.success : feedback.failure,
    type: copied ? "success" : "error",
  });
  return copied;
}
