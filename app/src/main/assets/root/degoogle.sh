#!/system/bin/sh
# =============================================================================
# degoogle.sh — DeGoogle backend (v0.2.0)
#
# Shell backend for the DeGoogle app. It runs exclusively as root; the profile
# below is used only for verified legacy recovery. New preparations use paths
# discovered within the allowlist and approved by runtime checks. All operations
# are idempotent; destructive operations have rollback; nothing is reported as
# successful without verification.
#
# Output protocol:
#   stdout = "DEGOOGLE_KEY=value" lines (machine-readable; value is everything
#            after the first '='). NEVER write human-readable text to stdout.
#   stderr = readable progress/errors (shown in the app's technical section).
#
# Exit codes:
#   0 success · 1 general error · 2 usage · 3 precondition failed (no changes) ·
#   4 rollback failed / partial state · 5 incompatible profile (probe)
#
# Failure injection for tests (on-device or emulated):
#   DEGOOGLE_FAIL_CP=1 DEGOOGLE_FAIL_MOUNT_GMS=1 DEGOOGLE_FAIL_MOUNT_GSF=1
#   DEGOOGLE_FAIL_MOUNT_STORE=1 DEGOOGLE_FAIL_RESTORECON=1 DEGOOGLE_FAIL_BACKUP=1
#   DEGOOGLE_FAIL_RESTORE=1 DEGOOGLE_FAIL_UMOUNT=1
#
# Usage:
#   degoogle.sh probe
#   degoogle.sh prepare <gms.apk> <companion.apk> <gms-sha256> <companion-sha256>
#   degoogle.sh finalize
#   degoogle.sh backup
#   degoogle.sh restore-backup
#   degoogle.sh restore-stock [--wipe-data]
#   degoogle.sh reindex-stock
#   degoogle.sh soft-reboot
#   degoogle.sh status
#   degoogle.sh dry-run
#   degoogle.sh preflight
#   degoogle.sh post-boot-validate
#   degoogle.sh test
# =============================================================================

set -u

SCRIPT_VERSION="0.2.0"

# ---------------------------------------------------------------------------
# Legacy recovery profile (Samsung Galaxy S24 Ultra / SM-S928x)
# ---------------------------------------------------------------------------
PROFILE_ID="samsung_sm-s928b"
PROFILE_MANUFACTURER="samsung"
PROFILE_MODELS="SM-S928B|SM-S928U|SM-S928W|SM-S928N|SM-S9280"

GMS_PKG="com.google.android.gms"
GSF_PKG="com.google.android.gsf"
STORE_PKG="com.android.vending"
FAKEGAPPS_PKG="${DEGOOGLE_FAKEGAPPS_PACKAGE:-inc.whew.android.fakegapps}"
LSPOSED_MARKER="${DEGOOGLE_LSPOSED_MARKER:-/data/adb/modules/zygisk_lsposed/module.prop}"

# Paths configurable by the test harness; on-device, the defaults are used only
# for verified legacy recovery.
PM_SYSTEM_DIR="${DEGOOGLE_PM_SYSTEM_DIR:-/data/system}"
PM_CACHE_DIR="${DEGOOGLE_PM_CACHE:-$PM_SYSTEM_DIR/package_cache}"

PROFILE_GMS="${DEGOOGLE_PROFILE_GMS:-/product/priv-app/GmsCore}"
PROFILE_GSF="${DEGOOGLE_PROFILE_GSF:-/system_ext/priv-app/GoogleServicesFramework}"
PROFILE_STORE="${DEGOOGLE_PROFILE_STORE:-/product/priv-app/Phonesky}"

MASK_BASE="${DEGOOGLE_MASK_BASE:-/data/local/tmp/degoogle-mask}"
MASK_GMS="$MASK_BASE/gms"
MASK_GSF="$MASK_BASE/gsf"
MASK_STORE="$MASK_BASE/store"

# Layout used by the legacy manual script. It remains a known recovery source,
# but is never treated as the app's current mask.
LEGACY_MASK_BASE="${DEGOOGLE_LEGACY_MASK_BASE:-/data/local/tmp/microg-mask}"
LEGACY_MASK_GMS="$LEGACY_MASK_BASE/gms"
LEGACY_MASK_GSF="$LEGACY_MASK_BASE/gsf"
LEGACY_MASK_STORE="$LEGACY_MASK_BASE/store"
GMS_APK="GmsCore.apk"
STORE_APK="Companion.apk"
GMS_MAPBOX_LIB=""

# Operational targets discovered by the Package Locator. PROFILE_* remain the
# S24 legacy baseline; these values are used only after the package is found
# within the allowed roots.
TARGET_GMS=""
TARGET_GSF=""
TARGET_STORE=""
ORIGINAL_GMS_OWNER=""
ORIGINAL_GMS_MODE=""
ORIGINAL_GMS_CONTEXT=""
ORIGINAL_GSF_OWNER=""
ORIGINAL_GSF_MODE=""
ORIGINAL_GSF_CONTEXT=""
ORIGINAL_STORE_OWNER=""
ORIGINAL_STORE_MODE=""
ORIGINAL_STORE_CONTEXT=""

BACKUP_BASE="${DEGOOGLE_BACKUP_BASE:-/data/local/tmp/microg-backup}"
BACKUP_GMS_USER="$BACKUP_BASE/gms-user0"
BACKUP_GMS_DE="$BACKUP_BASE/gms-userde"

# Layout matches microg-session.sh. These variables allow host-side backend
# tests without changing real Android paths.
GMS_DATA_USER0="${DEGOOGLE_GMS_DATA_USER0:-/data/user/0/com.google.android.gms}"
GMS_DATA_USERDE="${DEGOOGLE_GMS_DATA_USERDE:-/data/user_de/0/com.google.android.gms}"

LOCK_DIR="${DEGOOGLE_LOCK_DIR:-/data/local/tmp/degoogle.lockdir}"

MOUNTINFO="${DEGOOGLE_MOUNTINFO:-/proc/1/mountinfo}"

# Persistent transaction state. This directory contains only DeGoogle snapshots
# and journal data; it is never used as a system bind-mount target.
TRANSACTION_BASE="${DEGOOGLE_TRANSACTION_BASE:-$BACKUP_BASE/transaction}"
JOURNAL_FILE="$TRANSACTION_BASE/journal"
SNAPSHOT_FILE="$TRANSACTION_BASE/snapshot.json"
PROBE_BASELINE_FILE="$TRANSACTION_BASE/probe-baseline"
REBOOT_BASELINE_FILE="$TRANSACTION_BASE/reboot-baseline"
RESCUE_STATE_FILE="${DEGOOGLE_RESCUE_STATE:-$TRANSACTION_BASE/rescue-party.state}"
KSUD_PATH="${DEGOOGLE_KSUD_PATH:-/data/adb/ksud}"

# Fact variables (populated by collect_facts, used by compute_state)
ROOT_OK=0
ROOT_MANAGER=""
MANUFACTURER=""
MODEL=""
DEVICE=""
PRODUCT=""
ANDROID_SDK=""
ANDROID_RELEASE=""
FINGERPRINT=""
SELINUX=""
ABI=""
ABI_LIST=""
BOARD=""
HARDWARE=""
BUILD_ID=""
SECURITY_PATCH=""
ONE_UI_VERSION=""
KERNEL_VERSION=""
GMS_PATH=""
GMS_VERSION=""
GMS_VERSION_CODE=""
GMS_UID=""
GMS_FLAGS=""
GMS_PACKAGE_DUMP=""
GMS_DUMP_UID=""
GMS_SIGNATURE_GRANTED=0
GMS_SIGNATURE_MENTIONED=0
GMS_PRIVILEGED=0
GMS_MAPS_LIB_PRESENT=0
GMS_MAPS_LIB_RELPATH=""
GSF_PATH=""
STORE_PATH=""
STORE_VERSION=""
GMS_ACTIVE_CODE_PATH=""
GMS_ORIGINAL_SYSTEM_PATH=""
GMS_TARGET_DIRECTORY=""
GMS_BASE_APK=""
GMS_SPLIT_APKS=""
GMS_HAS_DATA_UPDATE=0
GMS_BACKING_PARTITION=""
GMS_FILESYSTEM_TYPE=""
GMS_RESOLVED_REAL_PATH=""
GSF_ACTIVE_CODE_PATH=""
GSF_ORIGINAL_SYSTEM_PATH=""
GSF_TARGET_DIRECTORY=""
GSF_BASE_APK=""
GSF_SPLIT_APKS=""
GSF_HAS_DATA_UPDATE=0
GSF_BACKING_PARTITION=""
GSF_FILESYSTEM_TYPE=""
GSF_RESOLVED_REAL_PATH=""
STORE_ACTIVE_CODE_PATH=""
STORE_ORIGINAL_SYSTEM_PATH=""
STORE_TARGET_DIRECTORY=""
STORE_BASE_APK=""
STORE_SPLIT_APKS=""
STORE_HAS_DATA_UPDATE=0
STORE_BACKING_PARTITION=""
STORE_FILESYSTEM_TYPE=""
STORE_RESOLVED_REAL_PATH=""
UPDATE_CLEANUP_INFO=""
MOUNT_GMS=0
MOUNT_GMS_IS_OURS=0
MOUNT_GSF=0
MOUNT_GSF_IS_OURS=0
MOUNT_STORE=0
MOUNT_STORE_IS_OURS=0
MOUNT_GMS_IS_LEGACY=0
MOUNT_GSF_IS_LEGACY=0
MOUNT_STORE_IS_LEGACY=0
MOUNT_GMS_IS_KNOWN=0
MOUNT_GSF_IS_KNOWN=0
MOUNT_STORE_IS_KNOWN=0
MOUNT_GMS_SOURCE=""
BACKUP_PRESENT=0
BACKUP_CREATED_AT=""
MASK_RESIDUE_PRESENT=0
FINALIZE_DONE=0
OPERATION_MODE=""
MODE_CONFLICT=0
MASK_GMS_EMPTY=0
MASK_GSF_EMPTY=0
MASK_STORE_EMPTY=0
PURE_MASKS_VALID=0
POST_BOOT_STATUS=""
POST_BOOT_REASON=""
STOCK_REINDEX_PENDING=0
STOCK_REINDEX_READY=0
STOCK_CONFIRMED=0
STOCK_CONFIRMATION_OPERATION_ID=""
REBOOT_STRATEGY_BACKEND=""
REBOOT_STRATEGY_METHOD=""
REBOOT_STRATEGY_CONFIDENCE="UNKNOWN"
REBOOT_STRATEGY_FIRMWARE_VALIDATED=0

# Capability statuses. UNKNOWN is intentional and conservative.
CAP_ROOT_STATUS=UNKNOWN; CAP_ROOT_EVIDENCE=""; CAP_ROOT_REASON=""
CAP_SAMSUNG_DEVICE_STATUS=UNKNOWN; CAP_SAMSUNG_DEVICE_EVIDENCE=""; CAP_SAMSUNG_DEVICE_REASON=""
CAP_GLOBAL_MOUNT_NAMESPACE_STATUS=UNKNOWN; CAP_GLOBAL_MOUNT_NAMESPACE_EVIDENCE=""; CAP_GLOBAL_MOUNT_NAMESPACE_REASON=""
CAP_BIND_MOUNT_STATUS=UNKNOWN; CAP_BIND_MOUNT_EVIDENCE=""; CAP_BIND_MOUNT_REASON=""
CAP_SYSTEM_GMS_FOUND_STATUS=UNKNOWN; CAP_SYSTEM_GMS_FOUND_EVIDENCE=""; CAP_SYSTEM_GMS_FOUND_REASON=""
CAP_SYSTEM_GSF_FOUND_STATUS=UNKNOWN; CAP_SYSTEM_GSF_FOUND_EVIDENCE=""; CAP_SYSTEM_GSF_FOUND_REASON=""
CAP_SYSTEM_STORE_FOUND_STATUS=UNKNOWN; CAP_SYSTEM_STORE_FOUND_EVIDENCE=""; CAP_SYSTEM_STORE_FOUND_REASON=""
CAP_GMS_MASKABLE_STATUS=UNKNOWN; CAP_GMS_MASKABLE_EVIDENCE=""; CAP_GMS_MASKABLE_REASON=""
CAP_GSF_MASKABLE_STATUS=UNKNOWN; CAP_GSF_MASKABLE_EVIDENCE=""; CAP_GSF_MASKABLE_REASON=""
CAP_STORE_MASKABLE_STATUS=UNKNOWN; CAP_STORE_MASKABLE_EVIDENCE=""; CAP_STORE_MASKABLE_REASON=""
CAP_SELINUX_ENFORCING_STATUS=UNKNOWN; CAP_SELINUX_ENFORCING_EVIDENCE=""; CAP_SELINUX_ENFORCING_REASON=""
CAP_SELINUX_CONTEXT_CLONABLE_STATUS=UNKNOWN; CAP_SELINUX_CONTEXT_CLONABLE_EVIDENCE=""; CAP_SELINUX_CONTEXT_CLONABLE_REASON=""
CAP_SIGNATURE_SPOOFING_STATUS=UNKNOWN; CAP_SIGNATURE_SPOOFING_EVIDENCE=""; CAP_SIGNATURE_SPOOFING_REASON=""
CAP_PACKAGE_MANAGER_CACHE_ACCESS_STATUS=UNKNOWN; CAP_PACKAGE_MANAGER_CACHE_ACCESS_EVIDENCE=""; CAP_PACKAGE_MANAGER_CACHE_ACCESS_REASON=""
CAP_PRIV_APP_COMPATIBLE_STATUS=UNKNOWN; CAP_PRIV_APP_COMPATIBLE_EVIDENCE=""; CAP_PRIV_APP_COMPATIBLE_REASON=""
CAP_SAFE_SOFT_REBOOT_STATUS=UNKNOWN; CAP_SAFE_SOFT_REBOOT_EVIDENCE=""; CAP_SAFE_SOFT_REBOOT_REASON=""
CAP_SAFE_RESTORE_STATUS=UNKNOWN; CAP_SAFE_RESTORE_EVIDENCE=""; CAP_SAFE_RESTORE_REASON=""

# ===========================================================================
# Helpers
# ===========================================================================

say()
{
    echo "$@" >&2
}

emit()
{
    if [ "${FACT_EMIT_MODE:-0}" = "1" ]; then
        printf 'DEGOOGLE_%s=%s\000' "$1" "$2"
    else
        printf 'DEGOOGLE_%s=%s\n' "$1" "$(printf '%s' "$2" | tr '\n' ' ')"
    fi
}

emit_progress()
{
    [ "${DEGOOGLE_PROGRESS:-0}" = "1" ] || return 0
    printf 'DEGOOGLE_PROGRESS=%s:%s\n' "$1" "$2" >&2
}

emit_probe_result()
{
    FACT_EMIT_MODE=1
    emit_facts
    emit STATE "$STATE"
}

fail()
{
    local code="$1"
    shift
    say "ERROR: $*"
    exit "$code"
}

fail_en()
{
    local code="$1"
    shift
    say "ERROR: $*"
    exit "$code"
}

fail_usage()
{
    say "Usage: $0 $*"
    exit 2
}

check_root()
{
    [ "$(id -u 2>/dev/null)" = "0" ] || fail 3 "This backend requires root (id -u = 0)."
}

require_lock()
{
    if ! mkdir "$LOCK_DIR" 2>/dev/null; then
        say "ERROR: another DeGoogle operation is in progress (lock: $LOCK_DIR)."
        exit 3
    fi
    trap 'rmdir "$LOCK_DIR" 2>/dev/null || true' EXIT INT TERM
}

unlock()
{
    check_root
    if [ ! -e "$LOCK_DIR" ]; then
        say "No pending DeGoogle lock."
        return 0
    fi
    rmdir "$LOCK_DIR" 2>/dev/null || fail 3 "the lock is not empty or another operation is still in progress: $LOCK_DIR"
    say "DeGoogle lock released."
}

global()
{
    nsenter --mount=/proc/1/ns/mnt -- "$@"
}

global_exec() { global "$@"; }
global_mount() { global mount "$@"; }
global_umount() { global umount "$@"; }
global_mountinfo() { cat "$MOUNTINFO" 2>/dev/null; }

with_timeout()
{
    # Binder calls (pm/am/cmd) can block indefinitely when system_server
    # is rescanning. Never let the backend hang: use a short timeout.
    local secs="$1"
    shift
    if command -v timeout >/dev/null 2>&1; then
        timeout "$secs" "$@"
    else
        "$@"
    fi
}

mount_namespace_identity()
{
    # The process invoking the backend may be in a private namespace. The
    # The key evidence is that the global helper actually runs in the same
    # namespace as PID 1 before any destructive change.
    local self_ns init_ns global_ns relation
    self_ns="$(readlink /proc/self/ns/mnt 2>/dev/null || true)"
    init_ns="$(readlink /proc/1/ns/mnt 2>/dev/null || true)"
    global_ns="$(global readlink /proc/self/ns/mnt 2>/dev/null || true)"
    [ -n "$self_ns" ] && [ -n "$init_ns" ] && [ -n "$global_ns" ] || return 1
    [ "$global_ns" = "$init_ns" ] || return 1
    if [ "$self_ns" = "$init_ns" ]; then
        relation="self=pid1"
    else
        relation="self!=pid1"
    fi
    printf 'self=%s; pid1=%s; global=%s; %s' "$self_ns" "$init_ns" "$global_ns" "$relation"
}

journal_state()
{
    local state="$1" tmp operation
    mkdir -p "$TRANSACTION_BASE" 2>/dev/null || return 1
    operation="${DEGOOGLE_OPERATION_ID:-unknown}"
    tmp="$JOURNAL_FILE.tmp.$$"
    {
        printf 'schemaVersion=1\n'
        printf 'operationId=%s\n' "${DEGOOGLE_OPERATION_ID:-unknown}"
        printf 'timestamp=%s\n' "$(date +%s 2>/dev/null || echo 0)"
        printf 'state=%s\n' "$state"
        [ -n "$OPERATION_MODE" ] && printf 'operationMode=%s\n' "$OPERATION_MODE"
        printf 'model=%s\n' "$MODEL"
        printf 'fingerprint=%s\n' "$FINGERPRINT"
        printf 'targetGms=%s\n' "$TARGET_GMS"
        printf 'targetGsf=%s\n' "$TARGET_GSF"
        printf 'targetStore=%s\n' "$TARGET_STORE"
    } > "$tmp" || return 1
    mv "$tmp" "$JOURNAL_FILE" || return 1
    [ "$(sed -n 's/^schemaVersion=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)" = "1" ] || return 1
    [ "$(sed -n 's/^operationId=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)" = "$operation" ] || return 1
    [ "$(sed -n 's/^state=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)" = "$state" ] || return 1
}

hash_file()
{
    local file="$1" hash=""
    [ -f "$file" ] || return 1
    if command -v sha256sum >/dev/null 2>&1; then
        hash="$(sha256sum "$file" 2>/dev/null | awk '{print $1}')"
    elif command -v toybox >/dev/null 2>&1; then
        hash="$(toybox sha256sum "$file" 2>/dev/null | awk '{print $1}')"
    fi
    [ -n "$hash" ] || return 1
    printf '%s' "$hash"
}

sha256_matches_file()
{
    local file="$1" expected="$2" actual
    [ "${#expected}" = "64" ] || return 1
    case "$expected" in *[!0123456789abcdefABCDEF]*) return 1 ;; esac
    expected="$(printf '%s' "$expected" | tr 'A-F' 'a-f')"
    actual="$(hash_file "$file")" || return 1
    [ "$actual" = "$expected" ]
}

snapshot_state()
{
    local tmp gms_hash gsf_hash store_hash
    mkdir -p "$TRANSACTION_BASE" 2>/dev/null || return 1
    ORIGINAL_GMS_OWNER="$(global stat -c %u:%g "$TARGET_GMS" 2>/dev/null || true)"
    ORIGINAL_GMS_MODE="$(global stat -c %a "$TARGET_GMS" 2>/dev/null || true)"
    ORIGINAL_GMS_CONTEXT="$(global ls -Zd "$TARGET_GMS" 2>/dev/null | awk '{print $1}')"
    ORIGINAL_GSF_OWNER="$(global stat -c %u:%g "$TARGET_GSF" 2>/dev/null || true)"
    ORIGINAL_GSF_MODE="$(global stat -c %a "$TARGET_GSF" 2>/dev/null || true)"
    ORIGINAL_GSF_CONTEXT="$(global ls -Zd "$TARGET_GSF" 2>/dev/null | awk '{print $1}')"
    ORIGINAL_STORE_OWNER="$(global stat -c %u:%g "$TARGET_STORE" 2>/dev/null || true)"
    ORIGINAL_STORE_MODE="$(global stat -c %a "$TARGET_STORE" 2>/dev/null || true)"
    ORIGINAL_STORE_CONTEXT="$(global ls -Zd "$TARGET_STORE" 2>/dev/null | awk '{print $1}')"
    [ -n "$ORIGINAL_GMS_OWNER" ] && [ -n "$ORIGINAL_GMS_MODE" ] && [ -n "$ORIGINAL_GMS_CONTEXT" ] || return 1
    [ -n "$ORIGINAL_GSF_OWNER" ] && [ -n "$ORIGINAL_GSF_MODE" ] && [ -n "$ORIGINAL_GSF_CONTEXT" ] || return 1
    [ -n "$ORIGINAL_STORE_OWNER" ] && [ -n "$ORIGINAL_STORE_MODE" ] && [ -n "$ORIGINAL_STORE_CONTEXT" ] || return 1
    gms_hash="$(hash_file "$GMS_ACTIVE_CODE_PATH" 2>/dev/null || true)"
    gsf_hash="$(hash_file "$GSF_ACTIVE_CODE_PATH" 2>/dev/null || true)"
    store_hash="$(hash_file "$STORE_ACTIVE_CODE_PATH" 2>/dev/null || true)"
    tmp="$SNAPSHOT_FILE.tmp.$$"
    {
        printf '{\n'
        printf '  "schemaVersion": 1,\n'
        printf '  "operationId": "%s",\n' "${DEGOOGLE_OPERATION_ID:-unknown}"
        printf '  "timestamp": "%s",\n' "$(date +%s 2>/dev/null || echo 0)"
        printf '  "deviceFingerprint": "%s",\n' "$FINGERPRINT"
        printf '  "model": "%s",\n' "$MODEL"
        printf '  "selinux": "%s",\n' "$SELINUX"
        printf '  "rootBackend": "%s",\n' "$ROOT_MANAGER"
        printf '  "operationMode": "%s",\n' "$OPERATION_MODE"
        printf '  "gms": {"activeCodePath":"%s","originalSystemPath":"%s","targetDirectory":"%s","sha256":"%s","owner":"%s","mode":"%s","selinuxContext":"%s"},\n' "$GMS_ACTIVE_CODE_PATH" "$GMS_ORIGINAL_SYSTEM_PATH" "$TARGET_GMS" "$gms_hash" "$ORIGINAL_GMS_OWNER" "$ORIGINAL_GMS_MODE" "$ORIGINAL_GMS_CONTEXT"
        printf '  "gsf": {"activeCodePath":"%s","originalSystemPath":"%s","targetDirectory":"%s","sha256":"%s","owner":"%s","mode":"%s","selinuxContext":"%s"},\n' "$GSF_ACTIVE_CODE_PATH" "$GSF_ORIGINAL_SYSTEM_PATH" "$TARGET_GSF" "$gsf_hash" "$ORIGINAL_GSF_OWNER" "$ORIGINAL_GSF_MODE" "$ORIGINAL_GSF_CONTEXT"
        printf '  "store": {"activeCodePath":"%s","originalSystemPath":"%s","targetDirectory":"%s","sha256":"%s","owner":"%s","mode":"%s","selinuxContext":"%s"},\n' "$STORE_ACTIVE_CODE_PATH" "$STORE_ORIGINAL_SYSTEM_PATH" "$TARGET_STORE" "$store_hash" "$ORIGINAL_STORE_OWNER" "$ORIGINAL_STORE_MODE" "$ORIGINAL_STORE_CONTEXT"
        printf '  "mounts": ['
        global_mountinfo | awk -v g="$TARGET_GMS" -v f="$TARGET_GSF" -v s="$TARGET_STORE" \
            '$5 == g || $5 == f || $5 == s { printf "%s{\"mountpoint\":\"%s\",\"sourceRoot\":\"%s\"}", sep, $5, $4; sep="," }'
        printf '],\n'
        printf '  "state": "PREPARED"\n'
        printf '}\n'
    } > "$tmp" || return 1
    mv "$tmp" "$SNAPSHOT_FILE" || return 1
    validate_snapshot || return 1
    journal_state "BACKUP_COMPLETE" || return 1
    [ "$(snapshot_top_field operationId)" = "$(sed -n 's/^operationId=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)" ] || return 1
    return 0
}

clone_mask_metadata()
{
    local target="$1" mask="$2" owner="$3" mode="$4" context="$5" label="$6" actual_owner actual_mode actual_context
    [ -n "$owner" ] && [ -n "$mode" ] && [ -n "$context" ] || \
        _prep_fail 4 "$label: incomplete owner/mode/SELinux metadata"
    global chown "$owner" "$mask" 2>/dev/null || _prep_fail 4 "$label: could not clone owner=$owner"
    global chmod "$mode" "$mask" 2>/dev/null || _prep_fail 4 "$label: could not clone mode=$mode"
    if ! global chcon -R "$context" "$mask" 2>/dev/null; then
        global restorecon -R "$mask" 2>/dev/null || _prep_fail 4 "$label: could not apply SELinux context"
    fi
    actual_owner="$(global stat -c %u:%g "$mask" 2>/dev/null)"
    actual_mode="$(global stat -c %a "$mask" 2>/dev/null)"
    actual_context="$(global ls -Zd "$mask" 2>/dev/null | awk '{print $1}')"
    [ "$actual_owner" = "$owner" ] && [ "$actual_mode" = "$mode" ] && [ "$actual_context" = "$context" ] || \
        _prep_fail 4 "$label: metadata mismatch (expected=$owner:$mode:$context actual=${actual_owner:-<missing>}:${actual_mode:-<missing>}:${actual_context:-<missing>})"
}

