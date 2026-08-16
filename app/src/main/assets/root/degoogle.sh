#!/system/bin/sh
# =============================================================================
# degoogle.sh — backend DeGoogle (v0.2.0)
#
# Backend shell do app DeGoogle. Opera exclusivamente com root; o profile
# abaixo é a baseline legacy do S24, enquanto o fluxo experimental usa paths
# descobertos sob allowlist e aprovação do Compatibility Engine. Todas as
# operações são idempotentes; operações destrutivas têm rollback; nada é
# declarado sucesso sem verificação.
#
# Protocolo de saída:
#   stdout = linhas "DEGOOGLE_KEY=value" (máquina; value = resto da linha após
#            o primeiro '='). NUNCA misture texto humano no stdout.
#   stderr = progresso/erros legíveis (mostrado na seção técnica do app).
#
# Exit codes:
#   0 ok · 1 erro geral · 2 uso · 3 pré-condição falhou (nada foi alterado) ·
#   4 rollback falhou / estado parcial · 5 perfil incompatível (probe)
#
# Injection de falhas para testes (em aparelho ou emulado):
#   DEGOOGLE_FAIL_CP=1 DEGOOGLE_FAIL_MOUNT_GMS=1 DEGOOGLE_FAIL_MOUNT_GSF=1
#   DEGOOGLE_FAIL_MOUNT_STORE=1 DEGOOGLE_FAIL_RESTORECON=1 DEGOOGLE_FAIL_BACKUP=1
#   DEGOOGLE_FAIL_RESTORE=1 DEGOOGLE_FAIL_UMOUNT=1
#
# Uso:
#   degoogle.sh probe
#   degoogle.sh prepare <gms.apk> <companion.apk>
#   degoogle.sh finalize
#   degoogle.sh backup
#   degoogle.sh restore-backup
#   degoogle.sh restore-stock [--wipe-data]
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
# Device profile (v1: Samsung Galaxy S24 Ultra / SM-S928x)
# ---------------------------------------------------------------------------
PROFILE_ID="samsung_sm-s928b"
PROFILE_MANUFACTURER="samsung"
PROFILE_MODELS="SM-S928B|SM-S928U|SM-S928W|SM-S928N|SM-S9280"

GMS_PKG="com.google.android.gms"
GSF_PKG="com.google.android.gsf"
STORE_PKG="com.android.vending"
FAKEGAPPS_PKG="${DEGOOGLE_FAKEGAPPS_PACKAGE:-inc.whew.android.fakegapps}"
LSPOSED_MARKER="${DEGOOGLE_LSPOSED_MARKER:-/data/adb/modules/zygisk_lsposed/module.prop}"

# Paths podem ser sobrescritos por env (usado pelo harness de teste em host;
# no aparelho valem os defaults do device profile)
PM_CACHE_DIR="${DEGOOGLE_PM_CACHE:-/data/system/package_cache}"

PROFILE_GMS="${DEGOOGLE_PROFILE_GMS:-/product/priv-app/GmsCore}"
PROFILE_GSF="${DEGOOGLE_PROFILE_GSF:-/system_ext/priv-app/GoogleServicesFramework}"
PROFILE_STORE="${DEGOOGLE_PROFILE_STORE:-/product/priv-app/Phonesky}"

MASK_BASE="${DEGOOGLE_MASK_BASE:-/data/local/tmp/degoogle-mask}"
MASK_GMS="$MASK_BASE/gms"
MASK_GSF="$MASK_BASE/gsf"
MASK_STORE="$MASK_BASE/store"

# Layout usado pelo script manual legado. Ele continua sendo uma origem
# conhecida para recuperação, mas nunca é tratado como máscara atual do app.
LEGACY_MASK_BASE="${DEGOOGLE_LEGACY_MASK_BASE:-/data/local/tmp/microg-mask}"
LEGACY_MASK_GMS="$LEGACY_MASK_BASE/gms"
LEGACY_MASK_GSF="$LEGACY_MASK_BASE/gsf"
LEGACY_MASK_STORE="$LEGACY_MASK_BASE/store"
GMS_APK="GmsCore.apk"
STORE_APK="Companion.apk"

# Alvos operacionais descobertos pelo Package Locator. Os PROFILE_* continuam
# sendo a baseline legacy do S24; estes valores só entram no fluxo quando o
# pacote foi localizado e passou pelas raízes permitidas.
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

# Layout idêntico ao microg-session.sh. As variáveis permitem testar o backend
# em host sem alterar os caminhos Android reais.
GMS_DATA_USER0="${DEGOOGLE_GMS_DATA_USER0:-/data/user/0/com.google.android.gms}"
GMS_DATA_USERDE="${DEGOOGLE_GMS_DATA_USERDE:-/data/user_de/0/com.google.android.gms}"

LOCK_DIR="${DEGOOGLE_LOCK_DIR:-/data/local/tmp/degoogle.lockdir}"

MOUNTINFO="${DEGOOGLE_MOUNTINFO:-/proc/1/mountinfo}"

# Estado persistente da transação. O diretório contém somente snapshots e
# journal do DeGoogle; nunca é usado como alvo de bind mount do sistema.
TRANSACTION_BASE="${DEGOOGLE_TRANSACTION_BASE:-$BACKUP_BASE/transaction}"
JOURNAL_FILE="$TRANSACTION_BASE/journal"
SNAPSHOT_FILE="$TRANSACTION_BASE/snapshot.json"
PROBE_BASELINE_FILE="$TRANSACTION_BASE/probe-baseline"
RESCUE_STATE_FILE="${DEGOOGLE_RESCUE_STATE:-$TRANSACTION_BASE/rescue-party.state}"
KSUD_PATH="${DEGOOGLE_KSUD_PATH:-/data/adb/ksud}"

# Variáveis de fatos (preenchidas por collect_facts, usadas por compute_state)
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
GMS_PRIVILEGED=0
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
MASK_RESIDUE_PRESENT=0
FINALIZE_DONE=0
REBOOT_STRATEGY_BACKEND=""
REBOOT_STRATEGY_METHOD=""
REBOOT_STRATEGY_CONFIDENCE="UNKNOWN"
REBOOT_STRATEGY_FIRMWARE_VALIDATED=0

# Status das capabilities. O valor UNKNOWN é intencional e conservador.
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
    # KEY VALUE (sem newline no value)
    printf 'DEGOOGLE_%s=%s\n' "$1" "$(printf '%s' "$2" | tr '\n' ' ')"
}

fail()
{
    local code="$1"
    shift
    say "ERRO: $*"
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
    say "Uso: $0 $*"
    exit 2
}

check_root()
{
    [ "$(id -u 2>/dev/null)" = "0" ] || fail 3 "Este backend exige root (id -u = 0)."
}

require_lock()
{
    if ! mkdir "$LOCK_DIR" 2>/dev/null; then
        say "ERRO: outra operação DeGoogle está em andamento (lock: $LOCK_DIR)."
        exit 3
    fi
    trap 'rmdir "$LOCK_DIR" 2>/dev/null || true' EXIT INT TERM
}

global()
{
    nsenter --mount=/proc/1/ns/mnt -- "$@"
}

global_exec() { global "$@"; }
global_mount() { global mount "$@"; }
global_umount() { global umount "$@"; }
global_mountinfo() { cat "$MOUNTINFO" 2>/dev/null; }

mount_namespace_identity()
{
    # O processo que chama o backend pode estar em um namespace privado. A
    # evidência importante é que o helper global realmente termina no mesmo
    # namespace do PID 1 antes de qualquer alteração destrutiva.
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
    local state="$1" tmp
    mkdir -p "$TRANSACTION_BASE" 2>/dev/null || return 1
    tmp="$JOURNAL_FILE.tmp.$$"
    {
        printf 'schemaVersion=1\n'
        printf 'operationId=%s\n' "${DEGOOGLE_OPERATION_ID:-unknown}"
        printf 'timestamp=%s\n' "$(date +%s 2>/dev/null || echo 0)"
        printf 'state=%s\n' "$state"
        printf 'model=%s\n' "$MODEL"
        printf 'fingerprint=%s\n' "$FINGERPRINT"
    } > "$tmp" || return 1
    mv "$tmp" "$JOURNAL_FILE" || return 1
    return 0
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
        printf '  "gms": {"activeCodePath":"%s","originalSystemPath":"%s","targetDirectory":"%s","sha256":"%s","owner":"%s","mode":"%s","selinuxContext":"%s"},\n' "$GMS_ACTIVE_CODE_PATH" "$GMS_ORIGINAL_SYSTEM_PATH" "$TARGET_GMS" "$gms_hash" "$ORIGINAL_GMS_OWNER" "$ORIGINAL_GMS_MODE" "$ORIGINAL_GMS_CONTEXT"
        printf '  "gsf": {"activeCodePath":"%s","originalSystemPath":"%s","targetDirectory":"%s","sha256":"%s","owner":"%s","mode":"%s","selinuxContext":"%s"},\n' "$GSF_ACTIVE_CODE_PATH" "$GSF_ORIGINAL_SYSTEM_PATH" "$TARGET_GSF" "$gsf_hash" "$ORIGINAL_GSF_OWNER" "$ORIGINAL_GSF_MODE" "$ORIGINAL_GSF_CONTEXT"
        printf '  "store": {"activeCodePath":"%s","originalSystemPath":"%s","targetDirectory":"%s","sha256":"%s","owner":"%s","mode":"%s","selinuxContext":"%s"},\n' "$STORE_ACTIVE_CODE_PATH" "$STORE_ORIGINAL_SYSTEM_PATH" "$TARGET_STORE" "$store_hash" "$ORIGINAL_STORE_OWNER" "$ORIGINAL_STORE_MODE" "$ORIGINAL_STORE_CONTEXT"
        printf '  "mounts": ['
        global_mountinfo | awk -v g="${TARGET_GMS:-$PROFILE_GMS}" -v f="${TARGET_GSF:-$PROFILE_GSF}" -v s="${TARGET_STORE:-$PROFILE_STORE}" \
            '$5 == g || $5 == f || $5 == s { printf "%s{\"mountpoint\":\"%s\",\"sourceRoot\":\"%s\"}", sep, $5, $4; sep="," }'
        printf '],\n'
        printf '  "state": "PREPARED"\n'
        printf '}\n'
    } > "$tmp" || return 1
    mv "$tmp" "$SNAPSHOT_FILE" || return 1
    journal_state "BACKUP_COMPLETE" || return 1
    return 0
}

