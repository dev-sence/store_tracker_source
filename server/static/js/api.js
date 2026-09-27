const StoreTrackerApi = {
  async fetchDashboardData(mapKey) {
    const res = await fetch(`/api/dashboard-data?map=${encodeURIComponent(mapKey)}`);
    if (res.status === 401 || res.redirected) {
      window.location.href = '/login';
      throw new Error('unauthorized');
    }
    if (!res.ok) {
      throw new Error(`request failed: ${res.status}`);
    }
    return res.json();
  },

  async updateProfileUsername(username) {
    const res = await fetch('/api/profile/username', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username }),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || `request failed: ${res.status}`);
    }
    return data;
  },

  async fetchFeatureToggles(mapKey) {
    const res = await fetch(`/api/admin/feature-toggles?map=${encodeURIComponent(mapKey)}`);
    if (!res.ok) {
      throw new Error(`request failed: ${res.status}`);
    }
    return res.json();
  },

  async toggleFeature(mapKey, key) {
    const res = await fetch('/api/admin/feature-toggles', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ map_key: mapKey, key }),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || `request failed: ${res.status}`);
    }
    return data;
  },

  async fetchChestStrict(mapKey) {
    const res = await fetch(`/api/admin/chest-strict?map=${encodeURIComponent(mapKey)}`);
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || `request failed: ${res.status}`);
    }
    return data;
  },

  async toggleChestStrict(mapKey) {
    const res = await fetch('/api/admin/chest-strict', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ map_key: mapKey }),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || `request failed: ${res.status}`);
    }
    return data;
  },

  async fetchRequestLogForEvent(eventId) {
    const res = await fetch(`/api/admin/request-logs/by-event/${eventId}`);
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || `request failed: ${res.status}`);
    }
    return data;
  },

  async fetchMembers() {
    const res = await fetch('/api/admin/members');
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || `request failed: ${res.status}`);
    }
    return data;
  },

  async addMember(username) {
    const res = await fetch('/api/admin/members', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username }),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || `request failed: ${res.status}`);
    }
    return data;
  },

  async deleteMember(memberId) {
    const res = await fetch(`/api/admin/members/${memberId}`, { method: 'DELETE' });
    if (!res.ok) {
      const data = await res.json().catch(() => ({}));
      throw new Error(data.error || `request failed: ${res.status}`);
    }
  },

  async toggleMemberDeveloper(memberId) {
    const res = await fetch(`/api/admin/members/${memberId}/developer`, { method: 'POST' });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || `request failed: ${res.status}`);
    }
    return data;
  },

  async fetchChestLogs(mapKey) {
    const res = await fetch(`/api/admin/chest-logs?map=${encodeURIComponent(mapKey)}`);
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || `request failed: ${res.status}`);
    }
    return data;
  },

  async manualTransfer(mapKey, itemId, count, action) {
    const res = await fetch('/api/manual-transfer', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ map_key: mapKey, item_id: itemId, count, action }),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || `request failed: ${res.status}`);
    }
    return data;
  },

  async fetchPublicItems(mapKey) {
    const res = await fetch(`/api/admin/public-items?map=${encodeURIComponent(mapKey)}`);
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || `request failed: ${res.status}`);
    }
    return data;
  },

  async addPublicItem(mapKey, itemId, displayName) {
    const res = await fetch('/api/admin/public-items', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ map_key: mapKey, item_id: itemId, display_name: displayName }),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || `request failed: ${res.status}`);
    }
    return data;
  },

  async removePublicItem(mapKey, itemId) {
    const res = await fetch('/api/admin/public-items/remove', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ map_key: mapKey, item_id: itemId }),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || `request failed: ${res.status}`);
    }
    return data;
  },

  async fetchLoginLogs() {
    const res = await fetch('/api/admin/login-logs');
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || `request failed: ${res.status}`);
    }
    return data;
  },

  async fetchApplications() {
    const res = await fetch('/api/admin/applications');
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || `request failed: ${res.status}`);
    }
    return data;
  },

  async approveApplication(applicationId) {
    const res = await fetch(`/api/admin/applications/${applicationId}/approve`, { method: 'POST' });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || `request failed: ${res.status}`);
    }
    return data;
  },

  async rejectApplication(applicationId) {
    const res = await fetch(`/api/admin/applications/${applicationId}/reject`, { method: 'POST' });
    if (!res.ok) {
      const data = await res.json().catch(() => ({}));
      throw new Error(data.error || `request failed: ${res.status}`);
    }
  },

  async adjustInventory(mapKey, itemId, count) {
    const res = await fetch('/api/admin/inventory/adjust', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ map_key: mapKey, item_id: itemId, count }),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || `request failed: ${res.status}`);
    }
    return data;
  },
};
