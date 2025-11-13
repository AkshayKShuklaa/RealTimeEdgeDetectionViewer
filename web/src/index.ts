interface FramePayload {
  imageBase64: string;
  fps: number;
  resolution: string;
  processingMs: number;
  updatedAt: string;
}

const SAMPLE_FRAME: FramePayload = {
  imageBase64:
    "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR42mP8/5+hHgAHggJ/PyGNsQAAAABJRU5ErkJggg==",
  fps: 14.8,
  resolution: "1280x720",
  processingMs: 7.1,
  updatedAt: new Date().toISOString()
};

function byId<T extends HTMLElement>(id: string): T {
  const element = document.getElementById(id);
  if (!element) {
    throw new Error(`Missing element with id ${id}`);
  }
  return element as T;
}

function populateFrame(payload: FramePayload) {
  const image = byId<HTMLImageElement>("frame-image");
  image.src = payload.imageBase64;
  image.alt = `Processed frame ${payload.resolution}`;

  const stats = byId<HTMLDivElement>("frame-stats");
  stats.innerHTML = `
    <strong>${payload.resolution}</strong><br/>
    FPS: ${payload.fps.toFixed(1)}<br/>
    Processing: ${payload.processingMs.toFixed(1)} ms<br/>
    Updated: ${new Date(payload.updatedAt).toLocaleTimeString()}
  `;
}

function simulateIncomingFrames() {
  populateFrame(SAMPLE_FRAME);

  const button = byId<HTMLButtonElement>("refresh-button");
  button.addEventListener("click", () => {
    const jittered: FramePayload = {
      ...SAMPLE_FRAME,
      fps: SAMPLE_FRAME.fps + (Math.random() - 0.5) * 2,
      processingMs: SAMPLE_FRAME.processingMs + (Math.random() - 0.5) * 1.5,
      updatedAt: new Date().toISOString()
    };
    populateFrame(jittered);
  });
}

document.addEventListener("DOMContentLoaded", simulateIncomingFrames);