clone_mask_metadata()
{
    local target="$1" mask="$2" owner="$3" mode="$4" context="$5" label="$6" actual
    [ -n "$owner" ] && [ -n "$mode" ] && [ -n "$context" ] || \
        _prep_fail 4 "$label: metadados owner/mode/SELinux incompletos"
    global chown "$owner" "$mask" 2>/dev/null || _prep_fail 4 "$label: não consegui clonar owner=$owner"
    global chmod "$mode" "$mask" 2>/dev/null || _prep_fail 4 "$label: não consegui clonar mode=$mode"
    if ! global chcon -R "$context" "$mask" 2>/dev/null; then
        global restorecon -R "$mask" 2>/dev/null || _prep_fail 4 "$label: não consegui aplicar contexto SELinux"
    fi
    actual="$(global ls -Zd "$mask" 2>/dev/null | awk '{print $1}')"
    [ "$actual" = "$context" ] || _prep_fail 4 "$label: contexto SELinux não reproduzido (esperado=$context atual=${actual:-<ausente>})"
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
    # Um prepare idempotente expõe o APK da própria máscara no mountpoint;
    # nesse caso o hash do path visível naturalmente difere do hash stock do
    # probe e não deve ser tratado como TOCTOU.
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

pm_paths()
{
    pm path "$1" 2>/dev/null | sed -n 's/^package://p'
}

is_allowed_system_path()
{
    local path="$1"
    case "$path" in
        "$PROFILE_GMS"|"$PROFILE_GMS"/*|"$PROFILE_GSF"|"$PROFILE_GSF"/*|"$PROFILE_STORE"|"$PROFILE_STORE"/*|/system|/system/*|/system_ext|/system_ext/*|/product|/product/*)
            case "$path" in *..*) return 1 ;; esac
            return 0
            ;;
        *) return 1 ;;
    esac
}

locate_package()
{
    # locate_package <GMS|GSF|STORE> <package>
    local prefix="$1" pkg="$2" paths active original target base splits update real fs partition
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
    original="$(printf '%s\n' "$paths" | while IFS= read -r p; do
        [ -n "$p" ] || continue
        if is_allowed_system_path "$p"; then printf '%s' "$p"; break; fi
    done)"
    if [ -n "$original" ]; then
        target="$(dirname "$original" 2>/dev/null)"
    elif [ -n "$active" ]; then
        target="$(dirname "$active" 2>/dev/null)"
    else
        target=""
    fi
    [ "$target" = "." ] && target=""
    base="$(printf '%s\n' "$paths" | awk -F/ '$NF == "base.apk" { print; exit }')"
    [ -n "$base" ] || base="${original:-$active}"
    splits="$(printf '%s\n' "$paths" | awk -v b="$base" 'NF && $0 != b { if (out != "") out=out ","; out=out $0 } END { print out }')"
    update=0
    printf '%s\n' "$paths" | grep -q '^/data/app/' && update=1
    dumpsys package "$pkg" 2>/dev/null | grep -q 'codePath=/data/app/' && update=1
    real="$(readlink -f "$active" 2>/dev/null || true)"
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
    # mountinfo: campo 4 = root (fonte do bind mount), campo 5 = mountpoint
    awk -v m="$1" '$5 == m { print $4; exit }' "$MOUNTINFO" 2>/dev/null
}

fs_root_of()
{
    # fs_root_of <path> — como o kernel grava o campo 4 (root) de um bind mount
    # cuja fonte é <path>: caminho relativo à raiz do filesystem que contém o
    # path. Ex.: fonte em /data/local/tmp/x com /data montado como f2fs →
    # o campo 4 é /local/tmp/x (não /data/local/tmp/x).
    # Se nenhum mount point do mountinfo for prefixo (ex.: mountinfo fake de
    # teste sem os mounts reais), o path completo é mantido.
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
    local root
    root="$(mountinfo_root "$mp")"
    [ -n "$root" ] || return 1
    [ "$root" = "$(fs_root_of "$mask")" ] || return 1
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
    local mp="$1" mask="$2" apk="${3:-}" root
    root="$(mountinfo_root "$mp")"
    [ -n "$root" ] || return 1
    [ "$root" = "$(fs_root_of "$mask")" ] || return 1
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
    # mountinfo é a fonte de verdade para classificar mounts já existentes.
    # Não exija que o arquivo apareça no mountpoint: durante um boot quebrado
    # o Package Manager pode ainda não ter recriado os diretórios/arquivos,
    # e os fixtures de recuperação representam apenas a origem do mount.
    local mp="$1" mask="$2" root
    root="$(mountinfo_root "$mp")"
    [ -n "$root" ] || return 1
    [ "$root" = "$(fs_root_of "$mask")" ]
}

refresh_residue_facts()
{
    MASK_RESIDUE_PRESENT=0
    for p in "$MASK_BASE" "$LEGACY_MASK_BASE" \
        "$MASK_GMS/$GMS_APK" "$MASK_STORE/$STORE_APK" \
        "$LEGACY_MASK_GMS/$GMS_APK" "$LEGACY_MASK_STORE/FakeStore.apk"; do
        if [ -e "$p" ]; then
            MASK_RESIDUE_PRESENT=1
            return 0
        fi
    done
}

refresh_mount_facts()
{
    # Package Manager pode não listar GSF/Store durante uma recuperação. Os
    # alvos aqui já devem ter sido resolvidos pelo locator, snapshot ou perfil;
    # mountinfo é a fonte de verdade para saber o que ainda está montado.
    local mr
    MOUNT_GMS=0; MOUNT_GMS_IS_OURS=0; MOUNT_GMS_IS_LEGACY=0; MOUNT_GMS_IS_KNOWN=0; MOUNT_GMS_SOURCE=""
    mr="$(mountinfo_root "${TARGET_GMS:-$PROFILE_GMS}")"
    if [ -n "$mr" ]; then
        MOUNT_GMS=1
        MOUNT_GMS_SOURCE="$mr"
        if mount_source_matches_mask "${TARGET_GMS:-$PROFILE_GMS}" "$MASK_GMS"; then
            MOUNT_GMS_IS_OURS=1
            MOUNT_GMS_IS_KNOWN=1
        elif mount_source_matches_mask "${TARGET_GMS:-$PROFILE_GMS}" "$LEGACY_MASK_GMS"; then
            MOUNT_GMS_IS_LEGACY=1
            MOUNT_GMS_IS_KNOWN=1
        fi
    fi

    MOUNT_GSF=0; MOUNT_GSF_IS_OURS=0; MOUNT_GSF_IS_LEGACY=0; MOUNT_GSF_IS_KNOWN=0
    mr="$(mountinfo_root "${TARGET_GSF:-$PROFILE_GSF}")"
    if [ -n "$mr" ]; then
        MOUNT_GSF=1
        if mount_source_matches_mask "${TARGET_GSF:-$PROFILE_GSF}" "$MASK_GSF"; then
            MOUNT_GSF_IS_OURS=1
            MOUNT_GSF_IS_KNOWN=1
        elif mount_source_matches_mask "${TARGET_GSF:-$PROFILE_GSF}" "$LEGACY_MASK_GSF"; then
            MOUNT_GSF_IS_LEGACY=1
            MOUNT_GSF_IS_KNOWN=1
        fi
    fi

    MOUNT_STORE=0; MOUNT_STORE_IS_OURS=0; MOUNT_STORE_IS_LEGACY=0; MOUNT_STORE_IS_KNOWN=0
    mr="$(mountinfo_root "${TARGET_STORE:-$PROFILE_STORE}")"
    if [ -n "$mr" ]; then
        MOUNT_STORE=1
        if mount_source_matches_mask "${TARGET_STORE:-$PROFILE_STORE}" "$MASK_STORE"; then
            MOUNT_STORE_IS_OURS=1
            MOUNT_STORE_IS_KNOWN=1
        elif mount_source_matches_mask "${TARGET_STORE:-$PROFILE_STORE}" "$LEGACY_MASK_STORE"; then
            MOUNT_STORE_IS_LEGACY=1
            MOUNT_STORE_IS_KNOWN=1
        fi
    fi
}

is_apk()
{
    # valida apenas magic bytes (PK\x03\x04); validação completa é feita pelo app
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
    # Deriva o UID atual — nunca assume o antigo.
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
    u="$(dumpsys package "$GMS_PKG" 2>/dev/null | sed -n 's/.*appId=\([0-9][0-9]*\).*/\1/p' | head -n 1)"
    case "$u" in
        ''|*[!0-9]*) ;;
        *) echo "$u"; return 0 ;;
    esac
    u="$(dumpsys package "$GMS_PKG" 2>/dev/null | sed -n 's/.*userId=\([0-9][0-9]*\).*/\1/p' | head -n 1)"
    [ -n "$u" ] || return 1
    echo "$u"
}

gms_version()
{
    dumpsys package "$GMS_PKG" 2>/dev/null | grep -m 1 "versionName=" | sed -n 's/.*versionName=\([^ ]*\).*/\1/p'
}

gms_version_code()
{
    dumpsys package "$GMS_PKG" 2>/dev/null | grep -m 1 "versionCode=" | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p'
}

gms_flags()
{
    # PRIVILEGED vive em privateFlags (não em pkgFlags); juntamos os dois.
    local pf priv
    pf="$(dumpsys package "$GMS_PKG" 2>/dev/null | grep -m 1 "pkgFlags=" | sed -n 's/.*pkgFlags=\[\([^]]*\)\].*/\1/p')"
    priv="$(dumpsys package "$GMS_PKG" 2>/dev/null | grep -m 1 "privateFlags=" | sed -n 's/.*privateFlags=\[\([^]]*\)\].*/\1/p')"
    echo "${pf}${priv:+ $priv}"
}

# O perfil legado continua sendo apenas uma referência operacional do fluxo
# atual; compatibilidade nova é decidida pelo engine Kotlin + capabilities.
profile_check()
{
    local man model
    man="$(getprop ro.product.manufacturer 2>/dev/null | tr 'A-Z' 'a-z')"
    [ "$man" = "$PROFILE_MANUFACTURER" ] || return 1
    model="$(getprop ro.product.model 2>/dev/null)"
    # padrões literais: em case, '|' vindo de variável NÃO é alternância
    case "$model" in
        SM-S928B|SM-S928U|SM-S928W|SM-S928N|SM-S9280) return 0 ;;
        *) return 1 ;;
    esac
}

# O Package Manager pode expor somente a atualização em /data/app e ocultar o
# diretório stock original. GMS e Play Store têm cleanup explícito no fluxo;
# GSF não tem, portanto nunca é considerado preparável por esta regra.
preparable_system_update()
{
    local prefix="$1" active update expected
    case "$prefix" in
        GMS)
            active="$GMS_ACTIVE_CODE_PATH"; update="$GMS_HAS_DATA_UPDATE"; expected="$PROFILE_GMS" ;;
        STORE)
            active="$STORE_ACTIVE_CODE_PATH"; update="$STORE_HAS_DATA_UPDATE"; expected="$PROFILE_STORE" ;;
        *) return 1 ;;
    esac
    [ "$update" = "1" ] || return 1
    printf '%s' "$active" | grep -q '^/data/app/' || return 1
    profile_check || return 1
    is_allowed_system_path "$expected"
}

