#!/system/bin/sh

# Galaxy S24 Ultra - ambiente microG temporário
#
# Uso:
#   su -c '/data/local/tmp/microg-session.sh prep [APK]'
#   [FAZER SOFT REBOOT]
#   su -c '/data/local/tmp/microg-session.sh install'
#
# Outros:
#   su -c '/data/local/tmp/microg-session.sh backup'
#   su -c '/data/local/tmp/microg-session.sh store install [APK]'
#   su -c '/data/local/tmp/microg-session.sh store enable'
#   su -c '/data/local/tmp/microg-session.sh status'
#   su -c '/data/local/tmp/microg-session.sh restore'
#
# APK padrão:
#   /sdcard/Download/microg.apk
#
# IMPORTANTE: o microG é copiado para DENTRO da máscara
# (/product/priv-app/GmsCore via bind mount), então o sistema o
# registra como priv-app e concede as permissões privileged
# (ex.: INTERACT_ACROSS_USERS). Instalar via pm install como user
# app causa crash do SingletonComponentRouterProvider (FLAG_SINGLE_USER)
# e quebra o FCM/push.
#
# O prep faz backup dos dados do GMS (registros FCM). O install
# restaura esses dados após o boot, preservando a identidade de
# registro — sem isso, os apps de mensagem perdem o push a cada
# ciclo de reinstalação.


GMS_PKG="com.google.android.gms"
GSF_PKG="com.google.android.gsf"
STORE_PKG="com.android.vending"

GMS_SYSTEM="/product/priv-app/GmsCore"
GSF_SYSTEM="/system_ext/priv-app/GoogleServicesFramework"

MASK_BASE="/data/local/tmp/microg-mask"
MASK_GMS="$MASK_BASE/gms"
MASK_GSF="$MASK_BASE/gsf"

BACKUP_BASE="/data/local/tmp/microg-backup"
BACKUP_GMS_USER="$BACKUP_BASE/gms-user0"
BACKUP_GMS_DE="$BACKUP_BASE/gms-userde"

STORE_SYSTEM="/product/priv-app/Phonesky"
MASK_STORE="$MASK_BASE/store"

MICROG_DEFAULT="/sdcard/Download/microg.apk"
FAKESTORE_DEFAULT="/sdcard/Download/fakestore.apk"


die()
{
    echo
    echo "ERRO: $*"
    exit 1
}


check_root()
{
    [ "$(id -u)" = "0" ] || die "Execute este script com su -c."
}


pkg_path()
{
    pm path "$1" 2>/dev/null || true
}


global()
{
    nsenter --mount=/proc/1/ns/mnt -- "$@"
}


is_global_mount()
{
    grep -Fq " $1 " /proc/1/mountinfo 2>/dev/null
}


gms_uid()
{
    dumpsys package "$GMS_PKG" 2>/dev/null |
        sed -n 's/.*userId=\([0-9][0-9]*\).*/\1/p' |
        head -1
}


backup_gms()
{
    check_root

    if [ "${1:-}" != "quiet" ]; then
        echo "===== BACKUP DOS DADOS DO microG ====="
        echo
    fi

    am force-stop "$GMS_PKG" 2>/dev/null || true

    mkdir -p "$BACKUP_BASE" ||
        die "Não consegui criar $BACKUP_BASE."

    if [ ! -d /data/user/0/com.google.android.gms ]; then
        echo "  GMS sem dados (nada a copiar)."
        return 0
    fi

    rm -rf "$BACKUP_GMS_USER" "$BACKUP_GMS_DE"

    cp -a /data/user/0/com.google.android.gms "$BACKUP_GMS_USER" ||
        die "Falhou ao copiar dados de /data/user/0."

    if [ -d /data/user_de/0/com.google.android.gms ]; then
        cp -a /data/user_de/0/com.google.android.gms "$BACKUP_GMS_DE" ||
            die "Falhou ao copiar dados de /data/user_de/0."
    fi

    echo "  uid original: $(stat -c %u /data/user/0/com.google.android.gms 2>/dev/null)"
    du -sh "$BACKUP_GMS_USER" 2>/dev/null | sed 's/^/  /'
    [ -d "$BACKUP_GMS_DE" ] &&
        du -sh "$BACKUP_GMS_DE" 2>/dev/null | sed 's/^/  /'

    if [ "${1:-}" != "quiet" ]; then
        echo
        echo "BACKUP CONCLUÍDO."
    fi
}


