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
    return document.execCommand("copy");
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

/** Copy in the browser or Studio shell; callers supply localized feedback. */
export async function copyText(
  value: string,
  feedback: { success: string; failure: string },
): Promise<boolean> {
  let copied = false;
  try {
    if (typeof navigator.clipboard?.writeText === "function") {
      try {
        await navigator.clipboard.writeText(value);
        copied = true;
      } catch {
        // Some browsers expose the API but deny it on this origin.
      }
    }
    if (!copied) copied = copyWithSelection(value);
  } catch {
    copied = false;
  }
  toast.add({
    title: copied ? feedback.success : feedback.failure,
    type: copied ? "success" : "error",
  });
  return copied;
}
