from datetime import datetime, timezone

from flask_sqlalchemy import SQLAlchemy

db = SQLAlchemy()


def _utcnow():
    return datetime.now(timezone.utc)


class Member(db.Model):
    __tablename__ = "members"

    id = db.Column(db.Integer, primary_key=True)
    minecraft_username = db.Column(db.String(32), unique=True, nullable=False, index=True)
    minecraft_uuid = db.Column(db.String(36), nullable=True)
    added_at = db.Column(db.DateTime(timezone=True), default=_utcnow, nullable=False)
    added_by = db.Column(db.String(64), nullable=True)
    # 웹 대시보드를 디스코드 로그인으로 여는 사람과 이 멤버를 연결해둔다 (아직 아무도 로그인 안 했으면 None).
    discord_id = db.Column(db.String(32), nullable=True, index=True)
    # 로그인할 때마다 최신값으로 갱신 - 멤버 관리 목록에서 마크 닉네임과 같이 보여주기 위함.
    discord_username = db.Column(db.String(64), nullable=True)
    # 개발자 탭(기능 토글/상세 로그/멤버·재고 관리)에 접근 가능한지 여부. sence1012(최고관리자)는
    # 이 값과 무관하게 항상 개발자로 취급되고, 이 값을 바꿀 수 있는 것도 sence1012뿐이다.
    is_developer = db.Column(db.Boolean, nullable=False, default=False)
    # 모드가 이 멤버 이름으로 서버에 한 번이라도 연동된 적이 있는지 - 최초/최종 시각 (없으면 None = 미연동).
    mod_first_linked_at = db.Column(db.DateTime(timezone=True), nullable=True)
    mod_last_linked_at = db.Column(db.DateTime(timezone=True), nullable=True)

    def to_dict(self):
        return {
            "id": self.id,
            "minecraft_username": self.minecraft_username,
            "minecraft_uuid": self.minecraft_uuid,
            "added_at": self.added_at.isoformat(),
            "added_by": self.added_by,
            "discord_id": self.discord_id,
            "discord_username": self.discord_username,
            "is_developer": self.is_developer,
            "mod_first_linked_at": self.mod_first_linked_at.isoformat() if self.mod_first_linked_at else None,
            "mod_last_linked_at": self.mod_last_linked_at.isoformat() if self.mod_last_linked_at else None,
        }


class MemberApplication(db.Model):
    """아직 정식 멤버로 등록되지 않은 디스코드 계정이 마크 닉네임을 입력해서 올리는 가입 신청.
    개발자가 승인하면 Member로 승격되고, 이 행은 지워진다. discord_id당 신청은 하나만 유지하고
    (다시 제출하면 그냥 갱신) 승인 대기 상태를 보여줄 때도 이 값을 기준으로 판단한다."""
    __tablename__ = "member_applications"

    id = db.Column(db.Integer, primary_key=True)
    minecraft_username = db.Column(db.String(32), nullable=False)
    discord_id = db.Column(db.String(32), unique=True, nullable=False, index=True)
    discord_username = db.Column(db.String(64), nullable=True)
    discord_avatar = db.Column(db.String(256), nullable=True)
    requested_at = db.Column(db.DateTime(timezone=True), default=_utcnow, nullable=False)

    def to_dict(self):
        return {
            "id": self.id,
            "minecraft_username": self.minecraft_username,
            "discord_id": self.discord_id,
            "discord_username": self.discord_username,
            "discord_avatar": self.discord_avatar,
            "requested_at": self.requested_at.isoformat(),
        }


