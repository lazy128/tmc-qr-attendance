import { z } from "zod";
import { API_BASE } from "./api-base";

const edge = z.object({
  site: z.string().min(1).max(100),
  station: z.string().min(1).max(100),
  url: z.string().regex(/^(https?:\/\/[^/]+(?:\/[^/?#]+)*\/?|\/[^/?#]+(?:\/[^/?#]+)*\/?)$/),
});
const edgeList = z.tuple([edge]).rest(edge);

type Edge = z.infer<typeof edge>;

export function configuredEdges(): [Edge, ...Edge[]] {
  const configured = import.meta.env["VITE_EDGES"];
  if (!configured) return [{ site: "SITE-1", station: "GATE-1", url: API_BASE }];
  try {
    const [first, ...other] = edgeList.parse(JSON.parse(configured));
    const normalize = (item: Edge) => ({ ...item, url: item.url.replace(/\/$/, "") });
    return [normalize(first), ...other.map(normalize)];
  } catch {
    throw new Error("VITE_EDGES phải là danh sách gồm site, station và URL API hợp lệ.");
  }
}