cap_set()
{
    # A chave é sempre literal no código abaixo; os valores são tratados como
    # dados e nunca entram em eval.
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

probe_global_mount_namespace()
{
    local evidence
    if global true >/dev/null 2>&1 && evidence="$(mount_namespace_identity)"; then
        cap_set GLOBAL_MOUNT_NAMESPACE PASS "$evidence" "nsenter validado contra /proc/1/ns/mnt"
    else
        cap_set GLOBAL_MOUNT_NAMESPACE FAIL "" "namespace global do PID 1 inacessível ou identidade não confirmada"
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
        cap_set BIND_MOUNT FAIL "" "não foi possível criar diretório temporário"
        return
    fi
    if ! printf 'degoogle-capability' > "$source/value"; then
        rm -rf "$base" 2>/dev/null || true
        cap_set BIND_MOUNT FAIL "" "não foi possível escrever fonte temporária"
        return
    fi
    if ! global_mount --bind "$source" "$target" 2>/dev/null; then
        rm -rf "$base" 2>/dev/null || true
        cap_set BIND_MOUNT FAIL "" "mount --bind global falhou"
        return
    fi
    value="$(global cat "$target/value" 2>/dev/null || true)"
    if [ "$value" != "degoogle-capability" ] || ! is_mounted "$target"; then
        if unmount_capability_test; then
            rm -rf "$base" 2>/dev/null || true
            cap_set BIND_MOUNT FAIL "mount criado mas leitura/mountinfo não confirmaram o alvo"
        else
            cap_set BIND_MOUNT FAIL "$target" "mount de capability criado, mas não pôde ser desmontado com segurança"
        fi
        return
    fi
    if ! unmount_capability_test; then
        rm -rf "$base" 2>/dev/null || true
        cap_set BIND_MOUNT FAIL "bind mount temporário não pôde ser removido"
        return
    fi
    rm -rf "$base" 2>/dev/null || true
    cap_set BIND_MOUNT PASS "bind temporário global criado, lido e desmontado"
}

probe_selinux_context()
{
    local base="${DEGOOGLE_CAP_TEST_BASE:-${MASK_BASE%/*}/degoogle-selinux-test.$$}"
    local original masked source_target="${TARGET_GMS:-$PROFILE_GMS}"
    [ "$SELINUX" = "Enforcing" ] || {
        cap_set SELINUX_CONTEXT_CLONABLE FAIL "" "SELinux não está Enforcing"
        return
    }
    original="$(global ls -Zd "$source_target" 2>/dev/null | awk '{print $1}')"
    [ -n "$original" ] || {
        cap_set SELINUX_CONTEXT_CLONABLE UNKNOWN "" "contexto SELinux do alvo original não pôde ser lido"
        return
    }
    mkdir -p "$base" 2>/dev/null || {
        cap_set SELINUX_CONTEXT_CLONABLE FAIL "" "não foi possível criar alvo de teste"
        return
    }
    if ! global chcon -R "$original" "$base" >/dev/null 2>&1 && \
        ! global restorecon -R "$base" >/dev/null 2>&1; then
        rm -rf "$base" 2>/dev/null || true
        cap_set SELINUX_CONTEXT_CLONABLE FAIL "" "não foi possível reproduzir o contexto SELinux do alvo"
        return
    fi
    masked="$(global ls -Zd "$base" 2>/dev/null | awk '{print $1}')"
    rm -rf "$base" 2>/dev/null || true
    if [ "$original" = "$masked" ]; then
        cap_set SELINUX_CONTEXT_CLONABLE PASS "origem=$original; alvo restaurado=$masked"
    else
        cap_set SELINUX_CONTEXT_CLONABLE FAIL "origem=$original; alvo=$masked" "contexto SELinux não pôde ser reproduzido exatamente"
    fi
}

probe_signature_spoofing()
{
    local check fake_path fake_version fake_state fake_disabled
    # Evidência funcional mínima: o PM precisa reportar a permissão especial
    # concedida ao próprio GMS. A existência de FakeGApps/module sozinha não
    # é aceita como prova.
    check=""
    # `pm check-permission` não existe em várias versões recentes do Android;
    # só o chamamos quando o próprio help confirma que o subcomando existe.
    if pm help 2>/dev/null | grep -q 'check-permission'; then
        check="$(pm check-permission "$GMS_PKG" android.permission.FAKE_PACKAGE_SIGNATURE 0 2>/dev/null || true)"
    fi
    if printf '%s\n' "$check" | grep -Eiq 'granted|(^|[^0-9])1([^0-9]|$)' || \
        dumpsys package "$GMS_PKG" 2>/dev/null | grep -Eiq 'FAKE_PACKAGE_SIGNATURE[^\n]*(granted[=:]true| granted)'; then
        cap_set SIGNATURE_SPOOFING PASS "dumpsys package reporta FAKE_PACKAGE_SIGNATURE concedida ao GMS"
    elif dumpsys package "$GMS_PKG" 2>/dev/null | grep -Eiq 'FAKE_PACKAGE_SIGNATURE'; then
        cap_set SIGNATURE_SPOOFING WARN "permissão especial mencionada, mas concessão não foi confirmada"
    elif fake_path="$(pm path "$FAKEGAPPS_PKG" 2>/dev/null | sed -n 's/^package://p' | head -n 1)" && [ -n "$fake_path" ]; then
        fake_version="$(dumpsys package "$FAKEGAPPS_PKG" 2>/dev/null | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -n 1)"
        fake_state="$(dumpsys package "$FAKEGAPPS_PKG" 2>/dev/null | grep -m 1 'User 0:.*enabled=' || true)"
        fake_disabled=0
        # COMPONENT_ENABLED_STATE_DEFAULT é 0; não confundir estado padrão
        # com pacote desabilitado. A lista -d é a fonte mais confiável para o
        # estado efetivo do usuário, com fallback para estados explícitos do
        # dumpsys (DISABLED, DISABLED_USER e DISABLED_UNTIL_USED).
        if pm list packages -d --user 0 2>/dev/null | grep -q "^package:${FAKEGAPPS_PKG}$" || \
            printf '%s' "$fake_state" | grep -Eq 'enabled=(2|3|4|5)([[:space:]]|$)'; then
            fake_disabled=1
        fi
        if [ "$fake_disabled" = "1" ]; then
            cap_set SIGNATURE_SPOOFING WARN \
                "FakeGApps ${fake_version:-detectado} instalado, mas desabilitado para o usuário; validação funcional pendente" \
                "package=$fake_path; framework=$( [ -f "$LSPOSED_MARKER" ] && echo LSPosed || echo UNKNOWN )"
        elif [ -f "$LSPOSED_MARKER" ]; then
            cap_set SIGNATURE_SPOOFING WARN \
                "FakeGApps ${fake_version:-detectado} + LSPosed detectados; validação funcional pendente" \
                "package=$fake_path; marker=$LSPOSED_MARKER"
        else
            cap_set SIGNATURE_SPOOFING WARN \
                "FakeGApps ${fake_version:-detectado} detectado, mas framework de hooks não foi confirmado" \
                "package=$fake_path"
        fi
    else
        cap_set SIGNATURE_SPOOFING UNKNOWN "" "nenhuma evidência funcional de signature spoofing"
    fi
}

probe_package_manager_cache()
{
    local parent test_file
    parent="$(dirname "$PM_CACHE_DIR")"
    test_file="$parent/.degoogle-pm-capability-test.$$"
    if [ -d "$parent" ] && [ -w "$parent" ] && : > "$test_file" 2>/dev/null; then
        if rm -f "$test_file" 2>/dev/null; then
            if [ -r "$PM_CACHE_DIR" ]; then
                cap_set PACKAGE_MANAGER_CACHE_ACCESS PASS "cache legível e diretório pai gravável: $PM_CACHE_DIR"
            else
                cap_set PACKAGE_MANAGER_CACHE_ACCESS PASS "diretório pai gravável; cache ausente será tratado como no-op: $PM_CACHE_DIR"
            fi
        else
            cap_set PACKAGE_MANAGER_CACHE_ACCESS FAIL "$test_file" "teste de limpeza do cache temporário falhou"
        fi
    elif [ -e "$PM_CACHE_DIR" ]; then
        cap_set PACKAGE_MANAGER_CACHE_ACCESS WARN "cache existe, mas acesso de escrita não foi confirmado" "$PM_CACHE_DIR"
    else
        cap_set PACKAGE_MANAGER_CACHE_ACCESS UNKNOWN "" "diretório pai do cache não é gravável"
    fi
}

probe_safe_restore()
{
    local test_file="$TRANSACTION_BASE/.capability-test.$$"
    if ! mkdir -p "$TRANSACTION_BASE" 2>/dev/null || ! : > "$test_file" 2>/dev/null; then
        cap_set SAFE_RESTORE FAIL "" "diretório persistente de rollback não é gravável"
        return
    fi
    rm -f "$test_file" 2>/dev/null || {
        cap_set SAFE_RESTORE FAIL "" "teste de limpeza do rollback falhou"
        return
    }
    cap_set SAFE_RESTORE PASS "snapshot/journal persistentes podem ser criados em $TRANSACTION_BASE"
}

probe_reboot_strategy()
{
    REBOOT_STRATEGY_BACKEND="$ROOT_MANAGER"
    REBOOT_STRATEGY_METHOD=""
    REBOOT_STRATEGY_CONFIDENCE="UNKNOWN"
    REBOOT_STRATEGY_FIRMWARE_VALIDATED=0
    if [ -x /data/adb/ksud ]; then
        REBOOT_STRATEGY_METHOD="KSUD_SOFT_REBOOT"
    elif command -v setprop >/dev/null 2>&1; then
        REBOOT_STRATEGY_METHOD="SYS_POWERCTL_USERSPACE"
    fi
    if [ -n "$REBOOT_STRATEGY_METHOD" ]; then
        REBOOT_STRATEGY_CONFIDENCE="LOW"
        if [ "${DEGOOGLE_FIRMWARE_HOMOLOGATED:-0}" = "1" ]; then
            REBOOT_STRATEGY_CONFIDENCE="VERIFIED"
            REBOOT_STRATEGY_FIRMWARE_VALIDATED=1
            cap_set SAFE_SOFT_REBOOT PASS "estratégia $REBOOT_STRATEGY_METHOD marcada como homologada"
        else
            cap_set SAFE_SOFT_REBOOT WARN "estratégia existe, mas firmware não foi homologado" "$REBOOT_STRATEGY_METHOD"
        fi
    else
        cap_set SAFE_SOFT_REBOOT UNKNOWN "" "nenhuma estratégia de soft reboot identificada"
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
        cap_set SAMSUNG_DEVICE UNKNOWN "" "manufacturer ausente"
    elif printf '%s' "$MANUFACTURER" | tr 'A-Z' 'a-z' | grep -qx samsung; then
        cap_set SAMSUNG_DEVICE PASS "ro.product.manufacturer=$MANUFACTURER"
    else
        cap_set SAMSUNG_DEVICE FAIL "$MANUFACTURER" "fabricante não é Samsung"
    fi

    if [ "$MOUNT_GMS_IS_OURS" = "1" ]; then
        cap_set SYSTEM_GMS_FOUND PASS "${GMS_PATH:-$TARGET_GMS}" "GMS mascarado via microG"
    elif [ -n "$GMS_PATH" ]; then
        cap_set SYSTEM_GMS_FOUND PASS "$GMS_PATH"
    else
        cap_set SYSTEM_GMS_FOUND FAIL "" "GMS ausente"
    fi

    if [ "$MOUNT_GSF_IS_OURS" = "1" ]; then
        cap_set SYSTEM_GSF_FOUND PASS "${TARGET_GSF:-$PROFILE_GSF}" "GSF mascarado (vazio)"
    elif [ -n "$GSF_PATH" ]; then
        cap_set SYSTEM_GSF_FOUND PASS "$GSF_PATH"
    else
        cap_set SYSTEM_GSF_FOUND FAIL "" "GSF ausente"
    fi

    if [ "$MOUNT_STORE_IS_OURS" = "1" ]; then
        cap_set SYSTEM_STORE_FOUND PASS "${STORE_PATH:-$TARGET_STORE}" "Store mascarada via Companion"
    elif [ -n "$STORE_PATH" ]; then
        cap_set SYSTEM_STORE_FOUND PASS "$STORE_PATH"
    else
        cap_set SYSTEM_STORE_FOUND FAIL "" "Store ausente"
    fi

    case "$SELINUX" in
        Enforcing) cap_set SELINUX_ENFORCING PASS "getenforce=Enforcing" ;;
        Permissive|Disabled) cap_set SELINUX_ENFORCING FAIL "$SELINUX" "SELinux não está Enforcing" ;;
        *) cap_set SELINUX_ENFORCING UNKNOWN "" "estado SELinux ausente ou não reconhecido" ;;
    esac

    probe_global_mount_namespace
    probe_bind_mount
    probe_selinux_context
    probe_signature_spoofing
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
            cap_set GMS_MASKABLE PASS "alvo=${TARGET_GMS:-$PROFILE_GMS}; máscara microG ativa"
        elif [ "$p" = "GSF" ] && [ "$MOUNT_GSF_IS_OURS" = "1" ]; then
            cap_set GSF_MASKABLE PASS "alvo=${TARGET_GSF:-$PROFILE_GSF}; máscara vazia ativa"
        elif [ "$p" = "STORE" ] && [ "$MOUNT_STORE_IS_OURS" = "1" ]; then
            cap_set STORE_MASKABLE PASS "alvo=${TARGET_STORE:-$PROFILE_STORE}; máscara companion ativa"
        elif preparable_system_update "$p"; then
            cap_set "${p}_MASKABLE" PASS \
                "active=$active; target stock=$expected; cleanup remove o update antes da máscara"
            if [ -n "$UPDATE_CLEANUP_INFO" ]; then UPDATE_CLEANUP_INFO="$UPDATE_CLEANUP_INFO; "; fi
            UPDATE_CLEANUP_INFO="$UPDATE_CLEANUP_INFO$p: update em /data/app será removido durante a preparação"
        elif [ -z "$original" ]; then
            cap_set "${p}_MASKABLE" UNKNOWN "" "$p: caminho original de sistema ambíguo"
        elif ! is_allowed_system_path "$target"; then
            cap_set "${p}_MASKABLE" FAIL "$target" "$p: target fora das raízes permitidas"
        elif [ "$update" = "1" ]; then
            cap_set "${p}_MASKABLE" WARN "$original" "$p: update em /data/app requer cleanup explícito"
        else
            cap_set "${p}_MASKABLE" PASS "target=$target; original=$original"
        fi
    done
    if [ "$MOUNT_GMS_IS_OURS" = "1" ] && [ "$GMS_PRIVILEGED" = "1" ]; then
        cap_set PRIV_APP_COMPATIBLE PASS "MicroG ativo com flag PRIVILEGED"
    elif printf '%s' "$GMS_FLAGS" | grep -q PRIVILEGED; then
        cap_set PRIV_APP_COMPATIBLE PASS "GMS flags contêm PRIVILEGED"
    elif [ -n "$GMS_PATH" ]; then
        cap_set PRIV_APP_COMPATIBLE FAIL "$GMS_FLAGS" "GMS não é priv-app privilegiado"
    else
        cap_set PRIV_APP_COMPATIBLE UNKNOWN "" "GMS não localizado"
    fi
}