write_probe_baseline()
{
    local tmp="$PROBE_BASELINE_FILE.tmp.$$"
    mkdir -p "$TRANSACTION_BASE" 2>/dev/null || return 1
    {
        printf 'model=%s\n' "$MODEL"
        printf 'fingerprint=%s\n' "$FINGERPRINT"
        printf 'gms_path=%s\n' "$GMS_ACTIVE_CODE_PATH"
        printf 'gms_hash=%s\n' "$(hash_file "$GMS_ACTIVE_CODE_PATH" 2>/dev/null || true)"
        printf 'gsf_path=%s\n' "$GSF_ACTIVE_CODE_PATH"
        printf 'gsf_hash=%s\n' "$(hash_file "$GSF_ACTIVE_CODE_PATH" 2>/dev/null || true)"
        printf 'store_path=%s\n' "$STORE_ACTIVE_CODE_PATH"
        printf 'store_hash=%s\n' "$(hash_file "$STORE_ACTIVE_CODE_PATH" 2>/dev/null || true)"
    } > "$tmp" || return 1
    mv "$tmp" "$PROBE_BASELINE_FILE" || return 1
}

verify_probe_baseline()
{
    [ -f "$PROBE_BASELINE_FILE" ] || {
        write_probe_baseline
        return $?
    }
    local old_model old_fp old_gms old_gms_hash old_gsf old_gsf_hash old_store old_store_hash
    old_model="$(sed -n 's/^model=//p' "$PROBE_BASELINE_FILE" | head -n 1)"
    old_fp="$(sed -n 's/^fingerprint=//p' "$PROBE_BASELINE_FILE" | head -n 1)"
    old_gms="$(sed -n 's/^gms_path=//p' "$PROBE_BASELINE_FILE" | head -n 1)"
    old_gms_hash="$(sed -n 's/^gms_hash=//p' "$PROBE_BASELINE_FILE" | head -n 1)"
    old_gsf="$(sed -n 's/^gsf_path=//p' "$PROBE_BASELINE_FILE" | head -n 1)"
    old_gsf_hash="$(sed -n 's/^gsf_hash=//p' "$PROBE_BASELINE_FILE" | head -n 1)"
    old_store="$(sed -n 's/^store_path=//p' "$PROBE_BASELINE_FILE" | head -n 1)"
    old_store_hash="$(sed -n 's/^store_hash=//p' "$PROBE_BASELINE_FILE" | head -n 1)"
    [ "$old_model" = "$MODEL" ] || return 1
    [ "$old_fp" = "$FINGERPRINT" ] || return 1
    [ "$old_gms" = "$GMS_ACTIVE_CODE_PATH" ] || return 1
    [ "$old_gsf" = "$GSF_ACTIVE_CODE_PATH" ] || return 1
    [ "$old_store" = "$STORE_ACTIVE_CODE_PATH" ] || return 1
    # An idempotent prepare exposes the mask's own APK at the mountpoint;
    # in that case, the visible path's hash naturally differs from the stock
    # hash from probe and must not be treated as TOCTOU.
    if [ "$MOUNT_GMS_IS_OURS" != "1" ]; then
        [ "$old_gms_hash" = "$(hash_file "$GMS_ACTIVE_CODE_PATH" 2>/dev/null || true)" ] || return 1
    fi
    if [ "$MOUNT_GSF_IS_OURS" != "1" ]; then
        [ "$old_gsf_hash" = "$(hash_file "$GSF_ACTIVE_CODE_PATH" 2>/dev/null || true)" ] || return 1
    fi
    if [ "$MOUNT_STORE_IS_OURS" != "1" ]; then
        [ "$old_store_hash" = "$(hash_file "$STORE_ACTIVE_CODE_PATH" 2>/dev/null || true)" ] || return 1
    fi
    return 0
}

pm_path()
{
    pm path "$1" 2>/dev/null | sed -n 's/^package://p' | head -n 1
}

pm_package_absent()
{
    local output status
    output="$(with_timeout 15 pm path "$1" 2>&1)"
    status=$?
    # AOSP returns 1 without output for an absent package; list packages distinguishes that from a PM failure.
    case "$status" in
        0) [ -z "$output" ] || return 1 ;;
        1) [ -z "$output" ] || [ "$output" = "Error: package $1 not found." ] || return 1 ;;
        *) return 1 ;;
    esac
    output="$(with_timeout 15 pm list packages "$1" 2>&1)" || return 1
    [ -z "$output" ]
}

pm_paths()
{
    pm path "$1" 2>/dev/null | sed -n 's/^package://p'
}

is_allowed_system_path()
{
    local path="$1"
    case "$path" in
        /system/*|/system_ext/*|/product/*|/vendor/*|/odm/*|/oem/*|/my_product/*|/mi_ext/*) ;;
        *)
            [ "${DEGOOGLE_TEST_MODE:-0}" = "1" ] || return 1
            case "$path" in
                "${DEGOOGLE_TEST_SYSTEM_ROOT:-/nonexistent}"/system/*|"${DEGOOGLE_TEST_SYSTEM_ROOT:-/nonexistent}"/system_ext/*|"${DEGOOGLE_TEST_SYSTEM_ROOT:-/nonexistent}"/product/*|\
                "${DEGOOGLE_TEST_SYSTEM_ROOT:-/nonexistent}"/vendor/*|"${DEGOOGLE_TEST_SYSTEM_ROOT:-/nonexistent}"/odm/*|"${DEGOOGLE_TEST_SYSTEM_ROOT:-/nonexistent}"/oem/*|\
                "${DEGOOGLE_TEST_SYSTEM_ROOT:-/nonexistent}"/my_product/*|"${DEGOOGLE_TEST_SYSTEM_ROOT:-/nonexistent}"/mi_ext/*|\
                "$PROFILE_GMS"|"$PROFILE_GMS"/*|"$PROFILE_GSF"|"$PROFILE_GSF"/*|"$PROFILE_STORE"|"$PROFILE_STORE"/*) ;;
                *) return 1 ;;
            esac
            ;;
    esac
    case "$path" in *..*|*/app|*/priv-app) return 1 ;; esac
    [ -n "$(basename "$path")" ] && [ "$(basename "$path")" != "." ]
}

is_safe_target_directory()
{
    local target="$1" real parent
    is_allowed_system_path "$target" || return 1
    parent="$(dirname "$target")"
    case "$parent" in */app|*/priv-app) ;; *) return 1 ;; esac
    real="$(global readlink -f "$target" 2>/dev/null || true)"
    [ -n "$real" ] || return 1
    is_allowed_system_path "$real" || return 1
    [ "$real" = "$target" ] && [ "$(dirname "$real")" = "$parent" ]
}

targets_are_distinct()
{
    local first="$1" second="$2" third="$3"
    [ "$first" != "$second" ] && [ "$first" != "$third" ] && [ "$second" != "$third" ] || return 1
    case "$first/" in "$second/"*|"$third/"*) return 1 ;; esac
    case "$second/" in "$first/"*|"$third/"*) return 1 ;; esac
    case "$third/" in "$first/"*|"$second/"*) return 1 ;; esac
    return 0
}

locate_package()
{
    # locate_package <GMS|GSF|STORE> <package>
    local prefix="$1" pkg="$2" paths active original target base splits update real fs partition package_dump discovered
    case "$prefix" in
        GMS)
            GMS_ACTIVE_CODE_PATH=""; GMS_ORIGINAL_SYSTEM_PATH=""; GMS_TARGET_DIRECTORY=""; GMS_BASE_APK=""; GMS_SPLIT_APKS=""
            GMS_HAS_DATA_UPDATE=0; GMS_BACKING_PARTITION=""; GMS_FILESYSTEM_TYPE=""; GMS_RESOLVED_REAL_PATH=""; GMS_PATH="" ;;
        GSF)
            GSF_ACTIVE_CODE_PATH=""; GSF_ORIGINAL_SYSTEM_PATH=""; GSF_TARGET_DIRECTORY=""; GSF_BASE_APK=""; GSF_SPLIT_APKS=""
            GSF_HAS_DATA_UPDATE=0; GSF_BACKING_PARTITION=""; GSF_FILESYSTEM_TYPE=""; GSF_RESOLVED_REAL_PATH=""; GSF_PATH="" ;;
        STORE)
            STORE_ACTIVE_CODE_PATH=""; STORE_ORIGINAL_SYSTEM_PATH=""; STORE_TARGET_DIRECTORY=""; STORE_BASE_APK=""; STORE_SPLIT_APKS=""
            STORE_HAS_DATA_UPDATE=0; STORE_BACKING_PARTITION=""; STORE_FILESYSTEM_TYPE=""; STORE_RESOLVED_REAL_PATH=""; STORE_PATH="" ;;
    esac
    paths="$(pm_paths "$pkg")"
    active="$(printf '%s\n' "$paths" | sed '/^$/d' | head -n 1)"
    case "$prefix" in
        GMS) GMS_PACKAGE_DUMP="$(dumpsys package "$pkg" 2>/dev/null || true)"; package_dump="$GMS_PACKAGE_DUMP" ;;
        GSF|STORE) package_dump="$(dumpsys package "$pkg" 2>/dev/null || true)" ;;
    esac
    original="$(printf '%s\n' "$paths" | while IFS= read -r p; do
        [ -n "$p" ] || continue
        if is_allowed_system_path "$p"; then printf '%s' "$p"; break; fi
    done)"
    if [ -z "$original" ] && printf '%s\n' "$package_dump" | grep -q 'UPDATED_SYSTEM_APP'; then
        original="$(printf '%s\n' "$package_dump" | sed -n 's/.*systemCodePath=\([^[:space:]]*\).*/\1/p' | head -n 1)"
        is_allowed_system_path "$original" || original=""
    fi
    if [ -z "$original" ] && printf '%s\n' "$paths" | grep -q '^/data/app/' && [ -r /data/system/packages.xml ]; then
        discovered="$(awk -v pkg="$pkg" '
            /<updated-package[[:space:]]/ && index($0, "name=\"" pkg "\"") {
                if (match($0, /codePath="[^"]+"/)) {
                    value=substr($0, RSTART+10, RLENGTH-11)
                    print value
                    exit
                }
            }
        ' /data/system/packages.xml 2>/dev/null)"
        case "$discovered" in
            *.apk) original="$discovered" ;;
            "") ;;
            *) original="$discovered/base.apk" ;;
        esac
        is_allowed_system_path "$original" || original=""
    fi
    if [ -z "$original" ] && printf '%s\n' "$paths" | grep -q '^/data/app/'; then
        for root in /system /system_ext /product /vendor /odm /oem /my_product /mi_ext; do
            [ -d "$root" ] || continue
            discovered="$(find "$root" -type f -name base.apk -path '*/app/*' -o -type f -name base.apk -path '*/priv-app/*' 2>/dev/null | head -n 200 | while IFS= read -r candidate; do
                if dumpsys package "$pkg" 2>/dev/null | grep -Fq "$candidate"; then printf '%s' "$candidate"; break; fi
            done)"
            [ -n "$discovered" ] || continue
            is_allowed_system_path "$discovered" || continue
            original="$discovered"
            break
        done
    fi
    if [ -n "$original" ]; then
        target="$(dirname "$original" 2>/dev/null)"
    elif [ -n "$active" ]; then
        target="$(dirname "$active" 2>/dev/null)"
        is_allowed_system_path "$target" || target=""
    else
        target=""
    fi
    [ "$target" = "." ] && target=""
    base="$(printf '%s\n' "$paths" | awk -F/ '$NF == "base.apk" { print; exit }')"
    [ -n "$base" ] || base="${original:-$active}"
    splits="$(printf '%s\n' "$paths" | awk -v b="$base" 'NF && $0 != b { if (out != "") out=out ","; out=out $0 } END { print out }')"
    update=0
    if printf '%s\n' "$paths" | grep -q '^/data/app/'; then
        if printf '%s\n' "$package_dump" | grep -q 'codePath=/data/app/' && \
            printf '%s\n' "$package_dump" | grep -q 'UPDATED_SYSTEM_APP' && [ -n "$original" ]; then
            update=1
        fi
    fi
    real="$(global readlink -f "$active" 2>/dev/null || true)"
    [ -n "$real" ] || real="$active"
    fs="$(stat -f -c %T "$active" 2>/dev/null || true)"
    partition="$(df -P "$active" 2>/dev/null | tail -n 1 | awk '{print $1}')"

    case "$prefix" in
        GMS)
            GMS_ACTIVE_CODE_PATH="$active"; GMS_ORIGINAL_SYSTEM_PATH="$original"
            GMS_TARGET_DIRECTORY="$target"; GMS_BASE_APK="$base"; GMS_SPLIT_APKS="$splits"
            GMS_HAS_DATA_UPDATE="$update"; GMS_BACKING_PARTITION="$partition"
            GMS_FILESYSTEM_TYPE="$fs"; GMS_RESOLVED_REAL_PATH="$real"; GMS_PATH="$active" ;;
        GSF)
            GSF_ACTIVE_CODE_PATH="$active"; GSF_ORIGINAL_SYSTEM_PATH="$original"
            GSF_TARGET_DIRECTORY="$target"; GSF_BASE_APK="$base"; GSF_SPLIT_APKS="$splits"
            GSF_HAS_DATA_UPDATE="$update"; GSF_BACKING_PARTITION="$partition"
            GSF_FILESYSTEM_TYPE="$fs"; GSF_RESOLVED_REAL_PATH="$real"; GSF_PATH="$active" ;;
        STORE)
            STORE_ACTIVE_CODE_PATH="$active"; STORE_ORIGINAL_SYSTEM_PATH="$original"
            STORE_TARGET_DIRECTORY="$target"; STORE_BASE_APK="$base"; STORE_SPLIT_APKS="$splits"
            STORE_HAS_DATA_UPDATE="$update"; STORE_BACKING_PARTITION="$partition"
            STORE_FILESYSTEM_TYPE="$fs"; STORE_RESOLVED_REAL_PATH="$real"; STORE_PATH="$active" ;;
    esac
}

mountinfo_root()
{
    # mountinfo: field 4 = root (bind mount source), field 5 = mountpoint
    awk -v m="$1" '$5 == m { print $4 }' "$MOUNTINFO" 2>/dev/null | head -n 1
}

mountinfo_count()
{
    awk -v m="$1" '$5 == m { count++ } END { print count+0 }' "$MOUNTINFO" 2>/dev/null
}

mountinfo_device_for_path()
{
    awk -v p="$1" '
        $5 != "" && (p == $5 || index(p, $5 "/") == 1) && length($5) > length(best) {
            best = $5
            device = $3
        }
        END { print device }
    ' "$MOUNTINFO" 2>/dev/null
}

fs_root_of()
{
    # fs_root_of <path> maps a bind mount source to mountinfo field 4 (root).
    # Field 4 is relative to the root of the filesystem containing the source.
    # Example: /data/local/tmp/x on a /data f2fs mount maps to /local/tmp/x.
    # If no mount point in mountinfo is a prefix (for example, in a host test
    # with fake mountinfo), the full path is retained.
    local path="$1"
    awk -v p="$path" '
        $5 != "" && (p == $5 || index(p, $5 "/") == 1) && length($5) > length(best) { best = $5 }
        END {
            if (best != "" && best != "/") print substr(p, length(best) + 1); else print p
        }
    ' "$MOUNTINFO" 2>/dev/null
}

is_mounted()
{
    [ -n "$(mountinfo_root "$1")" ]
}

path_under_target()
{
    local path="$1" target="$2"
    [ -n "$path" ] && [ -n "$target" ] || return 1
    case "$path" in
        "$target"/*) return 0 ;;
        *) return 1 ;;
    esac
}

is_masked_by_us()
{
    # is_masked_by_us <mountpoint> <mask> [apk]
    local mp="$1" mask="$2" apk="${3:-}"
    mount_source_matches_mask "$mp" "$mask" || return 1
    if [ -n "$apk" ]; then
        global test -f "$mp/$apk" 2>/dev/null || return 1
    else
        global test -d "$mp" 2>/dev/null || return 1
    fi
    return 0
}

is_masked_by_legacy()
{
    is_masked_by "$1" "$2" "${3:-}"
}

is_masked_by()
{
    # is_masked_by <mountpoint> <mask> [apk]
    local mp="$1" mask="$2" apk="${3:-}"
    mount_source_matches_mask "$mp" "$mask" || return 1
    if [ -n "$apk" ]; then
        global test -f "$mp/$apk" 2>/dev/null || return 1
    else
        global test -d "$mp" 2>/dev/null || return 1
    fi
    return 0
}

mount_source_matches_mask()
{
    # mount_source_matches_mask <mountpoint> <mask>
    #
    # mountinfo is the source of truth for classifying existing mounts.
    # Do not require the file to appear at the mountpoint: during a broken boot,
    # Package Manager may not have recreated the directories/files yet, and
    # recovery fixtures represent only the mount source.
    local mp="$1" mask="$2" root device mask_device
    [ "$(mountinfo_count "$mp")" = "1" ] || return 1
    root="$(mountinfo_root "$mp")"
    [ -n "$root" ] || return 1
    [ "$root" = "$(fs_root_of "$mask")" ] || return 1
    device="$(awk -v m="$mp" '$5 == m { print $3; exit }' "$MOUNTINFO" 2>/dev/null)"
    mask_device="$(mountinfo_device_for_path "$mask")"
    [ -n "$device" ] && [ "$device" = "$mask_device" ]
}

refresh_residue_facts()
{
    local base residue
    MASK_RESIDUE_PRESENT=0
    for p in "$MASK_BASE" "$LEGACY_MASK_BASE" \
        "$MASK_GMS/$GMS_APK" "$MASK_STORE/$STORE_APK" \
        "$LEGACY_MASK_GMS/$GMS_APK" "$LEGACY_MASK_STORE/FakeStore.apk"; do
        if [ -e "$p" ]; then
            MASK_RESIDUE_PRESENT=1
            return 0
        fi
    done
    for base in "$MASK_BASE" "$LEGACY_MASK_BASE"; do
        [ -d "$base" ] || continue
        residue="$(find "$base" -mindepth 1 -print -quit 2>/dev/null)" || continue
        if [ -n "$residue" ]; then
            MASK_RESIDUE_PRESENT=1
            return 0
        fi
    done
}

refresh_mount_facts()
{
    # Missing targets remain unknown. Recovery resolves the snapshot or a
    # verified legacy exception before checking mounts again.
    local mr
    MOUNT_GMS=0; MOUNT_GMS_IS_OURS=0; MOUNT_GMS_IS_LEGACY=0; MOUNT_GMS_IS_KNOWN=0; MOUNT_GMS_SOURCE=""
    mr="$(mountinfo_root "$TARGET_GMS")"
    if [ -n "$mr" ]; then
        MOUNT_GMS=1
        MOUNT_GMS_SOURCE="$mr"
        if mount_source_matches_mask "$TARGET_GMS" "$MASK_GMS"; then
            MOUNT_GMS_IS_OURS=1
            MOUNT_GMS_IS_KNOWN=1
        elif mount_source_matches_mask "$TARGET_GMS" "$LEGACY_MASK_GMS"; then
            MOUNT_GMS_IS_LEGACY=1
            MOUNT_GMS_IS_KNOWN=1
        fi
    fi

    MOUNT_GSF=0; MOUNT_GSF_IS_OURS=0; MOUNT_GSF_IS_LEGACY=0; MOUNT_GSF_IS_KNOWN=0
    mr="$(mountinfo_root "$TARGET_GSF")"
    if [ -n "$mr" ]; then
        MOUNT_GSF=1
        if mount_source_matches_mask "$TARGET_GSF" "$MASK_GSF"; then
            MOUNT_GSF_IS_OURS=1
            MOUNT_GSF_IS_KNOWN=1
        elif mount_source_matches_mask "$TARGET_GSF" "$LEGACY_MASK_GSF"; then
            MOUNT_GSF_IS_LEGACY=1
            MOUNT_GSF_IS_KNOWN=1
        fi
    fi

    MOUNT_STORE=0; MOUNT_STORE_IS_OURS=0; MOUNT_STORE_IS_LEGACY=0; MOUNT_STORE_IS_KNOWN=0
    mr="$(mountinfo_root "$TARGET_STORE")"
    if [ -n "$mr" ]; then
        MOUNT_STORE=1
        if mount_source_matches_mask "$TARGET_STORE" "$MASK_STORE"; then
            MOUNT_STORE_IS_OURS=1
            MOUNT_STORE_IS_KNOWN=1
        elif mount_source_matches_mask "$TARGET_STORE" "$LEGACY_MASK_STORE"; then
            MOUNT_STORE_IS_LEGACY=1
            MOUNT_STORE_IS_KNOWN=1
        fi
    fi
}

is_apk()
{
    # checks only magic bytes (PK\x03\x04); the app performs full validation
    local f="$1" magic
    [ -s "$f" ] || return 1
    magic="$(od -An -tx1 -N4 "$f" 2>/dev/null | tr -d ' \n')"
    [ "$magic" = "504b0304" ]
}

detect_root_manager()
{
    if [ -e /data/adb/ksud ] || [ -d /data/adb/ksu ]; then
        ROOT_MANAGER="KernelSU"
    elif [ -d /data/adb/magisk ] || [ -e /data/adb/magisk ]; then
        ROOT_MANAGER="Magisk"
    elif [ -e /data/adb/apd ] || [ -d /data/adb/ap ]; then
        ROOT_MANAGER="APatch"
    else
        ROOT_MANAGER="unknown"
    fi
}

gms_uid_current()
{
    # Derive the current UID; never assume the old one.
    local u
    u="$(stat -c %u "$GMS_DATA_USER0" 2>/dev/null)"
    case "$u" in
        ''|*[!0-9]*) ;;
        *) echo "$u"; return 0 ;;
    esac
    u="$(pm list packages -U 2>/dev/null | sed -n "s/^package:${GMS_PKG} uid=\([0-9][0-9]*\).*/\1/p" | head -n 1)"
    case "$u" in
        ''|*[!0-9]*) ;;
        *) echo "$u"; return 0 ;;
    esac
    u="$GMS_DUMP_UID"
    [ -n "$u" ] || return 1
    echo "$u"
}

read_gms_details()
{
    # Parse the cached Package Manager response once for all GMS details and
    # signature permission evidence used later in this probe.
    local details
    details="$(awk '
        /versionName=/ && version == "" { if (match($0, /versionName=[^ ]+/)) version=substr($0, RSTART+12, RLENGTH-12) }
        /versionCode=/ && code == "" { if (match($0, /versionCode=[0-9]+/)) code=substr($0, RSTART+12, RLENGTH-12) }
        /pkgFlags=/ && pkg == "" { if (match($0, /pkgFlags=\[[^]]*\]/)) { pkg=substr($0, RSTART+10, RLENGTH-11) } }
        /privateFlags=/ && priv == "" { if (match($0, /privateFlags=\[[^]]*\]/)) { priv=substr($0, RSTART+14, RLENGTH-15) } }
        /appId=[0-9]+/ && uid == "" { if (match($0, /appId=[0-9]+/)) uid=substr($0, RSTART+6, RLENGTH-6) }
        /userId=[0-9]+/ && uid == "" { if (match($0, /userId=[0-9]+/)) uid=substr($0, RSTART+7, RLENGTH-7) }
        {
            line=tolower($0)
            if (index(line, "fake_package_signature")) {
                mentioned=1
                if (line ~ /granted[=:]true/ || line ~ / granted/) granted=1
            }
        }
        END { printf "%s\n%s\n%s%s%s\n%s\n%d\n%d\n", version, code, pkg, (pkg != "" && priv != "" ? " " : ""), priv, uid, granted, mentioned }
    ' <<EOF
$GMS_PACKAGE_DUMP
EOF
    )"
    {
        IFS= read -r GMS_VERSION
        IFS= read -r GMS_VERSION_CODE
        IFS= read -r GMS_FLAGS
        IFS= read -r GMS_DUMP_UID
        IFS= read -r GMS_SIGNATURE_GRANTED
        IFS= read -r GMS_SIGNATURE_MENTIONED
    } <<EOF
$details
EOF
}

# The legacy profile remains only an operational reference for the current
# flow; new compatibility is determined by the Kotlin engine and capabilities.
profile_check()
{
    local man model
    man="$(getprop ro.product.manufacturer 2>/dev/null | tr 'A-Z' 'a-z')"
    [ "$man" = "$PROFILE_MANUFACTURER" ] || return 1
    model="$(getprop ro.product.model 2>/dev/null)"
    # Literal patterns: in case, '|' from a variable is NOT an alternative.
    case "$model" in
        SM-S928B|SM-S928U|SM-S928W|SM-S928N|SM-S9280) return 0 ;;
        *) return 1 ;;
    esac
}

# Package Manager may expose only the update in /data/app and hide the original
# stock directory. GMS and Play Store have explicit cleanup in this flow; GSF
# does not, so it is never considered preparable by this rule.
preparable_system_update()
{
    local prefix="$1" active update expected
    case "$prefix" in
        GMS)
            active="$GMS_ACTIVE_CODE_PATH"; update="$GMS_HAS_DATA_UPDATE"; expected="$GMS_ORIGINAL_SYSTEM_PATH" ;;
        STORE)
            active="$STORE_ACTIVE_CODE_PATH"; update="$STORE_HAS_DATA_UPDATE"; expected="$STORE_ORIGINAL_SYSTEM_PATH" ;;
        *) return 1 ;;
    esac
    [ "$update" = "1" ] || return 1
    printf '%s' "$active" | grep -q '^/data/app/' || return 1
    [ -n "$expected" ] && is_safe_target_directory "$(dirname "$expected")"
}

