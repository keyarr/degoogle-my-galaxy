#!/usr/bin/env sh
# =============================================================================
# Harness de teste do backend DeGoogle (roda em host, sem Android).
#
# Simula o ambiente Android:
#   - comandos falsos em PATH (pm, getprop, getenforce, nsenter, mount,
#     restorecon, ls -Zd, id)
#   - /proc/1/mountinfo simulado (DEGOOGLE_MOUNTINFO) que o mount/umount
#     falsos atualizam
#   - paths do device profile redirecionados para um diretório temporário
#     (DEGOOGLE_PROFILE_* / DEGOOGLE_MASK_BASE / ...)
#
# Cenários:
#   1. prepare feliz → PREPARED, 3 mounts, máscaras com APKs (perms 0644)
#   2. prepare idempotente (re-execução) → sem duplicar mounts
#   3. falha no mount GSF → rollback do GMS → exit 4, sem estado parcial
#   4. falha no mount GMS → exit 1 (nada montado), sem estado parcial
#   5. APK inválido → exit 3, nada alterado
#   6. pm path inesperado → exit 3, nada alterado
#   7. SELinux inválido → exit 4 + rollback
#   8. probe → STATE=STOCK com todos os pacotes stock presentes
#   9. backup/restauração no layout do MicroG Session
#  10. restore-stock move cache do PM
#  11. restore-stock usa lazy umount quando ocupado
#  12. mounts de fonte desconhecida (microg-mask) → prepare recusa e probe=ERROR
#  13. fonte relativa ao fs é reconhecida como nossa
#  14. dry-run/preflight não alteram mounts
#  15. preflight detecta mudança de hash desde o probe
#  16. opt-in usa targets descobertos em diretório permitido
#  17. rollback recusa mount externo
#  18. rollback com GSF/Store ausentes do PM usa targets seguros e reindex pendente
#  19. rollback prefere targets dinâmicos persistidos no snapshot
#  20. updates legítimos em /data/app não viram estado inesperado
#  21. FakeGApps/LSPosed detectados sem falso PASS funcional
#  22. cleanup-residue remove payloads conhecidos e preserva evidência estranha
#  23. restore-stock pre-reboot sem binder; enable no reindex pos-boot
#  24. registro stale do PM invalidado no reindex pos-boot, com backup
#   26. update em modelo desconhecido é preparável (cleanup no prepare)
#   27. prepare copia libmapbox-gl.so do APK para a máscara
# =============================================================================

set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/../degoogle.sh"

PASS=0
FAIL=0

ok() { PASS=$((PASS + 1)); echo "  OK: $*"; }
bad() { FAIL=$((FAIL + 1)); echo "  FAIL: $*" >&2; }

# ---------------------------------------------------------------------------
# setup de um cenário: raiz temporária + PATH falso
# ---------------------------------------------------------------------------
setup_root()
{
    export DEGOOGLE_TEST_MODE=1
    ROOT="$(mktemp -d)"
    SYSTEM="$ROOT/system"
    export DEGOOGLE_TEST_SYSTEM_ROOT="$ROOT"
    PROF_GMS="$SYSTEM/product/priv-app/GmsCore"
    PROF_GSF="$SYSTEM/system_ext/priv-app/GoogleServicesFramework"
    PROF_STORE="$SYSTEM/product/priv-app/Phonesky"
    MASK_BASE="$ROOT/mask"
    LEGACY_MASK_BASE="$ROOT/legacy-mask"
    BACKUP_BASE="$ROOT/backup"
    LOCK_DIR="$ROOT/lock"
    MOUNTINFO="$ROOT/mountinfo"
    FAKEBIN="$ROOT/bin"
    PM_SYSTEM="$ROOT/pm-system"
    PM_CACHE="$ROOT/pm-cache"
    DATA_USER0="$ROOT/data/user/0/com.google.android.gms"
    DATA_USERDE="$ROOT/data/user_de/0/com.google.android.gms"

    mkdir -p "$PROF_GMS" "$PROF_GSF" "$PROF_STORE" "$FAKEBIN" "$PM_SYSTEM" "$PM_CACHE" "$BACKUP_BASE"
    mkdir -p "$DATA_USER0" "$DATA_USERDE"
    printf 'registered-fcm-token\n' > "$DATA_USER0/registration.xml"
    printf 'device-lock\n' > "$DATA_USERDE/device.xml"
    : > "$MOUNTINFO"
    printf '0 0 0:0 / %s rw - ext4 /dev/root rw\n' "$ROOT" >> "$MOUNTINFO"
    touch "$PM_CACHE/package.odex"
    touch "$ROOT/gms.apk" "$ROOT/companion.apk"
    printf 'PK\003\004' | dd of="$ROOT/gms.apk" conv=notrunc 2>/dev/null
    printf 'PK\003\004' | dd of="$ROOT/companion.apk" conv=notrunc 2>/dev/null

    cat > "$FAKEBIN/id" <<'EOF'
#!/bin/sh
case "$1" in
    -u) echo "${FAKE_ROOT_UID:-0}" ;;
    *) echo "uid=${FAKE_ROOT_UID:-0}(root) gid=0(root) groups=0(root)" ;;
esac
EOF

    cat > "$FAKEBIN/getprop" <<'EOF'
#!/bin/sh
case "$1" in
    ro.product.manufacturer) echo "${FAKE_MANUFACTURER:-samsung}" ;;
    ro.product.model) echo "${FAKE_MODEL:-SM-S928B}" ;;
    ro.product.device) echo "e3q" ;;
    ro.product.name) echo "e3qxxx" ;;
    ro.build.version.sdk) echo "${FAKE_SDK:-36}" ;;
    ro.build.version.release) echo "${FAKE_RELEASE:-16}" ;;
    init.userspace_reboot.is_supported) echo "${FAKE_USERSPACE_REBOOT_SUPPORTED:-1}" ;;
    sys.boot_completed) echo "${FAKE_BOOT_COMPLETED:-}" ;;
    sys.boot.reason) echo "${FAKE_BOOT_REASON:-}" ;;
    ro.build.fingerprint) echo "samsung/e3qxxx/e3q:16/UP1A.231005.007/S928BXXU1AXK1:user/release-keys" ;;
    ro.product.cpu.abi) echo "arm64-v8a" ;;
    *) echo "" ;;
esac
EOF

    cat > "$FAKEBIN/getenforce" <<'EOF'
#!/bin/sh
echo "${FAKE_SELINUX_MODE:-Enforcing}"
EOF

    cat > "$FAKEBIN/setprop" <<'EOF'
#!/bin/sh
[ -n "${FAKE_SET_PROP_MARKER:-}" ] && echo "$*" > "$FAKE_SET_PROP_MARKER"
exit "${FAKE_SET_PROP_EXIT:-0}"
EOF

    cat > "$FAKEBIN/nsenter" <<'EOF'
#!/bin/sh
[ "${FAKE_NSENTER_FAIL:-0}" = "1" ] && exit 1
while [ "$#" -gt 0 ]; do
    case "$1" in
        --mount=*|--) shift ;;
        *) break ;;
    esac
done
exec "$@"
EOF

    # O container de CI pode bloquear a leitura de /proc/1/ns/mnt. No Android
    # real o readlink é permitido pelo backend root; aqui fixamos uma identidade
    # coerente para testar a comparação self/PID 1 sem depender do host.
    cat > "$FAKEBIN/readlink" <<'EOF'
#!/bin/sh
case "$1" in
    /proc/self/ns/mnt)
        [ "${FAKE_NS_NAMESPACE_MISMATCH:-0}" = "1" ] && echo "mnt:[4026534001]" || echo "mnt:[4026534000]" ;;
    /proc/1/ns/mnt) echo "mnt:[4026534000]" ;;
    *) exec /usr/bin/readlink "$@" ;;
esac
EOF

    cat > "$FAKEBIN/mount" <<'EOF'
#!/bin/sh
MI="${DEGOOGLE_MOUNTINFO:?}"
root_dir="$(dirname "$MI")"
case "$1" in
    --bind)
        src="$2"; tgt="$3"
        mkdir -p "$tgt"
        cp -r "$src/." "$tgt/" 2>/dev/null
        awk -v t="$tgt" '$5 != t' "$MI" > "$MI.tmp" && mv "$MI.tmp" "$MI"
        case "$src" in "$root_dir"/*) src_root="${src#"$root_dir"}" ;; *) src_root="$src" ;; esac
        printf '0 0 0:0 %s %s rw,relatime - ext4 /dev/root rw\n' "$src_root" "$tgt" >> "$MI"
        exit 0
        ;;
esac
exit 1
EOF

    cat > "$FAKEBIN/umount" <<'EOF'
#!/bin/sh
MI="${DEGOOGLE_MOUNTINFO:?}"
lazy=0
if [ "$1" = "-l" ]; then
    lazy=1; shift
fi
tgt="$1"
case "$tgt" in
    *degoogle-capability-test.*)
        [ "${FAKE_CAP_UMOUNT_FAIL:-0}" = "1" ] && exit 1 ;;
esac
# sem -l e FAKE_UMOUNT_BUSY=1: falha (simula path ocupado pelo system_server);
# com -l (lazy) sempre desmonta (o lazy umount remove do namespace mesmo com
# o processo usando o arquivo — comportamento real do kernel).
if [ -n "${FAKE_UMOUNT_BUSY:-}" ] && [ "$lazy" = "0" ]; then
    awk -v t="$tgt" '$5 == t' "$MI" 2>/dev/null | grep -q . && exit 1
fi
awk -v t="$tgt" '$5 != t' "$MI" > "$MI.tmp" && mv "$MI.tmp" "$MI"
find "$tgt" -mindepth 1 -delete 2>/dev/null
exit 0
EOF

    # chown/stat falsos: em host sem root, ownership não muda; simulamos root
    cat > "$FAKEBIN/chown" <<'EOF'
#!/bin/sh
exit 0
EOF

    cat > "$FAKEBIN/stat" <<'EOF'
#!/bin/sh
case "$1" in
    -c)
        case "$2" in
            %u:%g)
                if [ "${FAKE_STAT_BAD_OWNER_TARGET:-}" = "$3" ] || \
                    [ "${FAKE_STAT_BAD_MASK_OWNER_TARGET:-}" = "$3" ]; then
                    echo "1000:1000"
                else
                    echo "0:0"
                fi
                exit 0 ;;
            %a)
                if [ "${FAKE_STAT_BAD_MASK_MODE_TARGET:-}" = "$3" ] || \
                    [ "${FAKE_STAT_BAD_MODE_TARGET:-}" = "$3" ]; then
                    echo "700"
                elif [ -n "${FAKE_STAT_BAD_MODE_TARGET:-}" ]; then
                    case "$3" in */degoogle-selinux-test.*) echo "755"; exit 0 ;; esac
                    exec /usr/bin/stat -c %a "$3"
                else
                    exec /usr/bin/stat -c %a "$3"
                fi
                exit 0 ;;
            %u) echo "0"; exit 0 ;;
        esac
        ;;
esac
exec /usr/bin/stat "$@"
EOF

    cat > "$FAKEBIN/restorecon" <<'EOF'
#!/bin/sh
exit 0
EOF

    cat > "$FAKEBIN/chcon" <<'EOF'
#!/bin/sh
exit 0
EOF

    cat > "$FAKEBIN/ls" <<'EOF'
#!/bin/sh
case "$1" in
    -Zd)
        case "$2" in
            */mask/*|*/mask) is_mask=1 ;;
            *) is_mask=0 ;;
        esac
        if [ "${FAKE_SELINUX_BAD_TARGET:-}" = "$2" ] || \
            { [ "${FAKE_SELINUX_BAD:-0}" = "1" ] && [ "$is_mask" = "1" ]; }; then
            echo "unconfined_u:object_r:bad_context:s0 $2"
        else
            echo "u:object_r:system_file:s0 $2"
        fi
        exit 0
        ;;