class Event(db.Model):
    __tablename__ = "events"

    id = db.Column(db.Integer, primary_key=True)
    minecraft_username = db.Column(db.String(32), nullable=False, index=True)
    item_id = db.Column(db.String(128), nullable=False)
    item_name = db.Column(db.String(128), nullable=False)
    action = db.Column(db.String(8), nullable=False)  # TAKE | DEPOSIT | HOLD | RELEASE
    count = db.Column(db.Integer, nullable=False)
    occurred_at = db.Column(db.DateTime(timezone=True), nullable=False)
    received_at = db.Column(db.DateTime(timezone=True), default=_utcnow, nullable=False)

    # 어느 상자에서 일어난 일인지 (등록된 공용템 상자든, 경유한 다른 상자든)
    map_key = db.Column(db.String(128), nullable=True)
    dimension = db.Column(db.String(64), nullable=True)
    pos_x = db.Column(db.Integer, nullable=True)
    pos_y = db.Column(db.Integer, nullable=True)
    pos_z = db.Column(db.Integer, nullable=True)
    chest_label = db.Column(db.String(64), nullable=True)  # "공용템 상자" 또는 None(경유 상자)

    def to_dict(self):
        return {
            "id": self.id,
            "minecraft_username": self.minecraft_username,
            "item_id": self.item_id,
            "item_name": self.item_name,
            "action": self.action,
            "count": self.count,
            "occurred_at": self.occurred_at.isoformat(),
            "received_at": self.received_at.isoformat(),
            "map_key": self.map_key,
            "dimension": self.dimension,
            "pos_x": self.pos_x,
            "pos_y": self.pos_y,
            "pos_z": self.pos_z,
            "chest_label": self.chest_label,
        }


class TrackedChest(db.Model):
    __tablename__ = "tracked_chests"
    __table_args__ = (db.UniqueConstraint("map_key", "dimension", "x", "y", "z"),)

    id = db.Column(db.Integer, primary_key=True)
    map_key = db.Column(db.String(128), nullable=False, index=True)  # 접속 서버 주소 = 맵/채널 식별자
    dimension = db.Column(db.String(64), nullable=False)
    x = db.Column(db.Integer, nullable=False)
    y = db.Column(db.Integer, nullable=False)
    z = db.Column(db.Integer, nullable=False)
    label = db.Column(db.String(64), nullable=False, default="공용템 상자")
    registered_by = db.Column(db.String(32), nullable=True)
    created_at = db.Column(db.DateTime(timezone=True), default=_utcnow, nullable=False)
    # 켜져 있으면 배포용 빌드 사용자는 공용템 타입이 아닌 개인 아이템을 이 상자에 넣을 수 없다.
    strict_mode = db.Column(db.Boolean, nullable=False, default=False)

    def to_dict(self):
        return {
            "dimension": self.dimension,
            "x": self.x,
            "y": self.y,
            "z": self.z,
            "label": self.label,
            "strict_mode": self.strict_mode,
        }


class PublicItemType(db.Model):
    __tablename__ = "public_item_types"
    __table_args__ = (db.UniqueConstraint("map_key", "item_id"),)

    id = db.Column(db.Integer, primary_key=True)
    map_key = db.Column(db.String(128), nullable=False, index=True)
    item_id = db.Column(db.String(128), nullable=False)  # 예: minecraft:diamond_pickaxe
    display_name = db.Column(db.String(128), nullable=False)
    # 개발자 캡처 때 고정되는 이 공용템의 총 개수. 재고 = max_count - 전체 보유 합으로 항상 재계산된다.
    max_count = db.Column(db.Integer, nullable=True)
    added_at = db.Column(db.DateTime(timezone=True), default=_utcnow, nullable=False)

    def to_dict(self):
        return {"item_id": self.item_id, "display_name": self.display_name, "max_count": self.max_count}


class ChestInventoryItem(db.Model):
    """특정 상자(좌표)의 아이템별 재고. PublicItemType.max_count에서 전체 보유 합을 뺀 값으로만 갱신된다
    (inventory.py 참고 - 직접 증감시키지 않는다)."""
    __tablename__ = "chest_inventory"
    __table_args__ = (db.UniqueConstraint("map_key", "dimension", "x", "y", "z", "item_id"),)

    id = db.Column(db.Integer, primary_key=True)
    map_key = db.Column(db.String(128), nullable=False, index=True)
    dimension = db.Column(db.String(64), nullable=False)
    x = db.Column(db.Integer, nullable=False)
    y = db.Column(db.Integer, nullable=False)
    z = db.Column(db.Integer, nullable=False)
    item_id = db.Column(db.String(128), nullable=False)
    display_name = db.Column(db.String(128), nullable=False)
    count = db.Column(db.Integer, nullable=False, default=0)
    updated_at = db.Column(db.DateTime(timezone=True), default=_utcnow, onupdate=_utcnow, nullable=False)

    def to_dict(self):
        return {
            "item_id": self.item_id,
            "display_name": self.display_name,
            "count": self.count,
            "updated_at": self.updated_at.isoformat(),
        }


