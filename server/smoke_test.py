from datetime import datetime, timezone

from app import create_app
from crypto import SecureChannel

app = create_app()
client = app.test_client()
channel = SecureChannel(app.config["APP_SECRET"])
admin_headers = {"X-Admin-Token": app.config["ADMIN_TOKEN"]}

r = client.post("/admin/members", json={"username": "Steve"}, headers=admin_headers)
print("add member:", r.status_code, r.get_json())
assert r.status_code == 201

r = client.get("/admin/members", headers=admin_headers)
print("list members:", r.status_code, r.get_json())
assert r.status_code == 200 and len(r.get_json()) == 1

payload = channel.encrypt_json({"username": "Steve"})
r = client.post("/api/check-member", json={"payload": payload})
print("check-member (registered):", r.status_code)
assert r.status_code == 200

payload = channel.encrypt_json({"username": "Ghost"})
r = client.post("/api/check-member", json={"payload": payload})
print("check-member (unregistered):", r.status_code)
assert r.status_code == 404

payload = channel.encrypt_json({
    "username": "Steve",
    "item_id": "minecraft:diamond_pickaxe",
    "item_name": "Diamond Pickaxe",
    "action": "TAKE",
    "count": 1,
    "occurred_at": datetime.now(timezone.utc).isoformat(),
})
r = client.post("/api/log-event", json={"payload": payload})
print("log-event:", r.status_code, r.get_json())
assert r.status_code == 202

r = client.get("/admin/events", headers=admin_headers)
print("list events:", r.status_code, r.get_json())
assert r.status_code == 200 and len(r.get_json()) == 1

r = client.delete("/admin/members/Steve", headers=admin_headers)
print("remove member:", r.status_code)
assert r.status_code == 204

r = client.post("/admin/members", json={"username": ""}, headers=admin_headers)
print("add invalid member:", r.status_code)
assert r.status_code == 400

r = client.post("/api/check-member", json={"payload": "not-valid-base64"})
print("check-member (bad payload):", r.status_code)
assert r.status_code == 400

r = client.get("/admin/members")
print("admin without token:", r.status_code)
assert r.status_code == 401

print("ALL SMOKE TESTS PASSED")