esac
exec /usr/bin/ls "$@"
EOF

    cat > "$FAKEBIN/pm" <<'EOF'
#!/bin/sh
case "$1" in
    list)
        if [ "$2" = "packages" ] && [ "$3" = "-d" ] && [ "${FAKE_PM_GMS_DISABLED:-0}" = "1" ]; then
            echo "package:com.google.android.gms"
        elif [ "$2" = "packages" ] && [ "$3" = "com.google.android.gms" ]; then
            [ "${FAKE_PM_LIST_ERROR:-0}" = "1" ] && { echo "Package Manager unavailable" >&2; exit 1; }
            [ "${PM_GMS_MISSING:-0}" = "1" ] || echo "package:com.google.android.gms"
        elif [ "$2" = "packages" ] && [ "$3" = "com.google.android.gsf" ]; then
            [ "${FAKE_PM_LIST_ERROR:-0}" = "1" ] && { echo "Package Manager unavailable" >&2; exit 1; }
            [ "${PM_GSF_MISSING:-0}" = "1" ] || echo "package:com.google.android.gsf"
        elif [ "$2" = "packages" ] && [ "$3" = "com.android.vending" ]; then
            [ "${FAKE_PM_LIST_ERROR:-0}" = "1" ] && { echo "Package Manager unavailable" >&2; exit 1; }
            [ "${PM_STORE_MISSING:-0}" = "1" ] || echo "package:com.android.vending"
        elif [ "$2" = "packages" ] && [ "${FAKE_PM_LIST_ERROR:-0}" = "1" ]; then
            echo "Package Manager unavailable" >&2
            exit 1
        fi
        exit 0
        ;;
    path)
        case "$2" in
            com.google.android.gsf)
                [ "${PM_GSF_MISSING:-0}" = "1" ] && exit 1
                echo "package:${PM_GSF_PATH:-$DEGOOGLE_PROFILE_GSF/GoogleServicesFramework.apk}" ;;
            inc.whew.android.fakegapps)
                [ "${FAKEGAPPS_PRESENT:-0}" = "1" ] || exit 1
                echo "package:/data/app/fakegapps/base.apk" ;;
            com.android.vending)
                [ "${PM_STORE_MISSING:-0}" = "1" ] && exit 1
                echo "package:${PM_STORE_PATH:-$DEGOOGLE_PROFILE_STORE/Phonesky.apk}" ;;
            com.google.android.gms)
                [ "${PM_GMS_MISSING:-0}" = "1" ] && exit 1
                echo "package:${PM_GMS_PATH:-$DEGOOGLE_PROFILE_GMS/GmsCore.apk}" ;;
            *) exit 1 ;;
        esac
        ;;
    enable)
        if [ "$2" = "--user" ]; then
            shift 2
        fi
        if [ "$2" = "com.google.android.gms" ] && \
            [ "${PM_REQUIRE_GMS_DATA_CLEARED_BEFORE_ENABLE:-0}" = "1" ] && \
            { [ -e "$DEGOOGLE_GMS_DATA_USER0/registration.xml" ] || \
              [ -e "$DEGOOGLE_GMS_DATA_USERDE/device.xml" ]; }; then
            echo "GMS habilitado antes da limpeza dos dados" >&2
            exit 1
        fi
        case "$2" in
            com.google.android.gsf)
                [ "${PM_GSF_MISSING:-0}" = "1" ] && exit 1 ;;
            com.android.vending)
                [ "${PM_STORE_MISSING:-0}" = "1" ] && exit 1 ;;
        esac
        exit 0
        ;;
    uninstall|disable-user)
        if [ -n "${FAKE_PM_MUTATION_MARKER:-}" ]; then
            echo "$*" >> "$FAKE_PM_MUTATION_MARKER"
        fi
        exit 0
        ;;
    *) exit 0 ;;
esac
EOF

cat > "$FAKEBIN/dumpsys" <<'EOF'
#!/bin/sh
[ -n "${FAKE_DUMPSYS_MARKER:-}" ] && echo "$2" >> "$FAKE_DUMPSYS_MARKER"
if [ "$1" = "package" ]; then
    case "$2" in
        com.google.android.gms|com.android.vending)
            if [ "$2" = "com.google.android.gms" ] && [ -n "${FAKE_GMS_SYSTEM_CODE_PATH:-${FAKE_SYSTEM_CODE_PATH:-}}" ]; then
                echo "codePath=/data/app/update UPDATED_SYSTEM_APP systemCodePath=${FAKE_GMS_SYSTEM_CODE_PATH:-$FAKE_SYSTEM_CODE_PATH}"
            elif [ "$2" = "com.android.vending" ] && [ -n "${FAKE_STORE_SYSTEM_CODE_PATH:-${FAKE_SYSTEM_CODE_PATH:-}}" ]; then
                echo "codePath=/data/app/update UPDATED_SYSTEM_APP systemCodePath=${FAKE_STORE_SYSTEM_CODE_PATH:-$FAKE_SYSTEM_CODE_PATH}"
            elif [ -n "${FAKE_DATA_CODE_PATH:-}" ]; then
                echo "codePath=/data/app/update"
            fi
            if [ "$2" = "com.google.android.gms" ] && [ "${FAKE_GMS_UPDATED_SYSTEM_APP:-0}" = "1" ]; then
                echo "codePath=/data/app/update UPDATED_SYSTEM_APP"
            fi
            if [ "$2" = "com.google.android.gms" ] && [ "${FAKE_GMS_PRIVILEGED:-0}" = "1" ]; then
                echo "  pkgFlags=[ PRIVILEGED SYSTEM ]"
            fi
            if [ "$2" = "com.google.android.gms" ]; then
                echo "  versionCode=23484 minSdk=23"
                echo "  versionName=0.3.4.240913"
                echo "    userId=10123"
            fi
            exit 0
            ;;
    esac
fi
echo "  versionCode=23484 minSdk=23"
echo "  versionName=0.3.4.240913"
echo "  pkgFlags=[ PRIVILEGED SYSTEM ]"
echo "    userId=10123"
echo "  User 0: enabled=0"
exit 0
EOF

    cat > "$FAKEBIN/cmd" <<'EOF'
#!/bin/sh
exit 0
EOF

    cat > "$FAKEBIN/am" <<'EOF'
#!/bin/sh
exit 0
EOF

    cat > "$FAKEBIN/df" <<'EOF'
#!/bin/sh
if [ -n "${FAKE_DF_KB:-}" ]; then
    echo "Filesystem 1024-blocks Used Available Capacity Mounted on"
    echo "/dev/root 100000 1 $FAKE_DF_KB 1% /"
else
    exec /usr/bin/df "$@"
fi
EOF

