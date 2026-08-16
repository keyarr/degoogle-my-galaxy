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
# =============================================================================

set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/../degoogle.sh"

PASS=0
FAIL=0

ok() { PASS=$((PASS + 1)); echo "  ✓ $*"; }
bad() { FAIL=$((FAIL + 1)); echo "  ✗ FALHA: $*"; }

# ---------------------------------------------------------------------------
# setup de um cenário: raiz temporária + PATH falso
# ---------------------------------------------------------------------------
setup_root()
{
    ROOT="$(mktemp -d)"
    SYSTEM="$ROOT/system"
    PROF_GMS="$SYSTEM/product/priv-app/GmsCore"
    PROF_GSF="$SYSTEM/system_ext/priv-app/GoogleServicesFramework"
    PROF_STORE="$SYSTEM/product/priv-app/Phonesky"
    MASK_BASE="$ROOT/mask"
    BACKUP_BASE="$ROOT/backup"
    LOCK_DIR="$ROOT/lock"
    MOUNTINFO="$ROOT/mountinfo"
    FAKEBIN="$ROOT/bin"
    PM_CACHE="$ROOT/pm-cache"
    DATA_USER0="$ROOT/data/user/0/com.google.android.gms"
    DATA_USERDE="$ROOT/data/user_de/0/com.google.android.gms"

    mkdir -p "$PROF_GMS" "$PROF_GSF" "$PROF_STORE" "$FAKEBIN" "$PM_CACHE" "$BACKUP_BASE"
    mkdir -p "$DATA_USER0" "$DATA_USERDE"
    printf 'registered-fcm-token\n' > "$DATA_USER0/registration.xml"
    printf 'device-lock\n' > "$DATA_USERDE/device.xml"
    : > "$MOUNTINFO"
    touch "$PM_CACHE/package.odex"
    touch "$ROOT/gms.apk" "$ROOT/companion.apk"
    printf 'PK\003\004' | dd of="$ROOT/gms.apk" conv=notrunc 2>/dev/null
    printf 'PK\003\004' | dd of="$ROOT/companion.apk" conv=notrunc 2>/dev/null

    cat > "$FAKEBIN/id" <<'EOF'
#!/bin/sh
case "$1" in
    -u) echo 0 ;;
    *) echo "uid=0(root) gid=0(root) groups=0(root)" ;;
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
    ro.build.fingerprint) echo "samsung/e3qxxx/e3q:16/UP1A.231005.007/S928BXXU1AXK1:user/release-keys" ;;
    ro.product.cpu.abi) echo "arm64-v8a" ;;
    *) echo "" ;;
esac
EOF

    cat > "$FAKEBIN/getenforce" <<'EOF'
#!/bin/sh
echo "Enforcing"
EOF

    cat > "$FAKEBIN/nsenter" <<'EOF'
#!/bin/sh
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
    /proc/self/ns/mnt|/proc/1/ns/mnt) echo "mnt:[4026534000]" ;;
    *) exec /usr/bin/readlink "$@" ;;
esac
EOF

    cat > "$FAKEBIN/mount" <<'EOF'
#!/bin/sh
MI="${DEGOOGLE_MOUNTINFO:?}"
case "$1" in
    --bind)
        src="$2"; tgt="$3"
        mkdir -p "$tgt"
        cp -r "$src/." "$tgt/" 2>/dev/null
        awk -v t="$tgt" '$5 != t' "$MI" > "$MI.tmp" && mv "$MI.tmp" "$MI"
        printf '0 0 0:0 %s %s rw,relatime - ext4 /dev/root rw\n' "$src" "$tgt" >> "$MI"
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
            %u:%g) echo "0:0"; exit 0 ;;
            %a)
                if [ -d "$3" ]; then echo "755"; else echo "644"; fi
                exit 0
                ;;
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
        if [ "${FAKE_SELINUX_BAD:-0}" = "1" ]; then
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
            com.google.android.gms) echo "package:${PM_GMS_PATH:-$DEGOOGLE_PROFILE_GMS/GmsCore.apk}" ;;
            *) exit 1 ;;
        esac
        ;;
    enable)
        case "$2" in
            com.google.android.gsf)
                [ "${PM_GSF_MISSING:-0}" = "1" ] && exit 1 ;;
            com.android.vending)
                [ "${PM_STORE_MISSING:-0}" = "1" ] && exit 1 ;;
        esac
        exit 0
        ;;
    *) exit 0 ;;
esac
EOF

