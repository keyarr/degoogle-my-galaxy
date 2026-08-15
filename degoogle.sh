#!/system/bin/sh
# =============================================================================
# degoogle.sh — backend DeGoogle (v0.1.0)
#
# Backend shell do app DeGoogle. Opera exclusivamente com root e com o
# device profile abaixo. Todas as operações são idempotentes; operações
# destrutivas têm rollback; nada é declarado sucesso sem verificação.
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
#   degoogle.sh test
# =============================================================================

set -u

SCRIPT_VERSION="0.1.0"

# ---------------------------------------------------------------------------
# Device profile (v1: Samsung Galaxy S24 Ultra / SM-S928x)
# ---------------------------------------------------------------------------
PROFILE_ID="samsung_sm-s928b"
PROFILE_MANUFACTURER="samsung"
PROFILE_MODELS="SM-S928B|SM-S928U|SM-S928W|SM-S928N|SM-S9280"

GMS_PKG="com.google.android.gms"
GSF_PKG="com.google.android.gsf"
STORE_PKG="com.android.vending"

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
GMS_APK="GmsCore.apk"
STORE_APK="Companion.apk"

BACKUP_BASE="${DEGOOGLE_BACKUP_BASE:-/data/local/tmp/microg-backup}"
BACKUP_GMS_USER="$BACKUP_BASE/gms-user0"
BACKUP_GMS_DE="$BACKUP_BASE/gms-userde"

# Layout idêntico ao microg-session.sh. As variáveis permitem testar o backend
# em host sem alterar os caminhos Android reais.
GMS_DATA_USER0="${DEGOOGLE_GMS_DATA_USER0:-/data/user/0/com.google.android.gms}"
GMS_DATA_USERDE="${DEGOOGLE_GMS_DATA_USERDE:-/data/user_de/0/com.google.android.gms}"

LOCK_DIR="${DEGOOGLE_LOCK_DIR:-/data/local/tmp/degoogle.lockdir}"

MOUNTINFO="${DEGOOGLE_MOUNTINFO:-/proc/1/mountinfo}"

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
GMS_PATH=""
GMS_VERSION=""
GMS_VERSION_CODE=""
GMS_UID=""
GMS_FLAGS=""
GMS_PRIVILEGED=0
GSF_PATH=""
STORE_PATH=""
STORE_VERSION=""
MOUNT_GMS=0
MOUNT_GMS_IS_OURS=0
MOUNT_GSF=0
MOUNT_GSF_IS_OURS=0
MOUNT_STORE=0
MOUNT_STORE_IS_OURS=0
MOUNT_GMS_SOURCE=""
BACKUP_PRESENT=0
FINALIZE_DONE=0

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

