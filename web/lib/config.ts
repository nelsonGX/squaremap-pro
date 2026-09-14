/**
 * Runtime configuration. `NEXT_PUBLIC_*` values are inlined at build time, so they must be read with
 * the literal `process.env.NEXT_PUBLIC_...` expression.
 */

/** Base of the bundled mock squaremap web root (`public/mock-squaremap`). */
export const MOCK_SQUAREMAP_URL = "/mock-squaremap";
/** Base of the mock route handler; the client appends `/route`. */
export const MOCK_ROUTE_API = "/api/mock";

function clean(v: string | undefined): string | undefined {
  const t = v?.trim();
  return t ? t.replace(/\/+$/, "") : undefined;
}

export const SQUAREMAP_URL: string = clean(process.env.NEXT_PUBLIC_SQUAREMAP_URL) ?? MOCK_SQUAREMAP_URL;
export const ROUTE_API: string = clean(process.env.NEXT_PUBLIC_ROUTE_API) ?? MOCK_ROUTE_API;
export const USING_MOCK_SQUAREMAP = SQUAREMAP_URL === MOCK_SQUAREMAP_URL;
export const USING_MOCK_ROUTE_API = ROUTE_API === MOCK_ROUTE_API;
