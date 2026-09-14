import { mockRoute } from "../../../../lib/mockRoute";

export const dynamic = "force-dynamic";

/** Mock of the mod's `GET /route` (schema v1). Thin wrapper over the pure `mockRoute`. */
export function GET(request: Request): Response {
  const params = new URL(request.url).searchParams;
  const { httpStatus, body } = mockRoute({
    from: params.get("from"),
    to: params.get("to"),
    world: params.get("world"),
  });
  return Response.json(body, {
    status: httpStatus,
    headers: { "Cache-Control": "no-store" },
  });
}
