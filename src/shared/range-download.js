const WORKER_FLOOR_CHUNK_SIZE = 512 * 1024;
const DEFAULT_MAX_RETRIES = 3;

const delay = (ms, signal) => new Promise((resolve, reject) => {
  if (signal?.aborted) {
    reject(new DOMException('Download cancelled', 'AbortError'));
    return;
  }
  const timer = setTimeout(() => {
    signal?.removeEventListener('abort', abort);
    resolve();
  }, ms);
  const abort = () => {
    clearTimeout(timer);
    reject(new DOMException('Download cancelled', 'AbortError'));
  };
  signal?.addEventListener('abort', abort, { once: true });
});

export async function runAdaptiveRangeDownload({
  fileSize,
  workerCount,
  transferRange,
  writeChunk,
  onProgress,
  onActiveChange,
  waitForResume,
  signal,
  maxRetries = DEFAULT_MAX_RETRIES,
}) {
  const size = Number(fileSize || 0);
  if (!size) return;

  const targetWorkers = Math.max(1, Math.min(Number(workerCount || 1), Math.ceil(size / WORKER_FLOOR_CHUNK_SIZE)));
  const segmentSize = Math.ceil(size / targetWorkers);
  let nextWorker = 0;
  const activeWorkerSeenAt = new Map();
  const workerBytes = new Map();
  const speedSamples = [{ time: performance.now(), bytes: 0 }];
  const activeWindowMs = 1500;
  const speedWindowMs = 2500;
  let totalBytes = 0;

  const emitActive = () => {
    const now = performance.now();
    for (const [workerId, seenAt] of activeWorkerSeenAt) {
      if (now - seenAt > activeWindowMs) activeWorkerSeenAt.delete(workerId);
    }
    speedSamples.push({ time: now, bytes: totalBytes });
    while (speedSamples.length > 2 && now - speedSamples[0].time > speedWindowMs) speedSamples.shift();
    const firstSample = speedSamples[0];
    const seconds = Math.max(0.001, (now - firstSample.time) / 1000);
    const rate = Math.max(0, (totalBytes - firstSample.bytes) / seconds);
    const activeCount = activeWorkerSeenAt.size;
    const averageWorkerRate = activeCount > 0 ? rate / activeCount : 0;
    onActiveChange?.(activeCount, targetWorkers, {
      averageWorkerRate,
      chunkSize: segmentSize,
      workerBytes: Object.fromEntries(workerBytes),
    });
  };

  const claimSegment = () => {
    if (nextWorker >= targetWorkers) return null;
    const index = nextWorker;
    const start = index * segmentSize;
    if (start >= size) return null;
    const end = Math.min(size - 1, start + segmentSize - 1);
    nextWorker += 1;
    return { start, end, index };
  };

  const markWorkerReceiving = (workerId) => {
    activeWorkerSeenAt.set(workerId, performance.now());
    emitActive();
  };

  const downloadRange = async (workerId, range) => {
    let downloaded = 0;
    let retries = 0;
    while (range.start + downloaded <= range.end) {
      if (signal.aborted) throw new DOMException('Download cancelled', 'AbortError');
      await waitForResume?.(signal);
      const requestStart = range.start + downloaded;
      try {
        await transferRange({
          start: requestStart,
          end: range.end,
          index: range.index,
          signal,
          onBytes: async (piece) => {
            if (signal.aborted) throw new DOMException('Download cancelled', 'AbortError');
            await waitForResume?.(signal);
            const data = piece instanceof Uint8Array ? piece : new Uint8Array(piece);
            const position = range.start + downloaded;
            const allowed = Math.min(data.byteLength, range.end - position + 1);
            if (allowed <= 0) return;
            const writablePiece = allowed === data.byteLength ? data : data.slice(0, allowed);
            markWorkerReceiving(workerId);
            downloaded += writablePiece.byteLength;
            totalBytes += writablePiece.byteLength;
            workerBytes.set(workerId, (workerBytes.get(workerId) || 0) + writablePiece.byteLength);
            onProgress?.(writablePiece.byteLength);
            await writeChunk(position, writablePiece);
          },
        });
        return;
      } catch (error) {
        if (signal.aborted) throw new DOMException('Download cancelled', 'AbortError');
        retries += 1;
        if (retries >= maxRetries) throw error;
        await delay(Math.min(5000, 300 * retries), signal);
      } finally {
        emitActive();
      }
    }
  };

  const worker = async (workerId) => {
    if (signal.aborted) throw new DOMException('Download cancelled', 'AbortError');
    await waitForResume?.(signal);
    const range = claimSegment();
    if (!range) return;
    await downloadRange(workerId, range);
  };

  const timerApi = typeof window === 'undefined' ? globalThis : window;
  const activeTimer = timerApi.setInterval(emitActive, 500);
  try {
    await Promise.all(Array.from({ length: targetWorkers }, (_, index) => worker(index)));
  } finally {
    timerApi.clearInterval(activeTimer);
  }
}
