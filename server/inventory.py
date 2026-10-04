from models import ChestInventoryItem, PlayerItemLedger, PublicItemType, UnassignedReturn, db


def _catalog(map_key, item_id):
    return PublicItemType.query.filter_by(map_key=map_key, item_id=item_id).first()


def _stock_row(map_key, chest, item_id):
    return ChestInventoryItem.query.filter_by(
        map_key=map_key, dimension=chest["dimension"], x=chest["x"], y=chest["y"], z=chest["z"], item_id=item_id,
    ).first()


def _ledger_row(map_key, username, item_id):
    return PlayerItemLedger.query.filter_by(
        map_key=map_key, minecraft_username=username, item_id=item_id,
    ).first()


def _ensure_ledger(map_key, username, item_id):
    row = _ledger_row(map_key, username, item_id)
    if row is None:
        row = PlayerItemLedger(map_key=map_key, minecraft_username=username, item_id=item_id, held_count=0)
        db.session.add(row)
    return row


def total_held(map_key, item_id):
    return db.session.query(db.func.coalesce(db.func.sum(PlayerItemLedger.held_count), 0)).filter_by(
        map_key=map_key, item_id=item_id,
    ).scalar()


def _sync_stock(map_key, chest, item_id, display_name, max_count):
    """재고 = 최대값 - 전체 보유 합. 장부에서만 파생되므로 입출고/수정 경로가 여러 개여도 어긋나지 않는다."""
    row = _stock_row(map_key, chest, item_id)
    if row is None:
        row = ChestInventoryItem(
            map_key=map_key, dimension=chest["dimension"], x=chest["x"], y=chest["y"], z=chest["z"],
            item_id=item_id, display_name=display_name, count=0,
        )
        db.session.add(row)
    row.display_name = display_name
    row.count = max(0, max_count - total_held(map_key, item_id))
    return row.count


def take(map_key, chest, username, item_id, count):
    """상자에서 꺼냄. 재고를 넘게 꺼낼 수 없다. 반환: (실제 반영 개수, 이상 사유 또는 None)."""
    catalog = _catalog(map_key, item_id)
    if catalog is None or catalog.max_count is None:
        return 0, "공용템 최대값이 없음 (캡처 필요)"
    available = max(0, catalog.max_count - total_held(map_key, item_id))
    applied = min(count, available)
    if applied <= 0:
        return 0, f"재고 없음 (요청 {count}, 재고 0)"
    _ensure_ledger(map_key, username, item_id).held_count += applied
    _sync_stock(map_key, chest, item_id, catalog.display_name, catalog.max_count)
    anomaly = None if applied == count else f"재고 부족 (요청 {count}, 실제 {applied})"
    return applied, anomaly


def deposit(map_key, chest, username, item_id, count):
    """상자에 넣음. 본인이 들고 있다고 장부에 있는 만큼만 반영한다. 반환: (실제 반영 개수, 이상 사유 또는 None)."""
    catalog = _catalog(map_key, item_id)
    if catalog is None or catalog.max_count is None:
        return 0, "공용템 최대값이 없음 (캡처 필요)"
    ledger = _ensure_ledger(map_key, username, item_id)
    applied = min(count, ledger.held_count)
    remainder = count - applied
    if applied:
        ledger.held_count -= applied
        _sync_stock(map_key, chest, item_id, catalog.display_name, catalog.max_count)
    if remainder:
        db.session.add(UnassignedReturn(
            map_key=map_key, item_id=item_id, display_name=catalog.display_name,
            count=remainder, depositor=username,
        ))
    anomaly = None if remainder == 0 else f"본인 보유 아님 → 미귀속 반납 {remainder}개 (개발자 지정 필요)"
    return applied, anomaly


def assign_return(map_key, chest, ret, holder):
    """미귀속 반납을 특정 보유자의 몫으로 확정한다 - 그 보유자의 장부에서 빠지고 재고가 다시 계산된다."""
    catalog = _catalog(map_key, ret.item_id)
    if catalog is None or catalog.max_count is None:
        return False, "공용템 목록에 없는 아이템입니다"
    ledger = _ledger_row(map_key, holder, ret.item_id)
    held = ledger.held_count if ledger else 0
    if held < ret.count:
        return False, f"{holder}님 보유 {held}개 < 반납 {ret.count}개 (보유 수정 후 다시 지정하세요)"
    ledger.held_count -= ret.count
    _sync_stock(map_key, chest, ret.item_id, catalog.display_name, catalog.max_count)
    return True, None


def capture(map_key, chest, items):
    """개발자 캡처: 지금 상자 내용물을 기준으로 최대값을 고정하고, 모든 보유 장부를 0으로 되돌린다.
    items: [(item_id, display_name, count), ...]"""
    PublicItemType.query.filter_by(map_key=map_key).delete()
    ChestInventoryItem.query.filter_by(
        map_key=map_key, dimension=chest["dimension"], x=chest["x"], y=chest["y"], z=chest["z"],
    ).delete()
    PlayerItemLedger.query.filter_by(map_key=map_key).delete()
    for item_id, display_name, count in items:
        db.session.add(PublicItemType(
            map_key=map_key, item_id=item_id, display_name=display_name, max_count=count,
        ))
        db.session.add(ChestInventoryItem(
            map_key=map_key, dimension=chest["dimension"], x=chest["x"], y=chest["y"], z=chest["z"],
            item_id=item_id, display_name=display_name, count=count,
        ))


def set_held(map_key, chest, username, item_id, count):
    """관리자 수동 보유 수정 - 장부만 맞추고 재고는 최대값에서 다시 계산한다."""
    catalog = _catalog(map_key, item_id)
    if catalog is None or catalog.max_count is None:
        return None
    _ensure_ledger(map_key, username, item_id).held_count = count
    return _sync_stock(map_key, chest, item_id, catalog.display_name, catalog.max_count)


def set_stock(map_key, chest, item_id, count):
    """관리자 수동 재고 수정 - 재고를 바꾸려면 최대값을 바꿔야 장부와 어긋나지 않는다 (최대값 = 재고 + 전체 보유)."""
    catalog = _catalog(map_key, item_id)
    if catalog is None:
        return None
    catalog.max_count = count + total_held(map_key, item_id)
    return _sync_stock(map_key, chest, item_id, catalog.display_name, catalog.max_count)