cap_set()
{
    # The key is always literal below; values are treated as
    # data and are never passed to eval.
    local key="$1" status="$2" evidence="${3:-}" reason="${4:-}"
    case "$key" in
        ROOT) CAP_ROOT_STATUS="$status"; CAP_ROOT_EVIDENCE="$evidence"; CAP_ROOT_REASON="$reason" ;;
        SAMSUNG_DEVICE) CAP_SAMSUNG_DEVICE_STATUS="$status"; CAP_SAMSUNG_DEVICE_EVIDENCE="$evidence"; CAP_SAMSUNG_DEVICE_REASON="$reason" ;;
        GLOBAL_MOUNT_NAMESPACE) CAP_GLOBAL_MOUNT_NAMESPACE_STATUS="$status"; CAP_GLOBAL_MOUNT_NAMESPACE_EVIDENCE="$evidence"; CAP_GLOBAL_MOUNT_NAMESPACE_REASON="$reason" ;;
        BIND_MOUNT) CAP_BIND_MOUNT_STATUS="$status"; CAP_BIND_MOUNT_EVIDENCE="$evidence"; CAP_BIND_MOUNT_REASON="$reason" ;;
        SYSTEM_GMS_FOUND) CAP_SYSTEM_GMS_FOUND_STATUS="$status"; CAP_SYSTEM_GMS_FOUND_EVIDENCE="$evidence"; CAP_SYSTEM_GMS_FOUND_REASON="$reason" ;;
        SYSTEM_GSF_FOUND) CAP_SYSTEM_GSF_FOUND_STATUS="$status"; CAP_SYSTEM_GSF_FOUND_EVIDENCE="$evidence"; CAP_SYSTEM_GSF_FOUND_REASON="$reason" ;;
        SYSTEM_STORE_FOUND) CAP_SYSTEM_STORE_FOUND_STATUS="$status"; CAP_SYSTEM_STORE_FOUND_EVIDENCE="$evidence"; CAP_SYSTEM_STORE_FOUND_REASON="$reason" ;;
        GMS_MASKABLE) CAP_GMS_MASKABLE_STATUS="$status"; CAP_GMS_MASKABLE_EVIDENCE="$evidence"; CAP_GMS_MASKABLE_REASON="$reason" ;;
        GSF_MASKABLE) CAP_GSF_MASKABLE_STATUS="$status"; CAP_GSF_MASKABLE_EVIDENCE="$evidence"; CAP_GSF_MASKABLE_REASON="$reason" ;;
        STORE_MASKABLE) CAP_STORE_MASKABLE_STATUS="$status"; CAP_STORE_MASKABLE_EVIDENCE="$evidence"; CAP_STORE_MASKABLE_REASON="$reason" ;;
        SELINUX_ENFORCING) CAP_SELINUX_ENFORCING_STATUS="$status"; CAP_SELINUX_ENFORCING_EVIDENCE="$evidence"; CAP_SELINUX_ENFORCING_REASON="$reason" ;;
        SELINUX_CONTEXT_CLONABLE) CAP_SELINUX_CONTEXT_CLONABLE_STATUS="$status"; CAP_SELINUX_CONTEXT_CLONABLE_EVIDENCE="$evidence"; CAP_SELINUX_CONTEXT_CLONABLE_REASON="$reason" ;;
        SIGNATURE_SPOOFING) CAP_SIGNATURE_SPOOFING_STATUS="$status"; CAP_SIGNATURE_SPOOFING_EVIDENCE="$evidence"; CAP_SIGNATURE_SPOOFING_REASON="$reason" ;;
        PACKAGE_MANAGER_CACHE_ACCESS) CAP_PACKAGE_MANAGER_CACHE_ACCESS_STATUS="$status"; CAP_PACKAGE_MANAGER_CACHE_ACCESS_EVIDENCE="$evidence"; CAP_PACKAGE_MANAGER_CACHE_ACCESS_REASON="$reason" ;;
        PRIV_APP_COMPATIBLE) CAP_PRIV_APP_COMPATIBLE_STATUS="$status"; CAP_PRIV_APP_COMPATIBLE_EVIDENCE="$evidence"; CAP_PRIV_APP_COMPATIBLE_REASON="$reason" ;;
        SAFE_SOFT_REBOOT) CAP_SAFE_SOFT_REBOOT_STATUS="$status"; CAP_SAFE_SOFT_REBOOT_EVIDENCE="$evidence"; CAP_SAFE_SOFT_REBOOT_REASON="$reason" ;;
        SAFE_RESTORE) CAP_SAFE_RESTORE_STATUS="$status"; CAP_SAFE_RESTORE_EVIDENCE="$evidence"; CAP_SAFE_RESTORE_REASON="$reason" ;;
    esac
}

cap_emit()
{
    local key="$1" status evidence reason
    case "$key" in
        ROOT) status="$CAP_ROOT_STATUS"; evidence="$CAP_ROOT_EVIDENCE"; reason="$CAP_ROOT_REASON" ;;
        SAMSUNG_DEVICE) status="$CAP_SAMSUNG_DEVICE_STATUS"; evidence="$CAP_SAMSUNG_DEVICE_EVIDENCE"; reason="$CAP_SAMSUNG_DEVICE_REASON" ;;
        GLOBAL_MOUNT_NAMESPACE) status="$CAP_GLOBAL_MOUNT_NAMESPACE_STATUS"; evidence="$CAP_GLOBAL_MOUNT_NAMESPACE_EVIDENCE"; reason="$CAP_GLOBAL_MOUNT_NAMESPACE_REASON" ;;
        BIND_MOUNT) status="$CAP_BIND_MOUNT_STATUS"; evidence="$CAP_BIND_MOUNT_EVIDENCE"; reason="$CAP_BIND_MOUNT_REASON" ;;
        SYSTEM_GMS_FOUND) status="$CAP_SYSTEM_GMS_FOUND_STATUS"; evidence="$CAP_SYSTEM_GMS_FOUND_EVIDENCE"; reason="$CAP_SYSTEM_GMS_FOUND_REASON" ;;
        SYSTEM_GSF_FOUND) status="$CAP_SYSTEM_GSF_FOUND_STATUS"; evidence="$CAP_SYSTEM_GSF_FOUND_EVIDENCE"; reason="$CAP_SYSTEM_GSF_FOUND_REASON" ;;
        SYSTEM_STORE_FOUND) status="$CAP_SYSTEM_STORE_FOUND_STATUS"; evidence="$CAP_SYSTEM_STORE_FOUND_EVIDENCE"; reason="$CAP_SYSTEM_STORE_FOUND_REASON" ;;
        GMS_MASKABLE) status="$CAP_GMS_MASKABLE_STATUS"; evidence="$CAP_GMS_MASKABLE_EVIDENCE"; reason="$CAP_GMS_MASKABLE_REASON" ;;
        GSF_MASKABLE) status="$CAP_GSF_MASKABLE_STATUS"; evidence="$CAP_GSF_MASKABLE_EVIDENCE"; reason="$CAP_GSF_MASKABLE_REASON" ;;
        STORE_MASKABLE) status="$CAP_STORE_MASKABLE_STATUS"; evidence="$CAP_STORE_MASKABLE_EVIDENCE"; reason="$CAP_STORE_MASKABLE_REASON" ;;
        SELINUX_ENFORCING) status="$CAP_SELINUX_ENFORCING_STATUS"; evidence="$CAP_SELINUX_ENFORCING_EVIDENCE"; reason="$CAP_SELINUX_ENFORCING_REASON" ;;
        SELINUX_CONTEXT_CLONABLE) status="$CAP_SELINUX_CONTEXT_CLONABLE_STATUS"; evidence="$CAP_SELINUX_CONTEXT_CLONABLE_EVIDENCE"; reason="$CAP_SELINUX_CONTEXT_CLONABLE_REASON" ;;
        SIGNATURE_SPOOFING) status="$CAP_SIGNATURE_SPOOFING_STATUS"; evidence="$CAP_SIGNATURE_SPOOFING_EVIDENCE"; reason="$CAP_SIGNATURE_SPOOFING_REASON" ;;
        PACKAGE_MANAGER_CACHE_ACCESS) status="$CAP_PACKAGE_MANAGER_CACHE_ACCESS_STATUS"; evidence="$CAP_PACKAGE_MANAGER_CACHE_ACCESS_EVIDENCE"; reason="$CAP_PACKAGE_MANAGER_CACHE_ACCESS_REASON" ;;
        PRIV_APP_COMPATIBLE) status="$CAP_PRIV_APP_COMPATIBLE_STATUS"; evidence="$CAP_PRIV_APP_COMPATIBLE_EVIDENCE"; reason="$CAP_PRIV_APP_COMPATIBLE_REASON" ;;
        SAFE_SOFT_REBOOT) status="$CAP_SAFE_SOFT_REBOOT_STATUS"; evidence="$CAP_SAFE_SOFT_REBOOT_EVIDENCE"; reason="$CAP_SAFE_SOFT_REBOOT_REASON" ;;
        SAFE_RESTORE) status="$CAP_SAFE_RESTORE_STATUS"; evidence="$CAP_SAFE_RESTORE_EVIDENCE"; reason="$CAP_SAFE_RESTORE_REASON" ;;
    esac
    emit "CAP_${key}_STATUS" "$status"
    emit "CAP_${key}_EVIDENCE" "$evidence"
    emit "CAP_${key}_REASON" "$reason"
}

emit_capability_progress()
{
    local key="$1" check="$2" status
    case "$key" in
        ROOT) status="$CAP_ROOT_STATUS" ;;
        GLOBAL_MOUNT_NAMESPACE) status="$CAP_GLOBAL_MOUNT_NAMESPACE_STATUS" ;;
        BIND_MOUNT) status="$CAP_BIND_MOUNT_STATUS" ;;
        SELINUX_CONTEXT_CLONABLE) status="$CAP_SELINUX_CONTEXT_CLONABLE_STATUS" ;;
        SIGNATURE_SPOOFING) status="$CAP_SIGNATURE_SPOOFING_STATUS" ;;
    esac
    case "$status" in UNKNOWN) emit_progress "$check" FAILED ;; *) emit_progress "$check" CONFIRMED ;; esac
}

probe_global_mount_namespace()
{
    local evidence
    if global true >/dev/null 2>&1 && evidence="$(mount_namespace_identity)"; then
        cap_set GLOBAL_MOUNT_NAMESPACE PASS "$evidence" "nsenter validated against /proc/1/ns/mnt"
    else
        cap_set GLOBAL_MOUNT_NAMESPACE FAIL "" "PID 1 global namespace is inaccessible or identity could not be confirmed"
    fi
}

probe_bind_mount()
{
    local base="${DEGOOGLE_CAP_TEST_BASE:-${MASK_BASE%/*}/degoogle-capability-test.$$}"
    local source="$base/source" target="$base/target" value
    unmount_capability_test()
    {
        if global_umount "$target" >/dev/null 2>&1; then
            :
        elif ! global_umount -l "$target" >/dev/null 2>&1; then
            return 1
        fi
        ! is_mounted "$target"
    }
    rm -rf "$base" 2>/dev/null || true
    if ! mkdir -p "$source" "$target"; then
        cap_set BIND_MOUNT FAIL "" "could not create temporary directory"
        return
    fi
    if ! printf 'degoogle-capability' > "$source/value"; then
        rm -rf "$base" 2>/dev/null || true
        cap_set BIND_MOUNT FAIL "" "could not write temporary source"
        return
    fi
    if ! global_mount --bind "$source" "$target" 2>/dev/null; then
        rm -rf "$base" 2>/dev/null || true
        cap_set BIND_MOUNT FAIL "" "mount --bind global failed"
        return
    fi
    value="$(global cat "$target/value" 2>/dev/null || true)"
    if [ "$value" != "degoogle-capability" ] || ! is_mounted "$target"; then
        if unmount_capability_test; then
            rm -rf "$base" 2>/dev/null || true
            cap_set BIND_MOUNT FAIL "mount created, but read/mountinfo did not confirm the target"
        else
            cap_set BIND_MOUNT FAIL "$target" "capability mount was created but could not be safely unmounted"
        fi
        return
    fi
    if ! unmount_capability_test; then
        rm -rf "$base" 2>/dev/null || true
        cap_set BIND_MOUNT FAIL "temporary bind mount could not be removed"
        return
    fi
    rm -rf "$base" 2>/dev/null || true
    cap_set BIND_MOUNT PASS "temporary global bind mount created, read, and unmounted"
}

probe_selinux_context()
{
    local base="${DEGOOGLE_CAP_TEST_BASE:-${MASK_BASE%/*}/degoogle-selinux-test.$$}"
    local original masked component target owner mode actual_owner actual_mode
    case "$SELINUX" in Enforcing|Permissive) ;; *)
        cap_set SELINUX_CONTEXT_CLONABLE FAIL "" "SELinux is not Enforcing"
        return
        ;; esac
    for component in GMS GSF STORE; do
        case "$component" in
            GMS) target="$TARGET_GMS" ;;
            GSF) target="$TARGET_GSF" ;;
            STORE) target="$TARGET_STORE" ;;
        esac
        [ -n "$target" ] || {
            cap_set SELINUX_CONTEXT_CLONABLE UNKNOWN "" "$component target could not be resolved"
            return
        }
        original="$(global ls -Zd "$target" 2>/dev/null | awk '{print $1}')"
        owner="$(global stat -c %u:%g "$target" 2>/dev/null)"
        mode="$(global stat -c %a "$target" 2>/dev/null)"
        [ -n "$original" ] || {
            cap_set SELINUX_CONTEXT_CLONABLE UNKNOWN "" "could not read SELinux context for $component target"
            return
        }
        mkdir -p "$base" 2>/dev/null || {
            cap_set SELINUX_CONTEXT_CLONABLE FAIL "" "could not create test target"
            return
        }
        if ! global chcon -R "$original" "$base" >/dev/null 2>&1 && \
            ! global restorecon -R "$base" >/dev/null 2>&1; then
            rm -rf "$base" 2>/dev/null || true
            cap_set SELINUX_CONTEXT_CLONABLE FAIL "" "could not reproduce SELinux context for $component"
            return
        fi
        if ! global chown "$owner" "$base" >/dev/null 2>&1 || \
            ! global chmod "$mode" "$base" >/dev/null 2>&1; then
            rm -rf "$base" 2>/dev/null || true
            cap_set SELINUX_CONTEXT_CLONABLE FAIL "" "could not reproduce owner/mode for $component"
            return
        fi
        masked="$(global ls -Zd "$base" 2>/dev/null | awk '{print $1}')"
        actual_owner="$(global stat -c %u:%g "$base" 2>/dev/null)"
        actual_mode="$(global stat -c %a "$base" 2>/dev/null)"
        rm -rf "$base" 2>/dev/null || true
        [ "$original" = "$masked" ] && [ "$owner" = "$actual_owner" ] && [ "$mode" = "$actual_mode" ] || {
            cap_set SELINUX_CONTEXT_CLONABLE FAIL "source=$owner:$mode:$original; target=$actual_owner:$actual_mode:$masked" "could not exactly reproduce metadata for $component"
            return
        }
    done
    cap_set SELINUX_CONTEXT_CLONABLE PASS "owner, mode, and SELinux context reproducible for GMS, GSF, and Store"
}

probe_signature_spoofing()
{
    local check fake_path fake_version fake_state fake_disabled
    # Minimum functional evidence: PM must report the special permission
    # granted to GMS itself. FakeGApps/module presence alone is not accepted.
    check=""
    # `pm check-permission` is missing on several recent Android versions;
    # call it only when the command's own help confirms the subcommand exists.
    if pm help 2>/dev/null | grep -q 'check-permission'; then
        check="$(pm check-permission "$GMS_PKG" android.permission.FAKE_PACKAGE_SIGNATURE 0 2>/dev/null || true)"
    fi
    if printf '%s\n' "$check" | grep -Eiq 'granted|(^|[^0-9])1([^0-9]|$)' || \
        [ "$GMS_SIGNATURE_GRANTED" = "1" ]; then
        cap_set SIGNATURE_SPOOFING PASS "dumpsys package reports FAKE_PACKAGE_SIGNATURE granted to GMS"
    elif [ "$GMS_SIGNATURE_MENTIONED" = "1" ]; then
        cap_set SIGNATURE_SPOOFING WARN "special permission is mentioned, but the grant was not confirmed"
    elif fake_path="$(pm path "$FAKEGAPPS_PKG" 2>/dev/null | sed -n 's/^package://p' | head -n 1)" && [ -n "$fake_path" ]; then
        fake_version="$(dumpsys package "$FAKEGAPPS_PKG" 2>/dev/null | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -n 1)"
        fake_state="$(dumpsys package "$FAKEGAPPS_PKG" 2>/dev/null | grep -m 1 'User 0:.*enabled=' || true)"
        fake_disabled=0
        # COMPONENT_ENABLED_STATE_DEFAULT is 0; do not confuse the default
        # state with a disabled package. The -d list is the most reliable source
        # for the user's effective state, with a fallback to explicit states.
        # dumpsys (DISABLED, DISABLED_USER, and DISABLED_UNTIL_USED).
        if pm list packages -d --user 0 2>/dev/null | grep -q "^package:${FAKEGAPPS_PKG}$" || \
            printf '%s' "$fake_state" | grep -Eq 'enabled=(2|3|4|5)([[:space:]]|$)'; then
            fake_disabled=1
        fi
        if [ "$fake_disabled" = "1" ]; then
            cap_set SIGNATURE_SPOOFING WARN \
                "FakeGApps ${fake_version:-detected} installed but disabled for the user; functional validation pending" \
                "package=$fake_path; framework=$( [ -f "$LSPOSED_MARKER" ] && echo LSPosed || echo UNKNOWN )"
        elif [ -f "$LSPOSED_MARKER" ]; then
            cap_set SIGNATURE_SPOOFING WARN \
                "FakeGApps ${fake_version:-detected} + LSPosed detected; functional validation pending" \
                "package=$fake_path; marker=$LSPOSED_MARKER"
        else
            cap_set SIGNATURE_SPOOFING WARN \
                "FakeGApps ${fake_version:-detected} detected, but the hook framework was not confirmed" \
                "package=$fake_path"
        fi
    else
        cap_set SIGNATURE_SPOOFING UNKNOWN "" "no functional evidence of signature spoofing"
    fi
}

probe_package_manager_cache()
{
    if [ ! -e "$PM_CACHE_DIR" ]; then
        cap_set PACKAGE_MANAGER_CACHE_ACCESS PASS "cache is absent; invalidation is not required"
        return
    fi
    if [ ! -d "$PM_CACHE_DIR" ]; then
        cap_set PACKAGE_MANAGER_CACHE_ACCESS FAIL "$PM_CACHE_DIR" "cache exists but is not an inspectable directory"
        return
    fi
    [ -r "$PM_CACHE_DIR" ] && [ -w "$PM_CACHE_DIR" ] && [ -x "$PM_CACHE_DIR" ] && \
        { printf '' > "$PM_CACHE_DIR/.degoogle-capability-test.$$"; } 2>/dev/null && \
        rm -f "$PM_CACHE_DIR/.degoogle-capability-test.$$" 2>/dev/null && \
        cap_set PACKAGE_MANAGER_CACHE_ACCESS PASS "readable and writable cache: $PM_CACHE_DIR" && return
    rm -f "$PM_CACHE_DIR/.degoogle-capability-test.$$" 2>/dev/null || true
    cap_set PACKAGE_MANAGER_CACHE_ACCESS FAIL "$PM_CACHE_DIR" "cache exists but cannot be inspected or modified"
}

probe_safe_restore()
{
    local test_file="$TRANSACTION_BASE/.capability-test.$$"
    if ! mkdir -p "$TRANSACTION_BASE" 2>/dev/null || ! { printf '' > "$test_file"; } 2>/dev/null; then
        cap_set SAFE_RESTORE FAIL "" "persistent rollback directory is not writable"
        return
    fi
    rm -f "$test_file" 2>/dev/null || {
        cap_set SAFE_RESTORE FAIL "" "rollback cleanup test failed"
        return
    }
    cap_set SAFE_RESTORE PASS "persistent snapshot/journal can be created at $TRANSACTION_BASE"
}

framework_identity()
{
    local boot_id server_pid server_start
    if [ "${DEGOOGLE_TEST_MODE:-0}" = "1" ]; then
        boot_id="${DEGOOGLE_TEST_BOOT_ID:-test-boot-id}"
        server_pid="${DEGOOGLE_TEST_SYSTEM_SERVER_PID:-100}"
        server_start="${DEGOOGLE_TEST_SYSTEM_SERVER_START:-1000}"
    else
        boot_id="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || true)"
        server_pid="$(pidof system_server 2>/dev/null || true)"
        [ -n "$server_pid" ] || server_pid="$(ps -A 2>/dev/null | awk '$NF == "system_server" {print $1; exit}')"
        case "$server_pid" in *' '*) server_pid="${server_pid%% *}" ;; esac
        server_start="$(sed 's/^.*) //' "/proc/$server_pid/stat" 2>/dev/null | awk '{print $20}')"
    fi
    [ -n "$boot_id" ] && [ -n "$server_pid" ] && [ -n "$server_start" ] || return 1
    printf '%s\n%s\n%s' "$boot_id" "$server_pid" "$server_start"
}

stock_reindex_ready()
{
    local transaction_state old_boot old_pid old_start identity new_boot new_pid new_start
    transaction_state="$(sed -n 's/^state=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)"
    case "$transaction_state" in REINDEX_PENDING|REBOOT_REQUESTED) ;; *) return 1 ;; esac
    [ -f "$REBOOT_BASELINE_FILE" ] || return 1
    old_boot="$(sed -n 's/^kernelBootId=//p' "$REBOOT_BASELINE_FILE" | head -n 1)"
    old_pid="$(sed -n 's/^systemServerPid=//p' "$REBOOT_BASELINE_FILE" | head -n 1)"
    old_start="$(sed -n 's/^systemServerStart=//p' "$REBOOT_BASELINE_FILE" | head -n 1)"
    identity="$(framework_identity)" || return 1
    new_boot="$(printf '%s\n' "$identity" | sed -n '1p')"
    new_pid="$(printf '%s\n' "$identity" | sed -n '2p')"
    new_start="$(printf '%s\n' "$identity" | sed -n '3p')"
    [ -n "$old_boot" ] && [ -n "$old_pid" ] && [ -n "$old_start" ] || return 1
    [ "$new_boot" = "$old_boot" ] && [ "$new_pid:$new_start" != "$old_pid:$old_start" ] || return 1
    [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ] || return 1
    case "$(getprop sys.boot.reason 2>/dev/null)" in *rescueparty*|*rescue_party*) return 1 ;; esac
    return 0
}

persist_reboot_baseline()
{
    local identity="$1" tmp
    tmp="$REBOOT_BASELINE_FILE.tmp.$$"
    mkdir -p "$TRANSACTION_BASE" 2>/dev/null || return 1
    {
        printf 'kernelBootId=%s\n' "$(printf '%s\n' "$identity" | sed -n '1p')"
        printf 'systemServerPid=%s\n' "$(printf '%s\n' "$identity" | sed -n '2p')"
        printf 'systemServerStart=%s\n' "$(printf '%s\n' "$identity" | sed -n '3p')"
    } > "$tmp" || return 1
    mv "$tmp" "$REBOOT_BASELINE_FILE" || return 1
    [ "$(sed -n 's/^kernelBootId=//p' "$REBOOT_BASELINE_FILE")" = "$(printf '%s\n' "$identity" | sed -n '1p')" ] && \
        [ "$(sed -n 's/^systemServerPid=//p' "$REBOOT_BASELINE_FILE")" = "$(printf '%s\n' "$identity" | sed -n '2p')" ] && \
        [ "$(sed -n 's/^systemServerStart=//p' "$REBOOT_BASELINE_FILE")" = "$(printf '%s\n' "$identity" | sed -n '3p')" ]
}

probe_reboot_strategy()
{
    REBOOT_STRATEGY_BACKEND="$ROOT_MANAGER"
    REBOOT_STRATEGY_METHOD=""
    REBOOT_STRATEGY_CONFIDENCE="UNKNOWN"
    REBOOT_STRATEGY_FIRMWARE_VALIDATED=0
    if [ "$(getprop init.userspace_reboot.is_supported 2>/dev/null)" = "1" ] && command -v setprop >/dev/null 2>&1; then
        REBOOT_STRATEGY_METHOD="SYS_POWERCTL_USERSPACE"
        REBOOT_STRATEGY_CONFIDENCE="VERIFIED"
        REBOOT_STRATEGY_FIRMWARE_VALIDATED=1
        cap_set SAFE_SOFT_REBOOT PASS "init.userspace_reboot.is_supported=1; selected AOSP userspace method"
    elif [ -x "$KSUD_PATH" ]; then
        local ksud_help
        ksud_help="$("$KSUD_PATH" --help 2>&1; "$KSUD_PATH" reboot --help 2>&1)"
        REBOOT_STRATEGY_METHOD="KSUD_SOFT_REBOOT"
        if printf '%s' "$ksud_help" | grep -Eiq 'soft[-_ ]?reboot|userspace'; then
            REBOOT_STRATEGY_CONFIDENCE="VERIFIED"
            REBOOT_STRATEGY_FIRMWARE_VALIDATED=1
            cap_set SAFE_SOFT_REBOOT PASS "KernelSU userspace reboot command is advertised" "$KSUD_PATH"
        else
            REBOOT_STRATEGY_CONFIDENCE="LOW"
            cap_set SAFE_SOFT_REBOOT WARN "KernelSU soft reboot is present but not verified by help output" "$KSUD_PATH"
        fi
    elif command -v setprop >/dev/null 2>&1 && [ "${DEGOOGLE_ALLOW_ZYGOTE_RESTART:-0}" = "1" ]; then
        REBOOT_STRATEGY_METHOD="ZYGOTE_RESTART"
        REBOOT_STRATEGY_CONFIDENCE="LOW"
        REBOOT_STRATEGY_FIRMWARE_VALIDATED=1
        cap_set SAFE_SOFT_REBOOT WARN "Zygote restart selected by explicit opt-in; kernel reboot is not used" "DEGOOGLE_ALLOW_ZYGOTE_RESTART=1"
    elif command -v setprop >/dev/null 2>&1 || { command -v stop >/dev/null 2>&1 && command -v start >/dev/null 2>&1; }; then
        REBOOT_STRATEGY_METHOD="ZYGOTE_RESTART"
        REBOOT_STRATEGY_CONFIDENCE="LOW"
        cap_set SAFE_SOFT_REBOOT WARN "Zygote restart is available but requires explicit opt-in" "set DEGOOGLE_ALLOW_ZYGOTE_RESTART=1"
    else
        cap_set SAFE_SOFT_REBOOT UNKNOWN "" "no supported userspace restart mechanism was confirmed"
    fi
}

