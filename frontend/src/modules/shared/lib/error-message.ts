export function errorMessage(error: unknown): string {
  if (error instanceof TypeError && /fetch|network|load failed/i.test(error.message)) {
    return "Không kết nối được máy chủ. Kiểm tra mạng rồi thử lại.";
  }
  return error instanceof Error ? error.message : "Không thể hoàn thành yêu cầu.";
}
