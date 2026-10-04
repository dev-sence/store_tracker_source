from functools import wraps

from flask import Blueprint, current_app, jsonify, request
from sqlalchemy import func, or_

import inventory

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
    offset = int(request.args.get("offset", 0))
    events = Event.query.order_by(Event.received_at.desc()).offset(offset).limit(limit).all()
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
    """특정 유저의 보유 장부를 절대값으로 직접 맞춘다. 재고는 최대값에서 다시 계산되므로 항상 같이 맞는다."""
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

    chest = TrackedChest.query.filter_by(map_key=map_key).first()
    if chest is None:
        return jsonify({"error": "등록된 상자가 없습니다"}), 404
    chest_pos = {"dimension": chest.dimension, "x": chest.x, "y": chest.y, "z": chest.z}
    if inventory.set_held(map_key, chest_pos, username, item_id, count) is None:
        return jsonify({"error": "공용템 목록에 없는 아이템입니다 (먼저 캡처하세요)"}), 400
    row = PlayerItemLedger.query.filter_by(map_key=map_key, minecraft_username=username, item_id=item_id).first()
    db.session.commit()
    return jsonify({"status": "ok", "username": username, "item_id": item_id, "held_count": row.held_count})


@admin_bp.post("/inventory/set")
@require_admin
def set_inventory():
    """재고를 절대값으로 맞춘다 - 최대값을 (재고 + 전체 보유 합)으로 조정해서 장부와 어긋나지 않게 한다."""
    data = request.get_json(silent=True) or {}
    map_key = (data.get("map_key") or "").strip()
    item_id = data.get("item_id")
    try:
        count = int(data.get("count"))
    except (TypeError, ValueError):
        return jsonify({"error": "invalid count"}), 400
    if not map_key or not item_id or count < 0:
        return jsonify({"error": "invalid request"}), 400

    chest = TrackedChest.query.filter_by(map_key=map_key).first()
    if chest is None:
        return jsonify({"error": "등록된 상자가 없습니다"}), 404

    chest_pos = {"dimension": chest.dimension, "x": chest.x, "y": chest.y, "z": chest.z}
    stock = inventory.set_stock(map_key, chest_pos, item_id, count)
    if stock is None:
        return jsonify({"error": "공용템 목록에 없는 아이템입니다 (먼저 캡처하세요)"}), 400
    db.session.commit()
    return jsonify({"status": "ok", "item_id": item_id, "count": stock})


@admin_bp.post("/init-max-counts")
@require_admin
def init_max_counts():
    """최대값 도입 전 공용템에 최대값을 한 번 채운다: 최대값 = 현재 재고 + 전체 보유 합.
    이미 최대값이 있는 아이템은 건드리지 않는다. 이후 개발자 캡처로 정식 기준선을 다시 잡으면 된다."""
    updated = []
    for catalog in PublicItemType.query.filter(PublicItemType.max_count.is_(None)).all():
        chest = TrackedChest.query.filter_by(map_key=catalog.map_key).first()
        if chest is None:
            continue
        stock_row = ChestInventoryItem.query.filter_by(
            map_key=catalog.map_key, dimension=chest.dimension, x=chest.x, y=chest.y, z=chest.z, item_id=catalog.item_id,
        ).first()
        current_stock = stock_row.count if stock_row else 0
        held = inventory.total_held(catalog.map_key, catalog.item_id)
        catalog.max_count = current_stock + held
        updated.append({"item_id": catalog.item_id, "max_count": catalog.max_count})
    db.session.commit()
    return jsonify({"status": "ok", "updated": updated})


@admin_bp.post("/backfill-mod-link")
@require_admin
def backfill_mod_link():
    """RequestLog에 남아 있는 성공 연동 기록(인증됨/기록됨/상자 열림·닫힘)으로 멤버별 최초/최종
    연동 시각을 채운다. 이미 찍힌 값보다 더 과거/최근인 경우에만 갱신하므로 여러 번 돌려도 안전하다."""
    success = or_(
        (RequestLog.endpoint == "check-member") & RequestLog.result.like("인증됨%"),
        (RequestLog.endpoint == "log-event") & RequestLog.result.like("기록됨%"),
        (RequestLog.endpoint == "chest-log") & RequestLog.result.like("상자%"),
    )
    spans = db.session.query(
        RequestLog.minecraft_username,
        func.min(RequestLog.created_at),
        func.max(RequestLog.created_at),
    ).filter(success).group_by(RequestLog.minecraft_username).all()
    spans_by_user = {name: (first, last) for name, first, last in spans if name}

    updated = 0
    for member in Member.query.all():
        span = spans_by_user.get(member.minecraft_username)
        if span is None:
            continue
        first, last = span
        if member.mod_first_linked_at is None or first < member.mod_first_linked_at:
            member.mod_first_linked_at = first
        if member.mod_last_linked_at is None or last > member.mod_last_linked_at:
            member.mod_last_linked_at = last
        updated += 1
    db.session.commit()
    return jsonify({"status": "ok", "members_with_history": updated})


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