probe_capabilities()
{
    UPDATE_CLEANUP_INFO=""
    if [ "$ROOT_OK" = "1" ]; then
        cap_set ROOT PASS "id -u = 0; backend=${ROOT_MANAGER:-unknown}"
    else
        cap_set ROOT FAIL "" "id -u != 0"
    fi
    if [ -z "$MANUFACTURER" ]; then
        cap_set SAMSUNG_DEVICE UNKNOWN "" "manufacturer missing"
    elif printf '%s' "$MANUFACTURER" | tr 'A-Z' 'a-z' | grep -qx samsung; then
        cap_set SAMSUNG_DEVICE PASS "ro.product.manufacturer=$MANUFACTURER"
    else
        cap_set SAMSUNG_DEVICE FAIL "$MANUFACTURER" "manufacturer is not Samsung"
    fi

    if [ "$MOUNT_GMS_IS_OURS" = "1" ]; then
        cap_set SYSTEM_GMS_FOUND PASS "${GMS_PATH:-$TARGET_GMS}" "GMS masked via microG"
    elif [ -n "$GMS_PATH" ]; then
        cap_set SYSTEM_GMS_FOUND PASS "$GMS_PATH"
    else
        cap_set SYSTEM_GMS_FOUND FAIL "" "GMS missing"
    fi

    if [ "$MOUNT_GSF_IS_OURS" = "1" ]; then
        cap_set SYSTEM_GSF_FOUND PASS "${TARGET_GSF:-$PROFILE_GSF}" "GSF masked (empty)"
    elif [ -n "$GSF_PATH" ]; then
        cap_set SYSTEM_GSF_FOUND PASS "$GSF_PATH"
    else
        cap_set SYSTEM_GSF_FOUND FAIL "" "GSF missing"
    fi

    if [ "$MOUNT_STORE_IS_OURS" = "1" ]; then
        cap_set SYSTEM_STORE_FOUND PASS "${STORE_PATH:-$TARGET_STORE}" "Store masked with Companion"
    elif [ -n "$STORE_PATH" ]; then
        cap_set SYSTEM_STORE_FOUND PASS "$STORE_PATH"
    else
        cap_set SYSTEM_STORE_FOUND FAIL "" "Store missing"
    fi

    case "$SELINUX" in
        Enforcing) cap_set SELINUX_ENFORCING PASS "getenforce=Enforcing" ;;
        Permissive|Disabled)
            if [ "${DEGOOGLE_STRICT_SELINUX_POLICY:-0}" = "1" ]; then
                cap_set SELINUX_ENFORCING FAIL "getenforce=$SELINUX" "strict SELinux policy requires Enforcing"
            else
                cap_set SELINUX_ENFORCING WARN "getenforce=$SELINUX" "SELinux is not enforcing; policy was not changed"
            fi
            ;;
        *) cap_set SELINUX_ENFORCING UNKNOWN "" "SELinux state is missing or unrecognized" ;;
    esac

    probe_global_mount_namespace
    emit_capability_progress GLOBAL_MOUNT_NAMESPACE mount_namespace
    probe_bind_mount
    emit_capability_progress BIND_MOUNT bind_mount
    probe_selinux_context
    emit_capability_progress SELINUX_CONTEXT_CLONABLE selinux_context
    if [ "${DEGOOGLE_MODE:-$OPERATION_MODE}" = "PURE" ]; then
        cap_set SIGNATURE_SPOOFING UNKNOWN "" "not applicable to pure DeGoogle mode"
        emit_progress signature_spoofing CONFIRMED
    else
        probe_signature_spoofing
        emit_capability_progress SIGNATURE_SPOOFING signature_spoofing
    fi
    probe_package_manager_cache
    probe_reboot_strategy
    probe_safe_restore

    local p original target update active expected
    for p in GMS GSF STORE; do
        case "$p" in
            GMS)
                original="$GMS_ORIGINAL_SYSTEM_PATH"; target="$GMS_TARGET_DIRECTORY"; update="$GMS_HAS_DATA_UPDATE"
                active="$GMS_ACTIVE_CODE_PATH"; expected="$PROFILE_GMS" ;;
            GSF)
                original="$GSF_ORIGINAL_SYSTEM_PATH"; target="$GSF_TARGET_DIRECTORY"; update="$GSF_HAS_DATA_UPDATE"
                active="$GSF_ACTIVE_CODE_PATH"; expected="$PROFILE_GSF" ;;
            STORE)
                original="$STORE_ORIGINAL_SYSTEM_PATH"; target="$STORE_TARGET_DIRECTORY"; update="$STORE_HAS_DATA_UPDATE"
                active="$STORE_ACTIVE_CODE_PATH"; expected="$PROFILE_STORE" ;;
        esac
        if [ "$p" = "GMS" ] && [ "$MOUNT_GMS_IS_OURS" = "1" ]; then
            cap_set GMS_MASKABLE PASS "target=${TARGET_GMS:-$PROFILE_GMS}; microG mask active"
        elif [ "$p" = "GSF" ] && [ "$MOUNT_GSF_IS_OURS" = "1" ]; then
            cap_set GSF_MASKABLE PASS "target=${TARGET_GSF:-$PROFILE_GSF}; empty mask active"
        elif [ "$p" = "STORE" ] && [ "$MOUNT_STORE_IS_OURS" = "1" ]; then
            cap_set STORE_MASKABLE PASS "target=${TARGET_STORE:-$PROFILE_STORE}; companion mask active"
        elif preparable_system_update "$p"; then
            cap_set "${p}_MASKABLE" PASS \
                "active=$active; stock target=$expected; cleanup removes the update before masking"
            if [ -n "$UPDATE_CLEANUP_INFO" ]; then UPDATE_CLEANUP_INFO="$UPDATE_CLEANUP_INFO; "; fi
            UPDATE_CLEANUP_INFO="$UPDATE_CLEANUP_INFO$p: update in /data/app will be removed during preparation"
        elif [ "$update" = "1" ] && printf '%s' "$active" | grep -q '^/data/app/'; then
            # An update hides stock on any model; without a profile, we cannot
            # report PASS. Cleanup reveals the stock version, so the result is
            # not ambiguous.
            cap_set "${p}_MASKABLE" WARN \
                "active=$active; original=${original:-not exposed by PM}" \
                "$p: active update in /data/app; stock target will be revalidated during cleanup"
            if [ -n "$UPDATE_CLEANUP_INFO" ]; then UPDATE_CLEANUP_INFO="$UPDATE_CLEANUP_INFO; "; fi
            UPDATE_CLEANUP_INFO="$UPDATE_CLEANUP_INFO$p: update in /data/app will be removed during preparation"
        elif [ -z "$original" ]; then
            cap_set "${p}_MASKABLE" UNKNOWN "" "$p: original system path is ambiguous"
        elif ! is_allowed_system_path "$target"; then
            cap_set "${p}_MASKABLE" FAIL "$target" "$p: target is outside the allowed roots"
        elif [ "$update" = "1" ]; then
            cap_set "${p}_MASKABLE" WARN "$original" "$p: update in /data/app requires explicit cleanup"
        else
            cap_set "${p}_MASKABLE" PASS "target=$target; original=$original"
        fi
    done
    if [ "${DEGOOGLE_MODE:-$OPERATION_MODE}" = "PURE" ]; then
        cap_set PRIV_APP_COMPATIBLE UNKNOWN "" "not applicable to pure DeGoogle mode"
    elif [ "$MOUNT_GMS_IS_OURS" = "1" ] && [ "$GMS_PRIVILEGED" = "1" ]; then
        cap_set PRIV_APP_COMPATIBLE PASS "microG is active with the PRIVILEGED flag"
    elif printf '%s' "$GMS_FLAGS" | grep -q PRIVILEGED; then
        cap_set PRIV_APP_COMPATIBLE PASS "GMS flags contain PRIVILEGED"
    elif [ -n "$GMS_PATH" ]; then
        cap_set PRIV_APP_COMPATIBLE FAIL "$GMS_FLAGS" "GMS is not a privileged priv-app"
    else
        cap_set PRIV_APP_COMPATIBLE UNKNOWN "" "GMS not found"
    fi
}

# ===========================================================================
# Fact collection (source of truth = actual Android state)
# ===========================================================================

# Relative path to the Mapbox library inside GMS, using the same ISA as
# provision_gms_native_libs. Returns empty for an unmapped ABI.
gms_maps_lib_relpath()
{
    case "${ABI:-}" in
        arm64-v8a) printf 'lib/arm64/libmapbox-gl.so' ;;
        armeabi-v7a|armeabi) printf 'lib/arm/libmapbox-gl.so' ;;
        x86_64) printf 'lib/x86_64/libmapbox-gl.so' ;;
        x86) printf 'lib/x86/libmapbox-gl.so' ;;
        *) printf '' ;;
    esac
}

collect_facts()
{
    local transaction_state snapshot_validated
    snapshot_validated=0
    ROOT_OK=0; ROOT_MANAGER=""; GMS_PRIVILEGED=0; FINALIZE_DONE=0
    GMS_PATH=""; GSF_PATH=""; STORE_PATH=""; GMS_FLAGS=""; GMS_UID=""
    GMS_PACKAGE_DUMP=""; GMS_DUMP_UID=""; GMS_SIGNATURE_GRANTED=0; GMS_SIGNATURE_MENTIONED=0
    GMS_MAPS_LIB_PRESENT=0; GMS_MAPS_LIB_RELPATH=""
    GMS_VERSION=""; GMS_VERSION_CODE=""; STORE_VERSION=""
    TARGET_GMS=""; TARGET_GSF=""; TARGET_STORE=""
    OPERATION_MODE=""; MODE_CONFLICT=0; MASK_GMS_EMPTY=0; MASK_GSF_EMPTY=0; MASK_STORE_EMPTY=0; PURE_MASKS_VALID=0
    POST_BOOT_STATUS=""; POST_BOOT_REASON=""; STOCK_REINDEX_PENDING=0; STOCK_REINDEX_READY=0; STOCK_CONFIRMED=0; STOCK_CONFIRMATION_OPERATION_ID=""
    MANUFACTURER="$(getprop ro.product.manufacturer 2>/dev/null)"
    MODEL="$(getprop ro.product.model 2>/dev/null)"
    DEVICE="$(getprop ro.product.device 2>/dev/null)"
    PRODUCT="$(getprop ro.product.name 2>/dev/null)"
    BOARD="$(getprop ro.product.board 2>/dev/null)"
    HARDWARE="$(getprop ro.hardware 2>/dev/null)"
    ANDROID_SDK="$(getprop ro.build.version.sdk 2>/dev/null)"
    ANDROID_RELEASE="$(getprop ro.build.version.release 2>/dev/null)"
    FINGERPRINT="$(getprop ro.build.fingerprint 2>/dev/null)"
    BUILD_ID="$(getprop ro.build.id 2>/dev/null)"
    SECURITY_PATCH="$(getprop ro.build.version.security_patch 2>/dev/null)"
    ONE_UI_VERSION="$(getprop ro.build.version.oneui 2>/dev/null)"
    [ -n "$ONE_UI_VERSION" ] || ONE_UI_VERSION="$(getprop ro.build.version.sem 2>/dev/null)"
    [ -n "$ONE_UI_VERSION" ] || ONE_UI_VERSION="$(getprop ro.build.PDA 2>/dev/null)"
    ABI="$(getprop ro.product.cpu.abi 2>/dev/null)"
    ABI_LIST="$(getprop ro.product.cpu.abilist 2>/dev/null)"
    [ -n "$ABI_LIST" ] || ABI_LIST="$ABI"
    KERNEL_VERSION="$(uname -a 2>/dev/null || true)"
    SELINUX="$(getenforce 2>/dev/null || echo unknown)"
    case "$SELINUX" in Enforcing|Permissive|Disabled) emit_progress selinux_mode CONFIRMED ;; *) emit_progress selinux_mode FAILED ;; esac
    if [ -n "$MODEL" ] && [ -n "$FINGERPRINT" ]; then
        emit_progress device_properties CONFIRMED
    else
        emit_progress device_properties FAILED
    fi

    if [ "$(id -u 2>/dev/null)" = "0" ]; then
        ROOT_OK=1
        detect_root_manager
        emit_progress root CONFIRMED
    else
        emit_progress root FAILED
    fi

    # reads that require root (or at least namespace visibility)
    if [ "$ROOT_OK" = "1" ]; then
        locate_package GMS "$GMS_PKG"
        locate_package GSF "$GSF_PKG"
        locate_package STORE "$STORE_PKG"
        emit_progress package_locations CONFIRMED
        TARGET_GMS="${GMS_TARGET_DIRECTORY:-}"
        TARGET_GSF="${GSF_TARGET_DIRECTORY:-}"
        TARGET_STORE="${STORE_TARGET_DIRECTORY:-}"
        if [ -f "$SNAPSHOT_FILE" ]; then
            if validate_snapshot; then
                snapshot_validated=1
                TARGET_GMS="$(snapshot_field gms targetDirectory)"
                TARGET_GSF="$(snapshot_field gsf targetDirectory)"
                TARGET_STORE="$(snapshot_field store targetDirectory)"
                GMS_ORIGINAL_SYSTEM_PATH="$(snapshot_field gms originalSystemPath)"
                GSF_ORIGINAL_SYSTEM_PATH="$(snapshot_field gsf originalSystemPath)"
                STORE_ORIGINAL_SYSTEM_PATH="$(snapshot_field store originalSystemPath)"
                OPERATION_MODE="$(snapshot_top_field operationMode)"
                local journal_mode
                journal_mode="$(sed -n 's/^operationMode=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)"
                if [ -n "$OPERATION_MODE" ] && [ -n "$journal_mode" ] && [ "$OPERATION_MODE" != "$journal_mode" ]; then
                    MODE_CONFLICT=1
                fi
            else
                TARGET_GMS=""; TARGET_GSF=""; TARGET_STORE=""
            fi
        else
            preparable_system_update GMS && TARGET_GMS="$(dirname "$GMS_ORIGINAL_SYSTEM_PATH")"
            preparable_system_update STORE && TARGET_STORE="$(dirname "$STORE_ORIGINAL_SYSTEM_PATH")"
            snapshot_validated=1
        fi
        if [ "$snapshot_validated" = "1" ]; then
            emit_progress transaction_snapshot CONFIRMED
        else
            emit_progress transaction_snapshot FAILED
        fi
        read_gms_details
        GMS_PACKAGE_DUMP=""
        case "$GMS_FLAGS" in *PRIVILEGED*) GMS_PRIVILEGED=1 ;; esac
        GMS_UID="$(gms_uid_current 2>/dev/null || true)"
        if [ -n "$GMS_VERSION" ] || [ -z "$GMS_PATH" ]; then
            emit_progress gms_details CONFIRMED
        else
            emit_progress gms_details FAILED
        fi

        # WhatsApp Mapbox fix: libmapbox-gl.so copied from the APK must
        # exist for maps to load. The mask also counts as evidence,
        # because it survives reboot before it is mounted.
        GMS_MAPS_LIB_RELPATH="$(gms_maps_lib_relpath)"
        if [ -n "$GMS_MAPS_LIB_RELPATH" ] && \
            { [ -s "$TARGET_GMS/$GMS_MAPS_LIB_RELPATH" ] || [ -s "$MASK_GMS/$GMS_MAPS_LIB_RELPATH" ]; }; then
            GMS_MAPS_LIB_PRESENT=1
        fi

        # Mounts: detect ANY active mount at profile paths and record whether
        # the source is our mask. An unknown source means an unmanaged state.
        refresh_mount_facts
        emit_progress mounts CONFIRMED

        transaction_state="$(sed -n 's/^state=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)"
        if [ -n "$TARGET_GMS" ] && [ -n "$TARGET_GSF" ] && [ -n "$TARGET_STORE" ] && \
            is_safe_target_directory "$TARGET_GMS" && is_safe_target_directory "$TARGET_GSF" && \
            is_safe_target_directory "$TARGET_STORE" && targets_are_distinct "$TARGET_GMS" "$TARGET_GSF" "$TARGET_STORE" && \
            [ "$MOUNT_GMS" = "0" ] && [ "$MOUNT_GSF" = "0" ] && [ "$MOUNT_STORE" = "0" ] && \
            { [ -z "$GMS_PATH" ] || stock_path_valid "$GMS_PATH" "$TARGET_GMS" "$GMS_HAS_DATA_UPDATE"; } && \
            { [ -z "$GSF_PATH" ] || stock_path_valid "$GSF_PATH" "$TARGET_GSF" "$GSF_HAS_DATA_UPDATE"; } && \
            { [ -z "$STORE_PATH" ] || stock_path_valid "$STORE_PATH" "$TARGET_STORE" "$STORE_HAS_DATA_UPDATE"; }; then
            case "$transaction_state" in
                REINDEX_PENDING|REBOOT_REQUESTED)
                    STOCK_REINDEX_PENDING=1
                    stock_reindex_ready && STOCK_REINDEX_READY=1
                    ;;
                STOCK_CONFIRMED)
                    STOCK_CONFIRMED=1
                    STOCK_CONFIRMATION_OPERATION_ID="$(sed -n 's/^operationId=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)"
                    ;;
            esac
        fi

        if [ -d "$MASK_GMS" ] && [ -z "$(find "$MASK_GMS" -mindepth 1 -print -quit 2>/dev/null)" ]; then MASK_GMS_EMPTY=1; fi
        if [ -d "$MASK_GSF" ] && [ -z "$(find "$MASK_GSF" -mindepth 1 -print -quit 2>/dev/null)" ]; then MASK_GSF_EMPTY=1; fi
        if [ -d "$MASK_STORE" ] && [ -z "$(find "$MASK_STORE" -mindepth 1 -print -quit 2>/dev/null)" ]; then MASK_STORE_EMPTY=1; fi
        if [ "$OPERATION_MODE" = "PURE" ] && \
            [ "$(sed -n 's/^state=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)" = "COMMITTED" ]; then
            POST_BOOT_STATUS="PASS"
            POST_BOOT_REASON="pure masks, restart identity, packages, and SELinux validated"
        fi
        if [ "$OPERATION_MODE" = "PURE" ] && verify_pure_masks; then PURE_MASKS_VALID=1; fi
        emit_progress transaction_masks CONFIRMED

        if cmd deviceidle whitelist 2>/dev/null | grep -q "$GMS_PKG"; then FINALIZE_DONE=1; fi
    fi

    # Backup uses the microg-session.sh format: directories are copied directly.
    # The presence of user0 is the indicator used by the original script; user_de is
    # optional because not every firmware creates that directory.
    BACKUP_PRESENT=0
    BACKUP_CREATED_AT=""
    if [ -d "$BACKUP_GMS_USER" ]; then
        BACKUP_PRESENT=1
        # Epoch time of the last backup write; the UI formats it using the device locale.
        BACKUP_CREATED_AT="$(stat -c %Y "$BACKUP_GMS_USER" 2>/dev/null || true)"
    fi

    refresh_residue_facts

    probe_capabilities
}

emit_facts()
{
    emit ROOT_OK "$ROOT_OK"
    emit ROOT_MANAGER "$ROOT_MANAGER"
    emit PROFILE_ID "$PROFILE_ID"
    if profile_check; then emit PROFILE_MATCH "1"; else emit PROFILE_MATCH "0"; fi
    emit MANUFACTURER "$MANUFACTURER"
    emit MODEL "$MODEL"
    emit DEVICE "$DEVICE"
    emit PRODUCT "$PRODUCT"
    emit BOARD "$BOARD"
    emit HARDWARE "$HARDWARE"
    emit ANDROID_SDK "$ANDROID_SDK"
    emit ANDROID_RELEASE "$ANDROID_RELEASE"
    emit FINGERPRINT "$FINGERPRINT"
    emit BUILD_ID "$BUILD_ID"
    emit SECURITY_PATCH "$SECURITY_PATCH"
    emit ONE_UI_VERSION "$ONE_UI_VERSION"
    emit KERNEL_VERSION "$KERNEL_VERSION"
    emit SELINUX "$SELINUX"
    emit ABI "$ABI"
    emit ABI_LIST "$ABI_LIST"
    emit GMS_PATH "$GMS_PATH"
    emit GMS_VERSION "$GMS_VERSION"
    emit GMS_VERSION_CODE "$GMS_VERSION_CODE"
    emit GMS_UID "$GMS_UID"
    emit GMS_FLAGS "$GMS_FLAGS"
    emit GMS_PRIVILEGED "$GMS_PRIVILEGED"
    emit GSF_PATH "$GSF_PATH"
    emit STORE_PATH "$STORE_PATH"
    emit STORE_VERSION "$STORE_VERSION"
    emit PREPARATION_INFO "$UPDATE_CLEANUP_INFO"
    emit GMS_ACTIVE_CODE_PATH "$GMS_ACTIVE_CODE_PATH"
    emit GMS_ORIGINAL_SYSTEM_PATH "$GMS_ORIGINAL_SYSTEM_PATH"
    emit GMS_TARGET_DIRECTORY "${TARGET_GMS:-$GMS_TARGET_DIRECTORY}"
    emit GMS_BASE_APK "$GMS_BASE_APK"
    emit GMS_SPLIT_APKS "$GMS_SPLIT_APKS"
    emit GMS_HAS_DATA_UPDATE "$GMS_HAS_DATA_UPDATE"
    emit GMS_BACKING_PARTITION "$GMS_BACKING_PARTITION"
    emit GMS_FILESYSTEM_TYPE "$GMS_FILESYSTEM_TYPE"
    emit GMS_RESOLVED_REAL_PATH "$GMS_RESOLVED_REAL_PATH"
    emit GSF_ACTIVE_CODE_PATH "$GSF_ACTIVE_CODE_PATH"
    emit GSF_ORIGINAL_SYSTEM_PATH "$GSF_ORIGINAL_SYSTEM_PATH"
    emit GSF_TARGET_DIRECTORY "${TARGET_GSF:-$GSF_TARGET_DIRECTORY}"
    emit GSF_BASE_APK "$GSF_BASE_APK"
    emit GSF_SPLIT_APKS "$GSF_SPLIT_APKS"
    emit GSF_HAS_DATA_UPDATE "$GSF_HAS_DATA_UPDATE"
    emit GSF_BACKING_PARTITION "$GSF_BACKING_PARTITION"
    emit GSF_FILESYSTEM_TYPE "$GSF_FILESYSTEM_TYPE"
    emit GSF_RESOLVED_REAL_PATH "$GSF_RESOLVED_REAL_PATH"
    emit STORE_ACTIVE_CODE_PATH "$STORE_ACTIVE_CODE_PATH"
    emit STORE_ORIGINAL_SYSTEM_PATH "$STORE_ORIGINAL_SYSTEM_PATH"
    emit STORE_TARGET_DIRECTORY "${TARGET_STORE:-$STORE_TARGET_DIRECTORY}"
    emit STORE_BASE_APK "$STORE_BASE_APK"
    emit STORE_SPLIT_APKS "$STORE_SPLIT_APKS"
    emit STORE_HAS_DATA_UPDATE "$STORE_HAS_DATA_UPDATE"
    emit STORE_BACKING_PARTITION "$STORE_BACKING_PARTITION"
    emit STORE_FILESYSTEM_TYPE "$STORE_FILESYSTEM_TYPE"
    emit STORE_RESOLVED_REAL_PATH "$STORE_RESOLVED_REAL_PATH"
    emit MOUNT_GMS "$MOUNT_GMS"
    emit MOUNT_GMS_IS_OURS "$MOUNT_GMS_IS_OURS"
    emit MOUNT_GMS_IS_LEGACY "$MOUNT_GMS_IS_LEGACY"
    emit MOUNT_GSF "$MOUNT_GSF"
    emit MOUNT_GSF_IS_OURS "$MOUNT_GSF_IS_OURS"
    emit MOUNT_GSF_IS_LEGACY "$MOUNT_GSF_IS_LEGACY"
    emit MOUNT_STORE "$MOUNT_STORE"
    emit MOUNT_STORE_IS_OURS "$MOUNT_STORE_IS_OURS"
    emit MOUNT_STORE_IS_LEGACY "$MOUNT_STORE_IS_LEGACY"
    emit MOUNT_GMS_SOURCE "$MOUNT_GMS_SOURCE"
    emit BACKUP_PRESENT "$BACKUP_PRESENT"
    emit OPERATION_MODE "$OPERATION_MODE"
    emit MODE_CONFLICT "$MODE_CONFLICT"
    emit MASK_GMS_EMPTY "$MASK_GMS_EMPTY"
    emit MASK_GSF_EMPTY "$MASK_GSF_EMPTY"
    emit MASK_STORE_EMPTY "$MASK_STORE_EMPTY"
    emit PURE_MASKS_VALID "$PURE_MASKS_VALID"
    emit POST_BOOT_STATUS "$POST_BOOT_STATUS"
    emit POST_BOOT_REASON "$POST_BOOT_REASON"
    emit STOCK_REINDEX_PENDING "$STOCK_REINDEX_PENDING"
    emit STOCK_REINDEX_READY "$STOCK_REINDEX_READY"
    emit STOCK_CONFIRMED "$STOCK_CONFIRMED"
    emit STOCK_CONFIRMATION_OPERATION_ID "$STOCK_CONFIRMATION_OPERATION_ID"
    emit BACKUP_CREATED_AT "$BACKUP_CREATED_AT"
    emit GMS_MAPS_LIB_PRESENT "$GMS_MAPS_LIB_PRESENT"
    emit MASK_RESIDUE_PRESENT "$MASK_RESIDUE_PRESENT"
    emit FINALIZE_DONE "$FINALIZE_DONE"
    emit REBOOT_STRATEGY_BACKEND "$REBOOT_STRATEGY_BACKEND"
    emit REBOOT_STRATEGY_METHOD "$REBOOT_STRATEGY_METHOD"
    emit REBOOT_STRATEGY_CONFIDENCE "$REBOOT_STRATEGY_CONFIDENCE"
    emit REBOOT_STRATEGY_FIRMWARE_VALIDATED "$REBOOT_STRATEGY_FIRMWARE_VALIDATED"
    cap_emit ROOT
    cap_emit SAMSUNG_DEVICE
    cap_emit GLOBAL_MOUNT_NAMESPACE
    cap_emit BIND_MOUNT
    cap_emit SYSTEM_GMS_FOUND
    cap_emit SYSTEM_GSF_FOUND
    cap_emit SYSTEM_STORE_FOUND
    cap_emit GMS_MASKABLE
    cap_emit GSF_MASKABLE
    cap_emit STORE_MASKABLE
    cap_emit SELINUX_ENFORCING
    cap_emit SELINUX_CONTEXT_CLONABLE
    cap_emit SIGNATURE_SPOOFING
    cap_emit PACKAGE_MANAGER_CACHE_ACCESS
    cap_emit PRIV_APP_COMPATIBLE
    cap_emit SAFE_SOFT_REBOOT
    cap_emit SAFE_RESTORE
    emit SCRIPT_VERSION "$SCRIPT_VERSION"
}

# ===========================================================================
# State derivation (same rules as the app StateDetector)
# ===========================================================================

