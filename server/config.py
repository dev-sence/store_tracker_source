import os

from dotenv import load_dotenv

load_dotenv()


def _require(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise RuntimeError(f"{name} 환경변수가 설정되지 않았습니다. .env.example을 참고해 .env를 만드세요.")
    return value


BASE_DIR = os.path.dirname(os.path.abspath(__file__))
# Flask의 instance_path 자동 추론은 실행 방식(직접 실행/디버거/cwd)에 따라 달라져서
# 같은 프로젝트인데 DB가 엉뚱한 곳에 생기는 문제가 있었다. 그래서 server/ 기준 절대경로로 고정한다.
DEFAULT_DB_PATH = os.path.join(BASE_DIR, "storetracker.db").replace("\\", "/")


class Config:
    APP_SECRET = _require("APP_SECRET")
    ADMIN_TOKEN = _require("ADMIN_TOKEN")
    DISCORD_BOT_TOKEN = os.environ.get("DISCORD_BOT_TOKEN", "")
    DISCORD_CHANNEL_ID = os.environ.get("DISCORD_CHANNEL_ID", "")
    DISCORD_DEV_LOG_CHANNEL_ID = os.environ.get("DISCORD_DEV_LOG_CHANNEL_ID", "")
    DISCORD_INVENTORY_CHANNEL_ID = os.environ.get("DISCORD_INVENTORY_CHANNEL_ID", "")
    # 디스코드 로깅을 끄고 싶을 때(웹 대시보드로 대체 등) 봇 토큰은 그대로 두고 이 값만 0으로 바꾸면 된다.
    DISCORD_POSTING_ENABLED = os.environ.get("DISCORD_POSTING_ENABLED", "1") == "1"
    _database_url = os.environ.get("DATABASE_URL", f"sqlite:///{DEFAULT_DB_PATH}")
    # Neon/Render가 주는 접속 문자열은 옛 표기인 "postgres://"로 시작하는 경우가 있는데,
    # SQLAlchemy(2.x)는 이 스킴을 인식하지 못해 "postgresql://"로 바꿔줘야 한다.
    if _database_url.startswith("postgres://"):
        _database_url = "postgresql://" + _database_url[len("postgres://"):]
    # 드라이버를 명시하지 않으면 설치된 SQLAlchemy 버전에 따라 기본 드라이버가 달라질 수 있어서
    # (2.1부터 psycopg(v3)을 우선 시도) requirements.txt에 넣어둔 psycopg2로 명시 고정한다.
    if _database_url.startswith("postgresql://"):
        _database_url = "postgresql+psycopg2://" + _database_url[len("postgresql://"):]
    SQLALCHEMY_DATABASE_URI = _database_url
    SQLALCHEMY_TRACK_MODIFICATIONS = False