# ===========================================================================
# Coleta de fatos (fonte de verdade = estado real do Android)
# ===========================================================================

collect_facts()
{
    ROOT_OK=0; ROOT_MANAGER=""; GMS_PRIVILEGED=0; FINALIZE_DONE=0
    GMS_PATH=""; GSF_PATH=""; STORE_PATH=""; GMS_FLAGS=""; GMS_UID=""
    GMS_VERSION=""; GMS_VERSION_CODE=""; STORE_VERSION=""
    TARGET_GMS=""; TARGET_GSF=""; TARGET_STORE=""
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

    if [ "$(id -u 2>/dev/null)" = "0" ]; then
        ROOT_OK=1
        detect_root_manager
    fi

    # leituras que exigem root (ou pelo menos visibilidade do namespace)
    if [ "$ROOT_OK" = "1" ]; then
        locate_package GMS "$GMS_PKG"
        locate_package GSF "$GSF_PKG"
        locate_package STORE "$STORE_PKG"
        TARGET_GMS="${GMS_TARGET_DIRECTORY:-$PROFILE_GMS}"
        TARGET_GSF="${GSF_TARGET_DIRECTORY:-$PROFILE_GSF}"
        TARGET_STORE="${STORE_TARGET_DIRECTORY:-$PROFILE_STORE}"
        # Antes do cleanup, um update legítimo pode esconder o target stock.
        # Só usamos a baseline conhecida do S24 quando a regra acima prova
        # que o próprio fluxo sabe remover esse update; aparelhos/layouts
        # desconhecidos continuam sem alvo seguro.
        preparable_system_update GMS && TARGET_GMS="$PROFILE_GMS"
        preparable_system_update STORE && TARGET_STORE="$PROFILE_STORE"
        GMS_VERSION="$(gms_version)"
        GMS_VERSION_CODE="$(gms_version_code)"
        GMS_FLAGS="$(gms_flags)"
        case "$GMS_FLAGS" in *PRIVILEGED*) GMS_PRIVILEGED=1 ;; esac
        GMS_UID="$(gms_uid_current 2>/dev/null || true)"

        # Mounts: detecta QUALQUER mount ativo nos paths do perfil e marca se
        # a fonte é a nossa máscara. Fonte desconhecida = estado não gerenciado.
        refresh_mount_facts

        if cmd deviceidle whitelist 2>/dev/null | grep -q "$GMS_PKG"; then FINALIZE_DONE=1; fi
    fi

    # Backup no formato do microg-session.sh: diretórios copiados diretamente.
    # A presença de user0 é o indicador usado pelo script original; user_de é
    # opcional porque nem todo firmware cria esse diretório.
    BACKUP_PRESENT=0
    if [ -d "$BACKUP_GMS_USER" ]; then
        BACKUP_PRESENT=1
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
    emit GMS_TARGET_DIRECTORY "$GMS_TARGET_DIRECTORY"
    emit GMS_BASE_APK "$GMS_BASE_APK"
    emit GMS_SPLIT_APKS "$GMS_SPLIT_APKS"
    emit GMS_HAS_DATA_UPDATE "$GMS_HAS_DATA_UPDATE"
    emit GMS_BACKING_PARTITION "$GMS_BACKING_PARTITION"
    emit GMS_FILESYSTEM_TYPE "$GMS_FILESYSTEM_TYPE"
    emit GMS_RESOLVED_REAL_PATH "$GMS_RESOLVED_REAL_PATH"
    emit GSF_ACTIVE_CODE_PATH "$GSF_ACTIVE_CODE_PATH"
    emit GSF_ORIGINAL_SYSTEM_PATH "$GSF_ORIGINAL_SYSTEM_PATH"
    emit GSF_TARGET_DIRECTORY "$GSF_TARGET_DIRECTORY"
    emit GSF_BASE_APK "$GSF_BASE_APK"
    emit GSF_SPLIT_APKS "$GSF_SPLIT_APKS"
    emit GSF_HAS_DATA_UPDATE "$GSF_HAS_DATA_UPDATE"
    emit GSF_BACKING_PARTITION "$GSF_BACKING_PARTITION"
    emit GSF_FILESYSTEM_TYPE "$GSF_FILESYSTEM_TYPE"
    emit GSF_RESOLVED_REAL_PATH "$GSF_RESOLVED_REAL_PATH"
    emit STORE_ACTIVE_CODE_PATH "$STORE_ACTIVE_CODE_PATH"
    emit STORE_ORIGINAL_SYSTEM_PATH "$STORE_ORIGINAL_SYSTEM_PATH"
    emit STORE_TARGET_DIRECTORY "$STORE_TARGET_DIRECTORY"
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
# Derivação de estado (mesmas regras do StateDetector do app)
# ===========================================================================