stock_path_valid()
{
    # A system app update in /data/app is normal, but it is accepted only when
    # the locator explicitly confirmed this condition.
    local path="$1" expected_root="$2" has_data_update="$3"
    [ -n "$path" ] || return 1
    if [ -n "$expected_root" ] && is_safe_target_directory "$expected_root"; then
        case "$path" in
            "$expected_root"/*) return 0 ;;
        esac
    fi
    case "$path" in
        /data/app/*) [ "$has_data_update" = "1" ] && return 0 ;;
    esac
    return 1
}

compute_state()
{
    if [ "$ROOT_OK" != "1" ]; then
        STATE="NO_ROOT"
        return
    fi
    if [ "$MODE_CONFLICT" = "1" ]; then
        STATE="ERROR"
        return
    fi
    if [ "$MOUNT_GMS" = "0" ] && [ "$MOUNT_GSF" = "0" ] && [ "$MOUNT_STORE" = "0" ]; then
        if [ "$STOCK_REINDEX_PENDING" = "1" ]; then
            STATE="RESTORE_PREPARED"
            return
        fi
        # No mounts: stock state or rollback pending reboot.
        if ! stock_path_valid "$GMS_PATH" "$GMS_TARGET_DIRECTORY" "$GMS_HAS_DATA_UPDATE"; then
            STATE="ERROR"
            return
        fi
        if [ -z "$GSF_PATH" ] || [ -z "$STORE_PATH" ]; then
            STATE="RESTORE_PREPARED"
            return
        fi
        if stock_path_valid "$GSF_PATH" "$GSF_TARGET_DIRECTORY" "$GSF_HAS_DATA_UPDATE" && \
            stock_path_valid "$STORE_PATH" "$STORE_TARGET_DIRECTORY" "$STORE_HAS_DATA_UPDATE"; then
            STATE="STOCK"
        else
            STATE="ERROR"
        fi
        return
    fi

    # an active mount exists — all mounts must be present and use OUR masks;
    # A mount from an unknown source (for example, the legacy script's microg-mask) is ERROR.
    if [ "$MOUNT_GMS" != "1" ] || [ "$MOUNT_GSF" != "1" ] || [ "$MOUNT_STORE" != "1" ]; then
        STATE="ERROR"
        return
    fi
    if [ "$MOUNT_GMS_IS_OURS" != "1" ] || [ "$MOUNT_GSF_IS_OURS" != "1" ] || [ "$MOUNT_STORE_IS_OURS" != "1" ]; then
        STATE="ERROR"
        return
    fi

    if [ "$OPERATION_MODE" = "PURE" ]; then
        if [ "$MASK_GMS_EMPTY" != "1" ] || [ "$MASK_GSF_EMPTY" != "1" ] || [ "$MASK_STORE_EMPTY" != "1" ] || \
            [ "$PURE_MASKS_VALID" != "1" ]; then
            STATE="ERROR"
            return
        fi
        if [ -z "$GMS_PATH" ] && [ -z "$GSF_PATH" ] && [ -z "$STORE_PATH" ] && \
            [ "$POST_BOOT_STATUS" = "PASS" ]; then
            STATE="DEGOOGLE_ACTIVE"
        else
            STATE="DEGOOGLE_PREPARED"
        fi
        return
    fi

    case "$GMS_PATH" in
        "$TARGET_GMS"/*) ;;
        *) STATE="ERROR"; return ;;
    esac

    if [ -n "$GSF_PATH" ]; then
        # active mounts but GSF is still registered => PM still sees the stock state
        STATE="PREPARED"
        return
    fi

    # Post-boot: GSF is gone.
    if [ "$GMS_PRIVILEGED" != "1" ]; then
        STATE="ERROR"
        return
    fi
    case "$STORE_PATH" in
        "$TARGET_STORE"/*) ;;
        *) STATE="ERROR"; return ;;
    esac

    if [ "$FINALIZE_DONE" != "1" ]; then
        STATE="MICROG_BOOTED"
        return
    fi
    if [ "$BACKUP_PRESENT" = "1" ]; then
        STATE="MICROG_ACTIVE_BACKED_UP"
    else
        STATE="MICROG_ACTIVE"
    fi
}

# ===========================================================================
# probe / status
# ===========================================================================

probe()
{
    check_root
    collect_facts
    write_probe_baseline || say "WARNING: could not save probe baseline"
    compute_state
    emit_probe_result | tr '\000\012' '\012 '
    return 0
}

status()
{
    check_root
    collect_facts
    compute_state
    emit_probe_result | tr '\000\012' '\012 '
    say ""
    say "===== STATUS DeGoogle ====="
    say "State: $STATE"
    say "Device: $MANUFACTURER $MODEL ($DEVICE/$PRODUCT) SDK $ANDROID_SDK ($ANDROID_RELEASE)"
    say "Fingerprint: $FINGERPRINT"
    say "SELinux: $SELINUX · ABI: $ABI · Root: $ROOT_MANAGER"
    say ""
    say "GMS : ${GMS_PATH:-MISSING} v${GMS_VERSION:-?} (${GMS_PRIVILEGED:+privileged }uid=${GMS_UID:-?})"
    say "GSF : ${GSF_PATH:-MISSING}"
    say "Store: ${STORE_PATH:-MISSING} v${STORE_VERSION:-?}"
    say "Mounts: GMS=$MOUNT_GMS GSF=$MOUNT_GSF Store=$MOUNT_STORE (source: ${MOUNT_GMS_SOURCE:-n/d})"
    say "Finalize: $FINALIZE_DONE"
    if [ "$BACKUP_PRESENT" = "1" ]; then
        say "Backup: present (microg-session.sh format)"
        say "  user0: $(du -sh "$BACKUP_GMS_USER" 2>/dev/null | awk '{print $1}')"
        [ -d "$BACKUP_GMS_DE" ] && say "  user_de: $(du -sh "$BACKUP_GMS_DE" 2>/dev/null | awk '{print $1}')"
    else
        say "Backup: missing"
    fi
    return 0
}

run_safety_preflight()
{
    local gms_now gsf_now store_now target free_kb namespace_evidence
    gms_now="$GMS_PATH"; gsf_now="$GSF_PATH"; store_now="$STORE_PATH"

    case "$SELINUX" in Enforcing|Permissive) ;; *)
        [ "${DEGOOGLE_STRICT_SELINUX_POLICY:-0}" != "1" ] || {
        emit PREFLIGHT_STATUS "FAIL"
        emit PREFLIGHT_REASON "SELinux is not Enforcing"
        return 3
        }
        ;;
    esac
    if ! namespace_evidence="$(mount_namespace_identity)"; then
        emit PREFLIGHT_STATUS "FAIL"
        emit PREFLIGHT_REASON "PID 1 global namespace is inaccessible or identity could not be confirmed"
        return 3
    fi
    [ "$CAP_BIND_MOUNT_STATUS" = "PASS" ] || {
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "temporary global bind mount failed the create, read, and remove test"; return 3
    }
    [ "$CAP_SELINUX_CONTEXT_CLONABLE_STATUS" = "PASS" ] || {
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "owner, mode, and SELinux context were not validated for all three targets"; return 3
    }
    [ "$CAP_SAFE_RESTORE_STATUS" = "PASS" ] || {
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "persistent transaction storage is not writable"; return 3
    }
    [ "$CAP_SAFE_SOFT_REBOOT_STATUS" = "PASS" ] || {
        [ "$CAP_SAFE_SOFT_REBOOT_STATUS" = "WARN" ] && [ "${DEGOOGLE_ALLOW_FALLBACK_REBOOT:-0}" = "1" ] &&
            { [ "$REBOOT_STRATEGY_METHOD" = "KSUD_SOFT_REBOOT" ] || [ "$REBOOT_STRATEGY_METHOD" = "ZYGOTE_RESTART" ]; } || {
            emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "userspace restart mechanism is not confirmed or explicitly enabled"; return 3
        }
    }
    if [ -z "$GMS_ORIGINAL_SYSTEM_PATH" ]; then
        preparable_system_update GMS || {
            emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "GMS has no safe system target: ${TARGET_GMS:-missing}"; return 3
        }
    elif ! is_safe_target_directory "$TARGET_GMS"; then
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "GMS has no safe system target: ${TARGET_GMS:-missing}"; return 3
    fi
    if [ -z "$GSF_ORIGINAL_SYSTEM_PATH" ] || ! is_safe_target_directory "$TARGET_GSF"; then
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "GSF has no safe system target: ${TARGET_GSF:-missing}"; return 3
    fi
    if [ -z "$STORE_ORIGINAL_SYSTEM_PATH" ]; then
        preparable_system_update STORE || {
            emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "Store has no safe system target: ${TARGET_STORE:-missing}"; return 3
        }
    elif ! is_safe_target_directory "$TARGET_STORE"; then
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "Store has no safe system target: ${TARGET_STORE:-missing}"; return 3
    fi
    targets_are_distinct "$TARGET_GMS" "$TARGET_GSF" "$TARGET_STORE" || {
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "system targets are duplicated or overlapping"; return 3
    }
    case "$(dirname "$TARGET_GMS")" in */priv-app) ;; *)
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "GMS is not in a priv-app directory"; return 3 ;;
    esac
    if ! path_under_target "$gms_now" "$TARGET_GMS" && ! printf '%s' "$gms_now" | grep -q '^/data/app/'; then
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "GMS path is unexpected: ${gms_now:-missing}"; return 3
    fi
    if ! path_under_target "$gsf_now" "$TARGET_GSF"; then
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "GSF path is unexpected: ${gsf_now:-missing}"; return 3
    fi
    if ! path_under_target "$store_now" "$TARGET_STORE" && ! printf '%s' "$store_now" | grep -q '^/data/app/'; then
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "Store path is unexpected: ${store_now:-missing}"; return 3
    fi
    if [ "$MOUNT_GMS" = "1" ] && [ "$MOUNT_GMS_IS_OURS" != "1" ]; then
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "existing GMS mount has an unknown source"; return 3
    fi
    if [ "$MOUNT_GSF" = "1" ] && [ "$MOUNT_GSF_IS_OURS" != "1" ]; then
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "existing GSF mount has an unknown source"; return 3
    fi
    if [ "$MOUNT_STORE" = "1" ] && [ "$MOUNT_STORE_IS_OURS" != "1" ]; then
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "existing Store mount has an unknown source"; return 3
    fi
    if ! verify_probe_baseline; then
        emit PREFLIGHT_STATUS "FAIL"
        emit PREFLIGHT_REASON "paths or hashes changed since probe; TOCTOU detected"
        return 3
    fi

    target="$(dirname "$MASK_BASE")"
    [ -d "$target" ] && [ -w "$target" ] || {
        emit PREFLIGHT_STATUS "FAIL"
        emit PREFLIGHT_REASON "mask parent directory is not writable: $target"
        return 3
    }
    free_kb="$(df -Pk "$target" 2>/dev/null | tail -n 1 | awk '{print $4}')"
    case "$free_kb" in ''|*[!0-9]*) emit PREFLIGHT_STATUS "UNKNOWN"; emit PREFLIGHT_REASON "free space could not be read"; return 3 ;; esac
    [ "$free_kb" -ge 16384 ] || {
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "less than 16 MiB free for snapshot/mask"; return 3
    }
    emit PREFLIGHT_STATUS "PASS"
    emit PREFLIGHT_REASON "paths, SELinux, global namespace ($namespace_evidence), mounts, and free space revalidated"
    return 0
}

check_payload_storage()
{
    local gms_apk="$1" store_apk="$2" mask_dir free_kb payload_kb required_kb
    mask_dir="$(dirname "$MASK_BASE")"
    free_kb="$(df -Pk "$mask_dir" 2>/dev/null | tail -n 1 | awk '{print $4}')"
    payload_kb="$(( ($(wc -c < "$gms_apk") + $(wc -c < "$store_apk") + 1023) / 1024 ))"
    case "$free_kb:$payload_kb" in *[!0-9:]*) return 1 ;; esac
    required_kb=$((16384 + payload_kb))
    [ "$free_kb" -ge "$required_kb" ]
}

dry_run()
{
    check_root
    collect_facts
    write_probe_baseline || say "WARNING: could not save dry-run baseline"
    compute_state
    emit_facts
    emit DRY_RUN "1"
    emit STATE "$STATE"
    say "===== DEGOOGLE COMPATIBILITY PROBE ====="
    say "Manufacturer : ${MANUFACTURER:-UNKNOWN}"
    say "Device       : ${DEVICE:-UNKNOWN}"
    say "Model        : ${MODEL:-UNKNOWN}"
    say "Android      : ${ANDROID_RELEASE:-UNKNOWN}"
    say "SDK          : ${ANDROID_SDK:-UNKNOWN}"
    say "One UI       : ${ONE_UI_VERSION:-UNKNOWN}"
    say "Build        : ${BUILD_ID:-UNKNOWN}"
    say "Kernel       : ${KERNEL_VERSION:-UNKNOWN}"
    say "Root         : ${ROOT_MANAGER:-UNKNOWN}"
    say ""
    for key in ROOT SAMSUNG_DEVICE GLOBAL_MOUNT_NAMESPACE BIND_MOUNT \
        SYSTEM_GMS_FOUND SYSTEM_GSF_FOUND SYSTEM_STORE_FOUND GMS_MASKABLE \
        GSF_MASKABLE STORE_MASKABLE SELINUX_ENFORCING SELINUX_CONTEXT_CLONABLE \
        SIGNATURE_SPOOFING PACKAGE_MANAGER_CACHE_ACCESS PRIV_APP_COMPATIBLE \
        SAFE_SOFT_REBOOT SAFE_RESTORE; do
        cap_emit "$key" >/dev/null
        case "$key" in
            ROOT) say "[$CAP_ROOT_STATUS] $key ${CAP_ROOT_EVIDENCE:-$CAP_ROOT_REASON}" ;;
            SAMSUNG_DEVICE) say "[$CAP_SAMSUNG_DEVICE_STATUS] $key ${CAP_SAMSUNG_DEVICE_EVIDENCE:-$CAP_SAMSUNG_DEVICE_REASON}" ;;
            GLOBAL_MOUNT_NAMESPACE) say "[$CAP_GLOBAL_MOUNT_NAMESPACE_STATUS] $key ${CAP_GLOBAL_MOUNT_NAMESPACE_EVIDENCE:-$CAP_GLOBAL_MOUNT_NAMESPACE_REASON}" ;;
            BIND_MOUNT) say "[$CAP_BIND_MOUNT_STATUS] $key ${CAP_BIND_MOUNT_EVIDENCE:-$CAP_BIND_MOUNT_REASON}" ;;
            SYSTEM_GMS_FOUND) say "[$CAP_SYSTEM_GMS_FOUND_STATUS] $key ${CAP_SYSTEM_GMS_FOUND_EVIDENCE:-$CAP_SYSTEM_GMS_FOUND_REASON}" ;;
            SYSTEM_GSF_FOUND) say "[$CAP_SYSTEM_GSF_FOUND_STATUS] $key ${CAP_SYSTEM_GSF_FOUND_EVIDENCE:-$CAP_SYSTEM_GSF_FOUND_REASON}" ;;
            SYSTEM_STORE_FOUND) say "[$CAP_SYSTEM_STORE_FOUND_STATUS] $key ${CAP_SYSTEM_STORE_FOUND_EVIDENCE:-$CAP_SYSTEM_STORE_FOUND_REASON}" ;;
            GMS_MASKABLE) say "[$CAP_GMS_MASKABLE_STATUS] $key ${CAP_GMS_MASKABLE_EVIDENCE:-$CAP_GMS_MASKABLE_REASON}" ;;
            GSF_MASKABLE) say "[$CAP_GSF_MASKABLE_STATUS] $key ${CAP_GSF_MASKABLE_EVIDENCE:-$CAP_GSF_MASKABLE_REASON}" ;;
            STORE_MASKABLE) say "[$CAP_STORE_MASKABLE_STATUS] $key ${CAP_STORE_MASKABLE_EVIDENCE:-$CAP_STORE_MASKABLE_REASON}" ;;
            SELINUX_ENFORCING) say "[$CAP_SELINUX_ENFORCING_STATUS] $key ${CAP_SELINUX_ENFORCING_EVIDENCE:-$CAP_SELINUX_ENFORCING_REASON}" ;;
            SELINUX_CONTEXT_CLONABLE) say "[$CAP_SELINUX_CONTEXT_CLONABLE_STATUS] $key ${CAP_SELINUX_CONTEXT_CLONABLE_EVIDENCE:-$CAP_SELINUX_CONTEXT_CLONABLE_REASON}" ;;
            SIGNATURE_SPOOFING) say "[$CAP_SIGNATURE_SPOOFING_STATUS] $key ${CAP_SIGNATURE_SPOOFING_EVIDENCE:-$CAP_SIGNATURE_SPOOFING_REASON}" ;;
            PACKAGE_MANAGER_CACHE_ACCESS) say "[$CAP_PACKAGE_MANAGER_CACHE_ACCESS_STATUS] $key ${CAP_PACKAGE_MANAGER_CACHE_ACCESS_EVIDENCE:-$CAP_PACKAGE_MANAGER_CACHE_ACCESS_REASON}" ;;
            PRIV_APP_COMPATIBLE) say "[$CAP_PRIV_APP_COMPATIBLE_STATUS] $key ${CAP_PRIV_APP_COMPATIBLE_EVIDENCE:-$CAP_PRIV_APP_COMPATIBLE_REASON}" ;;
            SAFE_SOFT_REBOOT) say "[$CAP_SAFE_SOFT_REBOOT_STATUS] $key ${CAP_SAFE_SOFT_REBOOT_EVIDENCE:-$CAP_SAFE_SOFT_REBOOT_REASON}" ;;
            SAFE_RESTORE) say "[$CAP_SAFE_RESTORE_STATUS] $key ${CAP_SAFE_RESTORE_EVIDENCE:-$CAP_SAFE_RESTORE_REASON}" ;;
        esac
    done
    say ""
    say "Known-Good DB: final matching is performed by the app using the full fingerprint."
    say "Compatibility: see DEGOOGLE_CAP_* and the app structured report."
    return 0
}

preflight()
{
    local requested_mode="${DEGOOGLE_MODE:-MICROG}"
    case "$requested_mode" in PURE|MICROG) ;; *) fail_usage "preflight with DEGOOGLE_MODE=PURE|MICROG" ;; esac
    DEGOOGLE_MODE="$requested_mode"
    check_root
    collect_facts
    if [ -n "$OPERATION_MODE" ] && [ "$OPERATION_MODE" != "$requested_mode" ] && \
        [ "$(sed -n 's/^state=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)" != "STOCK_CONFIRMED" ]; then
        fail 3 "requested mode $requested_mode conflicts with the recorded $OPERATION_MODE transaction"
    fi
    OPERATION_MODE="$requested_mode"
    run_safety_preflight || return $?
    emit_facts
    emit PREFLIGHT_STATUS "PASS"
    return 0
}

# ===========================================================================
# prepare — STOCK → PREPARED (idempotent, transactional)
# ===========================================================================

NEW_MOUNTS=""
STORE_WAS_DISABLED=0
STORE_STATE_CAPTURED=0

restore_store_if_changed()
{
    [ "$STORE_STATE_CAPTURED" = "1" ] || return 0
    [ "$STORE_WAS_DISABLED" = "1" ] && return 0
    pm enable "$STORE_PKG" >/dev/null 2>&1 || {
        say "WARNING: could not re-enable Play Store during rollback"
        return 1
    }
    return 0
}

_prep_fail()
{
    local code="$1"
    shift
    say "ERROR: $*"
    local mp
    if [ -n "$NEW_MOUNTS" ]; then
        say "Rolling back masks applied by this run:"
        for mp in $NEW_MOUNTS; do
            if global umount "$mp" 2>/dev/null; then
                say "  unmounted: $mp"
            else
                say "  ERROR unmounting $mp — device is in a partial state. Run restore-stock."
            fi
            # Remove only APKs placed by this run (never those from a
            # valid pre-existing state).
            case "$mp" in
                "$TARGET_GMS") rm -f "$MASK_GMS/$GMS_APK" 2>/dev/null ;;
                "$TARGET_STORE") rm -f "$MASK_STORE/$STORE_APK" 2>/dev/null ;;
            esac
        done
        restore_store_if_changed
        if ! journal_state "ROLLBACK_REQUIRED" >/dev/null 2>&1; then
            say "ERROR: could not record ROLLBACK_REQUIRED in the journal."
        fi
        exit 4
    fi
    restore_store_if_changed
    if ! journal_state "ROLLBACK_REQUIRED" >/dev/null 2>&1; then
        say "ERROR: could not record ROLLBACK_REQUIRED in the journal."
    fi
    exit "$code"
}

mount_mask()
{
    # mount_mask <target> <mask> <apkname> <apk_src|''> <expected_sha256|''>
    local target="$1" mask="$2" apkname="$3" src="${4:-}" expected_sha256="${5:-}"

    if [ "$target" = "$TARGET_GSF" ] && find "$mask" -mindepth 1 -print -quit 2>/dev/null | grep -q .; then
        _prep_fail 3 "GSF mask is not empty; preserving existing payload"
    fi
    if is_masked_by_us "$target" "$mask" "$apkname"; then
        say "  $target is already masked by us (idempotent)."
        if [ -n "$src" ]; then
            sha256_matches_file "$src" "$expected_sha256" || _prep_fail 3 "APK SHA-256 does not match trusted metadata: $src"
            if [ ! -f "$mask/$apkname" ]; then
                cp "$src" "$mask/$apkname" || _prep_fail 1 "copy $apkname to the mask"
                chown root:root "$mask/$apkname" || _prep_fail 1 "chown root:root $apkname"
                chmod 0644 "$mask/$apkname" || _prep_fail 1 "chmod $apkname"
            fi
            sha256_matches_file "$mask/$apkname" "$expected_sha256" || _prep_fail 3 "masked APK SHA-256 does not match trusted metadata: $apkname"
        fi
        return 0
    fi
    if is_mounted "$target"; then
        _prep_fail 3 "unexpected mount at $target (source != our mask). Will not overwrite."
    fi
    if [ -n "$src" ]; then
        [ -f "$src" ] || _prep_fail 3 "APK not found: $src"
        is_apk "$src" || _prep_fail 3 "Not a valid APK: $src"
        sha256_matches_file "$src" "$expected_sha256" || _prep_fail 3 "APK SHA-256 does not match trusted metadata: $src"
        [ "${DEGOOGLE_FAIL_CP:-0}" = "1" ] && _prep_fail 1 "DEGOOGLE_FAIL_CP injected"
        cp "$src" "$mask/$apkname" || _prep_fail 1 "cp $src → $mask/$apkname"
        chown root:root "$mask/$apkname" || _prep_fail 1 "chown root:root $apkname"
        chmod 0644 "$mask/$apkname" || _prep_fail 1 "chmod $apkname"
        sha256_matches_file "$mask/$apkname" "$expected_sha256" || _prep_fail 3 "APK copy does not match trusted metadata: $apkname"
        # Verify the APK in the mask (owner/mode); never trust the command alone.
        local own mode
        own="$(stat -c %u:%g "$mask/$apkname" 2>/dev/null)"
        mode="$(stat -c %a "$mask/$apkname" 2>/dev/null)"
        [ "$own" = "0:0" ] && [ "$mode" = "644" ] || \
            _prep_fail 1 "invalid APK owner/mode in mask ($own $mode)"
    fi
    [ "${DEGOOGLE_FAIL_MOUNT:-0}" = "1" ] && {
        [ -n "$apkname" ] && rm -f "$mask/$apkname" 2>/dev/null
        _prep_fail 1 "DEGOOGLE_FAIL_MOUNT injected ($apkname)"
    }
    if ! global mount --bind "$mask" "$target"; then
        rm -f "$mask/$apkname" 2>/dev/null
        _prep_fail 1 "mount --bind failed at $target"
    fi
    NEW_MOUNTS="$NEW_MOUNTS $target"
    case "$target" in
        "$TARGET_GMS") journal_state "GMS_MASKED" >/dev/null 2>&1 || _prep_fail 4 "journal GMS_MASKED failed" ;;
        "$TARGET_GSF") journal_state "GSF_MASKED" >/dev/null 2>&1 || _prep_fail 4 "journal GSF_MASKED failed" ;;
        "$TARGET_STORE") journal_state "STORE_MASKED" >/dev/null 2>&1 || _prep_fail 4 "journal STORE_MASKED failed" ;;
    esac
    say "  masked: $target"
}

provision_gms_native_libs()
{
    # microG lite does not install MultiArchLoader; WhatsApp Mapbox looks for
    # libmapbox-gl.so at $TARGET_GMS/lib/<isa>. The library is added to the mask so
    # the bind mount exposes the expected path without changing /product.
    local src="$1" apk_abi isa dst unzip_bin
    GMS_MAPBOX_LIB=""
    case "${ABI:-}" in
        arm64-v8a) apk_abi="arm64-v8a"; isa="arm64" ;;
        armeabi-v7a|armeabi) apk_abi="armeabi-v7a"; isa="arm" ;;
        x86_64) apk_abi="x86_64"; isa="x86_64" ;;
        x86) apk_abi="x86"; isa="x86" ;;
        *)
            say "  WARNING: ABI '${ABI:-?}' has no mapping for libmapbox-gl.so; WhatsApp location may fail."
            return 0
            ;;
    esac
    dst="$MASK_GMS/lib/$isa/libmapbox-gl.so"
    if [ -s "$dst" ]; then
        chmod 0444 "$dst" 2>/dev/null || true
        GMS_MAPBOX_LIB="lib/$isa/libmapbox-gl.so"
        say "  libmapbox-gl.so already present in mask ($isa)."
        return 0
    fi
    mkdir -p "$MASK_GMS/lib/$isa" || {
        say "  WARNING: could not create $MASK_GMS/lib/$isa; WhatsApp location may fail."
        return 0
    }
    unzip_bin="$(command -v unzip 2>/dev/null || true)"
    if [ -z "$unzip_bin" ]; then
        rmdir "$MASK_GMS/lib/$isa" "$MASK_GMS/lib" 2>/dev/null || true
        say "  WARNING: unzip is missing; could not extract libmapbox-gl.so."
        return 0
    fi
    if "$unzip_bin" -p "$src" "lib/$apk_abi/libmapbox-gl.so" > "$dst.tmp" 2>/dev/null && [ -s "$dst.tmp" ]; then
        if ! mv "$dst.tmp" "$dst"; then
            rm -f "$dst.tmp"
            say "  WARNING: could not install libmapbox-gl.so in the mask."
            return 0
        fi
        chown root:root "$dst" 2>/dev/null || true
        chmod 0444 "$dst" 2>/dev/null || true
        GMS_MAPBOX_LIB="lib/$isa/libmapbox-gl.so"
        say "  libmapbox-gl.so installed in mask ($isa)."
    else
        rm -f "$dst.tmp"
        rmdir "$MASK_GMS/lib/$isa" "$MASK_GMS/lib" 2>/dev/null || true
        say "  WARNING: libmapbox-gl.so is missing from the APK; WhatsApp location may fail."
    fi
    return 0
}

