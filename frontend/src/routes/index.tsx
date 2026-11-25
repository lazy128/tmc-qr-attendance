import { createFileRoute } from "@tanstack/react-router";
import { EdgeConsole } from "../modules/console/EdgeConsole";

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [
      { title: "TMC Gate | Attendance & Access" },
      { name: "description", content: "TMC edge access and attendance console" },
    ],
  }),
  component: Index,
});

function Index() {
  return <EdgeConsole />;
}