stock_path_valid()
{
    # Um update de app de sistema em /data/app é normal, mas só pode ser
    # aceito quando o locator confirmou essa condição explicitamente.
    local path="$1" expected_root="$2" has_data_update="$3"
    [ -n "$path" ] || return 1
    if [ -n "$expected_root" ]; then
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
    if ! profile_check; then
        STATE="UNSUPPORTED"
        return
    fi
    # O perfil legacy continua informativo para o estado operacional; mounts
    # existentes ainda precisam ser completos e ter origem verificável.
    if [ "$MOUNT_GMS" = "0" ] && [ "$MOUNT_GSF" = "0" ] && [ "$MOUNT_STORE" = "0" ]; then
        # nenhum mount: stock ou rollback pendente de reboot
        if ! stock_path_valid "$GMS_PATH" "$PROFILE_GMS" "$GMS_HAS_DATA_UPDATE"; then
            STATE="ERROR"
            return
        fi
        if [ -z "$GSF_PATH" ] || [ -z "$STORE_PATH" ]; then
            STATE="RESTORE_PREPARED"
            return
        fi
        if stock_path_valid "$GSF_PATH" "$PROFILE_GSF" "$GSF_HAS_DATA_UPDATE" && \
            stock_path_valid "$STORE_PATH" "$PROFILE_STORE" "$STORE_HAS_DATA_UPDATE"; then
            STATE="STOCK"
        else
            STATE="ERROR"
        fi
        return
    fi

    # existe mount ativo — tudo deve ser completo e via NOSSAS máscaras;
    # mount de fonte desconhecida (ex.: microg-mask do script antigo) = ERROR
    if [ "$MOUNT_GMS" != "1" ] || [ "$MOUNT_GSF" != "1" ] || [ "$MOUNT_STORE" != "1" ]; then
        STATE="ERROR"
        return
    fi
    if [ "$MOUNT_GMS_IS_OURS" != "1" ] || [ "$MOUNT_GSF_IS_OURS" != "1" ] || [ "$MOUNT_STORE_IS_OURS" != "1" ]; then
        STATE="ERROR"
        return
    fi

    case "$GMS_PATH" in
        "$TARGET_GMS"/*) ;;
        *) STATE="ERROR"; return ;;
    esac

    if [ -n "$GSF_PATH" ]; then
        # mounts ativos mas GSF ainda registrado ⇒ PM ainda vê o estado stock
        STATE="PREPARED"
        return
    fi

    # pós-boot: GSF sumiu
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
    write_probe_baseline || say "AVISO: não foi possível salvar baseline do probe"
    compute_state
    emit_facts
    emit STATE "$STATE"
    return 0
}

status()
{
    check_root
    collect_facts
    compute_state
    emit_facts
    emit STATE "$STATE"
    say ""
    say "===== STATUS DeGoogle ====="
    say "Estado: $STATE"
    say "Dispositivo: $MANUFACTURER $MODEL ($DEVICE/$PRODUCT) SDK $ANDROID_SDK ($ANDROID_RELEASE)"
    say "Fingerprint: $FINGERPRINT"
    say "SELinux: $SELINUX · ABI: $ABI · Root: $ROOT_MANAGER"
    say ""
    say "GMS : ${GMS_PATH:-AUSENTE} v${GMS_VERSION:-?} (${GMS_PRIVILEGED:+privileged }uid=${GMS_UID:-?})"
    say "GSF : ${GSF_PATH:-AUSENTE}"
    say "Store: ${STORE_PATH:-AUSENTE} v${STORE_VERSION:-?}"
    say "Mounts: GMS=$MOUNT_GMS GSF=$MOUNT_GSF Store=$MOUNT_STORE (fonte: ${MOUNT_GMS_SOURCE:-n/d})"
    say "Finalize: $FINALIZE_DONE"
    if [ "$BACKUP_PRESENT" = "1" ]; then
        say "Backup: presente (formato microg-session.sh)"
        say "  user0: $(du -sh "$BACKUP_GMS_USER" 2>/dev/null | awk '{print $1}')"
        [ -d "$BACKUP_GMS_DE" ] && say "  user_de: $(du -sh "$BACKUP_GMS_DE" 2>/dev/null | awk '{print $1}')"
    else
        say "Backup: ausente"
    fi
    return 0
}

run_safety_preflight()
{
    local gms_now gsf_now store_now target free_kb namespace_evidence
    gms_now="$GMS_PATH"; gsf_now="$GSF_PATH"; store_now="$STORE_PATH"

    [ "$SELINUX" = "Enforcing" ] || {
        emit PREFLIGHT_STATUS "FAIL"
        emit PREFLIGHT_REASON "SELinux não está Enforcing"
        return 3
    }
    if ! namespace_evidence="$(mount_namespace_identity)"; then
        emit PREFLIGHT_STATUS "FAIL"
        emit PREFLIGHT_REASON "namespace global do PID 1 inacessível ou identidade não confirmada"
        return 3
    fi
    if [ -z "$GMS_ORIGINAL_SYSTEM_PATH" ]; then
        preparable_system_update GMS || {
            emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "GMS sem alvo de sistema seguro: ${TARGET_GMS:-ausente}"; return 3
        }
    elif ! is_allowed_system_path "$TARGET_GMS"; then
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "GMS sem alvo de sistema seguro: ${TARGET_GMS:-ausente}"; return 3
    fi
    if [ -z "$GSF_ORIGINAL_SYSTEM_PATH" ] || ! is_allowed_system_path "$TARGET_GSF"; then
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "GSF sem alvo de sistema seguro: ${TARGET_GSF:-ausente}"; return 3
    fi
    if [ -z "$STORE_ORIGINAL_SYSTEM_PATH" ]; then
        preparable_system_update STORE || {
            emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "Store sem alvo de sistema seguro: ${TARGET_STORE:-ausente}"; return 3
        }
    elif ! is_allowed_system_path "$TARGET_STORE"; then
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "Store sem alvo de sistema seguro: ${TARGET_STORE:-ausente}"; return 3
    fi
    if ! path_under_target "$gms_now" "$TARGET_GMS" && ! printf '%s' "$gms_now" | grep -q '^/data/app/'; then
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "GMS em caminho inesperado: ${gms_now:-ausente}"; return 3
    fi
    if ! path_under_target "$gsf_now" "$TARGET_GSF"; then
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "GSF em caminho inesperado: ${gsf_now:-ausente}"; return 3
    fi
    if ! path_under_target "$store_now" "$TARGET_STORE" && ! printf '%s' "$store_now" | grep -q '^/data/app/'; then
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "Store em caminho inesperado: ${store_now:-ausente}"; return 3
    fi
    if [ "$MOUNT_GMS" = "1" ] && [ "$MOUNT_GMS_IS_OURS" != "1" ]; then
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "mount GMS existente tem fonte desconhecida"; return 3
    fi
    if [ "$MOUNT_GSF" = "1" ] && [ "$MOUNT_GSF_IS_OURS" != "1" ]; then
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "mount GSF existente tem fonte desconhecida"; return 3
    fi
    if [ "$MOUNT_STORE" = "1" ] && [ "$MOUNT_STORE_IS_OURS" != "1" ]; then
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "mount Store existente tem fonte desconhecida"; return 3
    fi
    if ! verify_probe_baseline; then
        emit PREFLIGHT_STATUS "FAIL"
        emit PREFLIGHT_REASON "paths ou hashes mudaram desde o probe; TOCTOU detectado"
        return 3
    fi

    target="$(dirname "$MASK_BASE")"
    [ -d "$target" ] && [ -w "$target" ] || {
        emit PREFLIGHT_STATUS "FAIL"
        emit PREFLIGHT_REASON "diretório pai da máscara não é gravável: $target"
        return 3
    }
    free_kb="$(df -Pk "$target" 2>/dev/null | tail -n 1 | awk '{print $4}')"
    case "$free_kb" in ''|*[!0-9]*) emit PREFLIGHT_STATUS "UNKNOWN"; emit PREFLIGHT_REASON "espaço livre não pôde ser lido"; return 3 ;; esac
    [ "$free_kb" -ge 16384 ] || {
        emit PREFLIGHT_STATUS "FAIL"; emit PREFLIGHT_REASON "menos de 16 MiB livres para snapshot/máscara"; return 3
    }
    emit PREFLIGHT_STATUS "PASS"
    emit PREFLIGHT_REASON "paths, SELinux, namespace global ($namespace_evidence), mounts e espaço revalidados"
    return 0
}

dry_run()
{
    check_root
    collect_facts
    write_probe_baseline || say "AVISO: não foi possível salvar baseline do dry-run"
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
    say "Known-Good DB: matching final é feito pelo app com o fingerprint completo."
    say "Compatibility: consultar DEGOOGLE_CAP_* + relatório estruturado do app."
    return 0
}

preflight()
{
    check_root
    collect_facts
    run_safety_preflight || return $?
    emit_facts
    emit PREFLIGHT_STATUS "PASS"
    return 0
}

# ===========================================================================
# prepare — STOCK → PREPARED (idempotente, transacional)
# ===========================================================================

NEW_MOUNTS=""
STORE_WAS_DISABLED=0
STORE_STATE_CAPTURED=0

restore_store_if_changed()
{
    [ "$STORE_STATE_CAPTURED" = "1" ] || return 0
    [ "$STORE_WAS_DISABLED" = "1" ] && return 0
    pm enable "$STORE_PKG" >/dev/null 2>&1 || {
        say "AVISO: não consegui reabilitar a Play Store durante rollback"
        return 1
    }
    return 0
}

_prep_fail()
{
    local code="$1"
    shift
    say "ERRO: $*"
    local mp
    if [ -n "$NEW_MOUNTS" ]; then
        say "Rollback das máscaras aplicadas nesta execução:"
        for mp in $NEW_MOUNTS; do
            if global umount "$mp" 2>/dev/null; then
                say "  desmontado: $mp"
            else
                say "  FALHA ao desmontar $mp — aparelho em estado parcial. Execute restore-stock."
            fi
            # limpa apenas os APKs colocados por esta execução (nunca os de um
            # estado pré-existente válido)
            case "$mp" in
                "$TARGET_GMS") rm -f "$MASK_GMS/$GMS_APK" 2>/dev/null ;;
                "$TARGET_STORE") rm -f "$MASK_STORE/$STORE_APK" 2>/dev/null ;;
            esac
        done
        restore_store_if_changed
        if ! journal_state "ROLLBACK_REQUIRED" >/dev/null 2>&1; then
            say "FALHA: não foi possível registrar ROLLBACK_REQUIRED no journal."
        fi
        exit 4
    fi
    restore_store_if_changed
    if ! journal_state "ROLLBACK_REQUIRED" >/dev/null 2>&1; then
        say "FALHA: não foi possível registrar ROLLBACK_REQUIRED no journal."
    fi
    exit "$code"
}

mount_mask()
{
    # mount_mask <target> <mask> <apkname> <apk_src|''>
    local target="$1" mask="$2" apkname="$3" src="${4:-}"

    if is_masked_by_us "$target" "$mask" "$apkname"; then
        say "  $target já mascarado por nós (idempotente)."
        if [ -n "$src" ] && [ ! -f "$mask/$apkname" ]; then
            cp "$src" "$mask/$apkname" || _prep_fail 1 "cp $apkname para a máscara"
            chown root:root "$mask/$apkname" || _prep_fail 1 "chown root:root $apkname"
            chmod 0644 "$mask/$apkname" || _prep_fail 1 "chmod $apkname"
        fi
        return 0
    fi
    if is_mounted "$target"; then
        _prep_fail 3 "mount estranho em $target (fonte != nossa máscara). Não vou sobrescrever."
    fi
    if [ -n "$src" ]; then
        [ -f "$src" ] || _prep_fail 3 "APK não encontrado: $src"
        is_apk "$src" || _prep_fail 3 "Não é um APK válido: $src"
        [ "${DEGOOGLE_FAIL_CP:-0}" = "1" ] && _prep_fail 1 "DEGOOGLE_FAIL_CP injetado"
        cp "$src" "$mask/$apkname" || _prep_fail 1 "cp $src → $mask/$apkname"
        chown root:root "$mask/$apkname" || _prep_fail 1 "chown root:root $apkname"
        chmod 0644 "$mask/$apkname" || _prep_fail 1 "chmod $apkname"
        # verificação real do APK na máscara (owner/modo) — nunca confiar no comando
        local own mode
        own="$(stat -c %u:%g "$mask/$apkname" 2>/dev/null)"
        mode="$(stat -c %a "$mask/$apkname" 2>/dev/null)"
        [ "$own" = "0:0" ] && [ "$mode" = "644" ] || \
            _prep_fail 1 "owner/modo do APK na máscara inválido ($own $mode)"
    fi
    [ "${DEGOOGLE_FAIL_MOUNT:-0}" = "1" ] && {
        [ -n "$apkname" ] && rm -f "$mask/$apkname" 2>/dev/null
        _prep_fail 1 "DEGOOGLE_FAIL_MOUNT injetado ($apkname)"
    }
    if ! global mount --bind "$mask" "$target"; then
        rm -f "$mask/$apkname" 2>/dev/null
        _prep_fail 1 "mount --bind falhou em $target"
    fi
    NEW_MOUNTS="$NEW_MOUNTS $target"
    case "$target" in
        "$TARGET_GMS") journal_state "GMS_MASKED" >/dev/null 2>&1 || _prep_fail 4 "journal GMS_MASKED falhou" ;;
        "$TARGET_GSF") journal_state "GSF_MASKED" >/dev/null 2>&1 || _prep_fail 4 "journal GSF_MASKED falhou" ;;
        "$TARGET_STORE") journal_state "STORE_MASKED" >/dev/null 2>&1 || _prep_fail 4 "journal STORE_MASKED falhou" ;;
    esac
    say "  mascarado: $target"
}

prepare()
{
    check_root
    require_lock
    NEW_MOUNTS=""
    STORE_WAS_DISABLED=0
    STORE_STATE_CAPTURED=0

    local gms_src="${1:-}" store_src="${2:-}"
    [ -n "$gms_src" ] && [ -n "$store_src" ] || fail_usage "prepare <gms.apk> <companion.apk>"

    if ! profile_check && [ "${DEGOOGLE_EXPERIMENTAL:-0}" != "1" ]; then
        fail 5 "Perfil não homologado. Use o fluxo experimental somente após o app aprovar todas as capabilities."
    fi

    say "===== PREPARE ====="

    collect_facts
    run_safety_preflight || fail 3 "Safety preflight bloqueou a operação. Nada foi alterado."
    journal_state "PREFLIGHT_OK" || fail 4 "não foi possível registrar o preflight"

    # 1) Pré-condições: paths reais do PM, nunca assumidos. GMS/vending podem
    #    ter update em /data/app (auto-update da Play Store) — aceito e removido
    #    no passo seguinte. GSF precisa ser o stock (não há update esperado).
    local gms_now gsf_now store_now
    gms_now="$(pm_path "$GMS_PKG")"
    gsf_now="$(pm_path "$GSF_PKG")"
    store_now="$(pm_path "$STORE_PKG")"
    if path_under_target "$gms_now" "$TARGET_GMS" || printf '%s' "$gms_now" | grep -q '^/data/app/'; then
        :
    else
        fail 3 "GMS em path inesperado (atual: ${gms_now:-ausente}). Nada foi alterado."
    fi
    path_under_target "$gsf_now" "$TARGET_GSF" || fail 3 "GSF em path inesperado (atual: ${gsf_now:-ausente}). Nada foi alterado."
    if path_under_target "$store_now" "$TARGET_STORE" || printf '%s' "$store_now" | grep -q '^/data/app/'; then
        :
    else
        fail 3 "Play Store em path inesperado (atual: ${store_now:-ausente}). Nada foi alterado."
    fi

    # O caminho descoberto só pode substituir a baseline antiga no modo
    # experimental explicitamente autorizado pelo app. A execução legacy do
    # S24 continua exigindo os três diretórios known-good.
    if profile_check && [ "${DEGOOGLE_EXPERIMENTAL:-0}" != "1" ]; then
        [ "$TARGET_GMS" = "$PROFILE_GMS" ] && [ "$TARGET_GSF" = "$PROFILE_GSF" ] && [ "$TARGET_STORE" = "$PROFILE_STORE" ] || \
            fail 3 "paths descobertos divergem do perfil legacy; exige opt-in experimental"
    fi

    # Valida os APKs antes de remover qualquer update ou desabilitar a Store.
    # Um erro de download/arquivo inválido deve deixar o ambiente exatamente
    # como estava.
    [ -f "$gms_src" ] || fail 3 "APK microG não encontrado: $gms_src"
    [ -f "$store_src" ] || fail 3 "APK Companion não encontrado: $store_src"
    is_apk "$gms_src" || fail 3 "microG não é um APK válido: $gms_src"
    is_apk "$store_src" || fail 3 "Companion não é um APK válido: $store_src"

    if pm list packages -d 2>/dev/null | grep -q "$STORE_PKG"; then
        STORE_WAS_DISABLED=1
    else
        STORE_WAS_DISABLED=0
    fi
    STORE_STATE_CAPTURED=1

    # 1b) Remove updates de /data/app (GMS/vending) e desabilita a Play Store
    #     (impede o auto-update do GMS durante o fluxo). Idempotente.
    if ! cleanup; then
        restore_store_if_changed
        journal_state "FAILED" >/dev/null 2>&1 || say "AVISO: não consegui registrar FAILED no journal"
        fail 3 "cleanup do ambiente falhou. Nada foi mascarado."
    fi

    # O cleanup pode trocar o activeCodePath de /data/app para a cópia stock.
    # Relocaliza antes do snapshot para que hashes e metadados descrevam o
    # estado que realmente será mascarado.
    locate_package GMS "$GMS_PKG"
    locate_package GSF "$GSF_PKG"
    locate_package STORE "$STORE_PKG"
    TARGET_GMS="${GMS_TARGET_DIRECTORY:-}"
    TARGET_GSF="${GSF_TARGET_DIRECTORY:-}"
    TARGET_STORE="${STORE_TARGET_DIRECTORY:-}"

    gms_now="$(pm_path "$GMS_PKG")"
    store_now="$(pm_path "$STORE_PKG")"
    path_under_target "$gms_now" "$TARGET_GMS" || _prep_fail 3 "GMS não voltou ao alvo de sistema após remover o update (atual: ${gms_now:-ausente})."
    path_under_target "$store_now" "$TARGET_STORE" || _prep_fail 3 "Play Store não voltou ao alvo de sistema após remover o update (atual: ${store_now:-ausente})."

    snapshot_state || _prep_fail 4 "não foi possível criar snapshot/journal antes da alteração"

    # 3) Máscaras
    mkdir -p "$MASK_GMS" "$MASK_GSF" "$MASK_STORE" || _prep_fail 1 "mkdir das máscaras falhou"
    clone_mask_metadata "$TARGET_GMS" "$MASK_GMS" "$ORIGINAL_GMS_OWNER" "$ORIGINAL_GMS_MODE" "$ORIGINAL_GMS_CONTEXT" "GMS"
    clone_mask_metadata "$TARGET_GSF" "$MASK_GSF" "$ORIGINAL_GSF_OWNER" "$ORIGINAL_GSF_MODE" "$ORIGINAL_GSF_CONTEXT" "GSF"
    clone_mask_metadata "$TARGET_STORE" "$MASK_STORE" "$ORIGINAL_STORE_OWNER" "$ORIGINAL_STORE_MODE" "$ORIGINAL_STORE_CONTEXT" "Store"

    say "[1/3] Aplicando máscaras (namespace global)"
    DEGOOGLE_FAIL_MOUNT="${DEGOOGLE_FAIL_MOUNT_GMS:-0}"
    mount_mask "$TARGET_GMS" "$MASK_GMS" "$GMS_APK" "$gms_src"
    DEGOOGLE_FAIL_MOUNT="${DEGOOGLE_FAIL_MOUNT_GSF:-0}"
    mount_mask "$TARGET_GSF" "$MASK_GSF" "" ""
    DEGOOGLE_FAIL_MOUNT="${DEGOOGLE_FAIL_MOUNT_STORE:-0}"
    mount_mask "$TARGET_STORE" "$MASK_STORE" "$STORE_APK" "$store_src"

    # 4) SELinux — sempre no namespace global; sucesso só com validação
    say "[2/3] SELinux (restorecon no namespace global)"
    [ "${DEGOOGLE_FAIL_RESTORECON:-0}" = "1" ] && _prep_fail 1 "DEGOOGLE_FAIL_RESTORECON injetado"
    global restorecon -R "$TARGET_GMS" 2>/dev/null || _prep_fail 4 "restorecon GMS falhou"
    global restorecon -R "$TARGET_GSF" 2>/dev/null || _prep_fail 4 "restorecon GSF falhou"
    global restorecon -R "$TARGET_STORE" 2>/dev/null || _prep_fail 4 "restorecon Store falhou"

    local ctx
    ctx="$(global ls -Zd "$TARGET_GMS" 2>/dev/null | awk '{print $1}')"
    case "$ctx" in
        u:object_r:system_file:s0|u:object_r:system_file:s0:*)
            say "  contexto GMS ok: $ctx"
            ;;
        *)
            _prep_fail 4 "SELINUX_CONTEXT_INVALID: contexto GMS inesperado (${ctx:-<sem contexto>}). Rollback aplicado."
            ;;
    esac

    # 5) Verificação final dos mounts
    say "[3/3] Verificação"
    is_masked_by_us "$TARGET_GMS" "$MASK_GMS" "$GMS_APK" || _prep_fail 4 "GMS_MOUNT_INVALID"
    is_masked_by_us "$TARGET_GSF" "$MASK_GSF" "" || _prep_fail 4 "GSF_MOUNT_INVALID"
    is_masked_by_us "$TARGET_STORE" "$MASK_STORE" "$STORE_APK" || _prep_fail 4 "STORE_MOUNT_INVALID"

    say "  GMS : $(global ls -laZ "$TARGET_GMS" 2>/dev/null | tail -n +2 | tr '\n' ' ')"
    say "  GSF : mascarado (vazio)"
    say "  Store: $(global ls -laZ "$TARGET_STORE" 2>/dev/null | tail -n +2 | tr '\n' ' ')"

    journal_state "STORE_MASKED" || _prep_fail 4 "não foi possível registrar STORE_MASKED"

    say ""
    say "PREPARE CONCLUÍDO. Faça o soft reboot (degoogle.sh soft-reboot ou botão no app)."
    emit STATE "PREPARED"
    return 0
}

# ===========================================================================
# finalize — pós-boot: valida priv-app e aplica configuração técnica
# ===========================================================================

grant_ok()
{
    local pkg="$1" perm="$2"
    if pm grant "$pkg" "$perm" >/dev/null 2>&1; then
        say "  grant ok: $perm"
        return 0
    fi
    say "  grant ignorado (inexistente/não outorgável): $perm"
    return 0
}

appop_ok()
{
    local pkg="$1" op="$2"
    if cmd appops set "$pkg" "$op" allow >/dev/null 2>&1; then
        say "  appops ok: $op"
        return 0
    fi
    say "  appops ignorado (op inexistente): $op"
    return 0
}

finalize()
{
    check_root

    # O pacote pode estar em um diretório diferente no modo experimental; a
    # validação pós-boot deve usar o locator da mesma transação.
    collect_facts

    local gms_now gsf_now store_now flags

    say "===== FINALIZE ====="

    # Pré-condições (estado MICROG_BOOTED real)
    gms_now="$(pm_path "$GMS_PKG")"
    path_under_target "$gms_now" "$TARGET_GMS" || fail 3 "GMS não está registrado da máscara (atual: ${gms_now:-ausente}). Rode prepare + soft reboot."
    gsf_now="$(pm_path "$GSF_PKG")"
    [ -z "$gsf_now" ] || fail 3 "GSF ainda registrado (${gsf_now}). A máscara GSF falhou."
    store_now="$(pm_path "$STORE_PKG")"
    path_under_target "$store_now" "$TARGET_STORE" || fail 3 "Companion não está registrado da máscara (atual: ${store_now:-ausente})."

    # Validação de priv-app de verdade (não só path)
    flags="$(gms_flags)"
    case "$flags" in
        *PRIVILEGED*) say "  GMS priv-app OK (flags=$flags)" ;;
        *) fail 3 "GMS não é PRIVILEGED (flags=$flags). A máscara/contexto falhou." ;;
    esac
    if ! dumpsys package "$GMS_PKG" 2>/dev/null | grep -q "INTERACT_ACROSS_USERS"; then
        say "  AVISO: INTERACT_ACROSS_USERS não aparece nas permissões do GMS."
    fi

    # Permissões (permissão inexistente = condição suportada, não erro)
    say "[permissões]"
    grant_ok "$GMS_PKG" android.permission.ACCESS_COARSE_LOCATION
    grant_ok "$GMS_PKG" android.permission.ACCESS_FINE_LOCATION
    grant_ok "$GMS_PKG" android.permission.ACCESS_BACKGROUND_LOCATION
    grant_ok "$GMS_PKG" android.permission.READ_PHONE_STATE
    grant_ok "$GMS_PKG" android.permission.READ_CONTACTS
    grant_ok "$GMS_PKG" android.permission.POST_NOTIFICATIONS
    grant_ok "$GMS_PKG" android.permission.CAMERA
    grant_ok "$GMS_PKG" android.permission.READ_EXTERNAL_STORAGE
    grant_ok "$GMS_PKG" android.permission.WRITE_EXTERNAL_STORAGE

    say "[background / Doze]"
    cmd deviceidle whitelist +"$GMS_PKG" >/dev/null 2>&1 || true
    cmd deviceidle whitelist +"$STORE_PKG" >/dev/null 2>&1 || true
    appop_ok "$GMS_PKG" RUN_IN_BACKGROUND
    appop_ok "$GMS_PKG" RUN_ANY_IN_BACKGROUND
    appop_ok "$GMS_PKG" ACCESS_BACKGROUND_LOCATION
    appop_ok "$STORE_PKG" RUN_IN_BACKGROUND
    appop_ok "$STORE_PKG" RUN_ANY_IN_BACKGROUND

    # Verificação (não declarar sucesso às cegas)
    local whitelisted=0
    if cmd deviceidle whitelist 2>/dev/null | grep -q "$GMS_PKG"; then
        whitelisted=1
        say "  Doze whitelist: OK ($GMS_PKG)"
    else
        say "  AVISO: $GMS_PKG não apareceu na whitelist do Doze."
    fi

    say ""
    say "FINALIZE CONCLUÍDO (idempotente; pode ser re-executado)."
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

    [ "${DEGOOGLE_FAIL_BACKUP:-0}" = "1" ] && fail 1 "DEGOOGLE_FAIL_BACKUP injetado"

    say "===== BACKUP DOS DADOS DO microG ====="

    am force-stop "$GMS_PKG" 2>/dev/null || true

    mkdir -p "$BACKUP_BASE" || fail 1 "mkdir $BACKUP_BASE falhou"
    if [ ! -d "$GMS_DATA_USER0" ]; then
        say "  GMS sem dados (nada a copiar)."
        return 0
    fi

    # Mesmo comportamento do script original: um novo backup substitui o
    # anterior, e user_de só é copiado se existir.
    rm -rf "$BACKUP_GMS_USER" "$BACKUP_GMS_DE"

    cp -a "$GMS_DATA_USER0" "$BACKUP_GMS_USER" || fail 1 "Falhou ao copiar dados de user0."
    if [ -d "$GMS_DATA_USERDE" ]; then
        cp -a "$GMS_DATA_USERDE" "$BACKUP_GMS_DE" || fail 1 "Falhou ao copiar dados de user_de."
    fi

    say "  uid original: $(stat -c %u "$GMS_DATA_USER0" 2>/dev/null)"
    say "  user0: $(du -sh "$BACKUP_GMS_USER" 2>/dev/null | awk '{print $1}')"
    [ -d "$BACKUP_GMS_DE" ] && say "  user_de: $(du -sh "$BACKUP_GMS_DE" 2>/dev/null | awk '{print $1}')"
    say "BACKUP CONCLUÍDO."
    emit BACKUP_OK "1"
    return 0
}

restore_backup()
{
    check_root
    require_lock

    collect_facts

    say "===== RESTAURANDO DADOS DO microG ====="

    [ -d "$BACKUP_GMS_USER" ] || {
        say "  Backup ausente (nada a restaurar)."
        return 0
    }
    [ "${DEGOOGLE_FAIL_RESTORE:-0}" = "1" ] && fail 1 "DEGOOGLE_FAIL_RESTORE injetado"

    local gms_now uid ctx
    gms_now="$(pm_path "$GMS_PKG")"
    path_under_target "$gms_now" "$TARGET_GMS" || fail 3 "GMS não registrado da máscara. Nada foi restaurado."

    am force-stop "$GMS_PKG" 2>/dev/null || true

    # UID atual, como no script original. O backup nunca força o UID antigo.
    uid="$(gms_uid_current)" || fail 1 "Não consegui derivar o UID atual."
    ctx="$(stat -c %C "$GMS_DATA_USER0" 2>/dev/null || true)"

    # O layout é o mesmo do microg-session.sh, mas a cópia usa o destino
    # explícito para não criar /data/user/0/gms-user0 por engano.
    rm -rf "$GMS_DATA_USER0"
    mkdir -p "$GMS_DATA_USER0" || fail 4 "mkdir do destino user0 falhou"
    case "$ctx" in
        u:*) ;;
        *) ctx="$(stat -c %C "$GMS_DATA_USER0" 2>/dev/null || true)" ;;
    esac
    cp -a "$BACKUP_GMS_USER"/. "$GMS_DATA_USER0"/ || fail 4 "Falha ao restaurar user0"
    chown -R "$uid:$uid" "$GMS_DATA_USER0" || fail 4 "chown user0 falhou"
    case "$ctx" in
        u:*) chcon -R "$ctx" "$GMS_DATA_USER0" 2>/dev/null || fail 4 "não consegui reproduzir contexto SELinux de user0" ;;
        *) restorecon -R "$GMS_DATA_USER0" 2>/dev/null || fail 4 "restorecon de user0 falhou" ;;
    esac

    if [ -d "$BACKUP_GMS_DE" ]; then
        ctx="$(stat -c %C "$GMS_DATA_USERDE" 2>/dev/null || true)"
        rm -rf "$GMS_DATA_USERDE"
        mkdir -p "$GMS_DATA_USERDE" || fail 4 "mkdir do destino user_de falhou"
        case "$ctx" in
            u:*) ;;
            *) ctx="$(stat -c %C "$GMS_DATA_USERDE" 2>/dev/null || true)" ;;
        esac
        cp -a "$BACKUP_GMS_DE"/. "$GMS_DATA_USERDE"/ || fail 4 "Falha ao restaurar user_de"
        chown -R "$uid:$uid" "$GMS_DATA_USERDE" || fail 4 "chown user_de falhou"
        case "$ctx" in
            u:*) chcon -R "$ctx" "$GMS_DATA_USERDE" 2>/dev/null || fail 4 "não consegui reproduzir contexto SELinux de user_de" ;;
            *) restorecon -R "$GMS_DATA_USERDE" 2>/dev/null || fail 4 "restorecon de user_de falhou" ;;
        esac
    fi

    [ -d "$GMS_DATA_USER0" ] || fail 4 "restauração não materializou user0"
    say "  restaurado com uid $uid"
    say "  user0: $(du -sh "$GMS_DATA_USER0" 2>/dev/null | awk '{print $1}')"
    [ -d "$GMS_DATA_USERDE" ] && say "  user_de: $(du -sh "$GMS_DATA_USERDE" 2>/dev/null | awk '{print $1}')"
    say "RESTAURAÇÃO CONCLUÍDA."
    emit RESTORE_OK "1"
    return 0
}

# ===========================================================================
# cleanup — remove updates de /data/app e desabilita a Play Store
# (usado pelo prepare e como ação de recuperação do estado ERROR)
# ===========================================================================

cleanup()
{
    check_root
    local p g
    for p in "$GMS_PKG" "$STORE_PKG"; do
        g="$(pm_path "$p")"
        case "$g" in
            /data/app/*)
                say "  $p em /data/app — removendo update (volta ao base)..."
                pm uninstall "$p" >/dev/null 2>&1 || fail 1 "pm uninstall $p falhou"
                sleep 2
                ;;
        esac
    done
    # desabilita a Play Store para impedir re-update do GMS durante o fluxo
    if pm disable-user --user 0 "$STORE_PKG" >/dev/null 2>&1; then
        say "  Play Store desabilitada (evita re-update do GMS)."
    else
        say "  ERRO: não foi possível desabilitar a Play Store."
        return 1
    fi
}

# ===========================================================================
# restore-stock — rollback completo para o ambiente stock
# ===========================================================================

snapshot_field()
{
    # snapshot_field <component> <field>
    # Os paths gravados pelo backend já foram validados contra a allowlist;
    # esta leitura aceita apenas valores sem aspas/newlines.
    local component="$1" field="$2"
    [ -f "$SNAPSHOT_FILE" ] || return 1
    sed -n "s/.*\"$component\".*\"$field\":\"\([^\"]*\)\".*/\1/p" "$SNAPSHOT_FILE" 2>/dev/null | head -n 1
}