prepare()
{
    check_root
    require_lock
    NEW_MOUNTS=""
    STORE_WAS_DISABLED=0
    STORE_STATE_CAPTURED=0

    local requested_mode gms_src store_src gms_sha256 store_sha256 transaction_state existing_mode
    if [ "${1:-}" = "--mode" ]; then
        requested_mode="${2:-}"
        gms_src="${3:-}"; store_src="${4:-}"; gms_sha256="${5:-}"; store_sha256="${6:-}"
    else
        requested_mode="${DEGOOGLE_MODE:-MICROG}"
        gms_src="${1:-}"; store_src="${2:-}"; gms_sha256="${3:-}"; store_sha256="${4:-}"
    fi
    case "$requested_mode" in PURE|MICROG) ;; *) fail_usage "prepare --mode PURE|MICROG [gms.apk companion.apk gms-sha256 companion-sha256]" ;; esac
    if [ -n "${DEGOOGLE_MODE:-}" ] && [ "$DEGOOGLE_MODE" != "$requested_mode" ]; then
        fail_usage "DEGOOGLE_MODE conflicts with the requested prepare mode"
    fi
    DEGOOGLE_MODE="$requested_mode"
    if [ "$requested_mode" = "MICROG" ]; then
        [ -n "$gms_src" ] && [ -n "$store_src" ] && [ -n "$gms_sha256" ] && [ -n "$store_sha256" ] || \
            fail_usage "prepare <gms.apk> <companion.apk> <gms-sha256> <companion-sha256>"
    else
        [ -z "$gms_src$store_src$gms_sha256$store_sha256" ] || fail_usage "prepare --mode PURE"
        [ ! -L "$MASK_BASE" ] || fail 3 "pure mode mask base is a symlink; preserving it"
        for mask in "$MASK_GMS" "$MASK_GSF" "$MASK_STORE"; do
            [ ! -L "$mask" ] && { [ ! -d "$mask" ] || [ -z "$(find "$mask" -mindepth 1 -print -quit 2>/dev/null)" ]; } || \
                fail 3 "pure mode requires all mask source directories to be empty; preserving $mask"
        done
    fi

    say "===== PREPARE ====="

    collect_facts
    transaction_state="$(sed -n 's/^state=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)"
    existing_mode="$OPERATION_MODE"
    if [ -f "$SNAPSHOT_FILE" ] && [ "$transaction_state" != "STOCK_CONFIRMED" ]; then
        [ "$MOUNT_GMS_IS_OURS" = "1" ] && [ "$MOUNT_GSF_IS_OURS" = "1" ] && [ "$MOUNT_STORE_IS_OURS" = "1" ] || \
            fail 3 "existing transaction has not been confirmed as stock; run recovery before prepare"
        [ "$existing_mode" = "$requested_mode" ] || fail 3 "existing transaction mode ${existing_mode:-unknown} conflicts with $requested_mode"
        if [ "$requested_mode" = "PURE" ]; then
            [ "$MASK_GMS_EMPTY" = "1" ] && [ "$MASK_GSF_EMPTY" = "1" ] && [ "$MASK_STORE_EMPTY" = "1" ] || \
                fail 3 "existing pure transaction contains unexpected mask payloads"
            compute_state
            emit STATE "$STATE"
            [ "$STATE" = "DEGOOGLE_PREPARED" ] || [ "$STATE" = "DEGOOGLE_ACTIVE" ] || fail 3 "existing pure transaction is inconsistent"
            say "Existing pure transaction is already mounted; no duplicate mounts were created."
            return 0
        fi
    fi
    if [ "$MOUNT_GMS" = "1" ] || [ "$MOUNT_GSF" = "1" ] || [ "$MOUNT_STORE" = "1" ]; then
        [ "$MOUNT_GMS_IS_OURS" = "1" ] && [ "$MOUNT_GSF_IS_OURS" = "1" ] && [ "$MOUNT_STORE_IS_OURS" = "1" ] || \
            fail 3 "partial transaction or external mount detected; run recovery before prepare"
    fi
    OPERATION_MODE="$requested_mode"
    run_safety_preflight || fail 3 "Safety preflight blocked the operation. No changes were made."
    journal_state "PREFLIGHT_OK" || fail 4 "could not record preflight"

    # 1) Preconditions: use actual PM paths, never assumed ones. GMS/vending may
    #    have an update in /data/app (Play Store auto-update); it is accepted and
    #    removed in the next step. GSF must be stock (no update is expected).
    local gms_now gsf_now store_now
    gms_now="$(pm_path "$GMS_PKG")"
    gsf_now="$(pm_path "$GSF_PKG")"
    store_now="$(pm_path "$STORE_PKG")"
    if path_under_target "$gms_now" "$TARGET_GMS" || printf '%s' "$gms_now" | grep -q '^/data/app/'; then
        :
    else
        fail 3 "GMS path is unexpected (current: ${gms_now:-missing}). No changes were made."
    fi
    path_under_target "$gsf_now" "$TARGET_GSF" || fail 3 "GSF path is unexpected (current: ${gsf_now:-missing}). No changes were made."
    if path_under_target "$store_now" "$TARGET_STORE" || printf '%s' "$store_now" | grep -q '^/data/app/'; then
        :
    else
        fail 3 "Play Store path is unexpected (current: ${store_now:-missing}). No changes were made."
    fi

    # Validate APKs before removing any update or disabling the Store.
    # A download error or invalid file must leave the environment exactly
    # as it was.
    if [ "$requested_mode" = "MICROG" ]; then
        [ -f "$gms_src" ] || fail 3 "microG APK not found: $gms_src"
        [ -f "$store_src" ] || fail 3 "Companion APK not found: $store_src"
        is_apk "$gms_src" || fail 3 "microG is not a valid APK: $gms_src"
        is_apk "$store_src" || fail 3 "Companion is not a valid APK: $store_src"
        sha256_matches_file "$gms_src" "$gms_sha256" || fail 3 "microG SHA-256 does not match trusted metadata: $gms_src"
        sha256_matches_file "$store_src" "$store_sha256" || fail 3 "Companion SHA-256 does not match trusted metadata: $store_src"
        check_payload_storage "$gms_src" "$store_src" || fail 3 "not enough free space for APKs, snapshot, and masks"
    fi

    if pm list packages -d 2>/dev/null | grep -q "$STORE_PKG"; then
        STORE_WAS_DISABLED=1
    else
        STORE_WAS_DISABLED=0
    fi
    STORE_STATE_CAPTURED=1

    # 1b) Remove updates from /data/app (GMS/vending) and disable Play Store.
    #     (prevents GMS auto-updates during the flow). Idempotent.
    if ! cleanup; then
        restore_store_if_changed
        journal_state "FAILED" >/dev/null 2>&1 || say "WARNING: could not record FAILED in the journal"
        fail 3 "environment cleanup failed. Nothing was masked."
    fi

    # Cleanup may change activeCodePath from /data/app to the stock copy.
    # Resolve paths again before the snapshot so hashes and metadata describe the
    # state that will actually be masked.
    locate_package GMS "$GMS_PKG"
    locate_package GSF "$GSF_PKG"
    locate_package STORE "$STORE_PKG"
    TARGET_GMS="${GMS_TARGET_DIRECTORY:-}"
    TARGET_GSF="${GSF_TARGET_DIRECTORY:-}"
    TARGET_STORE="${STORE_TARGET_DIRECTORY:-}"

    gms_now="$(pm_path "$GMS_PKG")"
    store_now="$(pm_path "$STORE_PKG")"
    path_under_target "$gms_now" "$TARGET_GMS" || _prep_fail 3 "GMS did not return to its system target after removing the update (current: ${gms_now:-missing})."
    path_under_target "$store_now" "$TARGET_STORE" || _prep_fail 3 "Play Store did not return to its system target after removing the update (current: ${store_now:-missing})."
    if [ "$requested_mode" = "MICROG" ]; then
        sha256_matches_file "$gms_src" "$gms_sha256" || _prep_fail 3 "microG SHA-256 changed after initial validation."
        sha256_matches_file "$store_src" "$store_sha256" || _prep_fail 3 "Companion SHA-256 changed after initial validation."
    else
        for mask in "$MASK_GMS" "$MASK_GSF" "$MASK_STORE"; do
            [ ! -L "$mask" ] && { [ ! -d "$mask" ] || [ -z "$(find "$mask" -mindepth 1 -print -quit 2>/dev/null)" ]; } || \
                _prep_fail 3 "pure mode mask source is no longer empty; preserving $mask"
        done
    fi

    snapshot_state || _prep_fail 4 "could not create snapshot/journal before making changes"

    # 3) Masks
    mkdir -p "$MASK_GMS" "$MASK_GSF" "$MASK_STORE" || _prep_fail 1 "could not create mask directories"
    [ "$requested_mode" = "PURE" ] || provision_gms_native_libs "$gms_src"
    clone_mask_metadata "$TARGET_GMS" "$MASK_GMS" "$ORIGINAL_GMS_OWNER" "$ORIGINAL_GMS_MODE" "$ORIGINAL_GMS_CONTEXT" "GMS"
    clone_mask_metadata "$TARGET_GSF" "$MASK_GSF" "$ORIGINAL_GSF_OWNER" "$ORIGINAL_GSF_MODE" "$ORIGINAL_GSF_CONTEXT" "GSF"
    clone_mask_metadata "$TARGET_STORE" "$MASK_STORE" "$ORIGINAL_STORE_OWNER" "$ORIGINAL_STORE_MODE" "$ORIGINAL_STORE_CONTEXT" "Store"

    say "[1/3] Applying masks (global namespace)"
    DEGOOGLE_FAIL_MOUNT="${DEGOOGLE_FAIL_MOUNT_GMS:-0}"
    if [ "$requested_mode" = "PURE" ]; then
        mount_mask "$TARGET_GMS" "$MASK_GMS" "" ""
    else
        mount_mask "$TARGET_GMS" "$MASK_GMS" "$GMS_APK" "$gms_src" "$gms_sha256"
    fi
    DEGOOGLE_FAIL_MOUNT="${DEGOOGLE_FAIL_MOUNT_GSF:-0}"
    mount_mask "$TARGET_GSF" "$MASK_GSF" "" ""
    DEGOOGLE_FAIL_MOUNT="${DEGOOGLE_FAIL_MOUNT_STORE:-0}"
    if [ "$requested_mode" = "PURE" ]; then
        mount_mask "$TARGET_STORE" "$MASK_STORE" "" ""
    else
        mount_mask "$TARGET_STORE" "$MASK_STORE" "$STORE_APK" "$store_src" "$store_sha256"
    fi

    # 4) SELinux — always in the global namespace; success requires validation
    say "[2/3] SELinux (restorecon in the global namespace)"
    [ "${DEGOOGLE_FAIL_RESTORECON:-0}" = "1" ] && _prep_fail 1 "DEGOOGLE_FAIL_RESTORECON injected"
    global restorecon -R "$TARGET_GMS" 2>/dev/null || _prep_fail 4 "restorecon GMS failed"
    global restorecon -R "$TARGET_GSF" 2>/dev/null || _prep_fail 4 "restorecon GSF failed"
    global restorecon -R "$TARGET_STORE" 2>/dev/null || _prep_fail 4 "restorecon Store failed"

    local ctx gms_owner gms_mode gms_context gsf_owner gsf_mode gsf_context store_owner store_mode store_context
    gms_owner="$(global stat -c %u:%g "$TARGET_GMS" 2>/dev/null)"
    gms_mode="$(global stat -c %a "$TARGET_GMS" 2>/dev/null)"
    gms_context="$(global ls -Zd "$TARGET_GMS" 2>/dev/null | awk '{print $1}')"
    gsf_owner="$(global stat -c %u:%g "$TARGET_GSF" 2>/dev/null)"
    gsf_mode="$(global stat -c %a "$TARGET_GSF" 2>/dev/null)"
    gsf_context="$(global ls -Zd "$TARGET_GSF" 2>/dev/null | awk '{print $1}')"
    store_owner="$(global stat -c %u:%g "$TARGET_STORE" 2>/dev/null)"
    store_mode="$(global stat -c %a "$TARGET_STORE" 2>/dev/null)"
    store_context="$(global ls -Zd "$TARGET_STORE" 2>/dev/null | awk '{print $1}')"
    [ "$gms_owner" = "$ORIGINAL_GMS_OWNER" ] && [ "$gms_mode" = "$ORIGINAL_GMS_MODE" ] && \
        [ "$gms_context" = "$ORIGINAL_GMS_CONTEXT" ] || \
        _prep_fail 4 "GMS metadata does not match the original directory after mount"
    [ "$gsf_owner" = "$ORIGINAL_GSF_OWNER" ] && [ "$gsf_mode" = "$ORIGINAL_GSF_MODE" ] && \
        [ "$gsf_context" = "$ORIGINAL_GSF_CONTEXT" ] || \
        _prep_fail 4 "GSF metadata does not match the original directory after mount"
    [ "$store_owner" = "$ORIGINAL_STORE_OWNER" ] && [ "$store_mode" = "$ORIGINAL_STORE_MODE" ] && \
        [ "$store_context" = "$ORIGINAL_STORE_CONTEXT" ] || \
        _prep_fail 4 "Store metadata does not match the original directory after mount"
    say "  metadata owner/mode/context reproduced for GMS, GSF, and Store"

    if [ -n "$GMS_MAPBOX_LIB" ]; then
        local so_mode so_ctx
        so_mode="$(global stat -c %a "$TARGET_GMS/$GMS_MAPBOX_LIB" 2>/dev/null)"
        so_ctx="$(global ls -Zd "$TARGET_GMS/$GMS_MAPBOX_LIB" 2>/dev/null | awk '{print $1}')"
        [ "$so_mode" = "444" ] || \
            _prep_fail 4 "GMS_MAPBOX_LIB_MODE_INVALID: mode ${so_mode:-<missing>} at $GMS_MAPBOX_LIB. Rollback applied."
        [ "$so_ctx" = "$ORIGINAL_GMS_CONTEXT" ] || \
            _prep_fail 4 "GMS_MAPBOX_LIB_CONTEXT_INVALID: ${so_ctx:-<no context>} at $GMS_MAPBOX_LIB. Rollback applied."
        say "  libmapbox-gl.so OK: mode $so_mode, context $so_ctx"
    fi

    # 5) Final mount verification
    say "[3/3] Verification"
    if [ "$requested_mode" = "PURE" ]; then
        is_masked_by_us "$TARGET_GMS" "$MASK_GMS" "" || _prep_fail 4 "GMS_MOUNT_INVALID"
        is_masked_by_us "$TARGET_STORE" "$MASK_STORE" "" || _prep_fail 4 "STORE_MOUNT_INVALID"
    else
        is_masked_by_us "$TARGET_GMS" "$MASK_GMS" "$GMS_APK" || _prep_fail 4 "GMS_MOUNT_INVALID"
        is_masked_by_us "$TARGET_STORE" "$MASK_STORE" "$STORE_APK" || _prep_fail 4 "STORE_MOUNT_INVALID"
    fi
    is_masked_by_us "$TARGET_GSF" "$MASK_GSF" "" || _prep_fail 4 "GSF_MOUNT_INVALID"

    say "  GMS : $(global ls -laZ "$TARGET_GMS" 2>/dev/null | tail -n +2 | tr '\n' ' ')"
    say "  GSF : masked (empty)"
    say "  Store: $(global ls -laZ "$TARGET_STORE" 2>/dev/null | tail -n +2 | tr '\n' ' ')"

    journal_state "STORE_MASKED" || _prep_fail 4 "could not record STORE_MASKED"

    say ""
    say "PREPARE COMPLETE. Perform a soft reboot (degoogle.sh soft-reboot or the app button)."
    emit STATE "PREPARED"
    return 0
}

# ===========================================================================
# finalize — post-boot: validate priv-app and apply technical configuration
# ===========================================================================

grant_ok()
{
    local pkg="$1" perm="$2"
    if pm grant "$pkg" "$perm" >/dev/null 2>&1; then
        say "  grant ok: $perm"
        return 0
    fi
    say "  grant skipped (missing/not grantable): $perm"
    return 0
}

appop_ok()
{
    local pkg="$1" op="$2"
    if cmd appops set "$pkg" "$op" allow >/dev/null 2>&1; then
        say "  appops ok: $op"
        return 0
    fi
    say "  appops skipped (nonexistent op): $op"
    return 0
}

finalize()
{
    check_root

    # The package may be in a different directory in experimental mode; post-boot
    # validation must use the locator from the same transaction.
    collect_facts
    [ "$OPERATION_MODE" != "PURE" ] || fail 3 "microG finalization is not applicable to a pure DeGoogle transaction"

    local gms_now gsf_now store_now flags

    say "===== FINALIZE ====="

    # Preconditions (actual MICROG_BOOTED state)
    gms_now="$(pm_path "$GMS_PKG")"
    path_under_target "$gms_now" "$TARGET_GMS" || fail 3 "GMS is not registered from the mask (current: ${gms_now:-missing}). Run prepare + soft reboot."
    gsf_now="$(pm_path "$GSF_PKG")"
    [ -z "$gsf_now" ] || fail 3 "GSF is still registered (${gsf_now}). The GSF mask failed."
    store_now="$(pm_path "$STORE_PKG")"
    path_under_target "$store_now" "$TARGET_STORE" || fail 3 "Companion is not registered from the mask (current: ${store_now:-missing})."

    # Validate that this is a real priv-app (not just the path)
    flags="$(gms_flags)"
    case "$flags" in
        *PRIVILEGED*) say "  GMS priv-app OK (flags=$flags)" ;;
        *) fail 3 "GMS is not PRIVILEGED (flags=$flags). The mask/context failed." ;;
    esac
    if ! dumpsys package "$GMS_PKG" 2>/dev/null | grep -q "INTERACT_ACROSS_USERS"; then
        say "  WARNING: INTERACT_ACROSS_USERS is not listed in GMS permissions."
    fi

    # Permissions (a missing permission is supported, not an error)
    say "[permissions]"
    grant_ok "$GMS_PKG" android.permission.ACCESS_COARSE_LOCATION
    grant_ok "$GMS_PKG" android.permission.ACCESS_FINE_LOCATION
    grant_ok "$GMS_PKG" android.permission.ACCESS_BACKGROUND_LOCATION
    grant_ok "$GMS_PKG" android.permission.READ_PHONE_STATE
    grant_ok "$GMS_PKG" android.permission.READ_CONTACTS
    grant_ok "$GMS_PKG" android.permission.POST_NOTIFICATIONS
    grant_ok "$GMS_PKG" android.permission.CAMERA
    grant_ok "$GMS_PKG" android.permission.READ_EXTERNAL_STORAGE
    grant_ok "$GMS_PKG" android.permission.WRITE_EXTERNAL_STORAGE

    # Re-enable the Store (masked with Companion) if it was disabled during prepare.
    pm enable --user 0 "$STORE_PKG" >/dev/null 2>&1 || true

    say "[background / Doze]"
    cmd deviceidle whitelist +"$GMS_PKG" >/dev/null 2>&1 || true
    cmd deviceidle whitelist +"$STORE_PKG" >/dev/null 2>&1 || true
    appop_ok "$GMS_PKG" RUN_IN_BACKGROUND
    appop_ok "$GMS_PKG" RUN_ANY_IN_BACKGROUND
    appop_ok "$GMS_PKG" ACCESS_BACKGROUND_LOCATION
    appop_ok "$STORE_PKG" RUN_IN_BACKGROUND
    appop_ok "$STORE_PKG" RUN_ANY_IN_BACKGROUND

    # Verification (never report success without checking)
    local whitelisted=0
    if cmd deviceidle whitelist 2>/dev/null | grep -q "$GMS_PKG"; then
        whitelisted=1
        say "  Doze whitelist: OK ($GMS_PKG)"
    else
        say "  WARNING: $GMS_PKG is not listed in the Doze whitelist."
    fi

    say ""
    say "FINALIZE COMPLETE (idempotent; safe to run again)."
    emit FINALIZE_DONE "$whitelisted"
    return 0
}

# ===========================================================================
# backup / restore-backup
# ===========================================================================

backup()
{
    check_root
    require_lock
    collect_facts
    if [ "$OPERATION_MODE" = "PURE" ] && \
        [ "$(sed -n 's/^state=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)" != "STOCK_CONFIRMED" ]; then
        fail_en 3 "microG token backup is unavailable during a pure DeGoogle transaction"
    fi

    [ "${DEGOOGLE_FAIL_BACKUP:-0}" = "1" ] && fail_en 1 "DEGOOGLE_FAIL_BACKUP injected"

    say "===== MICROG DATA BACKUP ====="

    am force-stop "$GMS_PKG" 2>/dev/null || true

    mkdir -p "$BACKUP_BASE" || fail_en 1 "mkdir $BACKUP_BASE failed"
    if [ ! -d "$GMS_DATA_USER0" ]; then
        say "  GMS has no data (nothing to copy)."
        return 0
    fi

    # Same behavior as the original script: a new backup replaces the
    # previous one, and user_de is copied only if it exists.
    rm -rf "$BACKUP_GMS_USER" "$BACKUP_GMS_DE"

    cp -a "$GMS_DATA_USER0" "$BACKUP_GMS_USER" || fail_en 1 "Failed to copy user0 data."
    if [ -d "$GMS_DATA_USERDE" ]; then
        cp -a "$GMS_DATA_USERDE" "$BACKUP_GMS_DE" || fail_en 1 "Failed to copy user_de data."
    fi

    say "  original uid: $(stat -c %u "$GMS_DATA_USER0" 2>/dev/null)"
    say "  user0: $(du -sh "$BACKUP_GMS_USER" 2>/dev/null | awk '{print $1}')"
    [ -d "$BACKUP_GMS_DE" ] && say "  user_de: $(du -sh "$BACKUP_GMS_DE" 2>/dev/null | awk '{print $1}')"
    say "BACKUP COMPLETE."
    emit BACKUP_OK "1"
    return 0
}

restore_backup()
{
    check_root
    require_lock

    collect_facts
    if [ "$OPERATION_MODE" = "PURE" ] && \
        [ "$(sed -n 's/^state=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)" != "STOCK_CONFIRMED" ]; then
        fail_en 3 "microG token restoration is unavailable during a pure DeGoogle transaction"
    fi

    say "===== RESTORING MICROG DATA ====="

    [ -d "$BACKUP_GMS_USER" ] || {
        say "  Backup absent (nothing to restore)."
        return 0
    }
    [ "${DEGOOGLE_FAIL_RESTORE:-0}" = "1" ] && fail_en 1 "DEGOOGLE_FAIL_RESTORE injected"

    local gms_now uid ctx
    gms_now="$(pm_path "$GMS_PKG")"
    path_under_target "$gms_now" "$TARGET_GMS" || fail_en 3 "GMS not registered from mask. Nothing was restored."

    am force-stop "$GMS_PKG" 2>/dev/null || true

    # Current UID, as in the original script. The backup never forces the old UID.
    uid="$(gms_uid_current)" || fail_en 1 "Could not derive current UID."
    ctx="$(stat -c %C "$GMS_DATA_USER0" 2>/dev/null || true)"

    # The layout matches microg-session.sh, but copying uses the explicit target
    # to avoid accidentally creating /data/user/0/gms-user0.
    rm -rf "$GMS_DATA_USER0"
    mkdir -p "$GMS_DATA_USER0" || fail_en 4 "mkdir destination user0 failed"
    case "$ctx" in
        u:*) ;;
        *) ctx="$(stat -c %C "$GMS_DATA_USER0" 2>/dev/null || true)" ;;
    esac
    cp -a "$BACKUP_GMS_USER"/. "$GMS_DATA_USER0"/ || fail_en 4 "Failed to restore user0"
    chown -R "$uid:$uid" "$GMS_DATA_USER0" || fail_en 4 "chown user0 failed"
    case "$ctx" in
        u:*) chcon -R "$ctx" "$GMS_DATA_USER0" 2>/dev/null || fail_en 4 "Could not reproduce SELinux context for user0" ;;
        *) restorecon -R "$GMS_DATA_USER0" 2>/dev/null || fail_en 4 "restorecon of user0 failed" ;;
    esac

    if [ -d "$BACKUP_GMS_DE" ]; then
        ctx="$(stat -c %C "$GMS_DATA_USERDE" 2>/dev/null || true)"
        rm -rf "$GMS_DATA_USERDE"
        mkdir -p "$GMS_DATA_USERDE" || fail_en 4 "mkdir destination user_de failed"
        case "$ctx" in
            u:*) ;;
            *) ctx="$(stat -c %C "$GMS_DATA_USERDE" 2>/dev/null || true)" ;;
        esac
        cp -a "$BACKUP_GMS_DE"/. "$GMS_DATA_USERDE"/ || fail_en 4 "Failed to restore user_de"
        chown -R "$uid:$uid" "$GMS_DATA_USERDE" || fail_en 4 "chown user_de failed"
        case "$ctx" in
            u:*) chcon -R "$ctx" "$GMS_DATA_USERDE" 2>/dev/null || fail_en 4 "Could not reproduce SELinux context for user_de" ;;
            *) restorecon -R "$GMS_DATA_USERDE" 2>/dev/null || fail_en 4 "restorecon of user_de failed" ;;
        esac
    fi

    [ -d "$GMS_DATA_USER0" ] || fail_en 4 "Restoration did not materialize user0"
    say "  restored with uid $uid"
    say "  user0: $(du -sh "$GMS_DATA_USER0" 2>/dev/null | awk '{print $1}')"
    [ -d "$GMS_DATA_USERDE" ] && say "  user_de: $(du -sh "$GMS_DATA_USERDE" 2>/dev/null | awk '{print $1}')"
    say "RESTORE COMPLETE."
    emit RESTORE_OK "1"
    return 0
}

# ===========================================================================
# cleanup — remove updates from /data/app and disable Play Store
# (used by prepare and as recovery for the ERROR state)
# ===========================================================================

