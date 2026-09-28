from functools import wraps

from flask import Blueprint, current_app, jsonify, request

from models import Event, Member, RequestLog, db

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
