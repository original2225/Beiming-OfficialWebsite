const containerCachePrefix = 'beiming:containers:';
const containerDetailCachePrefix = 'beiming:container:';
const containerCacheTtl = 5 * 60 * 1000;

export function readCachedContainers(nodeId) {
  if (!nodeId || typeof window === 'undefined') return [];
  try {
    const raw = window.localStorage.getItem(`${containerCachePrefix}${nodeId}`);
    if (!raw) return [];
    const payload = JSON.parse(raw);
    if (!payload?.savedAt || Date.now() - payload.savedAt > containerCacheTtl) return [];
    return Array.isArray(payload.items) ? payload.items : [];
  } catch {
    return [];
  }
}

export function writeCachedContainers(nodeId, items) {
  if (!nodeId || !Array.isArray(items) || typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(`${containerCachePrefix}${nodeId}`, JSON.stringify({
      savedAt: Date.now(),
      items,
    }));
    for (const item of items) {
      writeCachedContainer(nodeId, item);
    }
  } catch {
    // Cache is only for faster first paint.
  }
}

export function readCachedContainer(nodeId, containerId) {
  if (!nodeId || !containerId || typeof window === 'undefined') return null;
  try {
    const raw = window.localStorage.getItem(`${containerDetailCachePrefix}${nodeId}:${containerId}`);
    if (!raw) return null;
    const payload = JSON.parse(raw);
    if (!payload?.savedAt || Date.now() - payload.savedAt > containerCacheTtl) return null;
    return payload.item || null;
  } catch {
    return null;
  }
}

export function writeCachedContainer(nodeId, item) {
  if (!nodeId || !item || typeof window === 'undefined') return;
  const keys = [item.id, item.name].filter(Boolean);
  if (item.ID) keys.push(item.ID);
  if (item.Names) keys.push(item.Names);
  try {
    const payload = JSON.stringify({ savedAt: Date.now(), item });
    for (const key of new Set(keys)) {
      window.localStorage.setItem(`${containerDetailCachePrefix}${nodeId}:${key}`, payload);
    }
  } catch {
    // Cache is only for faster first paint.
  }
}

export const containerFinalStates = new Set(['running', 'exited', 'created', 'dead']);