cleanup()
{
    check_root
    local p g
    for p in "$GMS_PKG" "$STORE_PKG"; do
        g="$(pm_path "$p")"
        case "$g" in
            /data/app/*)
                say "  $p in /data/app — removing update (returning to base)..."
                pm uninstall "$p" >/dev/null 2>&1 || fail 1 "pm uninstall $p failed"
                sleep 2
                ;;
        esac
    done
    # disable Play Store to prevent GMS from updating during the flow
    if pm disable-user --user 0 "$STORE_PKG" >/dev/null 2>&1; then
        say "  Play Store disabled (prevents GMS from updating again)."
    else
        say "  ERROR: could not disable Play Store."
        return 1
    fi
}

# ===========================================================================
# restore-stock — full rollback to the stock environment
# ===========================================================================

SNAPSHOT_CACHE_LOADED=0

load_snapshot_cache()
{
    local values_file
    values_file="$TRANSACTION_BASE/snapshot-fields.$$"
    awk '
        function value(line, key, pattern, result) {
            pattern="\"" key "\"[[:space:]]*:[[:space:]]*\""
            if (!match(line, pattern)) return ""
            result=substr(line, RSTART+RLENGTH)
            sub(/\".*/, "", result)
            return result
        }
        NF { if (first == "") first=$0; last=$0 }
        /"gms"[[:space:]]*:/ { gms++; component="gms" }
        /"gsf"[[:space:]]*:/ { gsf++; component="gsf" }
        /"store"[[:space:]]*:/ { store++; component="store" }
        component != "" {
            for (i=1; i<=7; i++) {
                key=(i==1 ? "targetDirectory" : i==2 ? "originalSystemPath" : i==3 ? "activeCodePath" : i==4 ? "owner" : i==5 ? "mode" : i==6 ? "selinuxContext" : "sha256")
                fields[component SUBSEP i]=value($0, key)
            }
            component=""
        }
        /"schemaVersion"[[:space:]]*:/ {
            rest=$0; sub(/^.*"schemaVersion"[[:space:]]*:[[:space:]]*/, "", rest); sub(/[^0-9].*$/, "", rest); schema=rest
        }
        /"operationId"[[:space:]]*:/ { operation=value($0, "operationId") }
        /"timestamp"[[:space:]]*:/ { timestamp=value($0, "timestamp") }
        /"deviceFingerprint"[[:space:]]*:/ { fingerprint=value($0, "deviceFingerprint") }
        /"model"[[:space:]]*:/ { model=value($0, "model") }
        /"operationMode"[[:space:]]*:/ { mode=value($0, "operationMode") }
        END {
            if (!(first ~ /^\{/ && last ~ /^\}/ && gms == 1 && gsf == 1 && store == 1)) exit 1
            print schema; print operation; print timestamp; print fingerprint; print model; print mode
            for (component_index=1; component_index<=3; component_index++) {
                component=(component_index==1 ? "gms" : component_index==2 ? "gsf" : "store")
                for (i=1; i<=7; i++) print fields[component SUBSEP i]
            }
        }
    ' "$SNAPSHOT_FILE" > "$values_file" 2>/dev/null || {
        rm -f "$values_file"
        return 1
    }
    exec 3< "$values_file"
    IFS= read -r SNAP_SCHEMA <&3 || return 1
    IFS= read -r SNAP_OPERATION <&3 || return 1
    IFS= read -r SNAP_TIMESTAMP <&3 || return 1
    IFS= read -r SNAP_FINGERPRINT <&3 || return 1
    IFS= read -r SNAP_MODEL <&3 || return 1
    IFS= read -r SNAP_MODE <&3 || return 1
    IFS= read -r SNAP_GMS_TARGET <&3 || return 1
    IFS= read -r SNAP_GMS_ORIGINAL <&3 || return 1
    IFS= read -r SNAP_GMS_ACTIVE <&3 || return 1
    IFS= read -r SNAP_GMS_OWNER <&3 || return 1
    IFS= read -r SNAP_GMS_MODE <&3 || return 1
    IFS= read -r SNAP_GMS_CONTEXT <&3 || return 1
    IFS= read -r SNAP_GMS_HASH <&3 || return 1
    IFS= read -r SNAP_GSF_TARGET <&3 || return 1
    IFS= read -r SNAP_GSF_ORIGINAL <&3 || return 1
    IFS= read -r SNAP_GSF_ACTIVE <&3 || return 1
    IFS= read -r SNAP_GSF_OWNER <&3 || return 1
    IFS= read -r SNAP_GSF_MODE <&3 || return 1
    IFS= read -r SNAP_GSF_CONTEXT <&3 || return 1
    IFS= read -r SNAP_GSF_HASH <&3 || return 1
    IFS= read -r SNAP_STORE_TARGET <&3 || return 1
    IFS= read -r SNAP_STORE_ORIGINAL <&3 || return 1
    IFS= read -r SNAP_STORE_ACTIVE <&3 || return 1
    IFS= read -r SNAP_STORE_OWNER <&3 || return 1
    IFS= read -r SNAP_STORE_MODE <&3 || return 1
    IFS= read -r SNAP_STORE_CONTEXT <&3 || return 1
    IFS= read -r SNAP_STORE_HASH <&3 || return 1
    exec 3<&-
    rm -f "$values_file"
    SNAPSHOT_CACHE_LOADED=1
}

snapshot_field()
{
    # snapshot_field <component> <field>
    # Paths recorded by the backend have already been validated against the allowlist;
    # this read accepts only values without quotes or newlines.
    local component="$1" field="$2"
    [ -f "$SNAPSHOT_FILE" ] || return 1
    if [ "$SNAPSHOT_CACHE_LOADED" = "1" ]; then
        case "$component:$field" in
            gms:targetDirectory) printf '%s' "$SNAP_GMS_TARGET" ;;
            gms:originalSystemPath) printf '%s' "$SNAP_GMS_ORIGINAL" ;;
            gms:activeCodePath) printf '%s' "$SNAP_GMS_ACTIVE" ;;
            gms:owner) printf '%s' "$SNAP_GMS_OWNER" ;;
            gms:mode) printf '%s' "$SNAP_GMS_MODE" ;;
            gms:selinuxContext) printf '%s' "$SNAP_GMS_CONTEXT" ;;
            gms:sha256) printf '%s' "$SNAP_GMS_HASH" ;;
            gsf:targetDirectory) printf '%s' "$SNAP_GSF_TARGET" ;;
            gsf:originalSystemPath) printf '%s' "$SNAP_GSF_ORIGINAL" ;;
            gsf:activeCodePath) printf '%s' "$SNAP_GSF_ACTIVE" ;;
            gsf:owner) printf '%s' "$SNAP_GSF_OWNER" ;;
            gsf:mode) printf '%s' "$SNAP_GSF_MODE" ;;
            gsf:selinuxContext) printf '%s' "$SNAP_GSF_CONTEXT" ;;
            gsf:sha256) printf '%s' "$SNAP_GSF_HASH" ;;
            store:targetDirectory) printf '%s' "$SNAP_STORE_TARGET" ;;
            store:originalSystemPath) printf '%s' "$SNAP_STORE_ORIGINAL" ;;
            store:activeCodePath) printf '%s' "$SNAP_STORE_ACTIVE" ;;
            store:owner) printf '%s' "$SNAP_STORE_OWNER" ;;
            store:mode) printf '%s' "$SNAP_STORE_MODE" ;;
            store:selinuxContext) printf '%s' "$SNAP_STORE_CONTEXT" ;;
            store:sha256) printf '%s' "$SNAP_STORE_HASH" ;;
            *) return 1 ;;
        esac
        return 0
    fi
    sed -n "s/.*\"$component\".*\"$field\":\"\([^\"]*\)\".*/\1/p" "$SNAPSHOT_FILE" 2>/dev/null | head -n 1
}

snapshot_top_field()
{
    local field="$1"
    [ -f "$SNAPSHOT_FILE" ] || return 1
    if [ "$SNAPSHOT_CACHE_LOADED" = "1" ]; then
        case "$field" in
            schemaVersion) printf '%s' "$SNAP_SCHEMA" ;;
            operationId) printf '%s' "$SNAP_OPERATION" ;;
            timestamp) printf '%s' "$SNAP_TIMESTAMP" ;;
            deviceFingerprint) printf '%s' "$SNAP_FINGERPRINT" ;;
            model) printf '%s' "$SNAP_MODEL" ;;
            operationMode) printf '%s' "$SNAP_MODE" ;;
            *) return 1 ;;
        esac
        return 0
    fi
    sed -n "s/.*\"$field\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$SNAPSHOT_FILE" 2>/dev/null | head -n 1
}

validate_snapshot()
{
    local component target original active owner mode context hash
    [ -f "$SNAPSHOT_FILE" ] || return 1
    load_snapshot_cache || return 1
    [ "$SNAP_SCHEMA" = "1" ] && [ -n "$SNAP_OPERATION" ] && [ -n "$SNAP_TIMESTAMP" ] && [ -n "$SNAP_FINGERPRINT" ] && [ -n "$SNAP_MODEL" ] || return 1
    case "$SNAP_MODE" in ""|PURE|MICROG) ;; *) return 1 ;; esac
    [ "$SNAP_FINGERPRINT" = "$FINGERPRINT" ] && [ "$SNAP_MODEL" = "$MODEL" ] || return 1
    for component in gms gsf store; do
        target="$(snapshot_field "$component" targetDirectory)"
        original="$(snapshot_field "$component" originalSystemPath)"
        active="$(snapshot_field "$component" activeCodePath)"
        owner="$(snapshot_field "$component" owner)"
        mode="$(snapshot_field "$component" mode)"
        context="$(snapshot_field "$component" selinuxContext)"
        hash="$(snapshot_field "$component" sha256)"
        is_safe_target_directory "$target" || return 1
        [ -n "$original" ] && is_allowed_system_path "$original" || return 1
        [ -n "$active" ] || return 1
        if [ -n "$hash" ]; then
            case "$hash" in *[!0-9a-fA-F]*) return 1 ;; esac
            [ "${#hash}" -eq 64 ] || return 1
        fi
        case "$owner" in *[!0-9:]*|:*|*:) return 1 ;; esac
        case "$owner" in *:*) ;; *) return 1 ;; esac
        case "$owner" in *:*:*) return 1 ;; esac
        case "$mode" in ''|*[!0-7]*) return 1 ;; esac
        [ -n "$context" ] || return 1
    done
    targets_are_distinct \
        "$SNAP_GMS_TARGET" "$SNAP_GSF_TARGET" "$SNAP_STORE_TARGET"
}

rollback_target_from_snapshot()
{
    # rollback_target_from_snapshot <component> <profileTarget>
    # Use the profile as a fallback only when no snapshot exists and the device matches
    # the known legacy exception.
    local component="$1" profile_target="$2" target original snapshot_seen=0
    target="$(snapshot_field "$component" targetDirectory)"
    original="$(snapshot_field "$component" originalSystemPath)"
    if [ -n "$target" ] || [ -n "$original" ]; then
        snapshot_seen=1
    fi
    if [ -z "$target" ] && [ -n "$original" ]; then
        target="$(dirname "$original" 2>/dev/null)"
    fi
    [ "$target" = "." ] && target=""
    if [ "$snapshot_seen" = "1" ]; then
        [ -n "$target" ] || return 1
    else
        profile_check || return 1
        target="$profile_target"
    fi
    is_safe_target_directory "$target" || return 1
    printf '%s' "$target"
}

resolve_rollback_targets()
{
    local allow_empty="${1:-0}" snapshot_fp snapshot_model snapshot_operation journal_operation journal_state_value journal_model journal_fp journal_gms journal_gsf journal_store
    if [ -f "$SNAPSHOT_FILE" ]; then
        validate_snapshot || {
            say "ERROR: snapshot exists but is invalid or incompatible; refusing to use alternate paths."
            return 1
        }
        snapshot_operation="$(snapshot_top_field operationId)"
        [ "$(sed -n 's/^schemaVersion=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)" = "1" ] || {
            say "ERROR: journal is missing or has an incompatible schema; recovery blocked."
            return 1
        }
        journal_operation="$(sed -n 's/^operationId=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)"
        journal_state_value="$(sed -n 's/^state=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)"
        journal_model="$(sed -n 's/^model=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)"
        journal_fp="$(sed -n 's/^fingerprint=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)"
        journal_gms="$(sed -n 's/^targetGms=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)"
        journal_gsf="$(sed -n 's/^targetGsf=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)"
        journal_store="$(sed -n 's/^targetStore=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)"
        [ -n "$journal_operation" ] && [ "$snapshot_operation" = "$journal_operation" ] && \
            [ -n "$journal_state_value" ] && [ "$journal_model" = "$MODEL" ] && [ "$journal_fp" = "$FINGERPRINT" ] || {
            say "ERROR: journal is missing or belongs to another transaction; recovery blocked."
            return 1
        }
        if [ -n "$journal_gms$journal_gsf$journal_store" ]; then
            [ "$journal_gms" = "$(snapshot_field gms targetDirectory)" ] && \
                [ "$journal_gsf" = "$(snapshot_field gsf targetDirectory)" ] && \
                [ "$journal_store" = "$(snapshot_field store targetDirectory)" ] || return 1
        fi
    else
        if [ -f "$JOURNAL_FILE" ]; then
            journal_operation="$(sed -n 's/^operationId=//p' "$JOURNAL_FILE" | head -n 1)"
            journal_state_value="$(sed -n 's/^state=//p' "$JOURNAL_FILE" | head -n 1)"
            journal_model="$(sed -n 's/^model=//p' "$JOURNAL_FILE" | head -n 1)"
            journal_fp="$(sed -n 's/^fingerprint=//p' "$JOURNAL_FILE" | head -n 1)"
            [ "$(sed -n 's/^schemaVersion=//p' "$JOURNAL_FILE" | head -n 1)" = "1" ] && \
                [ -n "$journal_operation" ] && [ -n "$journal_state_value" ] && \
                [ "$journal_model" = "$MODEL" ] && [ "$journal_fp" = "$FINGERPRINT" ] || return 1
        fi
        if [ "$allow_empty" = "1" ] && [ ! -f "$JOURNAL_FILE" ]; then
            refresh_mount_facts
            if [ "$MOUNT_GMS" = "0" ] && [ "$MOUNT_GSF" = "0" ] && [ "$MOUNT_STORE" = "0" ]; then
                return 0
            fi
        fi
        journal_gms="$(sed -n 's/^targetGms=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)"
        journal_gsf="$(sed -n 's/^targetGsf=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)"
        journal_store="$(sed -n 's/^targetStore=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)"
        if [ -n "$journal_gms$journal_gsf$journal_store" ]; then
            [ -n "$journal_gms" ] && [ -n "$journal_gsf" ] && [ -n "$journal_store" ] || return 1
            is_safe_target_directory "$journal_gms" && is_safe_target_directory "$journal_gsf" && \
                is_safe_target_directory "$journal_store" || return 1
            targets_are_distinct "$journal_gms" "$journal_gsf" "$journal_store" || return 1
            TARGET_GMS="$journal_gms"; TARGET_GSF="$journal_gsf"; TARGET_STORE="$journal_store"
            return 0
        fi
        if [ "$allow_empty" = "1" ]; then
            refresh_mount_facts
            if [ "$MOUNT_GMS" = "0" ] && [ "$MOUNT_GSF" = "0" ] && [ "$MOUNT_STORE" = "0" ]; then
                return 0
            fi
        fi
    fi
    snapshot_fp="$(snapshot_top_field deviceFingerprint)"
    snapshot_model="$(snapshot_top_field model)"
    if [ -n "$snapshot_fp" ] && [ "$snapshot_fp" != "$FINGERPRINT" ]; then
        say "ERROR: snapshot belongs to another fingerprint; refusing to guess rollback targets."
        return 1
    fi
    if [ -n "$snapshot_model" ] && [ "$snapshot_model" != "$MODEL" ]; then
        say "ERROR: snapshot belongs to another model; refusing to guess rollback targets."
        return 1
    fi

    TARGET_GMS="$(rollback_target_from_snapshot gms "$PROFILE_GMS")" || {
        say "ERROR: could not safely resolve GMS rollback target."
        return 1
    }
    TARGET_GSF="$(rollback_target_from_snapshot gsf "$PROFILE_GSF")" || {
        say "ERROR: could not safely resolve GSF rollback target."
        return 1
    }
    TARGET_STORE="$(rollback_target_from_snapshot store "$PROFILE_STORE")" || {
        say "ERROR: could not safely resolve Store rollback target."
        return 1
    }
    if [ ! -f "$SNAPSHOT_FILE" ]; then
        refresh_mount_facts
        if [ "$allow_empty" = "1" ] && [ "$MOUNT_GMS" = "0" ] && [ "$MOUNT_GSF" = "0" ] && [ "$MOUNT_STORE" = "0" ]; then
            return 0
        fi
        [ "$MOUNT_GMS" = "1" ] || [ "$MOUNT_GSF" = "1" ] || [ "$MOUNT_STORE" = "1" ] || {
            say "ERROR: snapshot is missing and no known recovery mount confirms the legacy targets."
            return 1
        }
    fi
    return 0
}

pm_enable_or_defer()
{
    # Run post-boot (reindex-stock), never during pre-reboot restore-stock:
    # binder calls before reboot can hang while PM is rescanning.
    # Between unmount and reboot, PM may not register a stock
    # APK yet. This is expected if the stock directory exists; enabling remains
    # pending until the post-boot rescan. Other errors remain blocking.
    local pkg="$1" target="$2" label="$3" current
    current="$(with_timeout 15 pm path "$pkg" 2>/dev/null | sed -n 's/^package://p' | head -n 1)"
    if [ -n "$current" ]; then
        if with_timeout 15 pm list packages -d --user 0 2>/dev/null | grep -qx "package:$pkg"; then
            with_timeout 20 pm enable --user 0 "$pkg" >/dev/null 2>&1 || {
                say "ERROR: could not re-enable $label ($pkg), which is already registered with PM."
                return 1
            }
            say "  $label re-enabled ($pkg)."
        else
            # Avoid emitting PACKAGE_CHANGED unnecessarily. With a still-stale PM,
            # this could immediately start the APK that was just
            # unmounted, before the required reboot/reindexing.
            say "  $label was already enabled; no change sent to PM."
        fi
        return 0
    fi
    if global test -d "$target" 2>/dev/null; then
        REINDEX_REGISTRATIONS_STALE=1
        say "  $label is not registered with PM yet; enable is pending until the post-boot rescan."
        return 0
    fi
    say "ERROR: $label is missing from PM and the stock target does not exist: $target"
    return 1
}

package_registry_needs_rebuild()
{
    local pkg target apk current
    for pkg in "$GSF_PKG" "$STORE_PKG"; do
        case "$pkg" in
            "$GSF_PKG") target="$TARGET_GSF"; apk="GoogleServicesFramework.apk" ;;
            "$STORE_PKG") target="$TARGET_STORE"; apk="Phonesky.apk" ;;
            *) continue ;;
        esac
        current="$(pm_path "$pkg")"
        [ -n "$current" ] && continue
        global test -f "$target/$apk" 2>/dev/null || continue
        return 0
    done
    return 1
}

invalidate_package_registry()
{
    # PM uses the primary files and the reserve copy. Moving only the parser
    # cache lets Android reopen the stale registry on the next
    # boot. Keep everything backed up for manual recovery if the firmware
    # misbehaves.
    local backup_dir name source moved=""
    backup_dir="$BACKUP_BASE/package-registry-$(date +%s)-$$"
    global mkdir -p "$backup_dir" 2>/dev/null || return 1
    for name in packages.xml packages.list packages.xml.reservecopy packages-backup.xml; do
        source="$PM_SYSTEM_DIR/$name"
        [ -e "$source" ] || continue
        if global mv "$source" "$backup_dir/$name" 2>/dev/null; then
            moved="$moved $name"
            say "  PM registry moved: $source"
        else
            for name in $moved; do
                global mv "$backup_dir/$name" "$PM_SYSTEM_DIR/$name" 2>/dev/null || true
            done
            return 1
        fi
    done
    [ -n "$moved" ] || return 1
    say "  PM registry invalidated; the next boot will scan system apps."
    return 0
}

microg_data_is_cleared()
{
    # PackageManager may recreate the basic tree (cache/code_cache) shortly
    # after removal. Empty directories are harmless; any file,
    # link, or other payload means cleanup is not complete.
    local root residue
    for root in "$GMS_DATA_USER0" "$GMS_DATA_USERDE"; do
        [ -e "$root" ] || continue
        [ -d "$root" ] || return 1
        residue="$(find "$root" -mindepth 1 ! -type d -print -quit 2>/dev/null)" || return 1
        [ -z "$residue" ] || return 1
    done
    return 0
}

remove_microg_data_safely()
{
    local attempt=1
    while [ "$attempt" -le 3 ]; do
        # force-stop immediately before each attempt. Re-enabling GMS
        # before this point creates a race with persistent/UI processes.
        with_timeout 15 am force-stop "$GMS_PKG" 2>/dev/null || true
        rm -rf "$GMS_DATA_USER0" "$GMS_DATA_USERDE" 2>/dev/null || true
        if microg_data_is_cleared; then
            return 0
        fi
        say "  microG data was recreated during cleanup; retrying ($attempt/3)…"
        attempt=$((attempt + 1))
    done
    say "ERROR: microG data directories still contain payloads after 3 attempts."
    return 1
}

rollback_unmount()
{
    # rollback_unmount <label> <target> <known>
    local label="$1" target="$2" known="$3"
    [ -n "$target" ] && [ "$target" != "." ] || {
        say "ERROR: target $label is empty/invalid during rollback."
        return 1
    }
    is_mounted "$target" || return 0
    [ "$known" = "1" ] || {
        say "ERROR: mount $label at $target does not belong to a known mask."
        return 1
    }
    if ! global umount "$target" 2>/dev/null; then
        say "  $label busy; trying umount -l (lazy)..."
        global umount -l "$target" 2>/dev/null || {
            say "ERROR: could not unmount $label at $target."
            return 1
        }
    fi
    if is_mounted "$target"; then
        say "ERROR: mount $label still appears in mountinfo after unmount."
        return 1
    fi
    say "  $label unmounted: $target"
    return 0
}

