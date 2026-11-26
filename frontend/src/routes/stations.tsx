import { createFileRoute } from "@tanstack/react-router";
import { StationsPage } from "../modules/access/ui/StationsPage";

export const Route = createFileRoute("/stations")({ component: StationsPage });