pm_path()
{
    pm path "$1" 2>/dev/null | sed -n 's/^package://p' | head -n 1
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

# ===========================================================================
# Coleta de fatos (fonte de verdade = estado real do Android)
# ===========================================================================

collect_facts()
{
    MANUFACTURER="$(getprop ro.product.manufacturer 2>/dev/null)"
    MODEL="$(getprop ro.product.model 2>/dev/null)"
    DEVICE="$(getprop ro.product.device 2>/dev/null)"
    PRODUCT="$(getprop ro.product.name 2>/dev/null)"
    ANDROID_SDK="$(getprop ro.build.version.sdk 2>/dev/null)"
    ANDROID_RELEASE="$(getprop ro.build.version.release 2>/dev/null)"
    FINGERPRINT="$(getprop ro.build.fingerprint 2>/dev/null)"
    ABI="$(getprop ro.product.cpu.abi 2>/dev/null)"
    SELINUX="$(getenforce 2>/dev/null || echo unknown)"

    if [ "$(id -u 2>/dev/null)" = "0" ]; then
        ROOT_OK=1
        detect_root_manager
    fi

    # leituras que exigem root (ou pelo menos visibilidade do namespace)
    if [ "$ROOT_OK" = "1" ]; then
        GMS_PATH="$(pm_path "$GMS_PKG")"
        GSF_PATH="$(pm_path "$GSF_PKG")"
        STORE_PATH="$(pm_path "$STORE_PKG")"
        GMS_VERSION="$(gms_version)"
        GMS_VERSION_CODE="$(gms_version_code)"
        GMS_FLAGS="$(gms_flags)"
        case "$GMS_FLAGS" in *PRIVILEGED*) GMS_PRIVILEGED=1 ;; esac
        GMS_UID="$(gms_uid_current 2>/dev/null || true)"

        # Mounts: detecta QUALQUER mount ativo nos paths do perfil e marca se
        # a fonte é a nossa máscara. Fonte desconhecida = estado não gerenciado.
        local mr
        MOUNT_GMS=0; MOUNT_GMS_IS_OURS=0; MOUNT_GMS_SOURCE=""
        mr="$(mountinfo_root "$PROFILE_GMS")"
        if [ -n "$mr" ]; then
            MOUNT_GMS=1
            MOUNT_GMS_SOURCE="$mr"
            [ "$mr" = "$(fs_root_of "$MASK_GMS")" ] && MOUNT_GMS_IS_OURS=1
        fi
        MOUNT_GSF=0; MOUNT_GSF_IS_OURS=0
        mr="$(mountinfo_root "$PROFILE_GSF")"
        if [ -n "$mr" ]; then MOUNT_GSF=1; [ "$mr" = "$(fs_root_of "$MASK_GSF")" ] && MOUNT_GSF_IS_OURS=1; fi
        MOUNT_STORE=0; MOUNT_STORE_IS_OURS=0
        mr="$(mountinfo_root "$PROFILE_STORE")"
        if [ -n "$mr" ]; then MOUNT_STORE=1; [ "$mr" = "$(fs_root_of "$MASK_STORE")" ] && MOUNT_STORE_IS_OURS=1; fi

        if cmd deviceidle whitelist 2>/dev/null | grep -q "$GMS_PKG"; then FINALIZE_DONE=1; fi
    fi

    # Backup no formato do microg-session.sh: diretórios copiados diretamente.
    # A presença de user0 é o indicador usado pelo script original; user_de é
    # opcional porque nem todo firmware cria esse diretório.
    BACKUP_PRESENT=0
    if [ -d "$BACKUP_GMS_USER" ]; then
        BACKUP_PRESENT=1
    fi
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
    emit ANDROID_SDK "$ANDROID_SDK"
    emit ANDROID_RELEASE "$ANDROID_RELEASE"
    emit FINGERPRINT "$FINGERPRINT"
    emit SELINUX "$SELINUX"
    emit ABI "$ABI"
    emit GMS_PATH "$GMS_PATH"
    emit GMS_VERSION "$GMS_VERSION"
    emit GMS_VERSION_CODE "$GMS_VERSION_CODE"
    emit GMS_UID "$GMS_UID"
    emit GMS_FLAGS "$GMS_FLAGS"
    emit GMS_PRIVILEGED "$GMS_PRIVILEGED"
    emit GSF_PATH "$GSF_PATH"
    emit STORE_PATH "$STORE_PATH"
    emit STORE_VERSION "$STORE_VERSION"
    emit MOUNT_GMS "$MOUNT_GMS"
    emit MOUNT_GMS_IS_OURS "$MOUNT_GMS_IS_OURS"
    emit MOUNT_GSF "$MOUNT_GSF"
    emit MOUNT_GSF_IS_OURS "$MOUNT_GSF_IS_OURS"
    emit MOUNT_STORE "$MOUNT_STORE"
    emit MOUNT_STORE_IS_OURS "$MOUNT_STORE_IS_OURS"
    emit MOUNT_GMS_SOURCE "$MOUNT_GMS_SOURCE"
    emit BACKUP_PRESENT "$BACKUP_PRESENT"
    emit FINALIZE_DONE "$FINALIZE_DONE"
    emit SCRIPT_VERSION "$SCRIPT_VERSION"
}

