from functools import wraps

from flask import Blueprint, current_app, jsonify, request

from models import Event, Member, db

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