restore_gms()
{
    check_root

    [ -d "$BACKUP_GMS_USER" ] || return 0

    echo "===== RESTAURANDO DADOS DO microG ====="
    echo

    am force-stop "$GMS_PKG" 2>/dev/null || true

    NEW_UID="$(stat -c %u /data/user/0/com.google.android.gms 2>/dev/null)"
    [ -n "$NEW_UID" ] || NEW_UID="$(gms_uid)"
    [ -n "$NEW_UID" ] || die "Não consegui descobrir o uid atual do GMS."

    rm -rf /data/user/0/com.google.android.gms
    cp -a "$BACKUP_GMS_USER" /data/user/0/ ||
        die "Falha ao restaurar /data/user/0."

    if [ -d "$BACKUP_GMS_DE" ]; then
        rm -rf /data/user_de/0/com.google.android.gms
        cp -a "$BACKUP_GMS_DE" /data/user_de/0/ ||
            die "Falha ao restaurar /data/user_de/0."
    fi

    chown -R "$NEW_UID:$NEW_UID" /data/user/0/com.google.android.gms
    [ -d /data/user_de/0/com.google.android.gms ] &&
        chown -R "$NEW_UID:$NEW_UID" /data/user_de/0/com.google.android.gms

    chcon -R u:object_r:privapp_data_file:s0:c512,c768 \
        /data/user/0/com.google.android.gms 2>/dev/null || true
    chcon -R u:object_r:privapp_data_file:s0:c512,c768 \
        /data/user_de/0/com.google.android.gms 2>/dev/null || true

    echo "  Restaurado com uid $NEW_UID:"
    du -sh /data/user/0/com.google.android.gms 2>/dev/null | sed 's/^/  /'
    [ -d /data/user_de/0/com.google.android.gms ] &&
        du -sh /data/user_de/0/com.google.android.gms 2>/dev/null | sed 's/^/  /'

    echo
    echo "RESTAURAÇÃO CONCLUÍDA."
}


store_install()
{
    check_root

    SRC="${1:-$FAKESTORE_DEFAULT}"

    echo "===== INSTALAÇÃO FAKESTORE ====="
    echo

    [ -d "$STORE_SYSTEM" ] ||
        die "Não encontrei $STORE_SYSTEM"

    [ -f "$SRC" ] ||
        die "APK não encontrado: $SRC"

    mkdir -p "$MASK_STORE" ||
        die "Não consegui criar $MASK_STORE."

    if ! is_global_mount "$STORE_SYSTEM"; then

        echo "[Fase 1/2] Mascarando a loja stock (vazia)..."
        echo
        echo "  O registro do com.android.vending será removido"
        echo "  no próximo boot (o APK some do sistema)."

        rm -f "$MASK_STORE/FakeStore.apk"

        global mount --bind "$MASK_STORE" "$STORE_SYSTEM" ||
            die "Falhou ao mascarar a loja."

        echo
        echo "=========================================="
        echo "FASE 1 CONCLUÍDA."
        echo
        echo "Agora faça o SOFT REBOOT."
        echo
        echo "Depois rode novamente:"
        echo
        echo "  su -c '/data/local/tmp/microg-session.sh store install'"
        echo
        echo "=========================================="
        return 0
    fi

    if [ ! -f "$MASK_STORE/FakeStore.apk" ]; then

        echo "[Fase 2/2] Copiando FakeStore para a máscara..."

        cp "$SRC" "$MASK_STORE/FakeStore.apk" ||
            die "Não consegui copiar o APK para a máscara."

        chown root:root "$MASK_STORE/FakeStore.apk"
        chmod 0644 "$MASK_STORE/FakeStore.apk"

        restorecon -R "$STORE_SYSTEM" 2>/dev/null || true

        echo "Verificação:"
        global ls -laZ "$STORE_SYSTEM"

        echo
        echo "=========================================="
        echo "FASE 2 CONCLUÍDA."
        echo
        echo "O com.android.vending será registrado como"
        echo "priv-app no próximo boot."
        echo
        echo "Agora faça o SOFT REBOOT."
        echo
        echo "Depois rode:"
        echo
        echo "  su -c '/data/local/tmp/microg-session.sh store enable'"
        echo
        echo "=========================================="
    else
        echo "[Fase 2/2] FakeStore já está na máscara."
        echo
        global ls -laZ "$STORE_SYSTEM"
    fi
}