cat > "$FAKEBIN/pidof" <<'EOF'
#!/bin/sh
echo "${DEGOOGLE_TEST_SYSTEM_SERVER_PID:-100}"
EOF

    chmod +x "$FAKEBIN"/*
}

teardown_root()
{
    rm -rf "$ROOT"
}

run_script()
{
    # run_script <expected_exit> <desc> <args...>
    local want="$1"; shift
    local desc="$1"; shift
    local out rc
    if [ "${1:-}" = "prepare" ] && [ "${2:-}" != "--mode" ] && [ "$#" = "3" ]; then
        local gms_sha256 companion_sha256
        gms_sha256="$(sha256sum "$2" | awk '{print $1}')"
        companion_sha256="$(sha256sum "$3" | awk '{print $1}')"
        set -- "$1" "$2" "$3" "$gms_sha256" "$companion_sha256"
    fi
    out="$(PATH="$FAKEBIN:/usr/bin:/bin" \
        DEGOOGLE_PROFILE_GMS="$PROF_GMS" \
        DEGOOGLE_PROFILE_GSF="$PROF_GSF" \
        DEGOOGLE_PROFILE_STORE="$PROF_STORE" \
        DEGOOGLE_MASK_BASE="$MASK_BASE" \
        DEGOOGLE_LEGACY_MASK_BASE="$LEGACY_MASK_BASE" \
        DEGOOGLE_BACKUP_BASE="$BACKUP_BASE" \
        DEGOOGLE_GMS_DATA_USER0="$DATA_USER0" \
        DEGOOGLE_GMS_DATA_USERDE="$DATA_USERDE" \
        DEGOOGLE_LOCK_DIR="$LOCK_DIR" \
        DEGOOGLE_MOUNTINFO="$MOUNTINFO" \
        DEGOOGLE_PM_SYSTEM_DIR="$PM_SYSTEM" \
        DEGOOGLE_PM_CACHE="$PM_CACHE" \
        FAKE_MANUFACTURER="${FAKE_MANUFACTURER:-}" \
        FAKE_MODEL="${FAKE_MODEL:-}" \
        FAKE_DUMPSYS_MARKER="${FAKE_DUMPSYS_MARKER:-}" \
        DEGOOGLE_PROGRESS="${DEGOOGLE_PROGRESS:-}" \
        DEGOOGLE_REBOOT_SOURCE="${DEGOOGLE_REBOOT_SOURCE:-}" \
        DEGOOGLE_MODE="${DEGOOGLE_MODE:-}" \
        DEGOOGLE_REBOOT_COOLDOWN_SECONDS="${DEGOOGLE_REBOOT_COOLDOWN_SECONDS:-}" \
        DEGOOGLE_KSUD_PATH="${DEGOOGLE_KSUD_PATH:-}" \
        PM_GMS_PATH="${PM_GMS_PATH:-}" \
        PM_GSF_PATH="${PM_GSF_PATH:-}" \
        PM_STORE_PATH="${PM_STORE_PATH:-}" \
        FAKE_USERSPACE_REBOOT_SUPPORTED="${FAKE_USERSPACE_REBOOT_SUPPORTED:-}" \
        FAKE_SYSTEM_CODE_PATH="${FAKE_SYSTEM_CODE_PATH:-}" \
        FAKE_DATA_CODE_PATH="${FAKE_DATA_CODE_PATH:-}" \
        PM_GSF_MISSING="${PM_GSF_MISSING:-}" \
        PM_GMS_MISSING="${PM_GMS_MISSING:-}" \
        PM_STORE_MISSING="${PM_STORE_MISSING:-}" \
        FAKE_GMS_UPDATED_SYSTEM_APP="${FAKE_GMS_UPDATED_SYSTEM_APP:-}" \
        FAKE_GMS_PRIVILEGED="${FAKE_GMS_PRIVILEGED:-}" \
        FAKE_PM_LIST_ERROR="${FAKE_PM_LIST_ERROR:-}" \
        FAKE_BOOT_COMPLETED="${FAKE_BOOT_COMPLETED:-}" \
        FAKE_BOOT_REASON="${FAKE_BOOT_REASON:-}" \
        DEGOOGLE_TEST_BOOT_ID="${DEGOOGLE_TEST_BOOT_ID:-}" \
        DEGOOGLE_TEST_SYSTEM_SERVER_PID="${DEGOOGLE_TEST_SYSTEM_SERVER_PID:-}" \
        DEGOOGLE_TEST_SYSTEM_SERVER_START="${DEGOOGLE_TEST_SYSTEM_SERVER_START:-}" \
        FAKE_SELINUX_MODE="${FAKE_SELINUX_MODE:-}" \
        FAKE_PM_GMS_DISABLED="${FAKE_PM_GMS_DISABLED:-}" \
        PM_REQUIRE_GMS_DATA_CLEARED_BEFORE_ENABLE="${PM_REQUIRE_GMS_DATA_CLEARED_BEFORE_ENABLE:-}" \
        FAKE_PM_MUTATION_MARKER="${FAKE_PM_MUTATION_MARKER:-}" \
        FAKEGAPPS_PRESENT="${FAKEGAPPS_PRESENT:-}" \
        FAKE_SET_PROP_EXIT="${FAKE_SET_PROP_EXIT:-1}" \
        FAKE_SET_PROP_MARKER="${FAKE_SET_PROP_MARKER:-}" \
        DEGOOGLE_ALLOW_ZYGOTE_RESTART="${DEGOOGLE_ALLOW_ZYGOTE_RESTART:-}" \
        DEGOOGLE_ALLOW_FALLBACK_REBOOT="${DEGOOGLE_ALLOW_FALLBACK_REBOOT:-}" \
        DEGOOGLE_LSPOSED_MARKER="${DEGOOGLE_LSPOSED_MARKER:-}" \
        sh "$SCRIPT" "$@" 2>&1)"
    rc=$?
    if [ "$rc" = "$want" ]; then
        ok "$desc (exit $rc)"
        echo "$out"
    else
        bad "$desc (esperado exit $want, obteve $rc)"
        echo "$out"
    fi
    return "$rc"
}

mount_count()
{
    awk -v root="$(dirname "$MOUNTINFO")" '$5 != root { count++ } END { print count+0 }' "$MOUNTINFO" 2>/dev/null
}

# ---------------------------------------------------------------------------
echo "== cenário 1: prepare feliz"
setup_root
run_script 0 "prepare com APKs válidos" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" || true
[ "$(mount_count)" = "3" ] && ok "3 mounts registrados" || bad "esperava 3 mounts, há $(mount_count)"
[ -f "$MASK_BASE/gms/GmsCore.apk" ] && ok "máscara GMS com APK" || bad "máscara GMS sem APK"
[ -f "$MASK_BASE/store/Companion.apk" ] && ok "máscara store com APK" || bad "máscara store sem APK"
[ "$(stat -c %a "$MASK_BASE/gms/GmsCore.apk")" = "644" ] && \
    [ "$(stat -c %a "$MASK_BASE/store/Companion.apk")" = "644" ] && \
    ok "payloads mantêm mode 0644" || bad "mode dos APKs na máscara divergiu de 0644"
[ -d "$MASK_BASE/gsf" ] && ok "máscara GSF (vazia) criada" || bad "máscara GSF ausente"
[ -f "$PROF_GMS/GmsCore.apk" ] && ok "GMS visível via mount fake" || bad "GMS não visível via mount fake"
[ -f "$BACKUP_BASE/transaction/snapshot.json" ] && ok "snapshot persistido antes da máscara" || bad "snapshot ausente"
grep -q 'selinuxContext' "$BACKUP_BASE/transaction/snapshot.json" && ok "snapshot contém metadados SELinux" || bad "snapshot sem metadados SELinux"
teardown_root

echo "== cenário 2: idempotência (re-execução do prepare)"
setup_root
run_script 0 "prepare #1" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
run_script 0 "prepare #2 (já mascarado)" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
[ "$(mount_count)" = "3" ] && ok "mounts não duplicados após re-execução" || bad "esperava 3 mounts, há $(mount_count)"
teardown_root

echo "== cenário 2b: pure mode usa três máscaras vazias e rejeita troca de modo"
setup_root
mkdir -p "$BACKUP_BASE/gms-user0"
printf 'preserve-token-backup\n' > "$BACKUP_BASE/gms-user0/registration.xml"
DEGOOGLE_MODE=PURE run_script 0 "pure prepare sem APKs" prepare --mode PURE >/dev/null || true
[ "$(mount_count)" = "3" ] && ok "pure mode montou os três targets" || bad "pure mode deixou $(mount_count) mounts"
[ -z "$(find "$MASK_BASE/gms" "$MASK_BASE/gsf" "$MASK_BASE/store" -mindepth 1 -print -quit 2>/dev/null)" ] && \
    ok "as três máscaras pure estão vazias" || bad "pure mode deixou payload em uma máscara"
grep -q '"operationMode": "PURE"' "$BACKUP_BASE/transaction/snapshot.json" && \
    ok "snapshot registra PURE" || bad "snapshot sem modo PURE"
[ -f "$BACKUP_BASE/gms-user0/registration.xml" ] && ok "backup microG preexistente preservado" || bad "backup microG foi alterado"
DEGOOGLE_MODE=PURE run_script 0 "pure prepare repetido não duplica mounts" prepare --mode PURE >/dev/null || true
[ "$(mount_count)" = "3" ] && ok "repetição manteve exatamente três mounts" || bad "repetição duplicou ou removeu mounts"
DEGOOGLE_MODE=MICROG run_script 3 "troca para microG sem restaurar stock é recusada" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
[ "$(mount_count)" = "3" ] && [ -f "$BACKUP_BASE/gms-user0/registration.xml" ] && \
    ok "modo e backup permaneceram intactos após troca recusada" || bad "troca recusada alterou estado"
run_script 3 "backup de tokens bloqueado durante pure mode" backup >/dev/null || true
run_script 3 "restauração de tokens bloqueada durante pure mode" restore-backup >/dev/null || true
teardown_root

echo "== cenário 2c: pure mode rejeita payload inesperado sem apagá-lo"
setup_root
mkdir -p "$MASK_BASE/gms"
printf 'unexpected\n' > "$MASK_BASE/gms/user-payload"
DEGOOGLE_MODE=PURE run_script 3 "pure prepare recusa payload preexistente" prepare --mode PURE >/dev/null || true
[ -f "$MASK_BASE/gms/user-payload" ] && [ "$(mount_count)" = "0" ] && \
    ok "payload inesperado preservado e nenhuma máscara montada" || bad "payload foi apagado ou houve mount"
teardown_root

echo "== cenário 2d: pure mode valida restart, ausência de pacotes e restauração"
setup_root
mkdir -p "$BACKUP_BASE/gms-user0"
printf 'preserve-token-backup\n' > "$BACKUP_BASE/gms-user0/registration.xml"
DEGOOGLE_MODE=PURE run_script 0 "pure prepare" prepare --mode PURE >/dev/null || true
FAKE_SET_PROP_EXIT=0 run_script 0 "userspace restart request aceito" soft-reboot >/dev/null || true
PM_GMS_MISSING=1 PM_GSF_MISSING=1 PM_STORE_MISSING=1 FAKE_PM_LIST_ERROR=1 \
    DEGOOGLE_TEST_SYSTEM_SERVER_PID=101 FAKE_BOOT_COMPLETED=1 \
    run_script 4 "pure postboot rejects an unavailable Package Manager" post-boot-validate > "$ROOT/pure-pm-error.out" || true
grep -q 'Package Manager could not confirm absence' "$ROOT/pure-pm-error.out" && \
    ok "falha de consulta do PM não confirmou pacote ausente" || bad "falha do PM foi tratada como ausência"
PM_GMS_MISSING=1 PM_GSF_MISSING=1 PM_STORE_MISSING=1 \
    DEGOOGLE_TEST_SYSTEM_SERVER_PID=101 FAKE_BOOT_COMPLETED=1 \
    run_script 0 "pure pós-restart valida ausência dos três pacotes" post-boot-validate >/dev/null || true
PM_GMS_MISSING=1 PM_GSF_MISSING=1 PM_STORE_MISSING=1 \
    DEGOOGLE_TEST_SYSTEM_SERVER_PID=101 FAKE_BOOT_COMPLETED=1 \
    run_script 0 "probe reconhece pure ativo validado" probe > "$ROOT/pure-probe.out" || true
grep -q '^DEGOOGLE_STATE=DEGOOGLE_ACTIVE$' "$ROOT/pure-probe.out" && \
    grep -q '^DEGOOGLE_OPERATION_MODE=PURE$' "$ROOT/pure-probe.out" && \
    grep -q '^DEGOOGLE_PURE_MASKS_VALID=1$' "$ROOT/pure-probe.out" && \
    grep -q '^DEGOOGLE_POST_BOOT_STATUS=PASS$' "$ROOT/pure-probe.out" && \
    ok "probe só confirma pure ativo com evidência completa" || bad "pure ativo sem as evidências esperadas"
run_script 0 "restauração stock desmonta pure" restore-stock --wipe-data >/dev/null || true
[ "$(mount_count)" = "0" ] && [ -f "$BACKUP_BASE/gms-user0/registration.xml" ] && \
    ok "restore-stock remove mounts e preserva backup" || bad "restauração perdeu mount ou backup"
FAKE_BOOT_COMPLETED=1 run_script 3 "reindex bloqueia antes do restart do framework" reindex-stock > "$ROOT/early_reindex.out" || true
grep -q 'verified userspace restart after restore-stock is required' "$ROOT/early_reindex.out" && \
    ok "reindex pre-restart foi bloqueado" || bad "reindex pre-restart não foi bloqueado"
FAKE_BOOT_COMPLETED=1 DEGOOGLE_TEST_SYSTEM_SERVER_PID=101 \
    run_script 0 "probe preserva rollback pendente após restart" probe > "$ROOT/restore_probe.out" || true
grep -q '^DEGOOGLE_STATE=RESTORE_PREPARED$' "$ROOT/restore_probe.out" && \
    grep -q '^DEGOOGLE_STOCK_REINDEX_PENDING=1$' "$ROOT/restore_probe.out" && \
    grep -q '^DEGOOGLE_STOCK_REINDEX_READY=1$' "$ROOT/restore_probe.out" && \
    ok "probe mantém reindex pendente até confirmação" || bad "probe perdeu o estado de reindex pendente"
FAKE_BOOT_COMPLETED=1 DEGOOGLE_TEST_SYSTEM_SERVER_PID=101 \
    run_script 0 "reindex confirma stock restaurado" reindex-stock >/dev/null || true
grep -q '^state=STOCK_CONFIRMED$' "$BACKUP_BASE/transaction/journal" && \
    ok "stock confirmado depois do reindex" || bad "stock não foi confirmado"
FAKE_BOOT_COMPLETED=1 DEGOOGLE_TEST_SYSTEM_SERVER_PID=101 \
    run_script 0 "probe expõe stock confirmado" probe > "$ROOT/confirmed_stock_probe.out" || true
grep -q '^DEGOOGLE_STOCK_CONFIRMED=1$' "$ROOT/confirmed_stock_probe.out" && \
    ok "probe permite reconciliar restauração após recriação do app" || bad "probe não expôs confirmação de stock"
DEGOOGLE_MODE=MICROG run_script 0 "microG permitido após stock confirmado" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
grep -q '"operationMode": "MICROG"' "$BACKUP_BASE/transaction/snapshot.json" && \
    ok "novo snapshot só troca para microG depois do stock" || bad "modo não mudou após restauração confirmada"
teardown_root

echo "== cenário 3: falha no mount GSF → rollback do GMS"
setup_root
DEGOOGLE_FAIL_MOUNT_GSF=1 run_script 4 "prepare com falha GSF" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" || true
[ "$(mount_count)" = "0" ] && ok "nenhum mount remanescente (rollback ok)" || bad "sobraram mounts: $(mount_count)"
[ ! -f "$MASK_BASE/gms/GmsCore.apk" ] && ok "APK GMS removido da máscara" || bad "APK GMS permanece na máscara"
teardown_root

echo "== cenário 4: falha no mount GMS"
setup_root
DEGOOGLE_FAIL_MOUNT_GMS=1 run_script 1 "prepare com falha GMS" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" || true
[ "$(mount_count)" = "0" ] && ok "nenhum mount remanescente" || bad "sobraram mounts: $(mount_count)"
teardown_root

echo "== cenário 5: APK inválido"
setup_root
printf 'notanapk' > "$ROOT/gms.apk"
run_script 3 "prepare com APK inválido" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" || true
[ "$(mount_count)" = "0" ] && ok "nada foi montado" || bad "mounts indevidos: $(mount_count)"
teardown_root

echo "== cenário 36: prepare bloqueia APK com hash inesperado"
setup_root
companion_sha256="$(sha256sum "$ROOT/companion.apk" | awk '{print $1}')"
wrong_sha256="$(printf '%064d' 0)"
run_script 3 "prepare rejeita SHA-256 inesperado" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" "$wrong_sha256" "$companion_sha256" >/dev/null || true
[ "$(mount_count)" = "0" ] && ok "hash inesperado não causou mount" || bad "hash inesperado foi mascarado"
teardown_root

echo "== cenário 37: root e namespace são requisitos operacionais"
setup_root
FAKE_ROOT_UID=2000 run_script 3 "preflight exige UID 0" preflight >/dev/null || true
[ "$(mount_count)" = "0" ] && ok "UID sem root não causou mount" || bad "UID sem root alterou mounts"
FAKE_NSENTER_FAIL=1 run_script 3 "preflight bloqueia nsenter inacessível" preflight >/dev/null || true
[ "$(mount_count)" = "0" ] && ok "namespace inacessível não causou mount" || bad "namespace inacessível alterou mounts"
FAKE_NS_NAMESPACE_MISMATCH=1 run_script 3 "preflight bloqueia namespace diferente do PID 1" preflight >/dev/null || true
[ "$(mount_count)" = "0" ] && ok "namespace divergente não causou mount" || bad "namespace divergente alterou mounts"
teardown_root

echo "== cenário 38: preflight preserva evidência de bind não removido"
setup_root
FAKE_CAP_UMOUNT_FAIL=1 run_script 3 "preflight falha se cleanup do bind falhar" preflight >/dev/null || true
grep -q 'degoogle-capability-test' "$MOUNTINFO" && ok "mount temporário permanece como evidência" || bad "mount temporário não foi preservado"
[ "$(mount_count)" = "1" ] && ok "somente mount de capability permaneceu" || bad "mounts inesperados após falha de cleanup: $(mount_count)"
teardown_root

echo "== scenario 39: preflight accepts SELinux Permissive without changing policy"
setup_root
FAKE_SELINUX_MODE=Permissive run_script 0 "preflight accepts permissive SELinux" preflight >/dev/null || true
[ "$(mount_count)" = "0" ] && ok "Permissive SELinux did not cause a mount" || bad "Permissive SELinux changed mounts"
for target in "$PROF_GMS" "$PROF_GSF" "$PROF_STORE"; do
    FAKE_STAT_BAD_OWNER_TARGET="$target" run_script 3 "preflight bloqueia owner não reproduzível em $target" preflight >/dev/null || true
    [ "$(mount_count)" = "0" ] && ok "owner inválido em $target não causou mount" || bad "owner inválido em $target alterou mounts"
    FAKE_STAT_BAD_MODE_TARGET="$target" run_script 3 "preflight bloqueia mode não reproduzível em $target" preflight >/dev/null || true
    [ "$(mount_count)" = "0" ] && ok "mode inválido em $target não causou mount" || bad "mode inválido em $target alterou mounts"
    FAKE_SELINUX_BAD_TARGET="$target" run_script 3 "preflight bloqueia contexto não reproduzível em $target" preflight >/dev/null || true
    [ "$(mount_count)" = "0" ] && ok "contexto inválido em $target não causou mount" || bad "contexto inválido em $target alterou mounts"
done
teardown_root

echo "== cenário 40: preflight exige armazenamento livre e persistência gravável"
setup_root
FAKE_DF_KB=1000 run_script 3 "preflight bloqueia espaço insuficiente" preflight >/dev/null || true
[ "$(mount_count)" = "0" ] && ok "espaço insuficiente não causou mount" || bad "espaço insuficiente alterou mounts"
mkdir -p "$BACKUP_BASE/transaction"
chmod 555 "$BACKUP_BASE/transaction"
run_script 3 "preflight bloqueia transaction directory não gravável" preflight >/dev/null || true
[ "$(mount_count)" = "0" ] && ok "persistência indisponível não causou mount" || bad "falha de persistência alterou mounts"
chmod 755 "$BACKUP_BASE/transaction"
teardown_root

echo "== cenário 41: restore-stock bloqueia cache inacessível antes do unmount"
setup_root
run_script 0 "prepare antes de bloquear acesso ao cache" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
chmod 555 "$PM_CACHE"
run_script 3 "restore-stock bloqueia cache inacessível" restore-stock >/dev/null || true
[ "$(mount_count)" = "3" ] && [ -f "$BACKUP_BASE/transaction/snapshot.json" ] && \
    ok "cache inacessível preservou mounts e snapshot" || bad "cache inacessível alterou estado recuperável"
chmod 755 "$PM_CACHE"
teardown_root

echo "== cenário 44: alvos ambíguos ou inseguros bloqueiam antes do cleanup"
setup_root
pm_mutations="$ROOT/pm-mutations"
PM_GMS_PATH="/data/app/~~gms/com.google.android.gms-1/base.apk" \
FAKE_GMS_UPDATED_SYSTEM_APP=1 FAKE_PM_MUTATION_MARKER="$pm_mutations" \
    run_script 3 "prepare bloqueia update sem target stock" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
[ ! -e "$pm_mutations" ] && [ "$(mount_count)" = "0" ] && \
    ok "update sem target preservou PackageManager e mounts" || bad "update sem target alcançou cleanup"
PM_GMS_PATH="/data/app/~~gms/com.google.android.gms-1/base.apk" \
FAKE_DATA_CODE_PATH=1 FAKE_PM_MUTATION_MARKER="$pm_mutations" \
    run_script 3 "prepare bloqueia instalação data-only" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
[ ! -e "$pm_mutations" ] && [ "$(mount_count)" = "0" ] && \
    ok "instalação data-only preservou PackageManager e mounts" || bad "instalação data-only alcançou cleanup"

PM_GMS_PATH="$SYSTEM/product/priv-app/GmsCore.apk" \
    run_script 3 "preflight recusa diretório pai compartilhado" preflight >/dev/null || true
[ "$(mount_count)" = "0" ] && ok "diretório pai compartilhado não causou mount" || bad "diretório compartilhado alterou mounts"

mkdir -p "$ROOT/outside/GmsCore"
touch "$ROOT/outside/GmsCore/base.apk"
ln -s "$ROOT/outside/GmsCore" "$SYSTEM/product/priv-app/GmsCoreLink"
PM_GMS_PATH="$SYSTEM/product/priv-app/GmsCoreLink/base.apk" \
    run_script 3 "preflight recusa symlink que escapa da partição" preflight >/dev/null || true
[ "$(mount_count)" = "0" ] && ok "symlink de escape não causou mount" || bad "symlink de escape alterou mounts"

PM_STORE_PATH="$PROF_GMS/Phonesky.apk" \
    run_script 3 "preflight recusa targets sobrepostos" preflight >/dev/null || true
[ "$(mount_count)" = "0" ] && ok "targets sobrepostos não causaram mount" || bad "targets sobrepostos alteraram mounts"
teardown_root

echo "== cenário 45: prepare confirma metadados reais das máscaras"
for component in gms gsf store; do
    setup_root
    FAKE_STAT_BAD_MASK_OWNER_TARGET="$MASK_BASE/$component" \
        run_script 4 "prepare bloqueia owner divergente na máscara $component" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
    [ "$(mount_count)" = "0" ] && grep -q '^state=ROLLBACK_REQUIRED$' "$BACKUP_BASE/transaction/journal" && \
        ok "owner da máscara $component preservou recuperação" || bad "owner da máscara $component não foi validado"
    teardown_root

    setup_root
    FAKE_STAT_BAD_MASK_MODE_TARGET="$MASK_BASE/$component" \
        run_script 4 "prepare bloqueia mode divergente na máscara $component" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
    [ "$(mount_count)" = "0" ] && grep -q '^state=ROLLBACK_REQUIRED$' "$BACKUP_BASE/transaction/journal" && \
        ok "mode da máscara $component preservou recuperação" || bad "mode da máscara $component não foi validado"
    teardown_root
done

echo "== cenário 46: recovery legacy verificado e unmount falho"
setup_root
mkdir -p "$LEGACY_MASK_BASE/gms" "$LEGACY_MASK_BASE/gsf" "$LEGACY_MASK_BASE/store"
cp "$ROOT/gms.apk" "$LEGACY_MASK_BASE/gms/GmsCore.apk"
cp "$ROOT/companion.apk" "$LEGACY_MASK_BASE/store/FakeStore.apk"
printf '0 0 0:0 /legacy-mask/gms %s rw - ext4 /dev/root rw\n' "$PROF_GMS" >> "$MOUNTINFO"
printf '0 0 0:0 /legacy-mask/gsf %s rw - ext4 /dev/root rw\n' "$PROF_GSF" >> "$MOUNTINFO"
printf '0 0 0:0 /legacy-mask/store %s rw - ext4 /dev/root rw\n' "$PROF_STORE" >> "$MOUNTINFO"
run_script 0 "restore-stock aceita somente mount legacy verificado" restore-stock >/dev/null || true
[ "$(mount_count)" = "0" ] && \
    [ ! -e "$LEGACY_MASK_BASE/gms/GmsCore.apk" ] && \
    [ ! -e "$LEGACY_MASK_BASE/store/FakeStore.apk" ] && \
    grep -q '^state=REINDEX_PENDING$' "$BACKUP_BASE/transaction/journal" && \
    ok "legacy desmontado e limpo com recovery pendente registrado" || bad "legacy verificado não foi recuperado"
FAKE_BOOT_COMPLETED=1 DEGOOGLE_TEST_SYSTEM_SERVER_PID=101 \
    run_script 0 "probe reconhece reindex legacy após restart" probe > "$ROOT/legacy_restore_probe.out" || true
grep -q '^DEGOOGLE_STOCK_REINDEX_PENDING=1$' "$ROOT/legacy_restore_probe.out" && \
    grep -q '^DEGOOGLE_STOCK_REINDEX_READY=1$' "$ROOT/legacy_restore_probe.out" && \
    ok "restore legacy pode seguir para o reindex pos-restart" || bad "reindex legacy não ficou disponível"
FAKE_BOOT_COMPLETED=1 DEGOOGLE_TEST_SYSTEM_SERVER_PID=101 \
    run_script 0 "reindex finaliza restore legacy sem snapshot" reindex-stock > "$ROOT/legacy_reindex.out" || true
grep -q '^state=STOCK_CONFIRMED$' "$BACKUP_BASE/transaction/journal" && \
    ok "restore legacy foi confirmado sem snapshot" || { bad "restore legacy não foi confirmado"; cat "$ROOT/legacy_reindex.out"; }
teardown_root

setup_root
run_script 0 "prepare antes de injetar unmount falho" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
DEGOOGLE_FAIL_UMOUNT=1 run_script 4 "restore-stock preserva GMS após unmount falho" restore-stock >/dev/null || true
[ "$(mount_count)" = "1" ] && grep -q "$PROF_GMS" "$MOUNTINFO" && \
    [ -f "$MASK_BASE/gms/GmsCore.apk" ] && \
    grep -q '^state=ROLLBACK_REQUIRED$' "$BACKUP_BASE/transaction/journal" && \
    ok "mount, payload e journal preservados após falha" || bad "unmount falho perdeu evidência de recovery"
teardown_root

echo "== cenário 6: pm path inesperado"
setup_root
PM_GMS_PATH="/system/app/GmsCore/GmsCore.apk" run_script 3 "prepare com GMS fora do perfil" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" || true
[ "$(mount_count)" = "0" ] && ok "nada foi montado" || bad "mounts indevidos: $(mount_count)"
teardown_root

echo "== cenário 7: SELinux inválido → rollback"
setup_root
FAKE_SELINUX_BAD=1 run_script 4 "prepare com contexto SELinux inesperado" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" || true
[ "$(mount_count)" = "0" ] && ok "rollback completo (sem mounts)" || bad "sobraram mounts: $(mount_count)"
teardown_root

echo "== cenário 8: probe → STOCK"
setup_root
run_script 0 "probe (stock)" probe >/dev/null || true
out="$(PATH="$FAKEBIN:/usr/bin:/bin" \
    DEGOOGLE_PROFILE_GMS="$PROF_GMS" \
    DEGOOGLE_PROFILE_GSF="$PROF_GSF" \
    DEGOOGLE_PROFILE_STORE="$PROF_STORE" \
    DEGOOGLE_MASK_BASE="$MASK_BASE" \
    DEGOOGLE_BACKUP_BASE="$BACKUP_BASE" \
    DEGOOGLE_LOCK_DIR="$LOCK_DIR" \
    DEGOOGLE_MOUNTINFO="$MOUNTINFO" \
    sh "$SCRIPT" probe 2>/dev/null)"
echo "$out" | grep -q "DEGOOGLE_STATE=STOCK" && ok "STATE=STOCK detectado" || bad "STATE!=STOCK: $(echo "$out" | grep DEGOOGLE_STATE)"
echo "$out" | grep -q "DEGOOGLE_PROFILE_MATCH=1" && ok "PROFILE_MATCH=1" || bad "PROFILE_MATCH!=1"
echo "$out" | grep -q "DEGOOGLE_ROOT_OK=1" && ok "ROOT_OK=1" || bad "ROOT_OK!=1"
teardown_root

echo "== cenário 8b: probe reaproveita detalhes do GMS e emite progresso"
setup_root
FAKE_DUMPSYS_MARKER="$ROOT/dumpsys.log" run_script 0 "probe com progresso" probe > "$ROOT/probe.out" 2>&1 || true
[ "$(grep -c '^com.google.android.gms$' "$ROOT/dumpsys.log")" = "1" ] && \
    ok "GMS consultado uma vez por probe" || bad "dumpsys do GMS foi repetido"
grep -Fxq 'DEGOOGLE_GMS_VERSION=0.3.4.240913' "$ROOT/probe.out" && \
    grep -Fxq 'DEGOOGLE_GMS_VERSION_CODE=23484' "$ROOT/probe.out" && \
    ok "detalhes GMS vieram do dump reutilizado" || bad "detalhes GMS ausentes"
FAKE_MODEL="$(printf 'SM-S928B\nextra')" DEGOOGLE_PROGRESS=1 \
    run_script 0 "probe sanitiza newline e emite eventos" probe > "$ROOT/progress.out" 2>&1 || true
grep -Fxq 'DEGOOGLE_MODEL=SM-S928B extra' "$ROOT/progress.out" && \
    ok "newline em fact continua sanitizado no protocolo" || bad "newline alterou o protocolo de facts"
grep -q '^DEGOOGLE_PROGRESS=device_properties:CONFIRMED$' "$ROOT/progress.out" && \
    grep -q '^DEGOOGLE_PROGRESS=transaction_masks:CONFIRMED$' "$ROOT/progress.out" && \
    ok "progresso confirma verificações concluídas" || bad "faltou evento de progresso"
teardown_root

echo "== cenário 9: backup/restauração no formato do MicroG Session"
setup_root
run_script 0 "backup copia user0 e user_de" backup >/dev/null || true
[ -f "$BACKUP_BASE/gms-user0/registration.xml" ] && ok "backup user0 no diretório original" || bad "backup user0 ausente"
[ -f "$BACKUP_BASE/gms-userde/device.xml" ] && ok "backup user_de no diretório original" || bad "backup user_de ausente"
printf 'dados-novos\n' > "$DATA_USER0/registration.xml"
printf 'dados-novos\n' > "$DATA_USERDE/device.xml"
run_script 0 "restore-backup restaura os diretórios" restore-backup >/dev/null || true
grep -q '^registered-fcm-token$' "$DATA_USER0/registration.xml" && ok "user0 restaurado" || bad "user0 não restaurado"
grep -q '^device-lock$' "$DATA_USERDE/device.xml" && ok "user_de restaurado" || bad "user_de não restaurado"
[ ! -e "$ROOT/data/user/0/gms-user0" ] && ok "não criou diretório com nome do backup" || bad "criou /data/user/0/gms-user0"
teardown_root

echo "== cenário 10: restore-stock invalida o cache de parse do PM"
setup_root
run_script 0 "prepare (baseline)" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
[ -f "$PM_CACHE/package.odex" ] && ok "cache do PM presente antes" || bad "cache do PM ausente antes"
run_script 0 "restore-stock move o cache do PM" restore-stock --wipe-data >/dev/null || true
[ ! -f "$PM_CACHE/package.odex" ] && ok "cache do PM removido (re-scan forçado)" || bad "cache do PM ainda presente"
ls "$BACKUP_BASE"/pm-cache-*/package.odex >/dev/null 2>&1 && ok "cache do PM com backup preservado" || bad "backup do cache do PM ausente"
teardown_root

