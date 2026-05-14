import assert from 'node:assert/strict';
import { afterEach, test } from 'node:test';
import { fetchAdminNotificationEvents, fetchInviteCodes, fetchUsers } from './api.js';

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
});

function mockJsonFetch(assertRequest, payload = { ok: true, data: [] }) {
  globalThis.fetch = async (url, options = {}) => {
    assertRequest(url, options);
    return {
      ok: true,
      json: async () => payload,
    };
  };
}

test('fetchUsers requests the auth user list with bearer token', async () => {
  mockJsonFetch((url, options) => {
    assert.equal(url, 'http://127.0.0.1:8787/api/users');
    assert.equal(options.headers.Authorization, 'Bearer admin-token');
  });

  await assert.doesNotReject(fetchUsers('admin-token'));
});

test('fetchInviteCodes requests invite code administration with bearer token', async () => {
  mockJsonFetch((url, options) => {
    assert.equal(url, 'http://127.0.0.1:8787/api/invite-codes');
    assert.equal(options.headers.Authorization, 'Bearer admin-token');
  });

  await assert.doesNotReject(fetchInviteCodes('admin-token'));
});

test('fetchAdminNotificationEvents includes filter query and bearer token', async () => {
  mockJsonFetch((url, options) => {
    assert.equal(url, 'http://127.0.0.1:8787/api/notifications/admin/events?eventType=POST_LIKED&page=2&pageSize=10');
    assert.equal(options.headers.Authorization, 'Bearer admin-token');
  });

  await assert.doesNotReject(fetchAdminNotificationEvents('admin-token', {
    eventType: 'POST_LIKED',
    page: 2,
    pageSize: 10,
  }));
});