cat > "$FAKEBIN/dumpsys" <<'EOF'
#!/bin/sh
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
    out="$(PATH="$FAKEBIN:/usr/bin:/bin" \
        DEGOOGLE_PROFILE_GMS="$PROF_GMS" \
        DEGOOGLE_PROFILE_GSF="$PROF_GSF" \
        DEGOOGLE_PROFILE_STORE="$PROF_STORE" \
        DEGOOGLE_MASK_BASE="$MASK_BASE" \
        DEGOOGLE_BACKUP_BASE="$BACKUP_BASE" \
        DEGOOGLE_GMS_DATA_USER0="$DATA_USER0" \
        DEGOOGLE_GMS_DATA_USERDE="$DATA_USERDE" \
        DEGOOGLE_LOCK_DIR="$LOCK_DIR" \
        DEGOOGLE_MOUNTINFO="$MOUNTINFO" \
        DEGOOGLE_PM_CACHE="$PM_CACHE" \
        DEGOOGLE_EXPERIMENTAL="${DEGOOGLE_EXPERIMENTAL:-}" \
        PM_GMS_PATH="${PM_GMS_PATH:-}" \
        PM_GSF_PATH="${PM_GSF_PATH:-}" \
        PM_STORE_PATH="${PM_STORE_PATH:-}" \
        PM_GSF_MISSING="${PM_GSF_MISSING:-}" \
        PM_STORE_MISSING="${PM_STORE_MISSING:-}" \
        FAKEGAPPS_PRESENT="${FAKEGAPPS_PRESENT:-}" \
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
    awk 'END { print NR }' "$MOUNTINFO" 2>/dev/null
}

# ---------------------------------------------------------------------------
echo "== cenário 1: prepare feliz"
setup_root
run_script 0 "prepare com APKs válidos" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" || true
[ "$(mount_count)" = "3" ] && ok "3 mounts registrados" || bad "esperava 3 mounts, há $(mount_count)"
[ -f "$MASK_BASE/gms/GmsCore.apk" ] && ok "máscara GMS com APK" || bad "máscara GMS sem APK"
[ -f "$MASK_BASE/store/Companion.apk" ] && ok "máscara store com APK" || bad "máscara store sem APK"
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
# mocka mounts ativos como se fossem nossos
printf '0 0 0:0 %s %s rw - ext4 /dev/root rw\n' "$MASK_BASE/gms" "$PROF_GMS" >> "$MOUNTINFO"
printf '0 0 0:0 %s %s rw - ext4 /dev/root rw\n' "$MASK_BASE/gsf" "$PROF_GSF" >> "$MOUNTINFO"
printf '0 0 0:0 %s %s rw - ext4 /dev/root rw\n' "$MASK_BASE/store" "$PROF_STORE" >> "$MOUNTINFO"
[ -f "$PM_CACHE/package.odex" ] && ok "cache do PM presente antes" || bad "cache do PM ausente antes"
run_script 0 "restore-stock move o cache do PM" restore-stock --wipe-data >/dev/null || true
[ ! -f "$PM_CACHE/package.odex" ] && ok "cache do PM removido (re-scan forçado)" || bad "cache do PM ainda presente"
ls "$BACKUP_BASE"/pm-cache-*/package.odex >/dev/null 2>&1 && ok "cache do PM com backup preservado" || bad "backup do cache do PM ausente"
teardown_root

echo "== cenário 11: restore-stock com GMS ocupado → umount -l (lazy)"
setup_root
run_script 0 "prepare (baseline)" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
printf '0 0 0:0 %s %s rw - ext4 /dev/root rw\n' "$MASK_BASE/gms" "$PROF_GMS" >> "$MOUNTINFO"
printf '0 0 0:0 %s %s rw - ext4 /dev/root rw\n' "$MASK_BASE/gsf" "$PROF_GSF" >> "$MOUNTINFO"
printf '0 0 0:0 %s %s rw - ext4 /dev/root rw\n' "$MASK_BASE/store" "$PROF_STORE" >> "$MOUNTINFO"
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
ALT_GMS_DIR="$PROF_GMS/alt"
mkdir -p "$ALT_GMS_DIR"
printf 'stock-alt\n' > "$ALT_GMS_DIR/base.apk"
DEGOOGLE_EXPERIMENTAL=1 PM_GMS_PATH="$ALT_GMS_DIR/base.apk" \
    run_script 0 "prepare experimental com GMS em diretório descoberto" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" || true
