import jsQR from "jsqr";

export function readQr(
  context: CanvasRenderingContext2D,
  source: CanvasImageSource,
  width: number,
  height: number,
) {
  const scale = Math.min(1, 1280 / Math.max(width, height));
  const canvasWidth = Math.max(1, Math.round(width * scale));
  const canvasHeight = Math.max(1, Math.round(height * scale));
  if (context.canvas.width !== canvasWidth || context.canvas.height !== canvasHeight) {
    context.canvas.width = canvasWidth;
    context.canvas.height = canvasHeight;
  }
  context.drawImage(source, 0, 0, canvasWidth, canvasHeight);
  const image = context.getImageData(0, 0, canvasWidth, canvasHeight);
  return jsQR(image.data, image.width, image.height)?.data;
}

export async function readQrFile(file: File) {
  const image = await createImageBitmap(file);
  try {
    const context = document.createElement("canvas").getContext("2d", { willReadFrequently: true });
    if (!context) throw new Error("Không thể đọc ảnh trên trình duyệt này.");
    return readQr(context, image, image.width, image.height);
  } finally {
    image.close();
  }
}