snapshot_top_field()
{
    local field="$1"
    [ -f "$SNAPSHOT_FILE" ] || return 1
    sed -n "s/.*\"$field\":\"\([^\"]*\)\".*/\1/p" "$SNAPSHOT_FILE" 2>/dev/null | head -n 1
}

rollback_target_from_snapshot()
{
    # rollback_target_from_snapshot <component> <profileTarget>
    # Snapshot é preferido. O perfil só é fallback para uma combinação Samsung
    # conhecida; em aparelho desconhecido, package ausente + target ausente é
    # uma condição de bloqueio, nunca um convite para adivinhar.
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
    is_allowed_system_path "$target" || return 1
    printf '%s' "$target"
}

resolve_rollback_targets()
{
    local snapshot_fp snapshot_model
    snapshot_fp="$(snapshot_top_field deviceFingerprint)"
    snapshot_model="$(snapshot_top_field model)"
    if [ -n "$snapshot_fp" ] && [ "$snapshot_fp" != "$FINGERPRINT" ]; then
        say "ERRO: snapshot pertence a outro fingerprint; não vou adivinhar alvos de rollback."
        return 1
    fi
    if [ -n "$snapshot_model" ] && [ "$snapshot_model" != "$MODEL" ]; then
        say "ERRO: snapshot pertence a outro modelo; não vou adivinhar alvos de rollback."
        return 1
    fi

    TARGET_GMS="$(rollback_target_from_snapshot gms "$PROFILE_GMS")" || {
        say "ERRO: alvo GMS de rollback não pôde ser resolvido com segurança."
        return 1
    }
    TARGET_GSF="$(rollback_target_from_snapshot gsf "$PROFILE_GSF")" || {
        say "ERRO: alvo GSF de rollback não pôde ser resolvido com segurança."
        return 1
    }
    TARGET_STORE="$(rollback_target_from_snapshot store "$PROFILE_STORE")" || {
        say "ERRO: alvo Store de rollback não pôde ser resolvido com segurança."
        return 1
    }
    return 0
}

