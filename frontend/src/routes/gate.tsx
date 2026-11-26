import { createFileRoute } from "@tanstack/react-router";
import { GatePage } from "../modules/access/ui/GatePage";

export const Route = createFileRoute("/gate")({ component: GatePage });
