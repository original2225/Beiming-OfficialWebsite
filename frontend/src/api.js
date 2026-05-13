const API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://127.0.0.1:8787';

export function readSession() {
  return {
    token: localStorage.getItem('beiming.token') || '',
  };
}

export function saveToken(token) {
  localStorage.setItem('beiming.token', token.trim());
}

export function clearToken() {
  localStorage.removeItem('beiming.token');
}

export async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (options.body && !headers.has('content-type')) headers.set('content-type', 'application/json');
  const token = options.token ?? readSession().token;
  if (token) headers.set('authorization', token.startsWith('Bearer ') ? token : `Bearer ${token}`);
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers,
    body: options.body && typeof options.body !== 'string' ? JSON.stringify(options.body) : options.body,
  });
  const payload = await response.json().catch(() => ({ ok: false, message: '服务返回了不可解析的响应' }));
  if (!response.ok || payload.ok === false) {
    throw new Error(payload.message || `请求失败 ${response.status}`);
  }
  return payload.data;
}

export async function login(email, password) {
  const data = await api('/api/auth/login', {
    method: 'POST',
    token: '',
    body: { email, password },
  });
  saveToken(data.token);
  return data;
}

export function getMe() {
  return api('/api/auth/me');
}

export function getProfileMe() {
  return api('/api/profile/me');
}

export function updateProfileMe(body) {
  return api('/api/profile/me', {
    method: 'PUT',
    body,
  });
}

export function getPublicMembers(query = {}) {
  const params = new URLSearchParams();
  params.set('page', String(query.page || 1));
  params.set('pageSize', String(query.pageSize || 20));
  if (query.q) params.set('q', query.q);
  return api(`/api/profile/members?${params.toString()}`, { token: '' });
}

export function getPublicMember(profileId) {
  return api(`/api/profile/members/${encodeURIComponent(profileId)}`, { token: '' });
}

export function getAdminMembers(query = {}) {
  const params = new URLSearchParams();
  params.set('page', String(query.page || 1));
  params.set('pageSize', String(query.pageSize || 50));
  if (query.q) params.set('q', query.q);
  return api(`/api/profile/admin/members?${params.toString()}`);
}

export function updateAdminMember(profileId, body) {
  return api(`/api/profile/admin/members/${encodeURIComponent(profileId)}`, {
    method: 'PUT',
    body,
  });
}
