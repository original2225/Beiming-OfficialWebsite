const API_BASE_URL = import.meta.env?.VITE_API_BASE_URL || 'http://127.0.0.1:8787';

const enc = (value) => encodeURIComponent(value);
const nodePath = (nodeId, suffix = '') => `/api/nodes/${enc(nodeId)}${suffix}`;
const containerPath = (nodeId, containerId, suffix = '') => nodePath(nodeId, `/containers/${enc(containerId)}${suffix}`);

function authHeaders(token = '', headers = {}) {
  const normalized = token ? token.trim() : '';
  return normalized ? { ...headers, Authorization: `Bearer ${normalized}` } : headers;
}

function queryString(params = {}) {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return;
    query.set(key, String(value));
  });
  const text = query.toString();
  return text ? `?${text}` : '';
}

function pagedParams(params = {}) {
  return {
    ...params,
    page: params.page || 1,
    pageSize: params.pageSize || 20,
  };
}

async function request(path, options = {}) {
  const response = await fetch(`${API_BASE_URL}${path}`, options);
  const payload = await response.json().catch(() => null);
  if (!response.ok || payload?.ok === false) {
    throw new Error(payload?.message || response.statusText || 'Request failed');
  }
  return payload?.data ?? payload;
}

export function fetchNodes() {
  return request('/api/nodes');
}

export function fetchUsers(token = '') {
  return request('/api/users', {
    headers: authHeaders(token),
  });
}

export function fetchInviteCodes(token = '') {
  return request('/api/invite-codes', {
    headers: authHeaders(token),
  });
}

export function fetchAdminMembers(token = '', params = {}) {
  return request(`/api/profile/admin/members${queryString(pagedParams(params))}`, {
    headers: authHeaders(token),
  });
}

export function fetchCommunityAdminPosts(token = '', params = {}) {
  return request(`/api/community/admin/posts${queryString(pagedParams(params))}`, {
    headers: authHeaders(token),
  });
}

export function fetchCommunityAdminReports(token = '', params = {}) {
  return request(`/api/community/admin/reports${queryString(pagedParams(params))}`, {
    headers: authHeaders(token),
  });
}

export function fetchCommunityAuditLogs(token = '', params = {}) {
  return request(`/api/community/admin/audit-logs${queryString(pagedParams(params))}`, {
    headers: authHeaders(token),
  });
}

export function fetchAdminNotificationEvents(token = '', params = {}) {
  return request(`/api/notifications/admin/events${queryString(pagedParams(params))}`, {
    headers: authHeaders(token),
  });
}

export function fetchAdminNotificationDeliveries(token = '', params = {}) {
  return request(`/api/notifications/admin/deliveries${queryString(pagedParams(params))}`, {
    headers: authHeaders(token),
  });
}

