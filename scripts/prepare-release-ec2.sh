#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# 사용법: bash prepare-release-ec2.sh <SHA> <다운로드한 deploy.tar.gz 경로>
# 전제: 호출자는 배포 잠금을 획득한 상태여야 한다.
DEPLOY_SHA="${1:-}"
ARCHIVE="${2:-}"
[[ "$DEPLOY_SHA" =~ ^[0-9a-f]{40}$ ]] || { echo 'ERROR: Invalid commit SHA' >&2; exit 1; }
[[ -f "$ARCHIVE" ]] || { echo 'ERROR: Artifact does not exist' >&2; exit 1; }
BASE_DIR=/opt/myshelf
RELEASES="$BASE_DIR/releases"
TARGET="$RELEASES/$DEPLOY_SHA"
[[ -f "$BASE_DIR/.env" ]] || { echo 'ERROR: Production .env missing' >&2; exit 1; }
mkdir -p -- "$RELEASES"
STAGING="$(mktemp -d "$RELEASES/.staging.XXXXXXXX")"
trap 'rm -rf -- "$STAGING"' EXIT

# 압축 파일에서 허용된 일반 파일 다섯 개만 안전하게 추출한다.
python3 - "$ARCHIVE" "$STAGING" <<'PY'
from pathlib import Path
import shutil
import sys
import tarfile

archive, dest = sys.argv[1:]
expected = {
    'compose.yaml', 'compose.prod.yaml', 'deploy-ec2.sh',
    'prepare-release-ec2.sh', 'activate-release-ec2.sh',
}
with tarfile.open(archive, 'r:gz') as tar:
    members = tar.getmembers()
    names = [member.name for member in members]
    if len(members) != len(expected) or set(names) != expected:
        raise SystemExit(f'ERROR: Invalid artifact members: {names}')
    for member in members:
        if not member.isfile() or member.size > 2 * 1024 * 1024:
            raise SystemExit(f'ERROR: Unsafe artifact member: {member.name}')
    for member in members:
        with tar.extractfile(member) as src, (Path(dest) / member.name).open('wb') as dst:
            shutil.copyfileobj(src, dst)
        (Path(dest) / member.name).chmod(0o600)
PY

# 아직 운영 파일이나 컨테이너는 변경하지 않는다.
sudo -n docker compose -p myshelf \
  --project-directory "$BASE_DIR" --env-file "$BASE_DIR/.env" \
  -f "$STAGING/compose.yaml" -f "$STAGING/compose.prod.yaml" \
  config --quiet

if [[ -e "$TARGET" || -L "$TARGET" ]]; then
  [[ -d "$TARGET" && ! -L "$TARGET" ]] || { echo 'ERROR: Invalid existing release path' >&2; exit 1; }
  for f in compose.yaml compose.prod.yaml deploy-ec2.sh prepare-release-ec2.sh activate-release-ec2.sh; do
    [[ -f "$TARGET/$f" && ! -L "$TARGET/$f" ]] || { echo "ERROR: Existing release incomplete: $f" >&2; exit 1; }
    cmp -s -- "$STAGING/$f" "$TARGET/$f" || { echo "ERROR: Existing release differs: $f" >&2; exit 1; }
  done
  echo "Identical release reused: $DEPLOY_SHA"
else
  mv -T -- "$STAGING" "$TARGET"
  echo "Release prepared: $TARGET"
fi