store_enable()
{
    check_root

    echo "===== CONFIGURAÇÃO FAKESTORE ====="
    echo

    OUT="$(pkg_path "$STORE_PKG")"

    if [ -z "$OUT" ]; then
        die "com.android.vending não está registrado. Rode store install primeiro."
    fi

    echo "$OUT"

    echo
    echo "[habilitando e dando permissões]"

    pm enable "$STORE_PKG" 2>/dev/null || true

    pm grant "$STORE_PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

    cmd deviceidle whitelist +"$STORE_PKG" 2>/dev/null || true
    cmd appops set "$STORE_PKG" RUN_IN_BACKGROUND allow 2>/dev/null || true
    cmd appops set "$STORE_PKG" RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true

    echo
    dumpsys package "$STORE_PKG" 2>/dev/null |
        grep -m1 "versionName=" |
        sed 's/^/  /'

    echo
    echo "FAKESTORE CONFIGURADA."
}


store_restore()
{
    check_root

    echo "===== RESTAURANDO LOJA STOCK ====="
    echo

    echo "[1/2] Limpando APK da máscara..."

    rm -f "$MASK_STORE/FakeStore.apk" || true

    echo
    echo "[2/2] Revelando a loja stock..."

    if is_global_mount "$STORE_SYSTEM"; then
        global umount "$STORE_SYSTEM" || true
    fi

    echo
    echo "Agora faça um SOFT REBOOT."
}


show_pkg()
{
    PKG="$1"
    echo
    echo "[$PKG]"

    OUT="$(pkg_path "$PKG")"

    if [ -z "$OUT" ]; then
        echo "  AUSENTE"
    else
        echo "$OUT" | sed 's/^/  /'
    fi
}


status()
{
    check_root

    echo "===== STATUS microG ====="

    show_pkg "$GMS_PKG"
    show_pkg "$GSF_PKG"
    show_pkg "$STORE_PKG"

    echo
    echo "[GMS registrado de]"
    dumpsys package "$GMS_PKG" 2>/dev/null |
        grep -m1 "codePath=" |
        sed 's/^/  /'

    echo
    echo "[flags do GMS]"
    dumpsys package "$GMS_PKG" 2>/dev/null |
        grep -m1 "pkgFlags=" |
        sed 's/^/  /'

    echo
    echo "[backup de dados]"
    if [ -d "$BACKUP_GMS_USER" ]; then
        du -sh "$BACKUP_GMS_USER" 2>/dev/null | sed 's/^/  /'
    else
        echo "  ausente (rode: backup)"
    fi

    echo
    echo "[APK na máscara]"
    if [ -f "$MASK_GMS/GmsCore.apk" ]; then
        echo "  presente"
    else
        echo "  ausente (o prep vai copiar)"
    fi

    echo
    echo "[mount GMS]"
    if is_global_mount "$GMS_SYSTEM"; then
        echo "  MASCARADO"
    else
        echo "  visível"
    fi

    echo
    echo "[mount GSF]"
    if is_global_mount "$GSF_SYSTEM"; then
        echo "  MASCARADO"
    else
        echo "  visível"
    fi

    echo
    echo "[versão GMS ativa]"
    dumpsys package "$GMS_PKG" 2>/dev/null |
        grep -m1 "versionName=" |
        sed 's/^/  /'

    echo
    echo "[loja]"
    OUT="$(pkg_path "$STORE_PKG")"
    if [ -z "$OUT" ]; then
        echo "  AUSENTE"
    else
        echo "$OUT" | sed 's/^/  /'
        dumpsys package "$STORE_PKG" 2>/dev/null |
            grep -m1 "versionName=" |
            sed 's/^/  /'
    fi

    if [ -f "$MASK_STORE/FakeStore.apk" ]; then
        echo "  FakeStore na máscara: presente"
    fi

    if is_global_mount "$STORE_SYSTEM"; then
        echo "  mount: MASCARADO"
    else
        echo "  mount: visível"
    fi
}