restore_stock()
{
    check_root
    require_lock

    # Pre-reboot by design: unmount + mask cleanup + data wipe +
    # parse cache (file operations, no binder). NEVER move
    # packages.xml or call pm/am here: with PM rescanning, the call hangs,
    # the device freezes, and the watchdog reboots it. The stale registry and
    # re-enabling are deferred to post-boot reindex-stock.
    local wipe="${1:-}" foreign="" failed="" cache_backup restart_identity

    # Package Manager may not see GSF/Store because mounts
    # are active. Resolve targets before any mutation, using the snapshot
    # or a known-good profile; never use dirname on an empty string.
    collect_facts
    [ ! -e "$PM_CACHE_DIR" ] || [ "$CAP_PACKAGE_MANAGER_CACHE_ACCESS_STATUS" = "PASS" ] || \
        fail 3 "Package Manager cache exists but cannot be inspected or modified"
    resolve_rollback_targets || fail 3 "could not resolve all three rollback targets without guessing"
    [ -f "$SNAPSHOT_FILE" ] && DEGOOGLE_OPERATION_ID="$(snapshot_top_field operationId)"
    refresh_mount_facts

    [ "$MOUNT_GMS" = "1" ] && [ "$MOUNT_GMS_IS_KNOWN" != "1" ] && foreign="$foreign GMS"
    [ "$MOUNT_GSF" = "1" ] && [ "$MOUNT_GSF_IS_KNOWN" != "1" ] && foreign="$foreign GSF"
    [ "$MOUNT_STORE" = "1" ] && [ "$MOUNT_STORE_IS_KNOWN" != "1" ] && foreign="$foreign Store"
    [ -z "$foreign" ] || fail 4 "external mount detected:$foreign; refusing to unmount it automatically"

    journal_state "ROLLBACK_RUNNING" >/dev/null 2>&1 || fail 4 "could not record ROLLBACK_RUNNING"

    say "===== RESTORE-STOCK ====="
    say "Targets: GMS=$TARGET_GMS GSF=$TARGET_GSF Store=$TARGET_STORE"

    with_timeout 15 am force-stop "$GMS_PKG" 2>/dev/null || true
    with_timeout 15 am force-stop "$STORE_PKG" 2>/dev/null || true

    # Unmount everything before removing APKs from the source. This way, an
    # unmount failure preserves evidence and allows an idempotent retry.
    if [ "$MOUNT_GMS" = "1" ]; then
        if [ "${DEGOOGLE_FAIL_UMOUNT:-0}" = "1" ]; then
            say "ERROR: DEGOOGLE_FAIL_UMOUNT injected for GMS."
            failed="$failed GMS"
        elif rollback_unmount "GMS" "$TARGET_GMS" "$MOUNT_GMS_IS_KNOWN"; then
            journal_state "GMS_UNMOUNTED" || failed="$failed GMS(journal)"
        else
            failed="$failed GMS"
        fi
    fi
    if [ "$MOUNT_GSF" = "1" ]; then
        if rollback_unmount "GSF" "$TARGET_GSF" "$MOUNT_GSF_IS_KNOWN"; then
            journal_state "GSF_UNMOUNTED" || failed="$failed GSF(journal)"
        else
            failed="$failed GSF"
        fi
    fi
    if [ "$MOUNT_STORE" = "1" ]; then
        if rollback_unmount "Store" "$TARGET_STORE" "$MOUNT_STORE_IS_KNOWN"; then
            journal_state "STORE_UNMOUNTED" || failed="$failed Store(journal)"
        else
            failed="$failed Store"
        fi
    fi

    refresh_mount_facts
    [ "$MOUNT_GMS" = "0" ] || failed="$failed GMS(remanescente)"
    [ "$MOUNT_GSF" = "0" ] || failed="$failed GSF(remanescente)"
    [ "$MOUNT_STORE" = "0" ] || failed="$failed Store(remanescente)"
    if [ -n "$failed" ]; then
        journal_state "ROLLBACK_REQUIRED" >/dev/null 2>&1 || true
        fail 4 "Could not unmount:$failed — sources preserved for retry."
    fi

    # Remove files only after proving no target remains
    # mounted. The GSF directory is intentionally empty. Cleanup also
    # removes only known payloads from the legacy manual script.
    cleanup_known_mask_residue || {
        journal_state "ROLLBACK_REQUIRED" >/dev/null 2>&1 || true
        fail 4 "could not remove known APKs from masks after unmounting them"
    }

    # Orphaned microG data (with explicit app consent). Run BEFORE
    # intentionally invalidating PM cache/registry: remove_microg_data uses
    # `am` (binder), and with the registry deleted, system_server may be
    # rescanning; the call hangs, load spikes, and the device appears
    # frozen. After the invalidation below, make no PM calls.
    if [ "$wipe" = "--wipe-data" ]; then
        remove_microg_data_safely || fail 4 "could not remove microG data"
        say "  microG data removed from /data."
    else
        say "  microG data preserved in /data (use --wipe-data to remove)."
    fi

    # Invalidate the PackageManager parse cache. A reboot is still required
    # for Android to reindex system apps whose codePath did not change.
    if [ -d "$PM_CACHE_DIR" ] && [ -n "$(ls -A "$PM_CACHE_DIR" 2>/dev/null)" ]; then
        mkdir -p "$BACKUP_BASE" 2>/dev/null || {
            journal_state "REINDEX_PENDING" >/dev/null 2>&1 || true
            fail 4 "could not prepare a backup of PackageManager cache"
        }
        cache_backup="$BACKUP_BASE/pm-cache-$(date +%s)"
        if mv "$PM_CACHE_DIR" "$cache_backup" 2>/dev/null; then
            say "  PM parse cache moved to $cache_backup (forced rescan)."
        elif rm -rf "${PM_CACHE_DIR:?}"/* 2>/dev/null || rm -rf "$PM_CACHE_DIR" 2>/dev/null; then
            say "  PM parse cache cleared (forced rescan)."
        else
            journal_state "REINDEX_PENDING" >/dev/null 2>&1 || true
            fail 4 "could not clear $PM_CACHE_DIR; mounts are removed, reboot/reindexing is required"
        fi
    else
        say "  PM parse cache is missing/empty — nothing to do."
    fi

    journal_state "PACKAGE_CACHE_INVALIDATED" || fail 4 "cache invalidated, but the journal could not be updated"

    # From here to the end, make no binder calls (`am`/`pm`/`cmd`): with the cache
    # After moving it, system_server may be rescanning and any call may hang
    # (the pre-reboot `pm enable` call was freezing the device).
    # Store/GMS are re-enabled during post-boot reindex-stock.
    if package_registry_needs_rebuild; then
        say "  PM registry appears stale; reindex-stock will handle it after reboot."
    fi
    say "  Play Store/GMS re-enable is pending until the post-boot rescan (reindex-stock)."

    say ""
    say "RESTORE-STOCK PREPARED. Package Manager reindexing and reboot are required."
    say "The microG backup at $BACKUP_BASE was preserved."
    restart_identity="$(framework_identity)" || fail 4 "rollback completed, but could not capture the system_server identity before restart"
    persist_reboot_baseline "$restart_identity" || fail 4 "rollback completed, but could not save restart validation evidence"
    journal_state "REINDEX_PENDING" || fail 4 "rollback completed, but could not record REINDEX_PENDING"
    emit REINDEX_PENDING "1"
    emit REBOOT_REQUIRED "1"
    emit STATE "RESTORE_PREPARED"
    return 0
}

reindex_stock()
{
    # Post-boot: PM is healthy again; binder calls are allowed here (with a timeout).
    # Invalidate the stale cache/registry with a backup, then re-enable Store/GMS.
    check_root
    require_lock

    local cache_backup failed=""
    REINDEX_REGISTRATIONS_STALE=0

    stock_reindex_ready || fail 3 "a verified userspace restart after restore-stock is required before reindexing"

    collect_facts
    [ ! -e "$PM_CACHE_DIR" ] || [ "$CAP_PACKAGE_MANAGER_CACHE_ACCESS_STATUS" = "PASS" ] || \
        fail 3 "Package Manager cache exists but cannot be inspected or modified"
    resolve_rollback_targets || fail 3 "could not resolve targets without guessing"
    [ -f "$SNAPSHOT_FILE" ] && DEGOOGLE_OPERATION_ID="$(snapshot_top_field operationId)"
    refresh_mount_facts

    [ "$STOCK_REINDEX_PENDING" = "1" ] && [ "$STOCK_REINDEX_READY" = "1" ] || \
        fail 3 "stock reindex is not pending after a verified userspace restart"

    [ "$MOUNT_GMS" = "0" ] || failed="$failed GMS"
    [ "$MOUNT_GSF" = "0" ] || failed="$failed GSF"
    [ "$MOUNT_STORE" = "0" ] || failed="$failed Store"
    [ -z "$failed" ] || fail 4 "active mounts remain:$failed — run restore-stock before reindexing"

    say "===== REINDEX-STOCK ====="

    if [ -d "$PM_CACHE_DIR" ] && [ -n "$(ls -A "$PM_CACHE_DIR" 2>/dev/null)" ]; then
        mkdir -p "$BACKUP_BASE" 2>/dev/null || fail 4 "could not prepare a backup of PackageManager cache"
        cache_backup="$BACKUP_BASE/pm-cache-$(date +%s)"
        if mv "$PM_CACHE_DIR" "$cache_backup" 2>/dev/null; then
            say "  PM parse cache moved to $cache_backup (forced rescan)."
        elif rm -rf "${PM_CACHE_DIR:?}"/* 2>/dev/null || rm -rf "$PM_CACHE_DIR" 2>/dev/null; then
            say "  PM parse cache cleared (forced rescan)."
        else
            fail 4 "could not clear $PM_CACHE_DIR"
        fi
    else
        say "  PM parse cache is missing/empty — nothing to do."
    fi

    if package_registry_needs_rebuild; then
        invalidate_package_registry || fail 4 "could not invalidate packages.xml with a backup"
    fi

    pm_enable_or_defer "$STORE_PKG" "$TARGET_STORE" "Play Store" || {
        journal_state "ROLLBACK_REQUIRED" >/dev/null 2>&1 || true
        fail 4 "could not re-enable Play Store"
    }
    pm_enable_or_defer "$GMS_PKG" "$TARGET_GMS" "GMS" || {
        journal_state "ROLLBACK_REQUIRED" >/dev/null 2>&1 || true
        fail 4 "could not re-enable GMS"
    }

    path_under_target "$(pm_path "$GMS_PKG")" "$TARGET_GMS" || REINDEX_REGISTRATIONS_STALE=1
    path_under_target "$(pm_path "$GSF_PKG")" "$TARGET_GSF" || REINDEX_REGISTRATIONS_STALE=1
    path_under_target "$(pm_path "$STORE_PKG")" "$TARGET_STORE" || REINDEX_REGISTRATIONS_STALE=1

    if [ "$REINDEX_REGISTRATIONS_STALE" = "1" ]; then
        journal_state "REINDEX_PENDING" || fail 4 "stock registrations are still pending, but could not persist REINDEX_PENDING"
        say "REINDEX-STOCK INCOMPLETE. Package Manager has not registered all stock packages yet."
        emit REINDEX_PENDING "1"
        return 0
    fi

    write_probe_baseline || fail 4 "stock packages are confirmed, but the probe baseline could not be refreshed"
    journal_state "STOCK_CONFIRMED" || fail 4 "stock packages confirmed, but could not persist the state"
    say "REINDEX-STOCK COMPLETE."
    emit REINDEX_OK "1"
    return 0
}

cleanup_known_mask_residue()
{
    # Never recursively remove /data/local/tmp. Only APKs, the
    # Mapbox library, and directories from the two known layouts are candidates;
    # Unknown files are preserved for diagnostics.
    local p
    for p in \
        "$MASK_GMS/lib/arm64/libmapbox-gl.so" \
        "$MASK_GMS/lib/arm/libmapbox-gl.so" \
        "$MASK_GMS/lib/x86_64/libmapbox-gl.so" \
        "$MASK_GMS/lib/x86/libmapbox-gl.so"; do
        rm -f "$p" || return 1
    done
    for p in \
        "$MASK_GMS/lib/arm64" "$MASK_GMS/lib/arm" \
        "$MASK_GMS/lib/x86_64" "$MASK_GMS/lib/x86" "$MASK_GMS/lib"; do
        rmdir "$p" 2>/dev/null || true
    done
    for p in \
        "$MASK_GMS/$GMS_APK" "$MASK_STORE/$STORE_APK" \
        "$LEGACY_MASK_GMS/$GMS_APK" "$LEGACY_MASK_STORE/FakeStore.apk"; do
        rm -f "$p" || return 1
    done
    for p in \
        "$MASK_GMS" "$MASK_GSF" "$MASK_STORE" "$MASK_BASE" \
        "$LEGACY_MASK_GMS" "$LEGACY_MASK_GSF" "$LEGACY_MASK_STORE" "$LEGACY_MASK_BASE"; do
        rmdir "$p" 2>/dev/null || true
    done
    refresh_residue_facts
    return 0
}

cleanup_residue()
{
    check_root
    require_lock

    local foreign=""
    collect_facts
    if [ -e "$SNAPSHOT_FILE" ] || [ -e "$JOURNAL_FILE" ]; then
        resolve_rollback_targets || fail 3 "could not resolve recovery targets"
    else
        is_safe_target_directory "$TARGET_GMS" && \
            is_safe_target_directory "$TARGET_GSF" && \
            is_safe_target_directory "$TARGET_STORE" && \
            targets_are_distinct "$TARGET_GMS" "$TARGET_GSF" "$TARGET_STORE" || \
            fail 3 "could not resolve three safe cleanup targets"
        refresh_mount_facts
    fi
    [ -f "$SNAPSHOT_FILE" ] && DEGOOGLE_OPERATION_ID="$(snapshot_top_field operationId)"
    refresh_mount_facts

    [ "$MOUNT_GMS" = "1" ] && foreign="$foreign GMS"
    [ "$MOUNT_GSF" = "1" ] && foreign="$foreign GSF"
    [ "$MOUNT_STORE" = "1" ] && foreign="$foreign Store"
    [ -z "$foreign" ] || fail 4 "active mount detected:$foreign; residue will not be removed while a mount exists"

    cleanup_known_mask_residue || fail 4 "could not clean known mask payloads"
    say "Known mask residue removed; microG backup preserved."
    emit RESIDUE_CLEANUP "1"
    emit MASK_RESIDUE_PRESENT "$MASK_RESIDUE_PRESENT"
    emit STATE "STOCK"
    return 0
}

post_boot_failure()
{
    local reason="$1"
    POST_BOOT_STATUS="FAIL"
    POST_BOOT_REASON="$reason"
    emit POST_BOOT_STATUS "FAIL"
    if ! journal_state "ROLLBACK_REQUIRED" >/dev/null 2>&1; then
        reason="$reason; could not record ROLLBACK_REQUIRED"
    fi
    say "ERROR: $reason"
    emit POST_BOOT_REASON "$reason"
    return 4
}

verify_pure_masks()
{
    local component mask target expected_owner expected_mode expected_context actual_owner actual_mode actual_context
    for component in gms gsf store; do
        case "$component" in
            gms) mask="$MASK_GMS"; target="$TARGET_GMS" ;;
            gsf) mask="$MASK_GSF"; target="$TARGET_GSF" ;;
            store) mask="$MASK_STORE"; target="$TARGET_STORE" ;;
        esac
        is_safe_target_directory "$target" || return 1
        is_masked_by_us "$target" "$mask" "" || return 1
        [ -d "$mask" ] && [ -z "$(find "$mask" -mindepth 1 -print -quit 2>/dev/null)" ] || return 1
        expected_owner="$(snapshot_field "$component" owner)"
        expected_mode="$(snapshot_field "$component" mode)"
        expected_context="$(snapshot_field "$component" selinuxContext)"
        actual_owner="$(global stat -c %u:%g "$mask" 2>/dev/null)"
        actual_mode="$(global stat -c %a "$mask" 2>/dev/null)"
        actual_context="$(global ls -Zd "$mask" 2>/dev/null | awk '{print $1}')"
        [ "$actual_owner" = "$expected_owner" ] && [ "$actual_mode" = "$expected_mode" ] && \
            [ "$actual_context" = "$expected_context" ] || return 1
    done
    return 0
}

post_boot_validate()
{
    check_root
    collect_facts
    if ! journal_state "POST_BOOT_VALIDATING" >/dev/null 2>&1; then
        post_boot_failure "could not record POST_BOOT_VALIDATING"
        return $?
    fi

    local boot_completed server_pid boot_reason restart_identity old_boot old_pid old_start new_boot new_pid new_start
    [ -f "$REBOOT_BASELINE_FILE" ] || { post_boot_failure "restart baseline is missing"; return $?; }
    old_boot="$(sed -n 's/^kernelBootId=//p' "$REBOOT_BASELINE_FILE" | head -n 1)"
    old_pid="$(sed -n 's/^systemServerPid=//p' "$REBOOT_BASELINE_FILE" | head -n 1)"
    old_start="$(sed -n 's/^systemServerStart=//p' "$REBOOT_BASELINE_FILE" | head -n 1)"
    restart_identity="$(framework_identity)" || { post_boot_failure "could not read current restart identity"; return $?; }
    new_boot="$(printf '%s\n' "$restart_identity" | sed -n '1p')"
    new_pid="$(printf '%s\n' "$restart_identity" | sed -n '2p')"
    new_start="$(printf '%s\n' "$restart_identity" | sed -n '3p')"
    [ -n "$old_boot" ] && [ -n "$old_pid" ] && [ -n "$old_start" ] || { post_boot_failure "restart baseline is incomplete"; return $?; }
    [ "$new_boot" = "$old_boot" ] || { post_boot_failure "kernel boot identity changed; full reboot is not accepted"; return $?; }
    [ "$new_pid:$new_start" != "$old_pid:$old_start" ] || { post_boot_failure "system_server identity did not change after restart request"; return $?; }
    boot_completed="$(getprop sys.boot_completed 2>/dev/null)"
    case "$boot_completed" in
        1) ;;
        *) post_boot_failure "sys.boot_completed did not confirm boot completion"; return $? ;;
    esac
    server_pid="$(pidof system_server 2>/dev/null || true)"
    [ -n "$server_pid" ] || server_pid="$(ps -A 2>/dev/null | awk '$NF == "system_server" {print $1; exit}')"
    [ -n "$server_pid" ] || {
        post_boot_failure "system_server is not running"
        return $?
    }
    boot_reason="$(getprop sys.boot.reason 2>/dev/null || true)"
    case "$boot_reason" in
        *rescueparty*|*rescue_party*)
            post_boot_failure "boot reason indica Rescue Party: $boot_reason"; return $? ;;
    esac
    [ "$MOUNT_GMS_IS_OURS" = "1" ] || { post_boot_failure "GMS mask mount ownership is not verified"; return $?; }
    [ "$MOUNT_GSF_IS_OURS" = "1" ] || { post_boot_failure "GSF mask mount ownership is not verified"; return $?; }
    [ "$MOUNT_STORE_IS_OURS" = "1" ] || { post_boot_failure "Store mask mount ownership is not verified"; return $?; }
    case "$SELINUX" in Enforcing|Permissive) ;; *) post_boot_failure "SELinux is not enforcing"; return $? ;; esac

    if [ "$OPERATION_MODE" = "PURE" ]; then
        verify_pure_masks || { post_boot_failure "pure mask ownership, metadata, or emptiness validation failed"; return $?; }
        pm_package_absent "$GMS_PKG" || { post_boot_failure "GMS is still registered or Package Manager could not confirm absence"; return $?; }
        pm_package_absent "$GSF_PKG" || { post_boot_failure "GSF is still registered or Package Manager could not confirm absence"; return $?; }
        pm_package_absent "$STORE_PKG" || { post_boot_failure "Play Store is still registered or Package Manager could not confirm absence"; return $?; }
        journal_state "COMMITTED" || { post_boot_failure "could not record COMMITTED"; return $?; }
        rm -f "$RESCUE_STATE_FILE" 2>/dev/null || true
        POST_BOOT_STATUS="PASS"
        POST_BOOT_REASON="system_server, boot_completed, empty owned masks, package absence, and SELinux validated"
        emit POST_BOOT_STATUS "$POST_BOOT_STATUS"
        emit POST_BOOT_REASON "$POST_BOOT_REASON"
        return 0
    fi

    [ -z "$(find "$MASK_GSF" -mindepth 1 -print -quit 2>/dev/null)" ] || {
        post_boot_failure "GSF mask is not empty"
        return $?
    }
    path_under_target "$GMS_PATH" "$TARGET_GMS" || { post_boot_failure "GMS does not resolve to the mask"; return $?; }
    [ -z "$GSF_PATH" ] || { post_boot_failure "stock GSF is still active"; return $?; }
    path_under_target "$STORE_PATH" "$TARGET_STORE" || { post_boot_failure "Companion does not resolve to the mask"; return $?; }
    [ "$GMS_PRIVILEGED" = "1" ] || { post_boot_failure "GMS is not PRIVILEGED"; return $?; }

    journal_state "COMMITTED" || {
        post_boot_failure "could not record COMMITTED"
        return $?
    }
    rm -f "$RESCUE_STATE_FILE" 2>/dev/null || true
    POST_BOOT_STATUS="PASS"
    POST_BOOT_REASON="system_server, boot_completed, packages, priv-app, and SELinux validated"
    emit POST_BOOT_STATUS "PASS"
    emit POST_BOOT_REASON "$POST_BOOT_REASON"
    return 0
}

# ===========================================================================
# soft-reboot
# ===========================================================================

soft_reboot()
{
    check_root
    local source now last attempts cooldown elapsed remaining previous_state restart_identity method selected_identity current_identity
    local power_output power_status
    collect_facts
    [ "$CAP_SAFE_SOFT_REBOOT_STATUS" = "PASS" ] || {
        [ "$CAP_SAFE_SOFT_REBOOT_STATUS" = "WARN" ] &&
            { [ "${DEGOOGLE_ALLOW_FALLBACK_REBOOT:-0}" = "1" ] || [ "${DEGOOGLE_ALLOW_ZYGOTE_RESTART:-0}" = "1" ]; } ||
            fail_en 5 "No applicable userspace restart mechanism is confirmed or explicitly enabled."
    }
    method="$REBOOT_STRATEGY_METHOD"
    case "$method" in SYS_POWERCTL_USERSPACE|KSUD_SOFT_REBOOT|ZYGOTE_RESTART) ;; *) fail_en 5 "The selected userspace restart mechanism is unresolved or unsupported." ;; esac
    source="${DEGOOGLE_REBOOT_SOURCE:-manual}"
    case "$source" in
        automatic|manual) ;;
        *) source="manual" ;;
    esac
    cooldown="${DEGOOGLE_REBOOT_COOLDOWN_SECONDS:-3600}"
    case "$cooldown" in ''|*[!0-9]*) cooldown=3600 ;; esac
    now="$(date +%s 2>/dev/null || echo 0)"
    last=0; attempts=0
    previous_state="$(sed -n 's/^state=//p' "$JOURNAL_FILE" 2>/dev/null | head -n 1)"
    restart_identity="$(framework_identity)" || fail_en 3 "Could not capture kernel and system_server identity before userspace restart."
    if [ "$previous_state" = "REBOOT_REQUESTED" ] && [ -f "$REBOOT_BASELINE_FILE" ]; then
        selected_identity="$(sed -n 's/^kernelBootId=//p' "$REBOOT_BASELINE_FILE" | head -n 1):$(sed -n 's/^systemServerPid=//p' "$REBOOT_BASELINE_FILE" | head -n 1):$(sed -n 's/^systemServerStart=//p' "$REBOOT_BASELINE_FILE" | head -n 1)"
        current_identity="$(printf '%s\n' "$restart_identity" | sed -n '1p'):$(printf '%s\n' "$restart_identity" | sed -n '2p'):$(printf '%s\n' "$restart_identity" | sed -n '3p')"
        [ "$selected_identity" != "$current_identity" ] || \
            fail_en 5 "The previous userspace restart request has not been observed; refusing to request it again."
    fi
    if [ "$source" = "automatic" ] && [ -f "$RESCUE_STATE_FILE" ]; then
        last="$(sed -n 's/^last=//p' "$RESCUE_STATE_FILE" 2>/dev/null | head -n 1)"
        attempts="$(sed -n 's/^attempts=//p' "$RESCUE_STATE_FILE" 2>/dev/null | head -n 1)"
    fi
    case "$last" in ''|*[!0-9]*) last=0 ;; esac
    case "$attempts" in ''|*[!0-9]*) attempts=0 ;; esac
    case "$now" in ''|*[!0-9]*) now=0 ;; esac
    if [ "$source" = "automatic" ] && [ "$attempts" -ge 1 ] &&
        [ "$last" -gt 0 ] && [ "$now" -ge "$last" ]; then
        elapsed=$((now - last))
        if [ "$elapsed" -lt "$cooldown" ]; then
            remaining=$((cooldown - elapsed))
            fail_en 6 "Automatic userspace reboot cooldown is active; retry in ${remaining}s."
        fi
    fi
    journal_state "REBOOT_REQUESTED" || fail_en 4 "Could not record the reboot request."
    persist_reboot_baseline "$restart_identity" || fail_en 4 "Could not persist restart validation evidence."
    if [ "$source" = "automatic" ]; then
        mkdir -p "$TRANSACTION_BASE" 2>/dev/null || {
            [ -n "$previous_state" ] && journal_state "$previous_state" >/dev/null 2>&1 || true
            fail_en 3 "Could not persist the automatic reboot guard."
        }
        {
            printf 'last=%s\n' "$now"
            printf 'attempts=%s\n' "$((attempts + 1))"
        } > "$RESCUE_STATE_FILE" || {
            [ -n "$previous_state" ] && journal_state "$previous_state" >/dev/null 2>&1 || true
            fail_en 3 "Could not persist the automatic reboot guard."
        }
    fi
    say "Requesting userspace reboot..."
    # AOSP userspace restart is used only when init advertises support.
    case "$method" in
        SYS_POWERCTL_USERSPACE) power_output="$(setprop sys.powerctl reboot,userspace 2>&1)"; power_status=$? ;;
        KSUD_SOFT_REBOOT)
            power_output="$("$KSUD_PATH" reboot 2>&1)"; power_status=$?
            ;;
        ZYGOTE_RESTART)
            if command -v setprop >/dev/null 2>&1; then
                power_output="$(setprop ctl.restart zygote 2>&1)"; power_status=$?
            else
                power_output="$(stop; start 2>&1)"; power_status=$?
            fi
            ;;
    esac
    if [ "$power_status" -eq 0 ]; then
        say "$method accepted."
        return 0
    fi
    say "AOSP userspace reboot rejected (exit ${power_status})."
    [ -n "$power_output" ] && say "AOSP detail: $power_output"
    # IMPORTANT: devices with root via exploit must NEVER use a full reboot;
    # a kernel reboot loses root and microG. Abort with a safe instruction
    # instead of suggesting a full reboot.
    [ "$source" = "automatic" ] && rm -f "$RESCUE_STATE_FILE" 2>/dev/null || true
    if [ -n "$previous_state" ]; then
        journal_state "$previous_state" >/dev/null 2>&1 || true
    else
        journal_state "FAILED" >/dev/null 2>&1 || true
    fi
    fail_en 5 "The confirmed AOSP userspace restart request failed; no fallback was attempted."
}

# ===========================================================================
# test — self-test (does not touch the device)
# ===========================================================================

self_test()
{
    local tmpdir ok=0
    tmpdir="$(mktemp -d 2>/dev/null || echo /data/local/tmp/degoogle-selftest)"
    mkdir -p "$tmpdir"

    say "[test] is_apk"
    printf 'PK\003\004fake' > "$tmpdir/ok.apk"
    printf 'notanapk' > "$tmpdir/bad.apk"
    if is_apk "$tmpdir/ok.apk"; then say "  ok.apk recognized as APK"; else say "  ERROR: ok.apk rejected"; ok=1; fi
    if is_apk "$tmpdir/bad.apk"; then say "  ERROR: bad.apk accepted"; ok=1; else say "  bad.apk rejected"; fi

    say "[test] parse mountinfo"
    cat > "$tmpdir/mountinfo" <<'EOF'
36 27 0:18 /data/local/tmp/degoogle-mask/gms /product/priv-app/GmsCore rw,relatime - ext4 /dev/block/sda1 rw
37 27 0:18 / /system rw,relatime - ext4 /dev/block/sda2 ro
EOF
    local r
    r="$(MOUNTINFO="$tmpdir/mountinfo" mountinfo_root /product/priv-app/GmsCore)"
    if [ "$r" = "/data/local/tmp/degoogle-mask/gms" ]; then
        say "  mountinfo_root(GmsCore) = $r"
    else
        say "  ERROR: mountinfo_root returned '$r'"; ok=1
    fi
    if MOUNTINFO="$tmpdir/mountinfo" is_mounted /system; then
        say "  is_mounted(/system)"
    else
        say "  ERROR: /system not detected"; ok=1
    fi

    say "[test] usage"
    if "$0" no-such-cmd >/dev/null 2>&1; then say "  ERROR: unknown command returned 0"; ok=1; else say "  nonexistent command → exit != 0"; fi

    rm -rf "$tmpdir"
    if [ "$ok" = "0" ]; then
        say "SELF-TEST OK."
        return 0
    fi
    fail 1 "SELF-TEST FAILED."
}

# ===========================================================================
# dispatch
# ===========================================================================

case "${1:-}" in
    probe)
        probe
        ;;
    prepare)
        shift
        prepare "$@"
        ;;
    finalize)
        finalize
        ;;
    cleanup)
        cleanup
        ;;
    cleanup-residue)
        cleanup_residue
        ;;
    backup)
        backup
        ;;
    restore-backup)
        restore_backup
        ;;
    restore-stock)
        restore_stock "${2:-}"
        ;;
    reindex-stock)
        reindex_stock
        ;;
    soft-reboot)
        soft_reboot
        ;;
    status)
        status
        ;;
    dry-run)
        dry_run
        ;;
    preflight)
        preflight
        ;;
    post-boot-validate)
        post_boot_validate
        ;;
    unlock)
        unlock
        ;;
    rollback)
        restore_stock "${2:-}"
        ;;
    test)
        self_test
        ;;
    *)
        fail_usage "probe|dry-run|preflight|prepare <gms> <companion> <gms-sha256> <companion-sha256>|finalize|cleanup|cleanup-residue|backup|restore-backup|restore-stock [--wipe-data]|reindex-stock|rollback [--wipe-data]|post-boot-validate|soft-reboot|unlock|status|test"
        ;;
esac