echo "== cenário 11: restore-stock com GMS ocupado → umount -l (lazy)"
setup_root
run_script 0 "prepare (baseline)" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
FAKE_UMOUNT_BUSY=1 run_script 0 "restore-stock com GMS ocupado usa lazy umount" restore-stock --wipe-data >/dev/null || true
[ "$(mount_count)" = "0" ] && ok "todos os mounts removidos via lazy" || bad "mounts remanescentes: $(mount_count)"
teardown_root

echo "== cenário 12: mounts de fonte desconhecida"
setup_root
# simula os mounts do script antigo (microg-mask) já ativos
printf '0 0 0:0 /local/tmp/microg-mask/gms %s rw - ext4 /dev/root rw\n' "$PROF_GMS" >> "$MOUNTINFO"
printf '0 0 0:0 /local/tmp/microg-mask/gsf %s rw - ext4 /dev/root rw\n' "$PROF_GSF" >> "$MOUNTINFO"
printf '0 0 0:0 /local/tmp/microg-mask/store %s rw - ext4 /dev/root rw\n' "$PROF_STORE" >> "$MOUNTINFO"
run_script 3 "prepare recusa mounts de fonte desconhecida" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" || true
[ "$(mount_count)" = "3" ] && ok "mounts do script antigo intactos (não mexeu)" || bad "mounts alterados: $(mount_count)"
probe_out="$(PATH="$FAKEBIN:/usr/bin:/bin" \
    DEGOOGLE_PROFILE_GMS="$PROF_GMS" \
    DEGOOGLE_PROFILE_GSF="$PROF_GSF" \
    DEGOOGLE_PROFILE_STORE="$PROF_STORE" \
    DEGOOGLE_MASK_BASE="$MASK_BASE" \
    DEGOOGLE_BACKUP_BASE="$BACKUP_BASE" \
    DEGOOGLE_LOCK_DIR="$LOCK_DIR" \
    DEGOOGLE_MOUNTINFO="$MOUNTINFO" \
    sh "$SCRIPT" probe 2>/dev/null)"
