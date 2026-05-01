#!/usr/bin/env bash
# release.sh — 一键发版到平台 App OTA 升级链路
#
# 自动完成:
#   1. 检查 git 工作区干净
#   2. 计算下一个 versionCode(当前 +1)
#   3. (回滚模式) 切到指定 ref + stash 当前改动
#   4. 改 build.gradle.kts 的 versionCode + versionName
#   5. ./gradlew assembleRelease
#   6. (回滚模式) 切回 + 恢复 stash
#   7. curl POST 上传到 /api/admin/app-release
#   8. 询问是否立即激活(默认询问,可 --no-activate 跳过)
#   9. 提示 git commit message 模板,但不自动 commit
#
# === 用法 ===
#
# 普通发版(从当前 HEAD 打包):
#   ./scripts/release.sh <versionName> "<changelog>"
#   例: ./scripts/release.sh 1.0.8 "修复对讲断流问题"
#
# 回滚发版(从指定 commit/tag 打包,vc 仍向前):
#   ./scripts/release.sh --rollback <git-ref> <versionName> "<changelog>"
#   例: ./scripts/release.sh --rollback v1.0.7 1.0.7-rollback "回滚 1.0.8 引入的崩溃"
#       (会基于 v1.0.7 的代码重新打包,但 versionCode 是当前 max+1,Android 视为更高版本可装)
#
# 选项:
#   --force          上传时勾选强制升级(慎用)
#   --no-activate    上传后不激活(灰度时用,后台手动激活)
#   --platform URL   覆盖平台地址(默认 http://106.53.166.236)
#   --keep-version   build 完不还原 build.gradle.kts(默认还原,
#                    避免污染 git;通常配合非 --rollback 使用,
#                    回滚模式总是还原)
#
# 例(强制升级):
#   ./scripts/release.sh --force 1.0.9 "安全补丁,所有设备必装"
#
# 例(灰度,先不激活):
#   ./scripts/release.sh --no-activate 1.0.10 "Beta: 新对讲实现"
#
# 退出码: 0=成功, 非0=失败(上传失败 APK 仍在 build/outputs/apk/release/)

set -euo pipefail

# ============ 解析参数 ============
ROLLBACK_REF=""
FORCE_UPGRADE=false
ACTIVATE_AFTER=true   # 上传后默认询问激活
PLATFORM="${HDC_PLATFORM_URL:-http://106.53.166.236}"
KEEP_VERSION=false

POSITIONAL=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --rollback)
            ROLLBACK_REF="$2"; shift 2;;
        --force)
            FORCE_UPGRADE=true; shift;;
        --no-activate)
            ACTIVATE_AFTER=false; shift;;
        --platform)
            PLATFORM="$2"; shift 2;;
        --keep-version)
            KEEP_VERSION=true; shift;;
        -h|--help)
            sed -n '2,40p' "$0"; exit 0;;
        --*)
            echo "未知选项: $1" >&2; exit 2;;
        *)
            POSITIONAL+=("$1"); shift;;
    esac
done