export function pingNode(nodeId) {
  return request(nodePath(nodeId, '/ping'));
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

export function fetchNodeMetrics(nodeId) {
  return request(nodePath(nodeId, '/metrics'));
}

export function fetchContainers(nodeId, options = {}) {
  const query = options.fast ? '?fast=1' : '';
  return request(nodePath(nodeId, `/containers${query}`));
}

export function fetchContainerStats(nodeId) {
  return request(nodePath(nodeId, '/containers/stats'));
}

export function fetchContainer(nodeId, containerId, options = {}) {
  const query = options.fast ? '?fast=1' : '';
  return request(containerPath(nodeId, containerId, query));
}

export function createContainer(nodeId, payload) {
  return request(nodePath(nodeId, '/containers'), {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export function fetchImages(nodeId) {
  return request(nodePath(nodeId, '/images'));
}

export function searchDockerImages(query) {
  return request(`/api/docker/images/search?q=${encodeURIComponent(query)}`);
}

export function fetchContainerLogs(nodeId, containerId, tail = 200) {
  return request(containerPath(nodeId, containerId, `/logs?tail=${tail}`));
}

export function sendContainerCommand(nodeId, containerId, command) {
  return request(containerPath(nodeId, containerId, '/exec'), {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ command }),
  });
}

export function operateContainer(nodeId, containerId, operation) {
  return request(containerPath(nodeId, containerId, `/${operation}`), {
    method: 'POST',
  });
}

export function updateContainer(nodeId, containerId, payload) {
  return request(containerPath(nodeId, containerId), {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export function deleteContainer(nodeId, containerId) {
  return request(containerPath(nodeId, containerId), {
    method: 'DELETE',
  });
}

export function fetchContainerFiles(nodeId, containerId, path = '/') {
  return request(containerPath(nodeId, containerId, `/files?path=${enc(path)}`));
}

export function fetchContainerFileContent(nodeId, containerId, path = '/') {
  return request(containerPath(nodeId, containerId, `/files?path=${enc(path)}&download=1`));
}

export function createContainerFileEntry(nodeId, containerId, payload) {
  return request(containerPath(nodeId, containerId, '/files'), {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export function copyContainerFile(nodeId, containerId, path, targetPath) {
  return createContainerFileEntry(nodeId, containerId, { action: 'copy', path, targetPath });
}

export function extractContainerFile(nodeId, containerId, path, targetPath, encoding = 'utf-8') {
  return createContainerFileEntry(nodeId, containerId, { action: 'extract', path, targetPath, encoding });
}

export function uploadContainerFileChunkBinary(nodeId, containerId, payload) {
  const params = new URLSearchParams({
    path: payload.path || '/',
    name: payload.name || '',
    uploadId: payload.uploadId || '',
    chunkIndex: String(payload.chunkIndex || 0),
    totalChunks: String(payload.totalChunks || 0),
    chunkSize: String(payload.chunkSize || 0),
    size: String(payload.size || 0),
  });
  return request(containerPath(nodeId, containerId, `/files/upload-chunk?${params.toString()}`), {
    method: 'POST',
    headers: { 'content-type': 'application/octet-stream' },
    body: payload.chunk,
    signal: payload.signal,
  });
}

export function cleanupContainerUploads(nodeId, containerId, uploadIds = []) {
  return request(containerPath(nodeId, containerId, '/files/upload-cleanup'), {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ uploadIds }),
  });
}

export function beaconCleanupContainerUploads(nodeId, containerId, uploadIds = []) {
  if (!uploadIds.length || typeof navigator === 'undefined' || !navigator.sendBeacon) return false;
  const body = new Blob([JSON.stringify({ uploadIds })], { type: 'application/json' });
  return navigator.sendBeacon(`${API_BASE_URL}${containerPath(nodeId, containerId, '/files/upload-cleanup')}`, body);
}

export function renameContainerFile(nodeId, containerId, path, name, targetPath = '') {
  return request(containerPath(nodeId, containerId, '/files'), {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ path, name, targetPath }),
  });
}

export function deleteContainerFile(nodeId, containerId, path) {
  return request(containerPath(nodeId, containerId, `/files?path=${enc(path)}`), {
    method: 'DELETE',
  });
}

export async function fetchContainerFileDownloadInfo(nodeId, containerId, path, downloadId = '') {
  const params = new URLSearchParams({ path, ...(downloadId ? { downloadId } : {}) });
  const response = await fetch(`${API_BASE_URL}${containerPath(nodeId, containerId, `/files/download?${params.toString()}`)}`, {
    method: 'HEAD',
    cache: 'no-store',
  });
  if (!response.ok) throw new Error(response.statusText || 'Download info failed');
  const disposition = response.headers.get('content-disposition') || '';
  const match = disposition.match(/filename="([^"]+)"/);
  return {
    name: response.headers.get('x-file-name') || match?.[1] || path.split('/').pop() || 'download',
    size: Number(response.headers.get('x-file-size') || response.headers.get('content-length') || 0),
    acceptRanges: /bytes/i.test(response.headers.get('accept-ranges') || ''),
  };
}

export async function streamContainerFileRange(nodeId, containerId, path, start, end, signal, downloadId = '', onChunk = () => {}) {
  const params = new URLSearchParams({ path, ...(downloadId ? { downloadId } : {}) });
  const response = await fetch(`${API_BASE_URL}${containerPath(nodeId, containerId, `/files/download?${params.toString()}`)}`, {
    method: 'GET',
    headers: { range: `bytes=${start}-${end}` },
    cache: 'no-store',
    signal,
  });
  if (response.status !== 206) throw new Error(response.statusText || `Download range failed: ${response.status}`);
  if (!response.body) {
    const buffer = await response.arrayBuffer();
    const chunk = new Uint8Array(buffer);
    onChunk(chunk);
    return [chunk];
  }
  const reader = response.body.getReader();
  const chunks = [];
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    if (!value?.byteLength) continue;
    chunks.push(value);
    onChunk(value);
  }
  return chunks;
}

export function cancelContainerFileDownload(nodeId, containerId, downloadId) {
  return request(containerPath(nodeId, containerId, '/files/download-cancel'), {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ downloadId }),
  });
}

export function fetchVms(nodeId) {
  return request(nodePath(nodeId, '/vms'));
}

export function operateVm(nodeId, vmName, operation) {
  return request(nodePath(nodeId, `/vms/${enc(vmName)}/${operation}`), {
    method: 'POST',
  });
}