echo "$probe_out" | grep -q "DEGOOGLE_MOUNT_GMS=1" && ok "MOUNT_GMS=1 detectado" || bad "MOUNT_GMS!=1"
echo "$probe_out" | grep -q "DEGOOGLE_MOUNT_GMS_IS_OURS=0" && ok "MOUNT_GMS_IS_OURS=0 (fonte não gerenciada)" || bad "MOUNT_GMS_IS_OURS!=0"
echo "$probe_out" | grep -q "DEGOOGLE_STATE=ERROR" && ok "STATE=ERROR para fonte desconhecida" || bad "STATE!=ERROR: $(echo "$probe_out" | grep DEGOOGLE_STATE)"
teardown_root

echo "== cenário 13: kernel real — fonte relativa ao fs é reconhecida como nossa"
setup_root
# Simula o comportamento real do kernel: a máscara vive sob /data (f2fs), então
# o campo 4 do mountinfo é relativo ao mount point /data, não o path completo.
printf '0 0 0:0 / / rw - ext4 /dev/root rw\n' >> "$MOUNTINFO"
# $ROOT faz o papel do /data real: fs próprio cujos mounts aparecem com
# fonte relativa ao mount point ($ROOT), não o path completo.
printf '0 0 0:0 %s %s rw - f2fs /dev/block/dm-65 rw\n' "$ROOT" "$ROOT" >> "$MOUNTINFO"
printf '0 0 0:0 /mask/gms %s rw - f2fs /dev/block/dm-65 rw\n' "$PROF_GMS" >> "$MOUNTINFO"
printf '0 0 0:0 /mask/gsf %s rw - f2fs /dev/block/dm-65 rw\n' "$PROF_GSF" >> "$MOUNTINFO"
printf '0 0 0:0 /mask/store %s rw - f2fs /dev/block/dm-65 rw\n' "$PROF_STORE" >> "$MOUNTINFO"
probe_out="$(PATH="$FAKEBIN:/usr/bin:/bin" \
    DEGOOGLE_PROFILE_GMS="$PROF_GMS" \
    DEGOOGLE_PROFILE_GSF="$PROF_GSF" \
    DEGOOGLE_PROFILE_STORE="$PROF_STORE" \
    DEGOOGLE_MASK_BASE="$MASK_BASE" \
    DEGOOGLE_BACKUP_BASE="$BACKUP_BASE" \
    DEGOOGLE_LOCK_DIR="$LOCK_DIR" \
    DEGOOGLE_MOUNTINFO="$MOUNTINFO" \
    sh "$SCRIPT" probe 2>/dev/null)"
echo "$probe_out" | grep -q "DEGOOGLE_MOUNT_GMS_IS_OURS=1" && ok "MOUNT_GMS_IS_OURS=1 com fonte relativa (kernel real)" || bad "MOUNT_GMS_IS_OURS!=1 com fonte relativa"
echo "$probe_out" | grep -q "DEGOOGLE_MOUNT_STORE_IS_OURS=1" && ok "MOUNT_STORE_IS_OURS=1 com fonte relativa" || bad "MOUNT_STORE_IS_OURS!=1 com fonte relativa"
teardown_root

echo "== cenário 14: dry-run e preflight são não destrutivos"
setup_root
dry_out="$(PATH="$FAKEBIN:/usr/bin:/bin" \
    DEGOOGLE_PROFILE_GMS="$PROF_GMS" \
    DEGOOGLE_PROFILE_GSF="$PROF_GSF" \
    DEGOOGLE_PROFILE_STORE="$PROF_STORE" \
    DEGOOGLE_MASK_BASE="$MASK_BASE" \
    DEGOOGLE_BACKUP_BASE="$BACKUP_BASE" \
    DEGOOGLE_TRANSACTION_BASE="$BACKUP_BASE/transaction" \
    DEGOOGLE_LOCK_DIR="$LOCK_DIR" \
    DEGOOGLE_MOUNTINFO="$MOUNTINFO" \
    DEGOOGLE_PM_CACHE="$PM_CACHE" \
    sh "$SCRIPT" dry-run 2>&1)"
echo "$dry_out" | grep -q 'DEGOOGLE_DRY_RUN=1' && ok "dry-run emite protocolo estruturado" || bad "dry-run sem marcador estruturado"
echo "$dry_out" | grep -q 'DEGOOGLE_CAP_BIND_MOUNT_STATUS=' && ok "dry-run emite capabilities" || bad "dry-run sem capabilities"
[ "$(mount_count)" = "0" ] && ok "dry-run não deixou mounts" || bad "dry-run deixou mounts"
run_script 0 "preflight após dry-run" preflight >/dev/null || true
[ "$(mount_count)" = "0" ] && ok "preflight não alterou mounts" || bad "preflight alterou mounts"
teardown_root

echo "== cenário 15: preflight detecta TOCTOU por hash"
setup_root
run_script 0 "probe cria baseline" probe >/dev/null || true
printf 'stock apk mudou\n' > "$PROF_GMS/GmsCore.apk"
run_script 3 "preflight bloqueia hash alterado" preflight >/dev/null || true
[ "$(mount_count)" = "0" ] && ok "hash alterado não causou mount" || bad "hash alterado causou mount"
teardown_root

echo "== cenário 16: opt-in usa target descoberto permitido"
setup_root
ALT_GMS_DIR="$SYSTEM/product/priv-app/GmsCoreAlt"
mkdir -p "$ALT_GMS_DIR"
printf 'stock-alt\n' > "$ALT_GMS_DIR/base.apk"
PM_GMS_PATH="$ALT_GMS_DIR/base.apk" \
    run_script 0 "prepare com GMS em diretório descoberto" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" || true
