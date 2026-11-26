import { createFileRoute } from "@tanstack/react-router";
import { TimesheetPage } from "../modules/attendance/ui/TimesheetPage";

export const Route = createFileRoute("/attendance")({ component: TimesheetPage });