# ===========================================================================
# Derivação de estado (mesmas regras do StateDetector do app)
# ===========================================================================

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
    if [ "$MOUNT_GMS" = "0" ] && [ "$MOUNT_GSF" = "0" ] && [ "$MOUNT_STORE" = "0" ]; then
        # nenhum mount: stock ou rollback pendente de reboot
        case "$GMS_PATH" in
            "$PROFILE_GMS"/*)
                if [ -n "$GSF_PATH" ] && [ -n "$STORE_PATH" ]; then
                    STATE="STOCK"
                else
                    STATE="RESTORE_PREPARED"
                fi
                ;;
            *)
                STATE="ERROR"
                ;;
        esac
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
        "$PROFILE_GMS"/*) ;;
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
        "$PROFILE_STORE"/*) ;;
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

# ===========================================================================
# prepare — STOCK → PREPARED (idempotente, transacional)
# ===========================================================================

NEW_MOUNTS=""

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
                "$PROFILE_GMS") rm -f "$MASK_GMS/$GMS_APK" 2>/dev/null ;;
                "$PROFILE_STORE") rm -f "$MASK_STORE/$STORE_APK" 2>/dev/null ;;
            esac
        done
        exit 4
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
    say "  mascarado: $target"
}

prepare()
{
    check_root
    require_lock

    local gms_src="${1:-}" store_src="${2:-}"
    [ -n "$gms_src" ] && [ -n "$store_src" ] || fail_usage "prepare <gms.apk> <companion.apk>"

    profile_check || fail 5 "Perfil incompatível. Nada foi alterado."

    say "===== PREPARE ====="

    # 1) Pré-condições: paths reais do PM, nunca assumidos. GMS/vending podem
    #    ter update em /data/app (auto-update da Play Store) — aceito e removido
    #    no passo seguinte. GSF precisa ser o stock (não há update esperado).
    local gms_now gsf_now store_now
    gms_now="$(pm_path "$GMS_PKG")"
    gsf_now="$(pm_path "$GSF_PKG")"
    store_now="$(pm_path "$STORE_PKG")"
    case "$gms_now" in
        "$PROFILE_GMS"/*|/data/app/*) ;;
        *) fail 3 "GMS em path inesperado (atual: ${gms_now:-ausente}). Nada foi alterado." ;;
    esac
    case "$gsf_now" in
        "$PROFILE_GSF"/*) ;;
        *) fail 3 "GSF não está em $PROFILE_GSF (atual: ${gsf_now:-ausente}). Nada foi alterado." ;;
    esac
    case "$store_now" in
        "$PROFILE_STORE"/*|/data/app/*) ;;
        *) fail 3 "Play Store em path inesperado (atual: ${store_now:-ausente}). Nada foi alterado." ;;
    esac

    # 1b) Remove updates de /data/app (GMS/vending) e desabilita a Play Store
    #     (impede o auto-update do GMS durante o fluxo). Idempotente.
    cleanup

    gms_now="$(pm_path "$GMS_PKG")"
    store_now="$(pm_path "$STORE_PKG")"
    case "$gms_now" in
        "$PROFILE_GMS"/*) ;;
        *) fail 3 "GMS não voltou ao base após remover o update (atual: ${gms_now:-ausente})." ;;
    esac
    case "$store_now" in
        "$PROFILE_STORE"/*) ;;
        *) fail 3 "Play Store não voltou ao base após remover o update (atual: ${store_now:-ausente})." ;;
    esac

    # 2) APKs (validação completa é do app; aqui: existência + magic)
    [ -f "$gms_src" ] || fail 3 "APK microG não encontrado: $gms_src"
    [ -f "$store_src" ] || fail 3 "APK Companion não encontrado: $store_src"
    is_apk "$gms_src" || fail 3 "microG não é um APK válido: $gms_src"
    is_apk "$store_src" || fail 3 "Companion não é um APK válido: $store_src"

    # 3) Máscaras
    mkdir -p "$MASK_GMS" "$MASK_GSF" "$MASK_STORE" || fail 1 "mkdir das máscaras falhou"

    say "[1/3] Aplicando máscaras (namespace global)"
    DEGOOGLE_FAIL_MOUNT="${DEGOOGLE_FAIL_MOUNT_GMS:-0}"
    mount_mask "$PROFILE_GMS" "$MASK_GMS" "$GMS_APK" "$gms_src"
    DEGOOGLE_FAIL_MOUNT="${DEGOOGLE_FAIL_MOUNT_GSF:-0}"
    mount_mask "$PROFILE_GSF" "$MASK_GSF" "" ""
    DEGOOGLE_FAIL_MOUNT="${DEGOOGLE_FAIL_MOUNT_STORE:-0}"
    mount_mask "$PROFILE_STORE" "$MASK_STORE" "$STORE_APK" "$store_src"

    # 4) SELinux — sempre no namespace global; sucesso só com validação
    say "[2/3] SELinux (restorecon no namespace global)"
    [ "${DEGOOGLE_FAIL_RESTORECON:-0}" = "1" ] && _prep_fail 1 "DEGOOGLE_FAIL_RESTORECON injetado"
    global restorecon -R "$PROFILE_GMS" 2>/dev/null || true
    global restorecon -R "$PROFILE_GSF" 2>/dev/null || true
    global restorecon -R "$PROFILE_STORE" 2>/dev/null || true

    local ctx
    ctx="$(global ls -Zd "$PROFILE_GMS" 2>/dev/null | awk '{print $1}')"
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
    is_masked_by_us "$PROFILE_GMS" "$MASK_GMS" "$GMS_APK" || _prep_fail 4 "GMS_MOUNT_INVALID"
    is_masked_by_us "$PROFILE_GSF" "$MASK_GSF" "" || _prep_fail 4 "GSF_MOUNT_INVALID"
    is_masked_by_us "$PROFILE_STORE" "$MASK_STORE" "$STORE_APK" || _prep_fail 4 "STORE_MOUNT_INVALID"

    say "  GMS : $(global ls -laZ "$PROFILE_GMS" 2>/dev/null | tail -n +2 | tr '\n' ' ')"
    say "  GSF : mascarado (vazio)"
    say "  Store: $(global ls -laZ "$PROFILE_STORE" 2>/dev/null | tail -n +2 | tr '\n' ' ')"

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

    local gms_now gsf_now store_now flags

    say "===== FINALIZE ====="

    # Pré-condições (estado MICROG_BOOTED real)
    gms_now="$(pm_path "$GMS_PKG")"
    case "$gms_now" in
        "$PROFILE_GMS"/*) ;;
        *) fail 3 "GMS não está registrado da máscara (atual: ${gms_now:-ausente}). Rode prepare + soft reboot." ;;
    esac
    gsf_now="$(pm_path "$GSF_PKG")"
    [ -z "$gsf_now" ] || fail 3 "GSF ainda registrado (${gsf_now}). A máscara GSF falhou."
    store_now="$(pm_path "$STORE_PKG")"
    case "$store_now" in
        "$PROFILE_STORE"/*) ;;
        *) fail 3 "Companion não está registrado da máscara (atual: ${store_now:-ausente})." ;;
    esac

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

    say "===== RESTAURANDO DADOS DO microG ====="

    [ -d "$BACKUP_GMS_USER" ] || {
        say "  Backup ausente (nada a restaurar)."
        return 0
    }
    [ "${DEGOOGLE_FAIL_RESTORE:-0}" = "1" ] && fail 1 "DEGOOGLE_FAIL_RESTORE injetado"

    local gms_now uid ctx
    gms_now="$(pm_path "$GMS_PKG")"
    case "$gms_now" in
        "$PROFILE_GMS"/*) ;;
        *) fail 3 "GMS não registrado da máscara. Nada foi restaurado." ;;
    esac

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
        u:*) chcon -R "$ctx" "$GMS_DATA_USER0" 2>/dev/null || true ;;
        *) restorecon -R "$GMS_DATA_USER0" 2>/dev/null || true ;;
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
            u:*) chcon -R "$ctx" "$GMS_DATA_USERDE" 2>/dev/null || true ;;
            *) restorecon -R "$GMS_DATA_USERDE" 2>/dev/null || true ;;
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
        say "  AVISO: não foi possível desabilitar a Play Store."
    fi
}

# ===========================================================================
# restore-stock — rollback completo para o ambiente stock
# ===========================================================================

restore_stock()
{
    check_root
    require_lock

    local wipe="${1:-}"

    say "===== RESTORE-STOCK ====="

    am force-stop "$GMS_PKG" 2>/dev/null || true
    am force-stop "$STORE_PKG" 2>/dev/null || true

    # 1) Remover APKs das máscaras
    rm -f "$MASK_GMS/$GMS_APK" "$MASK_STORE/$STORE_APK" || true

    # 2) Desmontar (apenas as nossas máscaras; mount estranho = abortar)
    #    O GMS em execução (system_server/PackageManager) mantém o path aberto:
    #    umount normal falha com EBUSY. Fallback seguro: umount -l (lazy), que
    #    remove o mount do namespace imediatamente — o processo antigo continua
    #    vendo o conteúdo até o soft reboot, que é o passo seguinte obrigatório.
    local failed=""
    if is_masked_by_us "$PROFILE_GMS" "$MASK_GMS" "$GMS_APK" || is_mounted "$PROFILE_GMS"; then
        [ "${DEGOOGLE_FAIL_UMOUNT:-0}" = "1" ] && failed="$failed GMS"
        if [ -z "$failed" ]; then
            if ! global umount "$PROFILE_GMS" 2>/dev/null; then
                say "  GMS ocupado; tentando umount -l (lazy)..."
                global umount -l "$PROFILE_GMS" 2>/dev/null || failed="$failed GMS"
            fi
            is_mounted "$PROFILE_GMS" && failed="$failed GMS(lazy não efetivou)"
        fi
    fi
    if is_masked_by_us "$PROFILE_GSF" "$MASK_GSF" "" || is_mounted "$PROFILE_GSF"; then
        if ! global umount "$PROFILE_GSF" 2>/dev/null; then
            global umount -l "$PROFILE_GSF" 2>/dev/null || failed="$failed GSF"
        fi
        is_mounted "$PROFILE_GSF" && failed="$failed GSF(lazy não efetivou)"
    fi
    if is_masked_by_us "$PROFILE_STORE" "$MASK_STORE" "$STORE_APK" || is_mounted "$PROFILE_STORE"; then
        if ! global umount "$PROFILE_STORE" 2>/dev/null; then
            global umount -l "$PROFILE_STORE" 2>/dev/null || failed="$failed Store"
        fi
        is_mounted "$PROFILE_STORE" && failed="$failed Store(lazy não efetivou)"
    fi

    if [ -n "$failed" ]; then
        fail 4 "Falha ao desmontar:$failed — aparelho em estado parcial. Execute de novo ou reinicie."
    fi

    # 3) Re-habilita a Play Store (o prepare a desabilitou) e o GMS base
    pm enable "$STORE_PKG" >/dev/null 2>&1 || true
    pm enable "$GMS_PKG" >/dev/null 2>&1 || true
    say "  Play Store e GMS re-habilitados (stock)."

    # 3b) Invalida o cache de parse do PackageManager. Sem isso, após o soft
    #     reboot o PM NÃO re-parseia system apps com codePath inalterado e
    #     continua reportando o microG mesmo com o stock físico de volta
    #     (registro stale — observado em campo). Backup antes, restauração
    #     manual possível; idempotente (sem cache = sem ação).
    if [ -d "$PM_CACHE_DIR" ] && [ -n "$(ls -A "$PM_CACHE_DIR" 2>/dev/null)" ]; then
        local cache_backup="$BACKUP_BASE/pm-cache-$(date +%s)"
        if mv "$PM_CACHE_DIR" "$cache_backup" 2>/dev/null; then
            say "  cache de parse do PM movido para $cache_backup (re-scan forçado)."
        else
            fail 4 "Não foi possível mover $PM_CACHE_DIR — estado parcial. Abortando (nada mais foi alterado)."
        fi
    else
        say "  cache de parse do PM ausente/vazio — nada a fazer."
    fi

    # 4) Dados órfãos do microG (com consentimento explícito do app)
    if [ "$wipe" = "--wipe-data" ]; then
        rm -rf "$GMS_DATA_USER0" "$GMS_DATA_USERDE"
        say "  dados do microG removidos de /data."
    else
        say "  dados do microG preservados em /data (use --wipe-data para remover)."
    fi

    say ""
    say "RESTORE-STOCK CONCLUÍDO. Soft reboot necessário para revelar o stock."
    say "O backup do microG em $BACKUP_BASE foi preservado."
    emit STATE "RESTORE_PREPARED"
    return 0
}

# ===========================================================================
# soft-reboot
# ===========================================================================

soft_reboot()
{
    check_root
    say "Solicitando soft reboot (framework)..."
    # Método 1: ksud soft-reboot (KernelSU) — o mecanismo do KernelSU Manager;
    # emula um reboot de sistema preservando o kernel (root via exploit) e os
    # mounts. É o método comprovado no fluxo do microg-session.sh.
    if [ -x /data/adb/ksud ]; then
        if /data/adb/ksud soft-reboot >/dev/null 2>&1; then
            say "  ksud soft-reboot aceito. O framework será reiniciado."
            return 0
        fi
        say "  ksud soft-reboot falhou; tentando sys.powerctl..."
    fi
    # Método 2: sys.powerctl (AOSP userspace reboot) — pode falhar por SELinux
    # no domínio do shell; vale a tentativa.
    if setprop sys.powerctl reboot,userspace 2>/dev/null; then
        say "  sys.powerctl=reboot,userspace aceito. O framework será reiniciado."
        return 0
    fi
    say "  sys.powerctl rejeitado."
    # IMPORTANTE: aparelhos com root via exploit NUNCA devem usar reboot
    # completo — o kernel reboot perde o root e o microG. Abortamos com
    # instrução segura em vez de sugerir reboot completo.
    fail 3 "Soft reboot não suportado aqui. Reinicie o framework pelo KernelSU Manager — NUNCA use reboot completo neste aparelho (perde root e microG)."
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
    test)
        self_test
        ;;
    *)
        fail_usage "probe|prepare <gms> <companion>|finalize|cleanup|backup|restore-backup|restore-stock [--wipe-data]|soft-reboot|status|test"
        ;;
esac