if [[ ${#POSITIONAL[@]} -ne 2 ]]; then
    echo "用法: $0 [--rollback <ref>] [--force] [--no-activate] <versionName> <changelog>" >&2
    echo "示例: $0 1.0.8 \"修复对讲断流\"" >&2
    echo "回滚: $0 --rollback v1.0.7 1.0.7-rollback \"回滚 1.0.8\"" >&2
    exit 2
fi
VERSION_NAME="${POSITIONAL[0]}"
CHANGELOG="${POSITIONAL[1]}"

# ============ 路径定位 ============
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
GRADLE_FILE="$APP_DIR/app/build.gradle.kts"

if [[ ! -f "$GRADLE_FILE" ]]; then
    echo "找不到 $GRADLE_FILE" >&2
    exit 1
fi

cd "$APP_DIR"

# ============ 检查 git 工作区 ============
if [[ -n "$(git status --porcelain)" ]]; then
    echo "❌ git 工作区不干净,先 commit 或 stash 后再发版:" >&2
    git status -s >&2
    exit 1
fi
ORIGINAL_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
ORIGINAL_COMMIT="$(git rev-parse --short HEAD)"

# ============ 读当前 versionCode 计算下一个 ============
CURRENT_VC=$(grep -E '^\s*versionCode\s*=\s*[0-9]+' "$GRADLE_FILE" | head -1 | sed 's/[^0-9]//g')
if [[ -z "$CURRENT_VC" ]]; then
    echo "❌ 无法从 $GRADLE_FILE 解析 versionCode" >&2
    exit 1
fi
NEW_VC=$((CURRENT_VC + 1))
echo "═════════════════════════════════════════════"
echo "  当前 versionCode = $CURRENT_VC → 新 versionCode = $NEW_VC"
echo "  versionName     = $VERSION_NAME"
echo "  changelog       = $CHANGELOG"
echo "  强制升级        = $FORCE_UPGRADE"
echo "  平台            = $PLATFORM"
if [[ -n "$ROLLBACK_REF" ]]; then
    echo "  ⚠️  回滚模式:基于 $ROLLBACK_REF 打包"
fi
echo "═════════════════════════════════════════════"
read -r -p "确认发版? [y/N] " confirm
[[ "$confirm" =~ ^[Yy]$ ]] || { echo "已取消"; exit 0; }

# ============ 回滚模式:切到指定 ref ============
SWITCHED_BRANCH=false
if [[ -n "$ROLLBACK_REF" ]]; then
    echo "→ 切换到 $ROLLBACK_REF"
    git checkout "$ROLLBACK_REF"
    SWITCHED_BRANCH=true
fi

# ============ 还原函数(无论成功失败都跑) ============
cleanup() {
    if $SWITCHED_BRANCH; then
        echo "→ 切回 $ORIGINAL_BRANCH"
        git checkout -- "$GRADLE_FILE" 2>/dev/null || true   # 撤销 sed 改动
        git checkout "$ORIGINAL_BRANCH" 2>/dev/null || true
    elif ! $KEEP_VERSION; then
        echo "→ 还原 $GRADLE_FILE"
        git checkout -- "$GRADLE_FILE" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# ============ 改 build.gradle.kts ============
echo "→ 写入 versionCode=$NEW_VC, versionName=$VERSION_NAME"
sed -i -E \
    -e "s/^(\s*versionCode\s*=\s*)[0-9]+/\1$NEW_VC/" \
    -e "s/^(\s*versionName\s*=\s*\")[^\"]+/\1$VERSION_NAME/" \
    "$GRADLE_FILE"
grep -E "versionCode|versionName" "$GRADLE_FILE" | head -2

# ============ 构建 release APK ============
echo "→ 构建 release APK..."
./gradlew assembleRelease --no-daemon </dev/null 2>&1 | tail -10
APK="$APP_DIR/app/build/outputs/apk/release/HdcEnforcement-v${VERSION_NAME}-vc${NEW_VC}.apk"
if [[ ! -f "$APK" ]]; then
    echo "❌ 找不到产物 $APK" >&2
    exit 1
fi
APK_SIZE=$(du -h "$APK" | cut -f1)
echo "✓ 构建成功: $APK ($APK_SIZE)"

# ============ 上传到平台 ============
echo "→ 上传到 $PLATFORM/api/admin/app-release ..."
RESP=$(curl -s -X POST "$PLATFORM/api/admin/app-release" \
    -F "apk=@$APK" \
    -F "versionCode=$NEW_VC" \
    -F "versionName=$VERSION_NAME" \
    -F "changelog=$CHANGELOG" \
    -F "isForce=$FORCE_UPGRADE" \
    --max-time 300)
echo "  返回: $RESP"

UPLOAD_OK=$(echo "$RESP" | python3 -c 'import json,sys; d=json.load(sys.stdin); print("yes" if d.get("success") else "no")' 2>/dev/null || echo "no")
if [[ "$UPLOAD_OK" != "yes" ]]; then
    echo "❌ 上传失败,APK 保留在 $APK" >&2
    exit 1
fi
RELEASE_ID=$(echo "$RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["id"])')
echo "✓ 上传成功 release_id=$RELEASE_ID"

# ============ 激活 ============
if $ACTIVATE_AFTER; then
    read -r -p "立即激活该版本? [y/N] " confirm
    if [[ "$confirm" =~ ^[Yy]$ ]]; then
        ACT_RESP=$(curl -s -X POST "$PLATFORM/api/admin/app-release/$RELEASE_ID/activate")
        echo "  激活返回: $ACT_RESP"
        ACT_OK=$(echo "$ACT_RESP" | python3 -c 'import json,sys; print("yes" if json.load(sys.stdin).get("success") else "no")' 2>/dev/null || echo "no")
        if [[ "$ACT_OK" == "yes" ]]; then
            echo "✓ 已激活,设备 1H 内自动升级"
        else
            echo "⚠️  激活失败,请到后台手动激活"
        fi
    else
        echo "ℹ️  未激活,版本保留为 IsActive=false。后台 /admin/app-release 手动激活。"
    fi
fi

# ============ 提示 git commit ============
echo
echo "═════════════════════════════════════════════"
echo "  发版完成"
echo "  Release ID:    $RELEASE_ID"
echo "  versionCode:   $NEW_VC"
echo "  versionName:   $VERSION_NAME"
echo "  APK:           $APK ($APK_SIZE)"
if [[ -n "$ROLLBACK_REF" ]]; then
    echo
    echo "  ℹ️  回滚模式发版,build.gradle.kts 改动已还原,"
    echo "     git 工作区干净,无需 commit。"
else
    echo
    echo "  下一步 — 提交版本号变更到 git:"
    echo "    cd $APP_DIR"
    echo "    git add app/build.gradle.kts"
    cat <<EOF
    git commit -m "chore: bump $VERSION_NAME (vc=$NEW_VC)

$CHANGELOG"
    git push
EOF
fi
echo "═════════════════════════════════════════════"
