export type AttendanceRecord = {
  eventId: string;
  employeeId: string;
  siteId: string;
  stationId: string;
  direction: string;
  scannedAt: string;
  command: string;
  evidence: string | null;
  eligible: boolean;
  hrReview: boolean;
};
