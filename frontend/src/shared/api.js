const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://127.0.0.1:8787';
const LOCAL_DOWNLOADER_URL = import.meta.env.VITE_LOCAL_DOWNLOADER_URL || 'http://127.0.0.1:18787';
const AUTH_TOKEN_STORAGE_KEY = 'beiming.auth.token';
const API_TIMEOUT_MS = 15000;

let authToken = '';

export function setApiAuthToken(token) {
  authToken = String(token || '').trim();
}

function currentAuthToken() {
  if (authToken) return authToken;
  if (typeof window === 'undefined') return '';
  return String(window.localStorage.getItem(AUTH_TOKEN_STORAGE_KEY) || '').trim();
}

const enc = (value) => encodeURIComponent(value);
const nodePath = (nodeId, suffix = '') => `/api/nodes/${enc(nodeId)}${suffix}`;
const daemonBaseUrl = (node) => String(node?.daemonUrl || '').replace(/\/+$/, '');
const daemonToken = (node) => String(node?.daemonToken || '').trim();
const daemonPath = (path = '') => `/api${path.startsWith('/') ? path : `/${path}`}`;
const daemonWsUrl = (node) => {
  const base = daemonBaseUrl(node).replace(/^http/i, 'ws');
  const token = daemonToken(node);
  return `${base}/ws${token ? `?token=${encodeURIComponent(token)}` : ''}`;
};
const daemonHeaders = (node, headers = {}) => {
  const token = daemonToken(node);
  return token ? { ...headers, Authorization: `Bearer ${token}` } : headers;
};
const networkSamples = new Map();
const nodeNetworkSamples = new Map();
const minNetworkSampleMs = 900;

async function errorMessage(response, fallback) {
  if (response.status === 204 || response.status === 304) return fallback;
  const text = await response.clone().text().catch(() => '');
  if (!text) return fallback;
  try {
    const payload = JSON.parse(text);
    return payload?.message || fallback;
  } catch {
    return text;
  }
}