prep()
{
    check_root

    SRC="${1:-$MICROG_DEFAULT}"

    echo "===== PREPARANDO AMBIENTE microG ====="
    echo

    [ -d "$GMS_SYSTEM" ] ||
        die "Não encontrei $GMS_SYSTEM"

    [ -d "$GSF_SYSTEM" ] ||
        die "Não encontrei $GSF_SYSTEM"

    [ -f "$SRC" ] ||
        die "APK não encontrado: $SRC"

echo "[1/8] Parando Play Store e GMS..."

if is_global_mount "$STORE_SYSTEM"; then
    echo "  Loja mascarada — não mexo nela."
else
    am force-stop "$STORE_PKG" 2>/dev/null || true
    pm disable-user --user 0 "$STORE_PKG" >/dev/null 2>&1 || true
fi

am force-stop "$GMS_PKG" 2>/dev/null || true


    echo
    echo "[2/8] Backup dos dados atuais do GMS (registros FCM)..."

    backup_gms quiet


    echo "[3/8] Estado atual do GMS:"

    CURRENT="$(pkg_path "$GMS_PKG")"

    if [ -n "$CURRENT" ]; then
        echo "$CURRENT"
    else
        echo "GMS não encontrado."
    fi


    # Depois de reboot completo normalmente existe novamente
    # a atualização oficial em /data/app.
    if echo "$CURRENT" | grep -q "/data/app/"; then

        echo
        echo "[4/8] Removendo versão de /data/app para voltar ao GMS base..."

        pm uninstall "$GMS_PKG" ||
            die "Não consegui remover a versão de /data/app."

        sleep 2

    else
        echo
        echo "[4/8] Nenhuma versão /data/app para remover."
    fi


    echo
    echo "[5/8] Conferindo GMS base..."

    BASE="$(pkg_path "$GMS_PKG")"

    echo "$BASE"

    echo "$BASE" | grep -q "$GMS_SYSTEM" ||
        die "O GMS não voltou para $GMS_SYSTEM. Não vou continuar."


    echo
    echo "[6/8] Criando máscaras e copiando APK do microG..."

    mkdir -p "$MASK_GMS" "$MASK_GSF" ||
        die "Não consegui criar diretórios de máscara."

    cp "$SRC" "$MASK_GMS/GmsCore.apk" ||
        die "Não consegui copiar o APK para a máscara."

    chown root:root "$MASK_GMS/GmsCore.apk"
    chmod 0644 "$MASK_GMS/GmsCore.apk"

    echo "APK na máscara: $MASK_GMS/GmsCore.apk"


    echo
    echo "[7/8] Aplicando bind mounts globais..."

    if ! is_global_mount "$GMS_SYSTEM"; then
        global mount --bind "$MASK_GMS" "$GMS_SYSTEM" ||
            die "Falhou ao mascarar GMS."
    else
        echo "GMS já estava mascarado."
    fi

    if ! is_global_mount "$GSF_SYSTEM"; then
        global mount --bind "$MASK_GSF" "$GSF_SYSTEM" ||
            die "Falhou ao mascarar GSF."
    else
        echo "GSF já estava mascarado."
    fi


    echo
    echo "[8/8] Ajustando SELinux do APK na máscara..."

    # O PM só registra o APK como priv-app se o contexto for system_file.
    restorecon -R "$GMS_SYSTEM" 2>/dev/null || true

    echo "Verificação física:"
    echo
    echo "--- GMS ---"
    global ls -laZ "$GMS_SYSTEM"

    echo
    echo "--- GSF ---"
    global ls -la "$GSF_SYSTEM"


    echo
    echo "=========================================="
    echo "PREPARAÇÃO CONCLUÍDA."
    echo
    echo "O microG será registrado como priv-app no próximo boot."
    echo
    echo "Agora faça o SOFT REBOOT."
    echo
    echo "Depois execute:"
    echo
    echo "  su -c '/data/local/tmp/microg-session.sh install'"
    echo
    echo "=========================================="
}


