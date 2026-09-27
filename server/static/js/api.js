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