[ "$(mount_count)" = "3" ] && ok "runtime checks aplicaram os 3 mounts" || bad "prepare deixou $(mount_count) mounts"
grep -q "$ALT_GMS_DIR" "$MOUNTINFO" && ok "mount GMS usa target descoberto" || bad "mount GMS não usa target descoberto"
teardown_root

echo "== cenário 17: rollback recusa mount externo"
setup_root
printf '0 0 0:0 /local/tmp/foreign %s rw - ext4 /dev/root rw\n' "$PROF_GMS" >> "$MOUNTINFO"
run_script 4 "restore-stock não desmonta fonte externa" restore-stock --wipe-data >/dev/null || true
[ "$(mount_count)" = "1" ] && ok "mount externo permaneceu intacto" || bad "mount externo foi alterado"
teardown_root

echo "== cenário 18: rollback com GSF/Store ausentes do PM"
setup_root
run_script 0 "prepare antes de ocultar GSF/Store" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
PM_GSF_MISSING=1 PM_STORE_MISSING=1 run_script 0 "restore-stock não depende de pm path para resolver targets" restore-stock >/dev/null || true
[ "$(mount_count)" = "0" ] && ok "GSF/Store desmontados mesmo ausentes do PM" || bad "mounts remanescentes: $(mount_count)"
[ ! -f "$MASK_BASE/gms/GmsCore.apk" ] && [ ! -f "$MASK_BASE/store/Companion.apk" ] && ok "APKs removidos somente após unmount" || bad "APKs da máscara permanecem"
grep -q '^state=REINDEX_PENDING$' "$BACKUP_BASE/transaction/journal" && ok "journal marca reindexamento pendente" || bad "journal não marca REINDEX_PENDING"
teardown_root

echo "== cenário 19: rollback usa target dinâmico persistido no snapshot"
setup_root
ALT_GMS_DIR="$SYSTEM/product/priv-app/GmsCoreAlt"
mkdir -p "$ALT_GMS_DIR"
printf 'stock-alt\n' > "$ALT_GMS_DIR/base.apk"
PM_GMS_PATH="$ALT_GMS_DIR/base.apk" run_script 0 "prepare cria snapshot com target dinâmico" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
PM_GMS_PATH="$ALT_GMS_DIR/base.apk" PM_GSF_MISSING=1 PM_STORE_MISSING=1 \
    run_script 0 "restore-stock usa snapshot mesmo com GSF/Store ausentes" restore-stock >/dev/null || true
[ "$(mount_count)" = "0" ] && ok "target dinâmico desmontado pelo snapshot" || bad "target dinâmico permaneceu montado"
teardown_root

echo "== cenário 20: updates legítimos em /data/app"
setup_root
PM_GMS_PATH="/data/app/~~gms/com.google.android.gms-1/base.apk" \
PM_STORE_PATH="/data/app/~~store/com.android.vending-1/base.apk" \
FAKE_GMS_SYSTEM_CODE_PATH="$PROF_GMS/GmsCore.apk" \
FAKE_STORE_SYSTEM_CODE_PATH="$PROF_STORE/Phonesky.apk" \
    run_script 0 "probe classifica updates como stock" probe > "$ROOT/update_probe.out" || true
grep -q '^DEGOOGLE_STATE=STOCK$' "$ROOT/update_probe.out" && ok "updates em /data/app resultam em STOCK" || bad "updates em /data/app resultaram em estado inesperado"
grep -q '^DEGOOGLE_GMS_HAS_DATA_UPDATE=1$' "$ROOT/update_probe.out" && \
    grep -q '^DEGOOGLE_STORE_HAS_DATA_UPDATE=1$' "$ROOT/update_probe.out" && \
    ok "locator confirmou os dois updates" || bad "locator não confirmou os updates"
PM_GMS_PATH="/data/app/~~gms/com.google.android.gms-1/base.apk" \
PM_STORE_PATH="/data/app/~~store/com.android.vending-1/base.apk" \
FAKE_GMS_SYSTEM_CODE_PATH="$PROF_GMS/GmsCore.apk" \
FAKE_STORE_SYSTEM_CODE_PATH="$PROF_STORE/Phonesky.apk" \
    run_script 0 "dry-run aceita updates removíveis" dry-run > "$ROOT/update_dry_run.out" || true
grep -q '^DEGOOGLE_CAP_GMS_MASKABLE_STATUS=PASS$' "$ROOT/update_dry_run.out" && \
    grep -q '^DEGOOGLE_CAP_STORE_MASKABLE_STATUS=PASS$' "$ROOT/update_dry_run.out" && \
    ok "GMS/Store update não geram WARN de maskabilidade" || bad "GMS/Store update ainda geram WARN"
grep -q 'update in /data/app will be removed during preparation' "$ROOT/update_dry_run.out" && \
    ok "dry-run informa o cleanup automático" || bad "dry-run não informa o cleanup automático"
PM_GMS_PATH="/data/app/~~gms/com.google.android.gms-1/base.apk" \
PM_STORE_PATH="/data/app/~~store/com.android.vending-1/base.apk" \
FAKE_GMS_SYSTEM_CODE_PATH="$PROF_GMS/GmsCore.apk" \
FAKE_STORE_SYSTEM_CODE_PATH="$PROF_STORE/Phonesky.apk" \
    run_script 0 "preflight aceita update antes do cleanup" preflight >/dev/null || true
teardown_root

echo "== cenário 28: update exige metadados de pacote de sistema"
setup_root
PM_GMS_PATH="/data/app/~~gms/com.google.android.gms-1/base.apk" \
FAKE_DATA_CODE_PATH=1 run_script 0 "probe reconhece caminho de dados" probe > "$ROOT/data_update_probe.out" || true
grep -q '^DEGOOGLE_GMS_HAS_DATA_UPDATE=0$' "$ROOT/data_update_probe.out" && ok "instalação em data sem flag de system update não é removível" || bad "caminho em data foi tratado como update de sistema"
PM_GMS_PATH="/data/app/~~gms/com.google.android.gms-1/base.apk" \
FAKE_SYSTEM_CODE_PATH="$PROF_GMS/GmsCore.apk" run_script 0 "probe resolve update com metadata stock" probe > "$ROOT/system_update_probe.out" || true
grep -q '^DEGOOGLE_GMS_HAS_DATA_UPDATE=1$' "$ROOT/system_update_probe.out" && \
    grep -q "^DEGOOGLE_GMS_ORIGINAL_SYSTEM_PATH=$PROF_GMS/GmsCore.apk$" "$ROOT/system_update_probe.out" && \
    ok "update de sistema usa metadata stock" || bad "metadata stock do update não foi resolvida"
teardown_root

echo "== cenário 29: snapshot inválido bloqueia recovery sem fallback"
setup_root
mkdir -p "$MASK_BASE/gms" "$MASK_BASE/gsf" "$MASK_BASE/store" "$BACKUP_BASE/transaction"
printf '{ "schemaVersion": 1, "operationId": "broken" }\n' > "$BACKUP_BASE/transaction/snapshot.json"
printf '0 0 0:0 %s/gms %s rw - ext4 /dev/root rw\n' "$MASK_BASE" "$PROF_GMS" >> "$MOUNTINFO"
run_script 3 "restore-stock bloqueia snapshot incompleto" restore-stock >/dev/null || true
[ "$(mount_count)" = "1" ] && ok "snapshot inválido preservou mount" || bad "snapshot inválido alterou mount"
teardown_root

echo "== cenário 30: mounts empilhados são tratados como externos"
setup_root
run_script 0 "prepare antes do mount empilhado" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
printf '0 0 0:0 /foreign %s rw - ext4 /dev/root rw\n' "$PROF_GMS" >> "$MOUNTINFO"
run_script 4 "restore-stock recusa mount empilhado" restore-stock >/dev/null || true
[ "$(mount_count)" = "4" ] && [ -f "$MASK_BASE/gms/GmsCore.apk" ] && \
    ok "mount empilhado e payload preservados" || bad "mount empilhado foi tratado como próprio"
teardown_root

echo "== cenário 31: GSF mask exige diretório vazio"
setup_root
mkdir -p "$MASK_BASE/gsf"
printf 'payload estranho\n' > "$MASK_BASE/gsf/unexpected"
run_script 4 "prepare recusa payload GSF preexistente" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
[ "$(mount_count)" = "0" ] && [ -f "$MASK_BASE/gsf/unexpected" ] && \
    ok "nenhum mount aplicado e payload preservado" || bad "payload GSF foi mascarado ou removido"
teardown_root

echo "== cenário 32: recuperação de transação parcial"
setup_root
run_script 0 "prepare antes da recuperação parcial" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
awk -v t="$PROF_GSF" '$5 != t' "$MOUNTINFO" > "$MOUNTINFO.tmp" && mv "$MOUNTINFO.tmp" "$MOUNTINFO"
run_script 0 "restore-stock recupera GMS/Store com GSF já desmontado" restore-stock >/dev/null || true
[ "$(mount_count)" = "0" ] && [ ! -f "$MASK_BASE/gms/GmsCore.apk" ] && \
    ok "transação parcial recuperada sem remover evidência ativa" || bad "transação parcial não foi recuperada"
teardown_root

echo "== cenário 33: identidade do filesystem faz parte da propriedade do mount"
setup_root
run_script 0 "prepare antes de trocar filesystem do mount" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
awk -v t="$PROF_GMS" '$5 == t { $3="8:1" } { print }' "$MOUNTINFO" > "$MOUNTINFO.tmp" && mv "$MOUNTINFO.tmp" "$MOUNTINFO"
run_script 4 "restore-stock recusa filesystem de backing diferente" restore-stock >/dev/null || true
[ "$(mount_count)" = "3" ] && [ -f "$MASK_BASE/gms/GmsCore.apk" ] && \
    ok "mount com device diferente e payload preservados" || bad "device diferente foi aceito como mount próprio"
teardown_root

echo "== cenário 34: prepare encaminha mounts parciais para recovery"
setup_root
run_script 0 "prepare antes de parcial" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
awk -v t="$PROF_GSF" '$5 != t' "$MOUNTINFO" > "$MOUNTINFO.tmp" && mv "$MOUNTINFO.tmp" "$MOUNTINFO"
run_script 3 "prepare bloqueia transação parcial" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
[ "$(mount_count)" = "2" ] && grep -q '^state=STORE_MASKED$' "$BACKUP_BASE/transaction/journal" && \
    ok "mounts e journal preservados para recovery" || bad "prepare alterou a transação parcial"
teardown_root

echo "== cenário 21: FakeGApps/LSPosed detectados com WARN"
setup_root
touch "$ROOT/lsposed.marker"
FAKEGAPPS_PRESENT=1 DEGOOGLE_LSPOSED_MARKER="$ROOT/lsposed.marker" \
    run_script 0 "probe detecta FakeGApps sem afirmar PASS funcional" probe > "$ROOT/fakegapps_probe.out" || true
grep -q '^DEGOOGLE_CAP_SIGNATURE_SPOOFING_STATUS=WARN$' "$ROOT/fakegapps_probe.out" && \
    ok "FakeGApps resulta em WARN conservador" || bad "FakeGApps não resultou em WARN"
grep -q 'FakeGApps' "$ROOT/fakegapps_probe.out" && ok "evidência do módulo foi registrada" || bad "evidência do módulo ausente"
! grep -q 'desabilitado para o usuário' "$ROOT/fakegapps_probe.out" && \
    ok "enabled=0 não é tratado como pacote desabilitado" || bad "enabled=0 foi interpretado como desabilitado"
teardown_root

echo "== cenário 22: limpeza automática de resíduos conhecidos"
setup_root
mkdir -p "$MASK_BASE/gms" "$MASK_BASE/gsf" "$MASK_BASE/store" \
    "$LEGACY_MASK_BASE/gms" "$LEGACY_MASK_BASE/gsf" "$LEGACY_MASK_BASE/store"