async function request(path, options = {}) {
  const token = currentAuthToken();
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), options.timeoutMs || API_TIMEOUT_MS);
  try {
    const response = await fetch(`${API_BASE_URL}${path}`, {
      ...options,
      signal: options.signal || controller.signal,
      headers: {
        ...(options.headers || {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
    });
    const payload = await response.json().catch(() => null);
    if (!response.ok || payload?.ok === false) {
      const error = new Error(payload?.message || response.statusText || 'Request failed');
      error.status = response.status;
      throw error;
    }
    return payload?.data ?? payload;
  } catch (error) {
    if (error.name === 'AbortError') throw new Error('请求超时，请检查后端服务或数据库连接');
    throw error;
  } finally {
    window.clearTimeout(timeout);
  }
}

export function loginUser(payload) {
  return request('/api/auth/login', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export function registerUser(payload) {
  return request('/api/auth/register', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export function fetchCurrentUser() {
  return request('/api/auth/me');
}

export function logoutUser() {
  return request('/api/auth/logout', { method: 'POST' });
}

export function fetchUsers() {
  return request('/api/users');
}

export function fetchOneDriveStatus() {
  return request('/api/cloud/onedrive/status');
}

export function saveOneDriveConfig(payload) {
  return request('/api/cloud/onedrive/config', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export function startOneDriveAuth() {
  return request('/api/cloud/onedrive/auth', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
  });
}

export function connectOneDrive(code) {
  return request('/api/cloud/onedrive/connect', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code }),
  });
}

export function mountOneDriveSharedFolder(payload) {
  return request('/api/cloud/onedrive/shared-folder', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export function mountOneDriveSharedItem(payload) {
  return request('/api/cloud/onedrive/shared-item', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export function disconnectCloudDrive(driveId) {
  return request(`/api/cloud/drives/${enc(driveId)}`, { method: 'DELETE' });
}

export function fetchCloudFiles(driveId, itemId = 'root', options = {}) {
  const params = new URLSearchParams({ itemId: itemId || 'root' });
  if (options.cursor) params.set('cursor', options.cursor);
  if (options.limit) params.set('limit', String(options.limit));
  return request(`/api/cloud/drives/${enc(driveId)}/items?${params.toString()}`, {
    signal: options.signal,
  });
}

export function createCloudFolder(driveId, parentId, name) {
  return request(`/api/cloud/drives/${enc(driveId)}/folders`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ parentId, name }),
  });
}

export function renameCloudItem(driveId, itemId, name) {
  return request(`/api/cloud/drives/${enc(driveId)}/items/${enc(itemId)}`, {
    method: 'PATCH',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ name }),
  });
}

export function copyCloudItem(driveId, itemId, payload) {
  return request(`/api/cloud/drives/${enc(driveId)}/items/${enc(itemId)}/copy`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    timeoutMs: 120000,
    body: JSON.stringify(payload),
  });
}

export function moveCloudItem(driveId, itemId, payload) {
  return request(`/api/cloud/drives/${enc(driveId)}/items/${enc(itemId)}/move`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export function deleteCloudItem(driveId, itemId) {
  return request(`/api/cloud/drives/${enc(driveId)}/items/${enc(itemId)}`, { method: 'DELETE' });
}

export function fetchCloudDownloadInfo(driveId, itemId) {
  return request(`/api/cloud/drives/${enc(driveId)}/items/${enc(itemId)}/download`);
}

async function localDownloaderRequest(path, options = {}) {
  const response = await fetch(`${LOCAL_DOWNLOADER_URL}${path}`, {
    ...options,
    headers: {
      ...(options.headers || {}),
    },
  });
  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    throw new Error(payload?.message || payload?.error || response.statusText || 'Local downloader request failed');
  }
  return payload;
}

export function pingLocalDownloader(options = {}) {
  return localDownloaderRequest('/health', { signal: options.signal });
}

export function fetchLocalTransfers() {
  return localDownloaderRequest('/transfers');
}

export function startLocalDownload(payload) {
  return localDownloaderRequest('/downloads', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export function fetchLocalDownloadStatus(taskId) {
  return localDownloaderRequest(`/downloads/${enc(taskId)}`);
}

export function pauseLocalDownload(taskId) {
  return localDownloaderRequest(`/downloads/${enc(taskId)}/pause`, { method: 'POST' });
}

export function resumeLocalDownload(taskId) {
  return localDownloaderRequest(`/downloads/${enc(taskId)}/resume`, { method: 'POST' });
}

export function cancelLocalDownload(taskId) {
  return localDownloaderRequest(`/downloads/${enc(taskId)}/cancel`, { method: 'POST' });
}

export function revealLocalDownload(taskId) {
  return localDownloaderRequest(`/downloads/${enc(taskId)}/reveal`, { method: 'POST' });
}

export function selectLocalUploadFiles() {
  return localDownloaderRequest('/uploads/select', { method: 'POST' });
}

export function startLocalUpload(payload) {
  return localDownloaderRequest('/uploads', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export function fetchLocalUploadStatus(taskId) {
  return localDownloaderRequest(`/uploads/${enc(taskId)}`);
}

export function cancelLocalUpload(taskId) {
  return localDownloaderRequest(`/uploads/${enc(taskId)}/cancel`, { method: 'POST' });
}

export function createCloudUploadSession(driveId, payload) {
  return request(`/api/cloud/drives/${enc(driveId)}/upload-session`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

async function daemonRequest(node, path, options = {}) {
  const baseUrl = daemonBaseUrl(node);
  if (!baseUrl) throw new Error('节点没有配置 daemon 地址');
  const response = await fetch(`${baseUrl}${daemonPath(path)}`, {
    ...options,
    headers: daemonHeaders(node, options.headers || {}),
  });
  const payload = await response.json().catch(() => null);
  if (!response.ok || payload?.ok === false) {
    throw new Error(payload?.message || response.statusText || 'Daemon request failed');
  }
  return payload?.data ?? payload;
}

async function daemonRootRequest(node, path, options = {}) {
  const baseUrl = daemonBaseUrl(node);
  if (!baseUrl) throw new Error('节点没有配置 daemon 地址');
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers: daemonHeaders(node, options.headers || {}),
  });
  const payload = await response.json().catch(() => null);
  if (!response.ok || payload?.ok === false) {
    throw new Error(payload?.message || response.statusText || 'Daemon request failed');
  }
  return payload?.data ?? payload;
}

export function fetchNodes() {
  return request('/api/nodes');
}

export function pingNode(node) {
  return daemonRootRequest(node, '/health');
}

export function createNode(payload) {
  return request('/api/nodes', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export function updateNode(nodeId, payload) {
  return request(nodePath(nodeId), {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export function deleteNode(nodeId) {
  return request(nodePath(nodeId), {
    method: 'DELETE',
  });
}

export function fetchNodeMetrics(node) {
  return daemonRequest(node, '/metrics').then((raw) => normalizeNodeMetrics(node, raw));
}

export function fetchContainers(node, options = {}) {
  const query = options.fast ? '?fast=1' : '';
  return daemonRequest(node, `/containers${query}`).then((payload) => normalizeContainersPayload(node, payload));
}

export function fetchContainerStats(node) {
  return daemonRequest(node, '/containers/stats').then((payload) => normalizeContainerStatsPayload(node, payload));
}

export function fetchContainer(node, containerId, options = {}) {
  const query = options.fast ? '?fast=1' : '';
  return daemonRequest(node, `/containers/${enc(containerId)}${query}`).then((payload) => normalizeContainerPayload(node, payload));
}

export function createContainer(node, payload) {
  return daemonRequest(node, '/containers', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export function fetchImages(node) {
  return daemonRequest(node, '/images').then((items) => (items || []).map(normalizeImage).filter((image) => image.name));
}

export function searchDockerImages(query) {
  return request(`/api/docker/images/search?q=${encodeURIComponent(query)}`);
}

export function fetchContainerLogs(node, containerId, tail = 200, options = {}) {
  const params = new URLSearchParams({ tail: String(tail) });
  if (options.sinceStart) params.set('sinceStart', '1');
  return daemonRequest(node, `/containers/${enc(containerId)}/logs?${params.toString()}`);
}

export function sendContainerCommand(node, containerId, command) {
  return daemonRequest(node, `/containers/${enc(containerId)}/exec`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ command }),
  });
}

export function operateContainer(node, containerId, operation) {
  return daemonRequest(node, `/containers/${enc(containerId)}/${operation}`, {
    method: 'POST',
  });
}

export function updateContainer(node, containerId, payload) {
  return daemonRequest(node, `/containers/${enc(containerId)}`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export function deleteContainer(node, containerId) {
  return daemonRequest(node, `/containers/${enc(containerId)}`, {
    method: 'DELETE',
  });
}

export function fetchContainerFiles(node, containerId, path = '/') {
  return daemonRequest(node, `/containers/${enc(containerId)}/files?path=${enc(path)}`);
}

export function fetchContainerFileContent(node, containerId, path = '/') {
  return daemonRequest(node, `/containers/${enc(containerId)}/files?path=${enc(path)}&download=1`);
}

export function createContainerFileEntry(node, containerId, payload) {
  return daemonRequest(node, `/containers/${enc(containerId)}/files`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export function copyContainerFile(node, containerId, path, targetPath) {
  return createContainerFileEntry(node, containerId, { action: 'copy', path, targetPath });
}

export function extractContainerFile(node, containerId, path, targetPath, encoding = 'utf-8') {
  return createContainerFileEntry(node, containerId, { action: 'extract', path, targetPath, encoding });
}

export function uploadContainerFileChunkBinary(node, containerId, payload) {
  const params = new URLSearchParams({
    path: payload.path || '/',
    name: payload.name || '',
    uploadId: payload.uploadId || '',
    chunkIndex: String(payload.chunkIndex || 0),
    totalChunks: String(payload.totalChunks || 0),
    chunkSize: String(payload.chunkSize || 0),
    size: String(payload.size || 0),
  });
  return daemonRequest(node, `/containers/${enc(containerId)}/files/upload-chunk?${params.toString()}`, {
    method: 'POST',
    headers: { 'content-type': 'application/octet-stream' },
    body: payload.chunk,
    signal: payload.signal,
  });
}

export function cleanupContainerUploads(node, containerId, uploadIds = []) {
  return daemonRequest(node, `/containers/${enc(containerId)}/files/upload-cleanup`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ uploadIds }),
  });
}

export function beaconCleanupContainerUploads(node, containerId, uploadIds = []) {
  if (!uploadIds.length || typeof navigator === 'undefined' || !navigator.sendBeacon) return false;
  const baseUrl = daemonBaseUrl(node);
  if (!baseUrl) return false;
  const params = new URLSearchParams();
  const token = daemonToken(node);
  if (token) params.set('token', token);
  const body = new Blob([JSON.stringify({ uploadIds })], { type: 'application/json' });
  return navigator.sendBeacon(`${baseUrl}${daemonPath(`/containers/${enc(containerId)}/files/upload-cleanup`)}${params.size ? `?${params}` : ''}`, body);
}

export function renameContainerFile(node, containerId, path, name, targetPath = '') {
  return daemonRequest(node, `/containers/${enc(containerId)}/files`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ path, name, targetPath }),
  });
}

export function deleteContainerFile(node, containerId, path) {
  return daemonRequest(node, `/containers/${enc(containerId)}/files?path=${enc(path)}`, {
    method: 'DELETE',
  });
}

export async function fetchContainerFileDownloadInfo(node, containerId, path, downloadId = '', signal) {
  const params = new URLSearchParams({ path, ...(downloadId ? { downloadId } : {}) });
  const response = await fetch(`${daemonBaseUrl(node)}${daemonPath(`/containers/${enc(containerId)}/files/download?${params.toString()}`)}`, {
    method: 'HEAD',
    headers: daemonHeaders(node),
    cache: 'no-store',
    signal,
  });
  if (!response.ok) throw new Error(await errorMessage(response, response.statusText || 'Download info failed'));
  const disposition = response.headers.get('content-disposition') || '';
  const encodedMatch = disposition.match(/filename\*=UTF-8''([^;]+)/i);
  const match = disposition.match(/filename="([^"]+)"/);
  const headerName = response.headers.get('x-file-name') || '';
  const decodedName = encodedMatch?.[1] ? decodeURIComponent(encodedMatch[1]) : '';
  return {
    name: decodedName || headerName || match?.[1] || path.split('/').pop() || 'download',
    size: Number(response.headers.get('x-file-size') || response.headers.get('content-length') || 0),
    acceptRanges: /bytes/i.test(response.headers.get('accept-ranges') || ''),
  };
}

export async function streamContainerFileRange(node, containerId, path, start, end, signal, downloadId = '', onChunk = () => {}) {
  const params = new URLSearchParams({ path, ...(downloadId ? { downloadId } : {}) });
  const response = await fetch(`${daemonBaseUrl(node)}${daemonPath(`/containers/${enc(containerId)}/files/download?${params.toString()}`)}`, {
    method: 'GET',
    headers: daemonHeaders(node, { range: `bytes=${start}-${end}` }),
    cache: 'no-store',
    signal,
  });
  if (response.status !== 206 && response.status !== 200) throw new Error(await errorMessage(response, response.statusText || `Download range failed: ${response.status}`));
  if (!response.body) {
    const buffer = await response.arrayBuffer();
    const chunk = new Uint8Array(buffer);
    await onChunk(chunk);
    return;
  }
  const reader = response.body.getReader();
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!value?.byteLength) continue;
      await onChunk(value);
    }
  } catch (error) {
    await reader.cancel().catch(() => undefined);
    throw error;
  }
}

export function cancelContainerFileDownload(node, containerId, downloadId) {
  return daemonRequest(node, `/containers/${enc(containerId)}/files/download-cancel`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ downloadId }),
  });
}

export function fetchVms(node) {
  return daemonRequest(node, '/vms').then(parseVirshList);
}

export function operateVm(node, vmName, operation) {
  return daemonRequest(node, `/vms/${enc(vmName)}/${operation}`, {
    method: 'POST',
  });
}

export function createDaemonRealtimeClientUrl(node) {
  return daemonWsUrl(node);
}

function normalizeContainersPayload(node, payload = {}) {
  const rows = Array.isArray(payload.rows) ? payload.rows : [];
  const statsRows = Array.isArray(payload.stats) ? payload.stats : [];
  const inspectRows = Array.isArray(payload.inspect) ? payload.inspect : [];
  const swapRows = payload.swap || {};
  const cpuThreads = Number(payload.cpuThreads || 1);
  const statsById = byAny(statsRows, ['ID', 'Container']);
  const statsByName = byAny(statsRows, ['Name']);
  const inspectById = inspectByShortId(inspectRows);
  return rows.map((row) => {
    const inspect = inspectById.get(String(row.ID || '')) || {};
    const swapValue = swapRows[String(row.ID || '')] ?? swapRows[pathString(inspect, 'Id')];
    return normalizeContainer(node, row, firstMap(statsById.get(String(row.ID || '')), statsByName.get(String(row.Names || ''))), inspect, swapValue, cpuThreads);
  });
}

function normalizeContainerStatsPayload(node, payload = {}) {
  const statsRows = Array.isArray(payload.stats) ? payload.stats : [];
  const inspectRows = Array.isArray(payload.inspect) ? payload.inspect : [];
  const inspectById = inspectByShortId(inspectRows);
  const inspectByName = byName(inspectRows);
  const swapRows = payload.swap || {};
  const cpuThreads = Number(payload.cpuThreads || 1);
  return statsRows.map((stats) => {
    const shortId = String(stats.ID || stats.Container || '');
    const inspect = firstMap(inspectById.get(shortId), inspectByName.get(String(stats.Name || '')));
    const row = {
      ID: shortId || left(pathString(inspect, 'Id'), 12),
      Names: String(stats.Name || '').trim() || trimSlash(pathString(inspect, 'Name')),
      Image: pathString(inspect, 'Config', 'Image'),
      State: pathString(inspect, 'State', 'Status'),
      Status: pathString(inspect, 'State', 'Status'),
      Ports: '',
      Command: '',
    };
    return normalizeContainer(node, row, stats, inspect, swapRows[String(row.ID || '')] ?? swapRows[pathString(inspect, 'Id')], cpuThreads);
  });
}

function normalizeContainerPayload(node, payload = {}) {
  const row = payload.row || {};
  return normalizeContainer(node, row, payload.stats || {}, payload.inspect || {}, payload.swap, Number(payload.cpuThreads || 1));
}

function normalizeContainer(node, row = {}, stats = {}, inspect = {}, swapUsedBytes = 0, cpuThreads = 1) {
  const mem = parseUsagePair(String(stats.MemUsage || ''));
  const netText = String(stats.NetIO || '');
  const net = parseUsagePair(netText);
  const rate = calculateNetworkRate(`${node?.id || ''}:${String(row.ID || '')}`, net.usedBytes, net.limitBytes, Boolean(netText.trim()));
  const labels = asMap(pathValue(inspect, 'Config', 'Labels'));
  const cpuPercent = parseDockerPercent(String(stats.CPUPerc || ''));
  return {
    id: String(row.ID || ''),
    name: String(row.Names || ''),
    image: stringWithFallback(pathValue(inspect, 'Config', 'Image'), row.Image),
    status: String(row.Status || ''),
    state: stringWithFallback(pathValue(inspect, 'State', 'Status'), row.State),
    ports: String(row.Ports || ''),
    command: String(row.Command || ''),
    startedAt: String(pathValue(inspect, 'State', 'StartedAt') || ''),
    finishedAt: String(pathValue(inspect, 'State', 'FinishedAt') || ''),
    created: String(inspect.Created || row.CreatedAt || ''),
    labels,
    stats: {
      cpuPercent,
      cpuUsagePercent: normalizeCpuUsagePercent(cpuPercent, cpuThreads),
      memoryPercent: parseDockerPercent(String(stats.MemPerc || '')),
      memoryUsedBytes: mem.usedBytes,
      memoryLimitBytes: mem.limitBytes,
      swapUsedBytes: Number(swapUsedBytes || 0),
      networkRxBytes: net.usedBytes,
      networkTxBytes: net.limitBytes,
      networkDownloadBps: rate.downloadBps,
      networkUploadBps: rate.uploadBps,
    },
    network: {
      mode: stringWithFallback(pathValue(inspect, 'HostConfig', 'NetworkMode'), 'default'),
      ports: normalizePorts(asMap(pathValue(inspect, 'NetworkSettings', 'Ports'))),
    },
    config: {
      env: asList(pathValue(inspect, 'Config', 'Env')),
      mounts: normalizeMounts(inspect),
      privileged: Boolean(pathValue(inspect, 'HostConfig', 'Privileged')),
      workingDir: String(pathValue(inspect, 'Config', 'WorkingDir') || ''),
      command: asList(pathValue(inspect, 'Config', 'Cmd')).map(String).join(' '),
      cpuLimit: Number(pathValue(inspect, 'HostConfig', 'NanoCpus') || 0) ? Math.round((Number(pathValue(inspect, 'HostConfig', 'NanoCpus')) / 1_000_000_000) * 100) / 100 : '',
      memoryLimit: Number(pathValue(inspect, 'HostConfig', 'Memory') || 0),
      networkDownloadLimit: String(labels['beiming.net.download'] || ''),
      networkUploadLimit: String(labels['beiming.net.upload'] || ''),
      stdinOpen: Boolean(pathValue(inspect, 'Config', 'OpenStdin')),
      tty: Boolean(pathValue(inspect, 'Config', 'Tty')),
    },
    restartPolicy: stringWithFallback(pathValue(inspect, 'HostConfig', 'RestartPolicy', 'Name'), 'no'),
    terminal: {
      attachStdin: Boolean(pathValue(inspect, 'Config', 'AttachStdin')),
      attachStdout: Boolean(pathValue(inspect, 'Config', 'AttachStdout')),
      attachStderr: Boolean(pathValue(inspect, 'Config', 'AttachStderr')),
      openStdin: Boolean(pathValue(inspect, 'Config', 'OpenStdin')),
      tty: Boolean(pathValue(inspect, 'Config', 'Tty')),
      interactive: Boolean(pathValue(inspect, 'Config', 'OpenStdin')) || Boolean(pathValue(inspect, 'Config', 'Tty')) || Boolean(pathValue(inspect, 'Config', 'AttachStdin')),
    },
  };
}

function normalizeNodeMetrics(node, raw = {}) {
  const mem = splitNumbers(raw.mem);
  const swap = splitNumbers(raw.swap);
  const diskParts = String(raw.disk || '').split(/\s+/);
  const net = splitNumbers(raw.net);
  const downloadBytes = Math.max(0, Number(net[0] || 0));
  const uploadBytes = Math.max(0, Number(net[1] || 0));
  const rate = calculateNodeNetworkRate(node?.id || daemonBaseUrl(node), downloadBytes, uploadBytes);
  return {
    cpu: parseCpuIdle(String(raw.cpuLine || '')),
    cpuSpec: { cores: Number(raw.cpuCores || 0), threads: Number(raw.cpuThreads || raw.cpuCores || 0) },
    memory: metricUsage(mem),
    swap: metricUsage(swap),
    disk: {
      totalMb: Number(diskParts[0] || 0),
      usedMb: Number(diskParts[1] || 0),
      percent: Number(String(diskParts[2] || 0).replace('%', '')) || 0,
    },
    network: rate,
    networkTotals: { downloadBytes, uploadBytes },
    load: splitNumbers(raw.load),
    updatedAt: Date.now(),
  };
}

function normalizeImage(row = {}) {
  const repo = String(row.Repository || '');
  const tag = String(row.Tag || 'latest');
  return {
    id: String(row.ID || ''),
    name: repo && tag && tag !== '<none>' ? `${repo}:${tag}` : repo,
    repository: repo,
    tag,
    size: String(row.Size || ''),
    createdSince: String(row.CreatedSince || ''),
  };
}

function parseVirshList(text = '') {
  return String(text || '').split(/\r?\n/).slice(2).map((line) => line.trim()).filter(Boolean).map((line) => {
    const parts = line.split(/\s{2,}/, 3);
    if (parts.length < 3) return null;
    return { id: parts[0] === '-' ? null : parts[0], name: parts[1].trim(), state: parts[2].trim() };
  }).filter(Boolean);
}

function calculateNetworkRate(key, rxBytes, txBytes, hasNetworkStats) {
  const now = Date.now();
  const previous = networkSamples.get(key);
  if (!hasNetworkStats) return previous ? { downloadBps: previous.downloadBps, uploadBps: previous.uploadBps } : { downloadBps: 0, uploadBps: 0 };
  if (!previous || rxBytes < previous.rxBytes || txBytes < previous.txBytes) {
    networkSamples.set(key, { rxBytes, txBytes, now, downloadBps: 0, uploadBps: 0 });
    return { downloadBps: 0, uploadBps: 0 };
  }
  const elapsedMs = now - previous.now;
  if (elapsedMs < minNetworkSampleMs) return { downloadBps: previous.downloadBps, uploadBps: previous.uploadBps };
  const seconds = elapsedMs / 1000;
  const next = {
    rxBytes,
    txBytes,
    now,
    downloadBps: Math.max(0, Math.round((rxBytes - previous.rxBytes) / seconds)),
    uploadBps: Math.max(0, Math.round((txBytes - previous.txBytes) / seconds)),
  };
  networkSamples.set(key, next);
  return { downloadBps: next.downloadBps, uploadBps: next.uploadBps };
}

function calculateNodeNetworkRate(key, downloadBytes, uploadBytes) {
  const now = Date.now();
  const previous = nodeNetworkSamples.get(key);
  if (!previous || downloadBytes < previous.downloadBytes || uploadBytes < previous.uploadBytes) {
    nodeNetworkSamples.set(key, { downloadBytes, uploadBytes, now, downloadBps: 0, uploadBps: 0 });
    return { downloadBps: 0, uploadBps: 0 };
  }
  const elapsedMs = now - previous.now;
  if (elapsedMs < minNetworkSampleMs) return { downloadBps: previous.downloadBps, uploadBps: previous.uploadBps };
  const seconds = elapsedMs / 1000;
  const next = {
    downloadBytes,
    uploadBytes,
    now,
    downloadBps: Math.round((downloadBytes - previous.downloadBytes) / seconds),
    uploadBps: Math.round((uploadBytes - previous.uploadBytes) / seconds),
  };
  nodeNetworkSamples.set(key, next);
  return { downloadBps: next.downloadBps, uploadBps: next.uploadBps };
}

function metricUsage(values = []) {
  const total = Number(values[0] || 0);
  const used = Number(values[1] || 0);
  return {
    totalMb: total,
    usedMb: used,
    totalGb: Math.round((total / 1024) * 100) / 100,
    usedGb: Math.round((used / 1024) * 100) / 100,
    percent: total > 0 ? Math.round((used / total) * 10000) / 100 : 0,
  };
}

function normalizePorts(ports = {}) {
  return Object.entries(ports).map(([containerPort, value]) => {
    const bindings = asList(value);
    return {
      containerPort,
      host: bindings.length ? bindings.map((binding) => `${String(asMap(binding).HostIp || '0.0.0.0')}:${String(asMap(binding).HostPort || '')}`).join(', ') : '',
    };
  });
}

function normalizeMounts(inspect = {}) {
  const result = [];
  asList(pathValue(inspect, 'HostConfig', 'Binds')).forEach((bind) => {
    const spec = String(bind);
    result.push({ spec, type: spec.split(':', 2)[0].startsWith('/') ? 'bind' : 'volume' });
  });
  asList(inspect.Mounts).forEach((mountRaw) => {
    const mount = asMap(mountRaw);
    const type = String(mount.Type) === 'volume' ? 'volume' : 'bind';
    const source = type === 'volume' ? String(mount.Name || '') : String(mount.Source || '');
    const target = String(mount.Destination || '');
    const mode = mount.RW === false ? 'ro' : 'rw';
    result.push({ spec: `${source}:${target}:${mode}`, type });
  });
  return Array.from(new Map(result.map((item) => [item.spec, item])).values());
}

function parseUsagePair(value = '') {
  const parts = String(value || '').split('/', 2);
  return { usedBytes: parseDockerSize(parts[0]), limitBytes: parseDockerSize(parts[1]) };
}

function parseDockerSize(value = '') {
  const match = String(value || '').trim().toLowerCase().match(/^([\d.]+)\s*([kmgt]?i?b|b)?$/);
  if (!match) return 0;
  const amount = Number(match[1]);
  const unit = match[2] || 'b';
  const scale = unit === 'kb' || unit === 'kib' ? 1024
    : unit === 'mb' || unit === 'mib' ? 1024 ** 2
      : unit === 'gb' || unit === 'gib' ? 1024 ** 3
        : unit === 'tb' || unit === 'tib' ? 1024 ** 4
          : 1;
  return Math.round(amount * scale);
}

function parseDockerPercent(value = '') {
  const number = Number(String(value || '').replace('%', ''));
  return Number.isFinite(number) ? Math.round(number * 100) / 100 : 0;
}

function normalizeCpuUsagePercent(cpuPercent, cpuThreads) {
  const threads = Number(cpuThreads || 0);
  if (threads <= 0) return Math.min(cpuPercent, 100);
  return Math.round(Math.min(cpuPercent / threads, 100) * 100) / 100;
}

function parseCpuIdle(cpuLine = '') {
  const match = String(cpuLine || '').match(/(\d+(?:\.\d+)?)\s*id/);
  return match ? Math.round(Math.max(0, Math.min(100, 100 - Number(match[1]))) * 100) / 100 : null;
}

function splitNumbers(value = '') {
  const text = String(value || '').trim();
  return text ? text.split(/\s+/).map(Number) : [];
}

function byAny(rows = [], keys = []) {
  const result = new Map();
  rows.forEach((row) => keys.forEach((key) => {
    const value = String(row[key] || '');
    if (value) result.set(value, row);
  }));
  return result;
}

function inspectByShortId(rows = []) {
  const result = new Map();
  rows.forEach((row) => result.set(left(String(row.Id || ''), 12), row));
  return result;
}

function byName(rows = []) {
  const result = new Map();
  rows.forEach((row) => result.set(trimSlash(String(row.Name || '')), row));
  return result;
}

function firstMap(leftValue, rightValue) {
  return leftValue && Object.keys(leftValue).length ? leftValue : rightValue || {};
}

function asMap(value) {
  return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
}

function asList(value) {
  return Array.isArray(value) ? value : [];
}

function pathValue(root, ...keys) {
  return keys.reduce((current, key) => (current && typeof current === 'object' ? current[key] : undefined), root);
}

function pathString(root, ...keys) {
  return String(pathValue(root, ...keys) || '');
}

function stringWithFallback(value, fallback = '') {
  const text = String(value || '');
  return text.trim() ? text : String(fallback || '');
}

function left(value = '', size = 0) {
  return String(value || '').slice(0, size);
}

function trimSlash(value = '') {
  return String(value || '').replace(/^\/+/, '');
}
