import { createFileRoute } from "@tanstack/react-router";
import { EmployeePage } from "../modules/userqr/ui/EmployeePage";

export const Route = createFileRoute("/employee")({ component: EmployeePage });