[ "$(mount_count)" = "3" ] && ok "opt-in aplicou os 3 mounts" || bad "opt-in deixou $(mount_count) mounts"
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
mkdir -p "$MASK_BASE/gms" "$MASK_BASE/gsf" "$MASK_BASE/store"
cp "$ROOT/gms.apk" "$MASK_BASE/gms/GmsCore.apk"
cp "$ROOT/companion.apk" "$MASK_BASE/store/Companion.apk"
printf '0 0 0:0 %s %s rw - ext4 /dev/root rw\n' "$MASK_BASE/gms" "$PROF_GMS" >> "$MOUNTINFO"
printf '0 0 0:0 %s %s rw - ext4 /dev/root rw\n' "$MASK_BASE/gsf" "$PROF_GSF" >> "$MOUNTINFO"
printf '0 0 0:0 %s %s rw - ext4 /dev/root rw\n' "$MASK_BASE/store" "$PROF_STORE" >> "$MOUNTINFO"
PM_GSF_MISSING=1 PM_STORE_MISSING=1 run_script 0 "restore-stock não depende de pm path para resolver targets" restore-stock >/dev/null || true
[ "$(mount_count)" = "0" ] && ok "GSF/Store desmontados mesmo ausentes do PM" || bad "mounts remanescentes: $(mount_count)"
[ ! -f "$MASK_BASE/gms/GmsCore.apk" ] && [ ! -f "$MASK_BASE/store/Companion.apk" ] && ok "APKs removidos somente após unmount" || bad "APKs da máscara permanecem"
grep -q '^state=REINDEX_PENDING$' "$BACKUP_BASE/transaction/journal" && ok "journal marca reindexamento pendente" || bad "journal não marca REINDEX_PENDING"
teardown_root

echo "== cenário 19: rollback usa target dinâmico persistido no snapshot"
setup_root
ALT_GMS_DIR="$PROF_GMS/alt"
mkdir -p "$ALT_GMS_DIR"
printf 'stock-alt\n' > "$ALT_GMS_DIR/base.apk"
DEGOOGLE_EXPERIMENTAL=1 PM_GMS_PATH="$ALT_GMS_DIR/base.apk" run_script 0 "prepare cria snapshot com target dinâmico" prepare "$ROOT/gms.apk" "$ROOT/companion.apk" >/dev/null || true
PM_GMS_PATH="$ALT_GMS_DIR/base.apk" PM_GSF_MISSING=1 PM_STORE_MISSING=1 \
    run_script 0 "restore-stock usa snapshot mesmo com GSF/Store ausentes" restore-stock >/dev/null || true
[ "$(mount_count)" = "0" ] && ok "target dinâmico desmontado pelo snapshot" || bad "target dinâmico permaneceu montado"
teardown_root

echo "== cenário 20: updates legítimos em /data/app"
setup_root
PM_GMS_PATH="/data/app/~~gms/com.google.android.gms-1/base.apk" \
PM_STORE_PATH="/data/app/~~store/com.android.vending-1/base.apk" \
    run_script 0 "probe classifica updates como stock" probe > "$ROOT/update_probe.out" || true
grep -q '^DEGOOGLE_STATE=STOCK$' "$ROOT/update_probe.out" && ok "updates em /data/app resultam em STOCK" || bad "updates em /data/app resultaram em estado inesperado"
grep -q '^DEGOOGLE_GMS_HAS_DATA_UPDATE=1$' "$ROOT/update_probe.out" && \
    grep -q '^DEGOOGLE_STORE_HAS_DATA_UPDATE=1$' "$ROOT/update_probe.out" && \
    ok "locator confirmou os dois updates" || bad "locator não confirmou os updates"
PM_GMS_PATH="/data/app/~~gms/com.google.android.gms-1/base.apk" \
PM_STORE_PATH="/data/app/~~store/com.android.vending-1/base.apk" \
    run_script 0 "dry-run aceita updates removíveis" dry-run > "$ROOT/update_dry_run.out" || true
grep -q '^DEGOOGLE_CAP_GMS_MASKABLE_STATUS=PASS$' "$ROOT/update_dry_run.out" && \
    grep -q '^DEGOOGLE_CAP_STORE_MASKABLE_STATUS=PASS$' "$ROOT/update_dry_run.out" && \
    ok "GMS/Store update não geram WARN de maskabilidade" || bad "GMS/Store update ainda geram WARN"
grep -q 'update em /data/app será removido durante a preparação' "$ROOT/update_dry_run.out" && \
    ok "dry-run informa o cleanup automático" || bad "dry-run não informa o cleanup automático"
PM_GMS_PATH="/data/app/~~gms/com.google.android.gms-1/base.apk" \
PM_STORE_PATH="/data/app/~~store/com.android.vending-1/base.apk" \
    run_script 0 "preflight aceita update antes do cleanup" preflight >/dev/null || true
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

# ---------------------------------------------------------------------------
echo
echo "RESULTADO: $PASS passaram, $FAIL falharam"
[ "$FAIL" = "0" ]
