// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { formatWhen, TechnicalDetails } from "./ActivityShared";

afterEach(() => {
  cleanup();
});

describe("TechnicalDetails", () => {
  it("hides entries behind a disclosure that expands on activation", () => {
    render(<TechnicalDetails value={{ screen: "lobby", attempt: 3 }} />);

    expect(screen.queryByText("lobby")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "View" }));
    expect(screen.getByText("lobby")).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();
  });

  it("renders a dash when there is nothing to show", () => {
    render(<TechnicalDetails value={{}} />);

    expect(screen.getByText("—")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "View" })).toBeNull();
  });
});

describe("activity timestamps", () => {
  it("distinguishes events within the same minute", () => {
    const first = formatWhen("2026-07-21T14:30:15Z");
    const second = formatWhen("2026-07-21T14:30:45Z");
    expect(first).not.toBe(second);
    expect(first).toMatch(/:15\b/);
    expect(second).toMatch(/:45\b/);
  });
});
