from datetime import datetime, timezone
from functools import wraps

from flask import Blueprint, current_app, jsonify, request
from sqlalchemy import func, or_

import inventory
from routes_public import _normalize_map_key

from models import (
    AlbaHire, AlbaPersonNote, AlbaWageRate, ChestInventoryItem, DashboardMessage, Event, FeatureToggle, Member,
    PlayerItemLedger, PublicItemType, RequestLog, TrackedChest, UnassignedReturn, db,
)

# map_key 컬럼을 갖는 모든 테이블 - 맵 키 이름을 바꿀 때(/admin/rename-map-key) 전부 같이 옮긴다.
# AlbaHire/AlbaWageRate/AlbaPersonNote/UnassignedReturn은 나중에 추가된 기능이라 처음엔 이 목록에
# 빠져 있었다 - 맵 키가 갈라지는 문제(클라이언트마다 서버 목록 주소 문자열이 달라서)가 알바
# 기능에서도 똑같이 날 수 있는데 그때는 이 도구로 못 옮겼음.
_MAP_KEY_MODELS = [
    TrackedChest, PublicItemType, ChestInventoryItem, PlayerItemLedger, FeatureToggle, Event, DashboardMessage,
    AlbaHire, AlbaWageRate, AlbaPersonNote, UnassignedReturn,
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


@admin_bp.get("/alba/people-names")
@require_admin
def admin_alba_people_names():
    """AlbaHire에 등장하는 employer/employee 닉네임 전부(대소문자 그대로) + 건수 - 닉네임 변경으로
    같은 사람이 두 이름으로 쪼개졌는지 찾을 때 쓴다. search 쿼리파라미터로 부분 일치 필터 가능."""
    map_key = _normalize_map_key(request.args.get("map", ""))
    search = (request.args.get("search") or "").lower()
    counts = {}
    for row in AlbaHire.query.filter_by(map_key=map_key).all():
        for name, role in ((row.employer, "employer"), (row.employee, "employee")):
            if search and search not in name.lower():
                continue
            entry = counts.setdefault(name, {"employer": 0, "employee": 0})
            entry[role] += 1
    return jsonify(counts)


@admin_bp.post("/alba/rename-player")
@require_admin
def admin_rename_alba_player():
    """닉네임이 바뀐 사람의 예전 기록을 새 닉네임으로 합친다 - AlbaHire의 employer/employee 둘 다,
    AlbaPersonNote도 같이 옮긴다."""
    data = request.get_json(silent=True) or {}
    map_key = _normalize_map_key(data.get("map") or request.args.get("map", ""))
    old_name = (data.get("from") or "").strip()
    new_name = (data.get("to") or "").strip()
    if not old_name or not new_name or old_name.lower() == new_name.lower():
        return jsonify({"error": "from, to가 필요하고 서로 달라야 합니다"}), 400

    moved = {"alba_hires_employer": 0, "alba_hires_employee": 0, "alba_person_notes": 0}
    for row in AlbaHire.query.filter(AlbaHire.map_key == map_key, func.lower(AlbaHire.employer) == old_name.lower()).all():
        row.employer = new_name
        moved["alba_hires_employer"] += 1
    for row in AlbaHire.query.filter(AlbaHire.map_key == map_key, func.lower(AlbaHire.employee) == old_name.lower()).all():
        row.employee = new_name
        moved["alba_hires_employee"] += 1
    for row in AlbaPersonNote.query.filter(
        AlbaPersonNote.map_key == map_key, func.lower(AlbaPersonNote.player_name) == old_name.lower(),
    ).all():
        row.player_name = new_name
        moved["alba_person_notes"] += 1
    db.session.commit()
    return jsonify({"status": "ok", "from": old_name, "to": new_name, "moved": moved})


@admin_bp.post("/alba/hires/<int:hire_id>/fire")
@require_admin
def admin_force_fire_alba(hire_id):
    """인게임 해고 감지가 안 돼서(목격한 클라이언트가 없었거나 등) 서버에 계속 활성으로 남아있는
    알바 기록을 개발자가 직접 끝낸다 - 대시보드에도 같은 기능(로그인 세션 기반)이 있지만, 이건
    관리자 토큰으로 직접 정리할 때 쓴다."""
    hire = AlbaHire.query.get(hire_id)
    if hire is None:
        return jsonify({"error": "not found"}), 404
    if hire.fired_at is None:
        hire.fired_at = datetime.now(timezone.utc)
        db.session.commit()
    return jsonify(hire.to_dict())


@admin_bp.post("/alba/fix-wage-units")
@require_admin
def fix_alba_wage_units():
    """단가를 "만원" 단위 숫자(37, 38처럼)로 잘못 저장해뒀던 걸 실제 원 단위로 올린다(×10000).
    이 맵 알바 단가는 실제로 수십만 원대라서, 1000 미만으로 저장된 값은 전부 "만원" 단위로
    잘못 적힌 것으로 보고 일괄 교정한다 - 기본값(DEFAULT_WAGE_RATES)을 바꾼 뒤에도 이미 저장된
    AlbaWageRate 행이나 이미 기록된 AlbaHire.unit_price는 그대로 작은 값으로 남아있었다."""
    threshold = 1000
    moved = {"alba_hires": 0, "alba_wage_rates": 0}
    for row in AlbaHire.query.filter(AlbaHire.unit_price.isnot(None), AlbaHire.unit_price < threshold).all():
        row.unit_price = row.unit_price * 10000
        moved["alba_hires"] += 1
    for row in AlbaWageRate.query.filter(AlbaWageRate.unit_price < threshold).all():
        row.unit_price = row.unit_price * 10000
        moved["alba_wage_rates"] += 1
    db.session.commit()
    return jsonify({"status": "ok", "moved": moved})


@admin_bp.get("/map-keys")
@require_admin
def list_map_keys():
    """테이블별로 지금 실제 쓰이고 있는 map_key 값과 개수를 보여준다 - "어떤 사람은 보이고 어떤
    사람은 안 보인다"는 신고가 들어오면(클라이언트마다 서버 목록 주소 문자열이 달라서 데이터가
    갈라지는 문제, 이전에 "playf.kr"/"playfarm.kr"로 실제 발생한 적 있음) 여기서 갈라진 값이
    있는지 바로 확인하고 /admin/rename-map-key로 합친다."""
    result = {}
    for model in _MAP_KEY_MODELS:
        rows = db.session.query(model.map_key, func.count()).group_by(model.map_key).all()
        result[model.__tablename__] = {key: count for key, count in rows}
    return jsonify(result)


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