install_microg()
{
    check_root

    echo "===== CONFIGURAÇÃO PÓS-BOOT microG ====="
    echo

    echo "[1/7] Verificando se o GMS está como priv-app..."

    GMS_NOW="$(pkg_path "$GMS_PKG")"

    if echo "$GMS_NOW" | grep -q "/data/app/"; then
        echo "$GMS_NOW"
        die "GMS está como user app — isso causa crash do \
SingletonComponentRouterProvider (FLAG_SINGLE_USER sem \
INTERACT_ACROSS_USERS) e quebra o FCM. Rode prep + soft reboot."
    fi

    echo "$GMS_NOW"

    echo "$GMS_NOW" | grep -q "$GMS_SYSTEM" ||
        die "GMS não registrado de $GMS_SYSTEM. O prep não foi concluído?"

    echo "OK: GMS registrado como priv-app."


    echo
    echo "[2/7] Verificando se o GSF stock sumiu..."

    GSF_NOW="$(pkg_path "$GSF_PKG")"

    if [ -n "$GSF_NOW" ]; then
        echo "$GSF_NOW"
        die "com.google.android.gsf ainda está registrado. A máscara falhou."
    fi

    echo "OK: GSF ausente."


    echo
    echo "[3/7] Restaurando dados do GMS (identidade FCM)..."

    restore_gms


    echo
    echo "[4/7] Garantindo permissões importantes..."

    # localização
    pm grant "$GMS_PKG" android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true
    pm grant "$GMS_PKG" android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
    pm grant "$GMS_PKG" android.permission.ACCESS_BACKGROUND_LOCATION 2>/dev/null || true

    # telefone / contatos
    pm grant "$GMS_PKG" android.permission.READ_PHONE_STATE 2>/dev/null || true
    pm grant "$GMS_PKG" android.permission.READ_CONTACTS 2>/dev/null || true

    # notificações
    pm grant "$GMS_PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

    # câmera
    pm grant "$GMS_PKG" android.permission.CAMERA 2>/dev/null || true

    # armazenamento legado, quando aplicável
    pm grant "$GMS_PKG" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
    pm grant "$GMS_PKG" android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true


    echo
    echo "[5/7] Liberando execução em segundo plano..."

    # tirar microG da otimização agressiva de bateria
    cmd deviceidle whitelist +"$GMS_PKG" 2>/dev/null || true

    # appops relevantes
    cmd appops set "$GMS_PKG" RUN_IN_BACKGROUND allow 2>/dev/null || true
    cmd appops set "$GMS_PKG" RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true

    # localização em background, se existir nesta versão do Android
    cmd appops set "$GMS_PKG" ACCESS_BACKGROUND_LOCATION allow 2>/dev/null || true


    echo
    echo "[6/7] Blindando o WhatsApp contra o Doze..."

    cmd deviceidle whitelist +com.whatsapp 2>/dev/null || true
    cmd appops set com.whatsapp RUN_IN_BACKGROUND allow 2>/dev/null || true
    cmd appops set com.whatsapp RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true


    echo
    echo "[7/7] Conferindo configuração..."

    echo "Permissões granted:"
    dumpsys package "$GMS_PKG" |
        sed -n '/runtime permissions:/,/^\s*$/p' |
        grep -c "granted=true" |
        sed 's/^/  /'

    echo
    echo "===== DEVICE IDLE ====="

    dumpsys deviceidle whitelist |
        grep -E "$GMS_PKG|com.whatsapp" || true


    echo
    echo "=========================================="
    echo "microG PRIV-APP CONFIGURADO."
    echo
    echo "Abra:"
    echo "  microG Settings -> Auto-verificação"
    echo
    echo "=========================================="
}


restore()
{
    check_root

    echo "===== RESTAURANDO AMBIENTE STOCK ====="
    echo

    echo "[1/4] Removendo microG de /data (se houver)..."

    if [ -n "$(pkg_path "$GMS_PKG")" ]; then
        pm uninstall "$GMS_PKG" >/dev/null 2>&1 || true
    fi


    echo
    echo "[2/4] Limpando APK da máscara..."

    rm -f "$MASK_GMS/GmsCore.apk" || true


    echo
    echo "[3/4] Revelando GMS stock..."

    if is_global_mount "$GMS_SYSTEM"; then
        global umount "$GMS_SYSTEM" || true
    fi


    echo
    echo "[4/4] Revelando GSF stock..."

    if is_global_mount "$GSF_SYSTEM"; then
        global umount "$GSF_SYSTEM" || true
    fi


    echo
    echo "Agora faça um SOFT REBOOT."
    echo
    echo "O backup em $BACKUP_BASE foi mantido — um novo prep"
    echo "o sobrescreve automaticamente."
    echo
    echo "Depois, se quiser a Play Store novamente:"
    echo
    echo "  su -c 'pm enable com.android.vending'"
}


case "${1:-}" in

    prep)
        prep "${2:-$MICROG_DEFAULT}"
        ;;

    install)
        install_microg
        ;;

    backup)
        backup_gms
        ;;

    store)
        case "${2:-}" in
            install)
                store_install "${3:-$FAKESTORE_DEFAULT}"
                ;;
            enable)
                store_enable
                ;;
            restore)
                store_restore
                ;;
            *)
                echo "Uso:"
                echo
                echo "  $0 store install [APK]"
                echo "  $0 store enable"
                echo "  $0 store restore"
                exit 1
                ;;
        esac
        ;;

    status)
        status
        ;;

    restore)
        restore
        ;;

    *)
        echo "Uso:"
        echo
        echo "  $0 prep [APK]"
        echo "  $0 install"
        echo "  $0 backup"
        echo "  $0 store install [APK]"
        echo "  $0 store enable"
        echo "  $0 store restore"
        echo "  $0 status"
        echo "  $0 restore"
        exit 1
        ;;
esac