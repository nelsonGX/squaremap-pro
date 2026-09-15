/** Maps API failures of editor writes to UI state. Pure. */
import { ApiError } from "../api/client";
import type { SaveFailure } from "./draft";

/** 401/403: the session is gone or edit permission was revoked. */
export function isAuthFailure(status: number): boolean {
  return status === 401 || status === 403;
}

export const SESSION_LOST_MESSAGE =
  "Your editor session has ended or edit permission was revoked. Run /mapedit in game for a new login link.";

export function saveFailureOf(e: unknown): SaveFailure {
  if (!(e instanceof ApiError)) {
    return { status: 0, message: e instanceof Error ? e.message : String(e), details: [] };
  }
  const base = { status: e.status, details: e.details, current: e.current };
  if (e.kind === "network") return { ...base, message: "Could not reach the server. Check your connection and try again." };
  if (e.kind === "invalid_response") return { ...base, message: `The server sent an unexpected response (${e.message}).` };
  switch (e.status) {
    case 400:
      return { ...base, message: e.details.length > 0 ? "The server rejected the changes." : e.message };
    case 401:
    case 403:
      return { ...base, message: SESSION_LOST_MESSAGE };
    case 404:
      return { ...base, message: "This feature no longer exists (it may have been deleted by someone else)." };
    case 409:
      return { ...base, message: "Someone else changed this feature." };
    case 422:
      return { ...base, message: e.code === "railway_has_stations" ? "This railway still has stations. Delete them first." : `The server refused the change (${e.code ?? "HTTP 422"}).` };
    default:
      return { ...base, message: e.message };
  }
}