pm_enable_or_defer()
{
    # Durante a janela entre unmount e reboot o PM pode ainda não registrar um
    # APK stock. Isso é esperado se o diretório stock existe; o enable fica
    # pendente para o re-scan pós-boot. Outros erros continuam bloqueando.
    local pkg="$1" target="$2" label="$3" current
    current="$(pm_path "$pkg")"
    if [ -n "$current" ]; then
        if pm list packages -d --user 0 2>/dev/null | grep -qx "package:$pkg"; then
            pm enable --user 0 "$pkg" >/dev/null 2>&1 || {
                say "ERRO: não consegui reabilitar $label ($pkg) já registrado no PM."
                return 1
            }
            say "  $label reabilitado ($pkg)."
        else
            # Evita emitir PACKAGE_CHANGED sem necessidade. Em um PM ainda
            # stale isso pode iniciar imediatamente o APK que acabou de ser
            # desmontado, antes do reboot/reindexamento obrigatório.
            say "  $label já estava habilitado; nenhuma alteração enviada ao PM."
        fi
        return 0
    fi
    if global test -d "$target" 2>/dev/null; then
        say "  $label ainda não está registrado no PM; enable pendente até o re-scan pós-boot."
        return 0
    fi
    say "ERRO: $label ausente no PM e o target stock não existe: $target"
    return 1
}

microg_data_is_cleared()
{
    # O PackageManager pode recriar a árvore básica (cache/code_cache) logo
    # após a remoção. Diretórios vazios são inofensivos; qualquer arquivo,
    # link ou outro payload significa que a limpeza ainda não terminou.
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
        # force-stop imediatamente antes de cada tentativa. Reabilitar o GMS
        # antes deste ponto cria uma corrida com processos persistent/UI.
        am force-stop "$GMS_PKG" 2>/dev/null || true
        rm -rf "$GMS_DATA_USER0" "$GMS_DATA_USERDE" 2>/dev/null || true
        if microg_data_is_cleared; then
            return 0
        fi
        say "  dados do microG foram recriados durante a limpeza; repetindo ($attempt/3)…"
        attempt=$((attempt + 1))
    done
    say "ERRO: ainda há payloads nos diretórios de dados do microG após 3 tentativas."
    return 1
}

rollback_unmount()
{
    # rollback_unmount <label> <target> <known>
    local label="$1" target="$2" known="$3"
    [ -n "$target" ] && [ "$target" != "." ] || {
        say "ERRO: target $label vazio/inválido durante rollback."
        return 1
    }
    is_mounted "$target" || return 0
    [ "$known" = "1" ] || {
        say "ERRO: mount $label em $target não pertence a uma máscara conhecida."
        return 1
    }
    if ! global umount "$target" 2>/dev/null; then
        say "  $label ocupado; tentando umount -l (lazy)…"
        global umount -l "$target" 2>/dev/null || {
            say "ERRO: não consegui desmontar $label em $target."
            return 1
        }
    fi
    if is_mounted "$target"; then
        say "ERRO: mount $label ainda aparece no mountinfo após unmount."
        return 1
    fi
    say "  $label desmontado: $target"
    return 0
}