cp "$ROOT/gms.apk" "$MASK_BASE/gms/GmsCore.apk"
cp "$ROOT/companion.apk" "$MASK_BASE/store/Companion.apk"
cp "$ROOT/gms.apk" "$LEGACY_MASK_BASE/gms/GmsCore.apk"
mkdir -p "$MASK_BASE/gms/lib/arm64"
printf 'lib-fake\n' > "$MASK_BASE/gms/lib/arm64/libmapbox-gl.so"
printf 'evidencia-estranha\n' > "$MASK_BASE/gms/keep-for-diagnostics.txt"
printf 'backup-intacto\n' > "$BACKUP_BASE/keep.txt"
run_script 0 "cleanup-residue remove payloads conhecidos" cleanup-residue > "$ROOT/residue_cleanup.out" || true
[ ! -e "$MASK_BASE/gms/GmsCore.apk" ] && \
    [ ! -e "$MASK_BASE/store/Companion.apk" ] && \
    [ ! -e "$LEGACY_MASK_BASE/gms/GmsCore.apk" ] && \
    ok "payloads atuais e legados removidos" || { bad "payload conhecido permaneceu"; cat "$ROOT/residue_cleanup.out"; }
[ ! -e "$MASK_BASE/gms/lib" ] && \
    ok "lib Mapbox removida da máscara" || { bad "lib Mapbox permaneceu na máscara"; cat "$ROOT/residue_cleanup.out"; }
[ -f "$MASK_BASE/gms/keep-for-diagnostics.txt" ] && \
    ok "arquivo desconhecido preservado" || bad "arquivo desconhecido foi removido"
grep -q '^DEGOOGLE_MASK_RESIDUE_PRESENT=1$' "$ROOT/residue_cleanup.out" && \
    ok "resíduo desconhecido continua sinalizado" || { bad "sinalização de resíduo desconhecido ausente"; cat "$ROOT/residue_cleanup.out"; }
[ -f "$BACKUP_BASE/keep.txt" ] && ok "backup preservado" || bad "backup foi alterado"
teardown_root

echo "== cenário 23: restore-stock pre-reboot não chama binder; enable fica para o reindex pos-boot"
setup_root
# restore-stock não pode mais executar `pm enable` (binder trava com o PM em
# rescan). O wipe roda pre-reboot, o enable roda no reindex-stock pos-boot; o
# fake falha se o enable rodar antes da limpeza dos dados.
run_script 0 "prepare antes do rollback ordenado" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
FAKE_PM_GMS_DISABLED=1 PM_REQUIRE_GMS_DATA_CLEARED_BEFORE_ENABLE=1 \
    run_script 0 "restore-stock faz wipe sem binder" restore-stock --wipe-data > "$ROOT/ordered_restore.out" || true
[ ! -e "$DATA_USER0/registration.xml" ] && [ ! -e "$DATA_USERDE/device.xml" ] && \
    ok "payloads do microG removidos pre-reboot" || bad "payloads antigos permaneceram"
grep -q '^state=REINDEX_PENDING$' "$BACKUP_BASE/transaction/journal" && \
    ok "rollback ordenado alcançou REINDEX_PENDING" || bad "rollback ordenado não foi concluído"
FAKE_BOOT_COMPLETED=1 DEGOOGLE_TEST_SYSTEM_SERVER_PID=101 \
FAKE_PM_GMS_DISABLED=1 PM_REQUIRE_GMS_DATA_CLEARED_BEFORE_ENABLE=1 \
    run_script 0 "reindex-stock reabilita apos o wipe" reindex-stock > "$ROOT/ordered_reindex.out" || true
grep -q 'REINDEX_OK' "$ROOT/ordered_reindex.out" || grep -q 'REINDEX-STOCK CONCLUÍDO' "$ROOT/ordered_reindex.out" && \
    ok "reindex-stock concluiu o enable pos-boot" || bad "reindex-stock não concluiu o enable"
teardown_root

echo "== cenário 24: registro stale do PM é invalidado no reindex pos-boot, com backup"
setup_root
printf 'packages\n' > "$PM_SYSTEM/packages.xml"
printf 'packages.list\n' > "$PM_SYSTEM/packages.list"
printf 'reserve\n' > "$PM_SYSTEM/packages.xml.reservecopy"
printf 'backup\n' > "$PM_SYSTEM/packages-backup.xml"
printf 'stock gsf\n' > "$PROF_GSF/GoogleServicesFramework.apk"
printf 'stock store\n' > "$PROF_STORE/Phonesky.apk"
run_script 0 "prepare antes de invalidar o registro PM" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
PM_GSF_MISSING=1 PM_STORE_MISSING=1 run_script 0 "restore-stock pre-reboot preserva registro para o pos-boot" restore-stock >/dev/null || true
[ -e "$PM_SYSTEM/packages.xml" ] && ok "restore-stock não move packages.xml pre-reboot" || bad "restore-stock moveu packages.xml pre-reboot"
printf 'stock gsf\n' > "$PROF_GSF/GoogleServicesFramework.apk"
printf 'stock store\n' > "$PROF_STORE/Phonesky.apk"
FAKE_BOOT_COMPLETED=1 DEGOOGLE_TEST_SYSTEM_SERVER_PID=101 PM_GSF_MISSING=1 PM_STORE_MISSING=1 \
    run_script 0 "reindex-stock invalida registro PM quando stock está presente" reindex-stock > "$ROOT/registry_reindex.out" || true
[ ! -e "$PM_SYSTEM/packages.xml" ] && [ ! -e "$PM_SYSTEM/packages.list" ] && \
    [ ! -e "$PM_SYSTEM/packages.xml.reservecopy" ] && ok "arquivos stale do PM foram removidos do caminho ativo" || \
    { bad "registro stale do PM permaneceu ativo"; cat "$ROOT/registry_reindex.out"; }