class PlayerItemLedger(db.Model):
    """플레이어별 '지금 공용템을 몇 개 들고 있는지' 장부. 실제 아이템에는 아무 표시도 남기지 않고
    (그럴 방법이 없음 - 서버가 아이템 데이터의 최종 권한을 가짐), 등록 상자에서의 TAKE/DEPOSIT만으로
    증감시킨다. 개인템과 이름이 같아도 이 카운트가 0이면 공용템으로 표시하지 않는다."""
    __tablename__ = "player_item_ledger"
    __table_args__ = (db.UniqueConstraint("map_key", "minecraft_username", "item_id"),)

    id = db.Column(db.Integer, primary_key=True)
    map_key = db.Column(db.String(128), nullable=False, index=True)
    minecraft_username = db.Column(db.String(32), nullable=False, index=True)
    item_id = db.Column(db.String(128), nullable=False)
    held_count = db.Column(db.Integer, nullable=False, default=0)
    updated_at = db.Column(db.DateTime(timezone=True), default=_utcnow, onupdate=_utcnow, nullable=False)

    def to_dict(self):
        return {"item_id": self.item_id, "held_count": self.held_count}


class FeatureToggle(db.Model):
    """맵별로 클라이언트 기능 on/off 상태를 서버가 갖고 있어서, 한 사람(개발자)이 /dev_toggle로
    바꾸면 같은 맵에 접속한 모든 유저(배포용/개발용 빌드 모두)에게 곧 반영된다."""
    __tablename__ = "feature_toggles"

    map_key = db.Column(db.String(128), primary_key=True)
    label_overlay = db.Column(db.Boolean, nullable=False, default=True)
    public_tag = db.Column(db.Boolean, nullable=False, default=True)
    passthrough_tracking = db.Column(db.Boolean, nullable=False, default=True)
    chest_log = db.Column(db.Boolean, nullable=False, default=True)

    def to_dict(self):
        return {
            "label_overlay": self.label_overlay,
            "public_tag": self.public_tag,
            "passthrough_tracking": self.passthrough_tracking,
            "chest_log": self.chest_log,
        }


class RequestLog(db.Model):
    """모든 API 요청 시도(성공/실패 무관)의 상세 기록 - 예전엔 디스코드 개발자 로그 채널에만
    남기고 저장은 안 했는데, 웹 대시보드에서 "입출고 로그 클릭 -> 상세 통신 로그" 기능을 만들려면
    저장된 데이터가 있어야 해서 새로 추가했다."""
    __tablename__ = "request_logs"

    id = db.Column(db.Integer, primary_key=True)
    endpoint = db.Column(db.String(64), nullable=False)
    minecraft_username = db.Column(db.String(32), nullable=True)
    ip = db.Column(db.String(64), nullable=True)
    result = db.Column(db.Text, nullable=False)
    payload = db.Column(db.Text, nullable=True)  # JSON 문자열
    # 이 요청이 실제로 Event를 하나 만들어냈으면(log-event 성공), 그 Event와 연결해서
    # "이 입출고 로그를 만든 통신이 정확히 뭐였는지" 바로 찾아볼 수 있게 한다.
    event_id = db.Column(db.Integer, db.ForeignKey("events.id"), nullable=True, index=True)
    created_at = db.Column(db.DateTime(timezone=True), default=_utcnow, nullable=False)

    def to_dict(self):
        return {
            "id": self.id,
            "endpoint": self.endpoint,
            "minecraft_username": self.minecraft_username,
            "ip": self.ip,
            "result": self.result,
            "payload": self.payload,
            "event_id": self.event_id,
            "created_at": self.created_at.isoformat(),
        }


class DashboardMessage(db.Model):
    """맵별로 재고/보유 현황 디스코드 메시지 1개를 계속 수정(edit)하기 위해 메시지 ID를 기억해둔다."""
    __tablename__ = "dashboard_messages"

    map_key = db.Column(db.String(128), primary_key=True)
    channel_id = db.Column(db.String(32), nullable=False)
    message_id = db.Column(db.String(32), nullable=True)
