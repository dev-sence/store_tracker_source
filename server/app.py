import logging
import os

from flask import Flask, jsonify

from config import Config
from crypto import SecureChannel
from models import db
from routes_admin import admin_bp
from routes_public import public_bp

logger = logging.getLogger(__name__)

# 실수로 프로덕션에 디버그 모드가 켜진 채로 배포되는 걸 막는다 - 디버그 모드는 에러 발생 시
# 원격 코드 실행이 가능한 Werkzeug 인터랙티브 디버거를 노출하고 스택 트레이스를 유출한다.
# 로컬 개발 중에만 명시적으로 FLASK_DEBUG=1을 지정해서 켠다.
DEBUG = os.environ.get("FLASK_DEBUG", "0") == "1"


def create_app():
    logging.basicConfig(level=logging.INFO)

    app = Flask(__name__)
    app.config.from_object(Config)

    app.secure_channel = SecureChannel(app.config["APP_SECRET"])

    db.init_app(app)
    with app.app_context():
        db.create_all()

    app.register_blueprint(public_bp)
    app.register_blueprint(admin_bp)

    @app.get("/healthz")
    def healthz():
        return {"status": "ok"}

    @app.errorhandler(Exception)
    def handle_unexpected_error(error):
        # 손상되거나 예상 못한 요청으로 아무 라우트에서나 예외가 터져도, 스택 트레이스를 그대로
        # 응답으로 흘려보내지 않는다 (내부 구조 노출 방지). 자세한 내용은 서버 로그에만 남긴다.
        db.session.rollback()
        logger.exception("처리되지 않은 예외")
        return jsonify({"error": "internal server error"}), 500

    return app


app = create_app()

if __name__ == "__main__":
    if DEBUG:
        logger.warning("디버그 모드로 실행 중입니다 - 절대 실제 배포 환경에서 이 상태로 두지 마세요.")
    app.run(debug=DEBUG)
