#!/usr/bin/env bash
# 지원하는 모든 1.21.x 마크 버전용 배포 jar를 한 번에 빌드한다.
# 사용법: APP_SECRET=... ./scripts/build-all-versions.sh
# (PUBLIC_API_BASE_URL / GITHUB_REPO / GRADLE는 필요하면 환경변수로 덮어쓸 수 있음)
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE="${GRADLE:-gradle}"
APP_SECRET="${APP_SECRET:?APP_SECRET 환경변수가 필요합니다 (Render의 APP_SECRET과 동일한 값)}"
PUBLIC_API_BASE_URL="${PUBLIC_API_BASE_URL:-https://store-tracker-source.onrender.com}"
GITHUB_REPO="${GITHUB_REPO:-dev-sence/store_tracker_publish}"

DIST_DIR="dist"
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

# mcVersion|yarnMappings|fabricApiVersion
# meta.fabricmc.net / maven.fabricmc.net에서 확인한 최신 값 (새 버전 나오면 여기 한 줄 추가).
VERSIONS=(
  "1.21|1.21+build.9|0.102.0+1.21"
  "1.21.1|1.21.1+build.3|0.116.17+1.21.1"
  "1.21.2|1.21.2+build.1|0.106.1+1.21.2"
  "1.21.3|1.21.3+build.2|0.114.1+1.21.3"
  "1.21.4|1.21.4+build.8|0.119.4+1.21.4"
  "1.21.5|1.21.5+build.1|0.128.2+1.21.5"
  "1.21.6|1.21.6+build.1|0.128.2+1.21.6"
  "1.21.7|1.21.7+build.8|0.129.0+1.21.7"
  "1.21.8|1.21.8+build.1|0.136.1+1.21.8"
  "1.21.9|1.21.9+build.1|0.134.1+1.21.9"
  "1.21.10|1.21.10+build.3|0.138.4+1.21.10"
  "1.21.11|1.21.11+build.6|0.141.6+1.21.11"
)

MOD_VERSION=$(grep '^mod_version=' gradle.properties | cut -d= -f2)
echo "mod version: $MOD_VERSION"

for entry in "${VERSIONS[@]}"; do
  IFS='|' read -r MC_VERSION YARN FABRIC_API <<< "$entry"
  echo ""
  echo "=== Building for Minecraft $MC_VERSION (yarn=$YARN, fabric-api=$FABRIC_API) ==="
  "$GRADLE" build \
    -PbuildType=public \
    -PpublicApiBaseUrl="$PUBLIC_API_BASE_URL" \
    -PappSecret="$APP_SECRET" \
    -PgithubRepo="$GITHUB_REPO" \
    -PgameVersion="$MC_VERSION" \
    -PyarnMappings="$YARN" \
    -PfabricApiVersion="$FABRIC_API" \
    --console=plain

  cp "build/libs/Sence_StoreTracker-${MOD_VERSION}.jar" "$DIST_DIR/Sence_StoreTracker-${MC_VERSION}.jar"
  echo "-> $DIST_DIR/Sence_StoreTracker-${MC_VERSION}.jar"
done

echo ""
echo "전부 완료. 결과물은 $DIST_DIR/ 안에 있습니다:"
ls -la "$DIST_DIR"
