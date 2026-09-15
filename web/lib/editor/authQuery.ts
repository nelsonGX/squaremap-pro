/** `?edit=1` / `?authError=` handling after `/api/auth/redeem` redirects. Pure. */

export interface AuthQuery {
  /** `edit=1` present. */
  edit: boolean;
  /** Human-readable message for `authError`, or null. */
  authError: string | null;
  /** The search string with both parameters removed ("" or "?..."). */
  cleanedSearch: string;
  /** Whether anything was removed (i.e. history.replaceState is needed). */
  changed: boolean;
}

export const AUTH_ERROR_MESSAGES: Record<string, string> = {
  expired: "Login link expired — run /mapedit again.",
};

export function parseAuthQuery(search: string): AuthQuery {
  const params = new URLSearchParams(search);
  const edit = params.get("edit") === "1";
  const code = params.get("authError");
  const changed = params.has("edit") || params.has("authError");
  params.delete("edit");
  params.delete("authError");
  const rest = params.toString();
  return {
    edit,
    authError: code === null ? null : (AUTH_ERROR_MESSAGES[code] ?? "Login failed — run /mapedit again."),
    cleanedSearch: rest === "" ? "" : `?${rest}`,
    changed,
  };
}
