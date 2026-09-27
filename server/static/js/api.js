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
};
