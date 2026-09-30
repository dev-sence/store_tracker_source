from functools import wraps

from flask import Blueprint, current_app, jsonify, request

from models import (
    ChestInventoryItem, DashboardMessage, Event, FeatureToggle, Member, PlayerItemLedger, PublicItemType,
    RequestLog, TrackedChest, db,
)

# map_key 컬럼을 갖는 모든 테이블 - 맵 키 이름을 바꿀 때(/admin/rename-map-key) 전부 같이 옮긴다.
_MAP_KEY_MODELS = [
    TrackedChest, PublicItemType, ChestInventoryItem, PlayerItemLedger, FeatureToggle, Event, DashboardMessage,
]

admin_bp = Blueprint("admin", __name__, url_prefix="/admin")


def require_admin(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        token = request.headers.get("X-Admin-Token", "")
        if token != current_app.config["ADMIN_TOKEN"]:
            return jsonify({"error": "unauthorized"}), 401
        return fn(*args, **kwargs)

    return wrapper


@admin_bp.get("/members")
@require_admin
def list_members():
    members = Member.query.order_by(Member.added_at.desc()).all()
    return jsonify([m.to_dict() for m in members])


@admin_bp.post("/members")
@require_admin
def add_member():
    body = request.get_json(silent=True) or {}
    username = (body.get("username") or "").strip()
    if not username:
        return jsonify({"error": "username is required"}), 400

    if Member.query.filter_by(minecraft_username=username).first():
        return jsonify({"error": "already registered"}), 409

    member = Member(
        minecraft_username=username,
        minecraft_uuid=body.get("uuid"),
        added_by=body.get("added_by"),
    )
    db.session.add(member)
    db.session.commit()
    return jsonify(member.to_dict()), 201


@admin_bp.delete("/members/<username>")
@require_admin
def remove_member(username):
    member = Member.query.filter_by(minecraft_username=username).first()
    if not member:
        return jsonify({"error": "not found"}), 404

    db.session.delete(member)
    db.session.commit()
    return "", 204


@admin_bp.get("/events")
@require_admin
def list_events():
    limit = min(int(request.args.get("limit", 100)), 500)
    events = Event.query.order_by(Event.received_at.desc()).limit(limit).all()
    return jsonify([e.to_dict() for e in events])


@admin_bp.post("/rename-map-key")
@require_admin
def rename_map_key():
    """등록 당시 실수로 다른 문자열로 등록된 맵 키를 실제 접속 주소로 옮긴다
    (예: 상자 등록자의 서버 목록엔 "playf.kr"로 저장돼 있었는데 실제 접속 주소는
    "playfarm.kr"라서, 일반 유저들 입장에선 등록된 상자가 하나도 없는 것처럼 보였던 문제)."""
    data = request.get_json(silent=True) or {}
    from_key = (data.get("from") or "").strip()
    to_key = (data.get("to") or "").strip()
    if not from_key or not to_key or from_key == to_key:
        return jsonify({"error": "from, to가 필요하고 서로 달라야 합니다"}), 400

    result = {}
    for model in _MAP_KEY_MODELS:
        rows = model.query.filter_by(map_key=from_key).all()
        for row in rows:
            row.map_key = to_key
        result[model.__tablename__] = len(rows)
    db.session.commit()
    return jsonify({"status": "ok", "from": from_key, "to": to_key, "moved": result})


@admin_bp.post("/held-items/set")
@require_admin
def set_held_item():
    """특정 유저의 보유 장부(PlayerItemLedger)를 절대값으로 직접 맞춘다 - "재고 수동 수정"의
    보유 쪽 버전. 재고(ChestInventoryItem)는 건드리지 않는다 - 실제로 상자 밖에서 들고 있는
    개수를 알고 있을 때(빠른 테스트로 장부가 꼬였을 때 등) 바로잡는 용도."""
    data = request.get_json(silent=True) or {}
    map_key = (data.get("map_key") or "").strip()
    username = (data.get("username") or "").strip()
    item_id = data.get("item_id")
    try:
        count = int(data.get("count"))
    except (TypeError, ValueError):
        return jsonify({"error": "invalid count"}), 400
    if not map_key or not username or not item_id or count < 0:
        return jsonify({"error": "invalid request"}), 400

    member = Member.query.filter(db.func.lower(Member.minecraft_username) == username.lower()).first()
    if member is None:
        return jsonify({"error": "등록되지 않은 닉네임입니다"}), 404
    username = member.minecraft_username

    row = PlayerItemLedger.query.filter_by(
        map_key=map_key, minecraft_username=username, item_id=item_id,
    ).first()
    if row is None:
        row = PlayerItemLedger(map_key=map_key, minecraft_username=username, item_id=item_id, held_count=count)
        db.session.add(row)
    else:
        row.held_count = count
    db.session.commit()
    return jsonify({"status": "ok", "username": username, "item_id": item_id, "held_count": row.held_count})


@admin_bp.get("/request-logs")
@require_admin
def list_request_logs():
    """실제 성공한 Event가 아니라, 서버에 도착한 모든 요청 시도(성공/실패 무관)를 본다.
    username/endpoint로 좁혀서, 특정 유저의 요청이 서버에 도착조차 안 하는지
    (클라이언트 문제) 도착은 했는데 거부됐는지(서버 로직 문제) 구분할 때 쓴다."""
    limit = min(int(request.args.get("limit", 100)), 500)
    query = RequestLog.query
    username = request.args.get("username")
    if username:
        query = query.filter_by(minecraft_username=username)
    endpoint = request.args.get("endpoint")
    if endpoint:
        query = query.filter_by(endpoint=endpoint)
    logs = query.order_by(RequestLog.id.desc()).limit(limit).all()
    return jsonify([l.to_dict() for l in logs])
