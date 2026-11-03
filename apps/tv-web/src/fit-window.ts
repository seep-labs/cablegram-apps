// The app is laid out at 1920×1080. A TV honours the viewport meta tag, so nothing changes there; a desktop browser
// ignores it and would crop the page, so the stage is scaled to fit the window and centred.
const STAGE_WIDTH = 1920;
const STAGE_HEIGHT = 1080;
const TOLERANCE_PX = 2;

export type Fit = { scale: number; x: number; y: number };

/** How to fit the stage into a window, or null when it already fits (a TV). */
export function fitStage(width: number, height: number): Fit | null {
  if (width <= 0 || height <= 0) return null;
  if (Math.abs(width - STAGE_WIDTH) <= TOLERANCE_PX && Math.abs(height - STAGE_HEIGHT) <= TOLERANCE_PX) return null;
  const scale = Math.min(width / STAGE_WIDTH, height / STAGE_HEIGHT);
  return { scale, x: (width - STAGE_WIDTH * scale) / 2, y: (height - STAGE_HEIGHT * scale) / 2 };
}

/** Keeps [root] fitted to the window, now and on every resize. */
export function keepFitted(root: HTMLElement) {
  const apply = () => {
    const fit = fitStage(window.innerWidth, window.innerHeight);
    const size = fit ? "100%" : "";
    document.documentElement.style.width = size;
    document.documentElement.style.height = size;
    document.body.style.width = size;
    document.body.style.height = size;
    root.style.transformOrigin = fit ? "0 0" : "";
    root.style.transform = fit ? `translate(${fit.x}px, ${fit.y}px) scale(${fit.scale})` : "";
  };
  apply();
  window.addEventListener("resize", apply);
}