restore_stock()
{
    check_root
    require_lock

    local wipe="${1:-}" foreign="" failed="" cache_backup

    # Package Manager pode estar sem GSF/Store justamente porque os mounts
    # estão ativos. Resolva os alvos antes de qualquer mutação, usando snapshot
    # ou perfil known-good, nunca dirname de uma string vazia.
    collect_facts
    resolve_rollback_targets || fail 3 "não foi possível resolver os três alvos de rollback sem adivinhação"
    refresh_mount_facts

    [ "$MOUNT_GMS" = "1" ] && [ "$MOUNT_GMS_IS_KNOWN" != "1" ] && foreign="$foreign GMS"
    [ "$MOUNT_GSF" = "1" ] && [ "$MOUNT_GSF_IS_KNOWN" != "1" ] && foreign="$foreign GSF"
    [ "$MOUNT_STORE" = "1" ] && [ "$MOUNT_STORE_IS_KNOWN" != "1" ] && foreign="$foreign Store"
    [ -z "$foreign" ] || fail 4 "mount externo detectado:$foreign — não vou desmontá-lo automaticamente"

    journal_state "ROLLBACK_RUNNING" >/dev/null 2>&1 || fail 4 "não foi possível registrar ROLLBACK_RUNNING"

    say "===== RESTORE-STOCK ====="
    say "Targets: GMS=$TARGET_GMS GSF=$TARGET_GSF Store=$TARGET_STORE"

    am force-stop "$GMS_PKG" 2>/dev/null || true
    am force-stop "$STORE_PKG" 2>/dev/null || true

    # Desmonta tudo antes de remover os APKs da fonte. Assim, uma falha de
    # unmount preserva a evidência e permite uma nova tentativa idempotente.
    if [ "$MOUNT_GMS" = "1" ]; then
        if [ "${DEGOOGLE_FAIL_UMOUNT:-0}" = "1" ]; then
            say "ERRO: DEGOOGLE_FAIL_UMOUNT injetado para GMS."
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
        fail 4 "Falha ao desmontar:$failed — fontes preservadas para nova tentativa."
    fi

    # Só remove os arquivos depois de provar que nenhum target continua
    # montado. O diretório GSF é intencionalmente vazio. A limpeza também
    # remove somente payloads conhecidos do script manual legado.
    cleanup_known_mask_residue || {
        journal_state "ROLLBACK_REQUIRED" >/dev/null 2>&1 || true
        fail 4 "não consegui remover os APKs conhecidos das máscaras após desmontá-las"
    }

    # Invalida o cache de parse do PackageManager. O reboot ainda é obrigatório
    # para o Android reindexar system apps cujo codePath não mudou.
    if [ -d "$PM_CACHE_DIR" ] && [ -n "$(ls -A "$PM_CACHE_DIR" 2>/dev/null)" ]; then
        mkdir -p "$BACKUP_BASE" 2>/dev/null || {
            journal_state "REINDEX_PENDING" >/dev/null 2>&1 || true
            fail 4 "não foi possível preparar backup do cache do PackageManager"
        }
        cache_backup="$BACKUP_BASE/pm-cache-$(date +%s)"
        if mv "$PM_CACHE_DIR" "$cache_backup" 2>/dev/null; then
            say "  cache de parse do PM movido para $cache_backup (re-scan forçado)."
        elif rm -rf "${PM_CACHE_DIR:?}"/* 2>/dev/null || rm -rf "$PM_CACHE_DIR" 2>/dev/null; then
            say "  cache de parse do PM limpo (re-scan forçado)."
        else
            journal_state "REINDEX_PENDING" >/dev/null 2>&1 || true
            fail 4 "não foi possível limpar $PM_CACHE_DIR; mounts já foram removidos, reboot/reindexamento é obrigatório"
        fi
    else
        say "  cache de parse do PM ausente/vazio — nada a fazer."
    fi
    journal_state "PACKAGE_CACHE_INVALIDATED" || fail 4 "cache invalidado, mas journal não pôde ser atualizado"

    # Dados órfãos do microG (com consentimento explícito do app).
    if [ "$wipe" = "--wipe-data" ]; then
        remove_microg_data_safely || fail 4 "não consegui remover dados do microG"
        say "  dados do microG removidos de /data."
    else
        say "  dados do microG preservados em /data (use --wipe-data para remover)."
    fi

    # Só notifica o PackageManager depois que os dados antigos desapareceram.
    # Antes disso, um `pm enable` pode iniciar o GMS stale e fazê-lo recriar os
    # diretórios enquanto o rollback ainda os remove.
    pm_enable_or_defer "$STORE_PKG" "$TARGET_STORE" "Play Store" || {
        journal_state "ROLLBACK_REQUIRED" >/dev/null 2>&1 || true
        fail 4 "não consegui preparar a habilitação da Play Store"
    }
    pm_enable_or_defer "$GMS_PKG" "$TARGET_GMS" "GMS" || {
        journal_state "ROLLBACK_REQUIRED" >/dev/null 2>&1 || true
        fail 4 "não consegui preparar a habilitação do GMS"
    }

    say ""
    say "RESTORE-STOCK PREPARADO. Reindexamento do Package Manager e reboot são obrigatórios."
    say "O backup do microG em $BACKUP_BASE foi preservado."
    journal_state "REINDEX_PENDING" || fail 4 "rollback concluído, mas não consegui registrar REINDEX_PENDING"
    emit REINDEX_PENDING "1"
    emit REBOOT_REQUIRED "1"
    emit STATE "RESTORE_PREPARED"
    return 0
}

cleanup_known_mask_residue()
{
    # Nunca remove recursivamente /data/local/tmp. Apenas os APKs e diretórios
    # criados pelos dois layouts conhecidos são candidatos; arquivos estranhos
    # ficam preservados para diagnóstico.
    local p
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
    resolve_rollback_targets || fail 3 "não foi possível resolver os alvos de recuperação"
    refresh_mount_facts

    [ "$MOUNT_GMS" = "1" ] && foreign="$foreign GMS"
    [ "$MOUNT_GSF" = "1" ] && foreign="$foreign GSF"
    [ "$MOUNT_STORE" = "1" ] && foreign="$foreign Store"
    [ -z "$foreign" ] || fail 4 "mount ativo detectado:$foreign — resíduos não serão removidos enquanto houver mount"

    cleanup_known_mask_residue || fail 4 "não consegui limpar os payloads conhecidos das máscaras"
    say "Resíduos conhecidos das máscaras removidos; backup do microG preservado."
    emit RESIDUE_CLEANUP "1"
    emit MASK_RESIDUE_PRESENT "$MASK_RESIDUE_PRESENT"
    emit STATE "STOCK"
    return 0
}

post_boot_failure()
{
    local reason="$1"
    emit POST_BOOT_STATUS "FAIL"
    if ! journal_state "ROLLBACK_REQUIRED" >/dev/null 2>&1; then
        reason="$reason; não foi possível registrar ROLLBACK_REQUIRED"
    fi
    emit POST_BOOT_REASON "$reason"
    return 4
}

post_boot_validate()
{
    check_root
    collect_facts
    if ! journal_state "POST_BOOT_VALIDATING" >/dev/null 2>&1; then
        post_boot_failure "não foi possível registrar POST_BOOT_VALIDATING"
        return $?
    fi

    local boot_completed server_pid boot_reason
    boot_completed="$(getprop sys.boot_completed 2>/dev/null)"
    case "$boot_completed" in
        1) ;;
        *) post_boot_failure "sys.boot_completed não confirmou boot concluído"; return $? ;;
    esac
    server_pid="$(pidof system_server 2>/dev/null || true)"
    [ -n "$server_pid" ] || server_pid="$(ps -A 2>/dev/null | awk '$NF == "system_server" {print $1; exit}')"
    [ -n "$server_pid" ] || {
        post_boot_failure "system_server não está vivo"
        return $?
    }
    boot_reason="$(getprop sys.boot.reason 2>/dev/null || true)"
    case "$boot_reason" in
        *rescueparty*|*rescue_party*)
            post_boot_failure "boot reason indica Rescue Party: $boot_reason"; return $? ;;
    esac
    path_under_target "$GMS_PATH" "$TARGET_GMS" || { post_boot_failure "GMS não resolve para a máscara"; return $?; }
    [ -z "$GSF_PATH" ] || { post_boot_failure "GSF stock ainda está ativo"; return $?; }
    path_under_target "$STORE_PATH" "$TARGET_STORE" || { post_boot_failure "Companion não resolve para a máscara"; return $?; }
    [ "$GMS_PRIVILEGED" = "1" ] || { post_boot_failure "GMS não está PRIVILEGED"; return $?; }
    [ "$SELINUX" = "Enforcing" ] || { post_boot_failure "SELinux deixou de estar Enforcing"; return $?; }

    journal_state "COMMITTED" || {
        emit POST_BOOT_STATUS "FAIL"; emit POST_BOOT_REASON "não foi possível registrar COMMITTED"; return 4
    }
    rm -f "$RESCUE_STATE_FILE" 2>/dev/null || true
    emit POST_BOOT_STATUS "PASS"
    emit POST_BOOT_REASON "system_server, boot_completed, packages, priv-app e SELinux validados"
    return 0
}

# ===========================================================================
# soft-reboot
# ===========================================================================

soft_reboot()
{
    check_root
    local source now last attempts cooldown elapsed remaining previous_state
    local ksud_output ksud_status power_output power_status
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
    # Método 1: ksud soft-reboot (KernelSU) — o mecanismo do KernelSU Manager;
    # emula um reboot de sistema preservando o kernel (root via exploit) e os
    # mounts. É o método comprovado no fluxo do microg-session.sh.
    if [ -x "$KSUD_PATH" ]; then
        ksud_output="$($KSUD_PATH soft-reboot 2>&1)"
        ksud_status=$?
        if [ "$ksud_status" -eq 0 ]; then
            say "KernelSU userspace reboot accepted."
            return 0
        fi
        say "KernelSU userspace reboot failed (exit ${ksud_status})."
        [ -n "$ksud_output" ] && say "KernelSU detail: $ksud_output"
    else
        say "KernelSU userspace reboot binary is unavailable."
    fi
    # Método 2: sys.powerctl (AOSP userspace reboot) — pode falhar por SELinux
    # no domínio do shell; vale a tentativa.
    power_output="$(setprop sys.powerctl reboot,userspace 2>&1)"
    power_status=$?
    if [ "$power_status" -eq 0 ]; then
        say "AOSP userspace reboot accepted."
        return 0
    fi
    say "AOSP userspace reboot rejected (exit ${power_status})."
    [ -n "$power_output" ] && say "AOSP detail: $power_output"
    # IMPORTANTE: aparelhos com root via exploit NUNCA devem usar reboot
    # completo — o kernel reboot perde o root e o microG. Abortamos com
    # instrução segura em vez de sugerir reboot completo.
    [ "$source" = "automatic" ] && rm -f "$RESCUE_STATE_FILE" 2>/dev/null || true
    if [ -n "$previous_state" ]; then
        journal_state "$previous_state" >/dev/null 2>&1 || true
    else
        journal_state "FAILED" >/dev/null 2>&1 || true
    fi
    fail_en 5 "No supported userspace reboot method succeeded; no reboot was started."
}

# ===========================================================================
# test — self-test (não toca no aparelho)
# ===========================================================================

self_test()
{
    local tmpdir ok=0
    tmpdir="$(mktemp -d 2>/dev/null || echo /data/local/tmp/degoogle-selftest)"
    mkdir -p "$tmpdir"

    say "[test] is_apk"
    printf 'PK\003\004fake' > "$tmpdir/ok.apk"
    printf 'notanapk' > "$tmpdir/bad.apk"
    if is_apk "$tmpdir/ok.apk"; then say "  ok.apk detectado como APK ✓"; else say "  FALHA: ok.apk rejeitado"; ok=1; fi
    if is_apk "$tmpdir/bad.apk"; then say "  FALHA: bad.apk aceito"; ok=1; else say "  bad.apk rejeitado ✓"; fi

    say "[test] parse de mountinfo"
    cat > "$tmpdir/mountinfo" <<'EOF'
36 27 0:18 /data/local/tmp/degoogle-mask/gms /product/priv-app/GmsCore rw,relatime - ext4 /dev/block/sda1 rw
37 27 0:18 / /system rw,relatime - ext4 /dev/block/sda2 ro
EOF
    local r
    r="$(MOUNTINFO="$tmpdir/mountinfo" mountinfo_root /product/priv-app/GmsCore)"
    if [ "$r" = "/data/local/tmp/degoogle-mask/gms" ]; then
        say "  mountinfo_root(GmsCore) = $r ✓"
    else
        say "  FALHA: mountinfo_root retornou '$r'"; ok=1
    fi
    if MOUNTINFO="$tmpdir/mountinfo" is_mounted /system; then
        say "  is_mounted(/system) ✓"
    else
        say "  FALHA: /system não detectado"; ok=1
    fi

    say "[test] usage"
    if "$0" no-such-cmd >/dev/null 2>&1; then say "  FALHA: comando inexistente retornou 0"; ok=1; else say "  comando inexistente → exit != 0 ✓"; fi

    rm -rf "$tmpdir"
    if [ "$ok" = "0" ]; then
        say "SELF-TEST OK."
        return 0
    fi
    fail 1 "SELF-TEST FALHOU."
}

# ===========================================================================
# dispatch
# ===========================================================================

case "${1:-}" in
    probe)
        probe
        ;;
    prepare)
        prepare "${2:-}" "${3:-}"
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
    rollback)
        restore_stock "${2:-}"
        ;;
    test)
        self_test
        ;;
    *)
        fail_usage "probe|dry-run|preflight|prepare <gms> <companion>|finalize|cleanup|cleanup-residue|backup|restore-backup|restore-stock [--wipe-data]|rollback [--wipe-data]|post-boot-validate|soft-reboot|status|test"
        ;;
esac