ls "$BACKUP_BASE"/package-registry-*/* >/dev/null 2>&1 && \
    ok "registro stale foi preservado no backup" || { bad "backup do registro do PM ausente"; cat "$ROOT/registry_reindex.out"; }
grep -q '^state=REINDEX_PENDING$' "$BACKUP_BASE/transaction/journal" && \
    ok "journal continua pendente enquanto GSF/Store não estão registrados" || \
    bad "journal saiu de REINDEX_PENDING com registros ausentes"
teardown_root

echo "== cenário 25: cooldown protege apenas a recuperação automática"
setup_root
cat > "$FAKEBIN/setprop" <<'EOF'
#!/bin/sh
exit "${FAKE_SET_PROP_EXIT:-1}"
EOF
KSUD_STUB="$ROOT/ksud"
cat > "$KSUD_STUB" <<EOF
#!/bin/sh
echo invoked > "$ROOT/ksud.called"
exit 1
EOF
chmod +x "$FAKEBIN/setprop" "$KSUD_STUB"
mkdir -p "$BACKUP_BASE/transaction"
printf 'last=%s\nattempts=1\n' "$(date +%s)" > "$BACKUP_BASE/transaction/rescue-party.state"
DEGOOGLE_REBOOT_SOURCE=automatic DEGOOGLE_KSUD_PATH="$KSUD_STUB" \
    run_script 6 "recovery automática respeita cooldown" soft-reboot || true
[ ! -e "$ROOT/ksud.called" ] && ok "cooldown não chamou nenhum método" || bad "cooldown chamou o método de reboot"
[ -f "$BACKUP_BASE/transaction/rescue-party.state" ] && ok "cooldown preservou o guard" || bad "cooldown removeu o guard"

rm -f "$ROOT/ksud.called"
DEGOOGLE_REBOOT_SOURCE=manual DEGOOGLE_KSUD_PATH="$KSUD_STUB" \
    run_script 5 "tentativa manual ignora cooldown automático" soft-reboot || true
[ ! -e "$ROOT/ksud.called" ] && ok "método não selecionado não foi chamado" || bad "backend chamou KernelSU apesar do método AOSP selecionado"

rm -f "$BACKUP_BASE/transaction/rescue-party.state" "$ROOT/ksud.called"
DEGOOGLE_REBOOT_SOURCE=automatic DEGOOGLE_KSUD_PATH="$KSUD_STUB" \
    run_script 5 "falha dos métodos não consome cooldown" soft-reboot || true
[ ! -e "$BACKUP_BASE/transaction/rescue-party.state" ] && ok "falha real limpou o guard" || bad "falha real deixou cooldown falso"
teardown_root

echo "== cenário 35: restart exige nova identidade do framework"
setup_root
run_script 0 "prepare antes do userspace restart" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
FAKE_SET_PROP_EXIT=0 run_script 0 "userspace restart registra baseline" soft-reboot >/dev/null || true
grep -q '^state=REBOOT_REQUESTED$' "$BACKUP_BASE/transaction/journal" && \
    ok "pedido de restart foi persistido" || bad "pedido de restart ausente"
grep -q '^kernelBootId=test-boot-id$' "$BACKUP_BASE/transaction/reboot-baseline" && \
    ok "identidade anterior foi persistida" || bad "baseline de restart ausente"
run_script 5 "restart sem mudança de identidade não se repete" soft-reboot > "$ROOT/repeated_restart.out" || true
grep -q 'refusing to request it again' "$ROOT/repeated_restart.out" && \
    ok "pedido no-op foi bloqueado" || bad "pedido no-op não foi bloqueado"
FAKE_BOOT_COMPLETED=1 FAKE_GMS_PRIVILEGED=1 PM_GSF_MISSING=1 \
    run_script 4 "post-boot rejeita pedido sem restart do framework" post-boot-validate > "$ROOT/no_restart_validation.out" || true
grep -q 'system_server identity did not change' "$ROOT/no_restart_validation.out" && \
    ok "identidade unchanged bloqueou conclusão" || bad "post-boot aceitou identidade anterior"
FAKE_BOOT_COMPLETED=1 FAKE_GMS_PRIVILEGED=1 DEGOOGLE_TEST_SYSTEM_SERVER_PID=101 PM_GSF_MISSING=1 \
    run_script 0 "post-boot aceita novo system_server com mesmo kernel" post-boot-validate > "$ROOT/post_boot_validation.out" || true
grep -q '^state=COMMITTED$' "$BACKUP_BASE/transaction/journal" && \
    ok "transação concluída após validação post-boot" || { bad "transação não foi concluída"; cat "$ROOT/post_boot_validation.out" >&2; }
teardown_root

echo "== scenario 42: restart methods without confirmed support are rejected"
setup_root
KSUD_STUB="$ROOT/ksud"
printf '#!/bin/sh\nprintf "%%s\\n" "$*" >> %s\ncase "$*" in *help*) exit 0 ;; esac\necho invoked > %s\nexit 0\n' \
    "$ROOT/ksud.calls" "$ROOT/ksud.called" > "$KSUD_STUB"
chmod +x "$KSUD_STUB"
FAKE_USERSPACE_REBOOT_SUPPORTED=0 DEGOOGLE_KSUD_PATH="$KSUD_STUB" \
FAKE_SET_PROP_MARKER="$ROOT/setprop.called" \
    run_script 5 "soft reboot rejects unsupported AOSP and unverified KernelSU" soft-reboot >/dev/null || true
[ ! -e "$ROOT/ksud.called" ] && [ ! -e "$ROOT/setprop.called" ] && \
    grep -q -- '--help' "$ROOT/ksud.calls" && \
    [ ! -e "$BACKUP_BASE/transaction/reboot-baseline" ] && \
    ok "help was checked without rebooting or persisting a request" || bad "unverified method attempted a restart"
teardown_root

echo "== scenario 47: zygote fallback requires explicit opt-in and does not reboot the kernel"
setup_root
FAKE_USERSPACE_REBOOT_SUPPORTED=0 DEGOOGLE_ALLOW_ZYGOTE_RESTART=1 \
FAKE_SET_PROP_EXIT=0 FAKE_SET_PROP_MARKER="$ROOT/zygote.called" \
    run_script 0 "soft-reboot usa zygote com opt-in" soft-reboot >/dev/null || true
grep -q '^ctl.restart zygote$' "$ROOT/zygote.called" && \
    ok "zygote restart requested without kernel reboot" || bad "zygote fallback was not requested"
[ -s "$BACKUP_BASE/transaction/reboot-baseline" ] && \
    ok "baseline persistido antes do zygote restart" || bad "baseline do restart ausente"
teardown_root

echo "== scenario 48: ksud soft reboot passes only when its subcommand is advertised"
setup_root
KSUD_STUB="$ROOT/ksud"
cat > "$KSUD_STUB" <<EOF
#!/bin/sh
case "\$*" in *help*) echo 'userspace soft-reboot'; exit 0 ;; esac
echo "\$*" > "$ROOT/ksud.called"
exit 0
EOF
chmod +x "$KSUD_STUB"
FAKE_USERSPACE_REBOOT_SUPPORTED=0 DEGOOGLE_KSUD_PATH="$KSUD_STUB" \
    run_script 0 "soft-reboot usa ksud validado" soft-reboot >/dev/null || true
grep -q '^soft-reboot$' "$ROOT/ksud.called" && \
    ok "ksud soft-reboot ran after checking help output" || bad "verified ksud was not executed"
[ -s "$BACKUP_BASE/transaction/reboot-baseline" ] && \
    ok "baseline persistido antes do ksud reboot" || bad "baseline do ksud restart ausente"
teardown_root

echo "== cenário 43: post-boot revalida kernel, mounts, pacotes e estado"
setup_root
run_script 0 "prepare para matriz post-boot" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
FAKE_SET_PROP_EXIT=0 run_script 0 "persiste baseline para matriz post-boot" soft-reboot >/dev/null || true
cp "$MOUNTINFO" "$ROOT/full_mountinfo"

DEGOOGLE_TEST_BOOT_ID=changed DEGOOGLE_TEST_SYSTEM_SERVER_PID=101 FAKE_BOOT_COMPLETED=1 \
FAKE_GMS_PRIVILEGED=1 PM_GSF_MISSING=1 \
    run_script 4 "post-boot rejeita mudança da identidade do kernel" post-boot-validate > "$ROOT/kernel_change.out" || true
grep -q 'kernel boot identity changed' "$ROOT/kernel_change.out" && \
    ok "kernel reboot não foi aceito como userspace restart" || bad "kernel reboot não foi detectado"

DEGOOGLE_TEST_SYSTEM_SERVER_PID=101 FAKE_BOOT_COMPLETED=0 FAKE_GMS_PRIVILEGED=1 PM_GSF_MISSING=1 \
    run_script 4 "post-boot aguarda sys.boot_completed" post-boot-validate > "$ROOT/boot_incomplete.out" || true
grep -q 'sys.boot_completed did not confirm boot completion' "$ROOT/boot_incomplete.out" && \
    ok "boot incompleto não concluiu transação" || bad "boot incompleto foi aceito"

for target in "$PROF_GMS" "$PROF_GSF" "$PROF_STORE"; do
    cp "$ROOT/full_mountinfo" "$MOUNTINFO"
    awk -v t="$target" '$5 != t' "$MOUNTINFO" > "$MOUNTINFO.tmp" && mv "$MOUNTINFO.tmp" "$MOUNTINFO"
    case "$target" in
        "$PROF_GMS") component=GMS ;;
        "$PROF_GSF") component=GSF ;;
        *) component=Store ;;
    esac
    DEGOOGLE_TEST_SYSTEM_SERVER_PID=101 FAKE_BOOT_COMPLETED=1 FAKE_GMS_PRIVILEGED=1 PM_GSF_MISSING=1 \
        run_script 4 "post-boot exige mount $component da transação" post-boot-validate > "$ROOT/${component}_mount_missing.out" || true
    grep -Fq "$component mask mount ownership is not verified" "$ROOT/${component}_mount_missing.out" && \
        ok "mount $component ausente bloqueou conclusão" || bad "mount $component ausente não foi detectado"
done

cp "$ROOT/full_mountinfo" "$MOUNTINFO"
printf 'unexpected\n' > "$MASK_BASE/gsf/unexpected"
DEGOOGLE_TEST_SYSTEM_SERVER_PID=101 FAKE_BOOT_COMPLETED=1 FAKE_GMS_PRIVILEGED=1 PM_GSF_MISSING=1 \
    run_script 4 "post-boot rejeita máscara GSF não vazia" post-boot-validate > "$ROOT/gsf_payload.out" || true
grep -q 'GSF mask is not empty' "$ROOT/gsf_payload.out" && \
    ok "payload inesperado GSF bloqueou conclusão" || bad "payload inesperado GSF foi aceito"

rm -f "$MASK_BASE/gsf/unexpected"
cp "$ROOT/full_mountinfo" "$MOUNTINFO"
DEGOOGLE_TEST_SYSTEM_SERVER_PID=101 FAKE_BOOT_COMPLETED=1 FAKE_GMS_PRIVILEGED=1 PM_GSF_MISSING=1 \
PM_GMS_PATH="/data/app/other/base.apk" \
    run_script 4 "post-boot rejeita GMS fora do target" post-boot-validate > "$ROOT/gms_path.out" || true
grep -q 'GMS does not resolve to the mask' "$ROOT/gms_path.out" && \
    ok "path GMS inesperado bloqueou conclusão" || bad "path GMS inesperado foi aceito"

DEGOOGLE_TEST_SYSTEM_SERVER_PID=101 FAKE_BOOT_COMPLETED=1 FAKE_GMS_PRIVILEGED=1 \
    run_script 4 "post-boot rejeita GSF stock ativo" post-boot-validate > "$ROOT/gsf_active.out" || true
grep -q 'stock GSF is still active' "$ROOT/gsf_active.out" && \
    ok "GSF stock ativo bloqueou conclusão" || bad "GSF stock ativo foi aceito"

PM_GSF_MISSING=1 DEGOOGLE_TEST_SYSTEM_SERVER_PID=101 FAKE_BOOT_COMPLETED=1 \
PM_STORE_PATH="/data/app/other/base.apk" FAKE_GMS_PRIVILEGED=1 \
    run_script 4 "post-boot rejeita Store fora do target" post-boot-validate > "$ROOT/store_path.out" || true
grep -q 'Companion does not resolve to the mask' "$ROOT/store_path.out" && \
    ok "path Store inesperado bloqueou conclusão" || bad "path Store inesperado foi aceito"

PM_GSF_MISSING=1 DEGOOGLE_TEST_SYSTEM_SERVER_PID=101 FAKE_BOOT_COMPLETED=1 \
    run_script 4 "post-boot exige GMS privilegiado" post-boot-validate > "$ROOT/gms_privilege.out" || true
grep -q 'GMS is not PRIVILEGED' "$ROOT/gms_privilege.out" && \
    ok "GMS sem flag privilegiada bloqueou conclusão" || bad "GMS sem flag privilegiada foi aceito"

PM_GSF_MISSING=1 DEGOOGLE_TEST_SYSTEM_SERVER_PID=101 FAKE_BOOT_COMPLETED=1 \
FAKE_GMS_PRIVILEGED=1 FAKE_SELINUX_MODE=Permissive \
    run_script 0 "post-boot accepts permissive SELinux" post-boot-validate > "$ROOT/selinux_post_boot.out" || true
grep -q 'DEGOOGLE_POST_BOOT_STATUS=PASS' "$ROOT/selinux_post_boot.out" && \
    ok "Permissive SELinux did not block completion" || bad "Permissive SELinux blocked completion"

PM_GSF_MISSING=1 DEGOOGLE_TEST_SYSTEM_SERVER_PID=101 FAKE_BOOT_COMPLETED=1 \
FAKE_GMS_PRIVILEGED=1 FAKE_BOOT_REASON=rescueparty \
    run_script 4 "post-boot rejeita Rescue Party" post-boot-validate > "$ROOT/rescue_party.out" || true
grep -q 'Rescue Party' "$ROOT/rescue_party.out" && \
    ok "Rescue Party bloqueou conclusão" || bad "Rescue Party não foi detectado"

teardown_root

echo "== cenário 26: update com metadata validada não depende do modelo"
setup_root
FAKE_MANUFACTURER="Google" FAKE_MODEL="Unknown Model" PM_GMS_PATH="/data/app/~~gms/com.google.android.gms-1/base.apk" \
PM_STORE_PATH="/data/app/~~store/com.android.vending-1/base.apk" \
FAKE_GMS_SYSTEM_CODE_PATH="$PROF_GMS/GmsCore.apk" \
FAKE_STORE_SYSTEM_CODE_PATH="$PROF_STORE/Phonesky.apk" \
    run_script 0 "dry-run em modelo desconhecido com update" dry-run > "$ROOT/unknown_model_dry_run.out" || true
grep -q '^DEGOOGLE_CAP_GMS_MASKABLE_STATUS=PASS$' "$ROOT/unknown_model_dry_run.out" && \
    grep -q '^DEGOOGLE_CAP_STORE_MASKABLE_STATUS=PASS$' "$ROOT/unknown_model_dry_run.out" && \
    ok "update em modelo desconhecido resulta em PASS com cleanup" || bad "update em modelo desconhecido não resultou em PASS"
FAKE_MANUFACTURER="Google" FAKE_MODEL="Unknown Model" PM_GMS_PATH="/data/app/~~gms/com.google.android.gms-1/base.apk" \
PM_STORE_PATH="/data/app/~~store/com.android.vending-1/base.apk" \
FAKE_GMS_SYSTEM_CODE_PATH="$PROF_GMS/GmsCore.apk" \
FAKE_STORE_SYSTEM_CODE_PATH="$PROF_STORE/Phonesky.apk" \
    run_script 0 "preflight aceita modelo desconhecido com update" preflight >/dev/null || true
teardown_root

echo "== cenário 27: prepare instala libmapbox-gl.so na máscara"
setup_root
if command -v zip >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1; then
    rm -f "$ROOT/gms.apk"
    mkdir -p "$ROOT/gms-apk/lib/arm64-v8a"
    printf '\177ELFfake\n' > "$ROOT/gms-apk/lib/arm64-v8a/libmapbox-gl.so"
    (cd "$ROOT/gms-apk" && zip -q -r "$ROOT/gms.apk" .)
    run_script 0 "prepare extrai lib do APK" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" > "$ROOT/mapbox_prepare.out" || true
    [ -f "$MASK_BASE/gms/lib/arm64/libmapbox-gl.so" ] && \
        ok "lib presente na máscara" || bad "lib ausente na máscara"
    [ "$(stat -c %a "$MASK_BASE/gms/lib/arm64/libmapbox-gl.so" 2>/dev/null)" = "444" ] && \
        ok "lib com modo 0444" || bad "modo da lib inválido"
    [ -f "$PROF_GMS/lib/arm64/libmapbox-gl.so" ] && \
        ok "lib visível no alvo mascarado" || bad "lib não apareceu no alvo"
    grep -qi 'libmapbox-gl.so OK' "$ROOT/mapbox_prepare.out" && \
        ok "prepare validou a lib" || bad "prepare não validou a lib"
else
    echo "  - zip/unzip ausentes no PATH (pulando)"
fi
teardown_root

# ---------------------------------------------------------------------------
echo "== análise estática (shellcheck)"
if command -v shellcheck >/dev/null 2>&1; then
    shellcheck -s sh "$SCRIPT" && ok "shellcheck sem apontamentos" || bad "shellcheck detectou problemas"
else
    echo "  - shellcheck não encontrado no PATH (pulando)"
fi

echo
echo "RESULTADO: $PASS passaram, $FAIL falharam"
[ "$FAIL" = "0" ]
