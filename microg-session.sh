#!/system/bin/sh

# Galaxy S24 Ultra - temporary microG environment
#
# Usage:
#   su -c '/data/local/tmp/microg-session.sh prep [APK]'
#   [PERFORM SOFT REBOOT]
#   su -c '/data/local/tmp/microg-session.sh install'
#
# Other commands:
#   su -c '/data/local/tmp/microg-session.sh backup'
#   su -c '/data/local/tmp/microg-session.sh store install [APK]'
#   su -c '/data/local/tmp/microg-session.sh store enable'
#   su -c '/data/local/tmp/microg-session.sh status'
#   su -c '/data/local/tmp/microg-session.sh restore'
#
# Default APK:
#   /sdcard/Download/microg.apk
#
# IMPORTANT: microG is copied INSIDE the mask
# (/product/priv-app/GmsCore via bind mount), so the system
# registers it as a priv-app and grants privileged permissions
# (for example, INTERACT_ACROSS_USERS). Installing with pm install as a user
# app causes SingletonComponentRouterProvider to crash (FLAG_SINGLE_USER)
# and breaks FCM/push.
#
# prep backs up GMS data (FCM registrations). install restores that data after
# boot, preserving registration identity. Without it, messaging apps lose push
# notifications after each reinstall.


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
    echo "ERROR: $*"
    exit 1
}


check_root()
{
    [ "$(id -u)" = "0" ] || die "Run this script with su -c."
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
        echo "===== BACKING UP microG DATA ====="
        echo
    fi

    am force-stop "$GMS_PKG" 2>/dev/null || true

    mkdir -p "$BACKUP_BASE" ||
        die "Could not create $BACKUP_BASE."

    if [ ! -d /data/user/0/com.google.android.gms ]; then
        echo "  GMS has no data (nothing to copy)."
        return 0
    fi

    rm -rf "$BACKUP_GMS_USER" "$BACKUP_GMS_DE"

    cp -a /data/user/0/com.google.android.gms "$BACKUP_GMS_USER" ||
        die "Could not copy data from /data/user/0."

    if [ -d /data/user_de/0/com.google.android.gms ]; then
        cp -a /data/user_de/0/com.google.android.gms "$BACKUP_GMS_DE" ||
            die "Could not copy data from /data/user_de/0."
    fi

    echo "  original UID: $(stat -c %u /data/user/0/com.google.android.gms 2>/dev/null)"
    du -sh "$BACKUP_GMS_USER" 2>/dev/null | sed 's/^/  /'
    [ -d "$BACKUP_GMS_DE" ] &&
        du -sh "$BACKUP_GMS_DE" 2>/dev/null | sed 's/^/  /'

    if [ "${1:-}" != "quiet" ]; then
        echo
        echo "BACKUP COMPLETE."
    fi
}


restore_gms()
{
    check_root

    [ -d "$BACKUP_GMS_USER" ] || return 0

    echo "===== RESTORING microG DATA ====="
    echo

    am force-stop "$GMS_PKG" 2>/dev/null || true

    NEW_UID="$(stat -c %u /data/user/0/com.google.android.gms 2>/dev/null)"
    [ -n "$NEW_UID" ] || NEW_UID="$(gms_uid)"
    [ -n "$NEW_UID" ] || die "Could not determine the current GMS UID."

    rm -rf /data/user/0/com.google.android.gms
    cp -a "$BACKUP_GMS_USER" /data/user/0/ ||
        die "Could not restore /data/user/0."

    if [ -d "$BACKUP_GMS_DE" ]; then
        rm -rf /data/user_de/0/com.google.android.gms
        cp -a "$BACKUP_GMS_DE" /data/user_de/0/ ||
            die "Could not restore /data/user_de/0."
    fi

    chown -R "$NEW_UID:$NEW_UID" /data/user/0/com.google.android.gms
    [ -d /data/user_de/0/com.google.android.gms ] &&
        chown -R "$NEW_UID:$NEW_UID" /data/user_de/0/com.google.android.gms

    chcon -R u:object_r:privapp_data_file:s0:c512,c768 \
        /data/user/0/com.google.android.gms 2>/dev/null || true
    chcon -R u:object_r:privapp_data_file:s0:c512,c768 \
        /data/user_de/0/com.google.android.gms 2>/dev/null || true

    echo "  Restored with UID $NEW_UID:"
    du -sh /data/user/0/com.google.android.gms 2>/dev/null | sed 's/^/  /'
    [ -d /data/user_de/0/com.google.android.gms ] &&
        du -sh /data/user_de/0/com.google.android.gms 2>/dev/null | sed 's/^/  /'

    echo
    echo "RESTORE COMPLETE."
}


store_install()
{
    check_root

    SRC="${1:-$FAKESTORE_DEFAULT}"

    echo "===== FAKESTORE INSTALLATION ====="
    echo

    [ -d "$STORE_SYSTEM" ] ||
        die "Could not find $STORE_SYSTEM"

    [ -f "$SRC" ] ||
        die "APK not found: $SRC"

    mkdir -p "$MASK_STORE" ||
        die "Could not create $MASK_STORE."

    if ! is_global_mount "$STORE_SYSTEM"; then

        echo "[Phase 1/2] Masking the stock store (empty)..."
        echo
        echo "  The com.android.vending registration will be removed"
        echo "  on the next boot (the APK will disappear from the system)."

        rm -f "$MASK_STORE/FakeStore.apk"

        global mount --bind "$MASK_STORE" "$STORE_SYSTEM" ||
            die "Could not mask the store."

        echo
        echo "=========================================="
        echo "PHASE 1 COMPLETE."
        echo
        echo "Now perform a SOFT REBOOT."
        echo
        echo "Run again:"
        echo
        echo "  su -c '/data/local/tmp/microg-session.sh store install'"
        echo
        echo "=========================================="
        return 0
    fi

    if [ ! -f "$MASK_STORE/FakeStore.apk" ]; then

        echo "[Phase 2/2] Copying FakeStore to the mask..."

        cp "$SRC" "$MASK_STORE/FakeStore.apk" ||
            die "Could not copy the APK to the mask."

        chown root:root "$MASK_STORE/FakeStore.apk"
        chmod 0644 "$MASK_STORE/FakeStore.apk"

        restorecon -R "$STORE_SYSTEM" 2>/dev/null || true

        echo "Verification:"
        global ls -laZ "$STORE_SYSTEM"

        echo
        echo "=========================================="
        echo "PHASE 2 COMPLETE."
        echo
        echo "com.android.vending will be registered as a"
        echo "priv-app on the next boot."
        echo
        echo "Now perform a SOFT REBOOT."
        echo
        echo "Run:"
        echo
        echo "  su -c '/data/local/tmp/microg-session.sh store enable'"
        echo
        echo "=========================================="
    else
        echo "[Phase 2/2] FakeStore is already in the mask."
        echo
        global ls -laZ "$STORE_SYSTEM"
    fi
}


store_enable()
{
    check_root

    echo "===== FAKESTORE CONFIGURATION ====="
    echo

    OUT="$(pkg_path "$STORE_PKG")"

    if [ -z "$OUT" ]; then
        die "com.android.vending is not registered. Run store install first."
    fi

    echo "$OUT"

    echo
    echo "[enabling and granting permissions]"

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
    echo "FAKESTORE CONFIGURED."
}


store_restore()
{
    check_root

    echo "===== RESTORING STOCK STORE ====="
    echo

    echo "[1/2] Removing APK from the mask..."

    rm -f "$MASK_STORE/FakeStore.apk" || true

    echo
    echo "[2/2] Revealing the stock store..."

    if is_global_mount "$STORE_SYSTEM"; then
        global umount "$STORE_SYSTEM" || true
    fi

    echo
        echo "Now perform a SOFT REBOOT."
}


show_pkg()
{
    PKG="$1"
    echo
    echo "[$PKG]"

    OUT="$(pkg_path "$PKG")"

    if [ -z "$OUT" ]; then
        echo "  MISSING"
    else
        echo "$OUT" | sed 's/^/  /'
    fi
}


status()
{
    check_root

    echo "===== microG STATUS ====="

    show_pkg "$GMS_PKG"
    show_pkg "$GSF_PKG"
    show_pkg "$STORE_PKG"

    echo
    echo "[GMS registered from]"
    dumpsys package "$GMS_PKG" 2>/dev/null |
        grep -m1 "codePath=" |
        sed 's/^/  /'

    echo
    echo "[GMS flags]"
    dumpsys package "$GMS_PKG" 2>/dev/null |
        grep -m1 "pkgFlags=" |
        sed 's/^/  /'

    echo
    echo "[data backup]"
    if [ -d "$BACKUP_GMS_USER" ]; then
        du -sh "$BACKUP_GMS_USER" 2>/dev/null | sed 's/^/  /'
    else
        echo "  missing (run: backup)"
    fi

    echo
    echo "[APK in mask]"
    if [ -f "$MASK_GMS/GmsCore.apk" ]; then
        echo "  present"
    else
        echo "  missing (prep will copy it)"
    fi

    echo
    echo "[GMS mount]"
    if is_global_mount "$GMS_SYSTEM"; then
        echo "  MASKED"
    else
        echo "  visible"
    fi

    echo
    echo "[GSF mount]"
    if is_global_mount "$GSF_SYSTEM"; then
        echo "  MASKED"
    else
        echo "  visible"
    fi

    echo
    echo "[active GMS version]"
    dumpsys package "$GMS_PKG" 2>/dev/null |
        grep -m1 "versionName=" |
        sed 's/^/  /'

    echo
    echo "[store]"
    OUT="$(pkg_path "$STORE_PKG")"
    if [ -z "$OUT" ]; then
        echo "  MISSING"
    else
        echo "$OUT" | sed 's/^/  /'
        dumpsys package "$STORE_PKG" 2>/dev/null |
            grep -m1 "versionName=" |
            sed 's/^/  /'
    fi

    if [ -f "$MASK_STORE/FakeStore.apk" ]; then
        echo "  FakeStore in mask: present"
    fi

    if is_global_mount "$STORE_SYSTEM"; then
        echo "  mount: MASKED"
    else
        echo "  mount: visible"
    fi
}


prep()
{
    check_root

    SRC="${1:-$MICROG_DEFAULT}"

    echo "===== PREPARING microG ENVIRONMENT ====="
    echo

    [ -d "$GMS_SYSTEM" ] ||
        die "Could not find $GMS_SYSTEM"

    [ -d "$GSF_SYSTEM" ] ||
        die "Could not find $GSF_SYSTEM"

    [ -f "$SRC" ] ||
        die "APK not found: $SRC"

echo "[1/8] Stopping Play Store and GMS..."

if is_global_mount "$STORE_SYSTEM"; then
    echo "  Store is masked; leaving it unchanged."
else
    am force-stop "$STORE_PKG" 2>/dev/null || true
    pm disable-user --user 0 "$STORE_PKG" >/dev/null 2>&1 || true
fi

am force-stop "$GMS_PKG" 2>/dev/null || true


    echo
    echo "[2/8] Backing up current GMS data (FCM registrations)..."

    backup_gms quiet


    echo "[3/8] Current GMS state:"

    CURRENT="$(pkg_path "$GMS_PKG")"

    if [ -n "$CURRENT" ]; then
        echo "$CURRENT"
    else
        echo "GMS not found."
    fi


    # After a full reboot, the official
    # update in /data/app is usually present again.
    if echo "$CURRENT" | grep -q "/data/app/"; then

        echo
        echo "[4/8] Removing the /data/app version to return to base GMS..."

        pm uninstall "$GMS_PKG" ||
            die "Could not remove the /data/app version."

        sleep 2

    else
        echo
        echo "[4/8] No /data/app version to remove."
    fi


    echo
    echo "[5/8] Checking base GMS..."

    BASE="$(pkg_path "$GMS_PKG")"

    echo "$BASE"

    echo "$BASE" | grep -q "$GMS_SYSTEM" ||
        die "GMS did not return to $GMS_SYSTEM. Will not continue."


    echo
    echo "[6/8] Creating masks and copying the microG APK..."

    mkdir -p "$MASK_GMS" "$MASK_GSF" ||
        die "Could not create mask directories."

    cp "$SRC" "$MASK_GMS/GmsCore.apk" ||
        die "Could not copy the APK to the mask."

    chown root:root "$MASK_GMS/GmsCore.apk"
    chmod 0644 "$MASK_GMS/GmsCore.apk"

    echo "APK in mask: $MASK_GMS/GmsCore.apk"


    echo
    echo "[7/8] Applying global bind mounts..."

    if ! is_global_mount "$GMS_SYSTEM"; then
        global mount --bind "$MASK_GMS" "$GMS_SYSTEM" ||
            die "Could not mask GMS."
    else
        echo "GMS was already masked."
    fi

    if ! is_global_mount "$GSF_SYSTEM"; then
        global mount --bind "$MASK_GSF" "$GSF_SYSTEM" ||
            die "Could not mask GSF."
    else
        echo "GSF was already masked."
    fi


    echo
    echo "[8/8] Adjusting SELinux for the APK in the mask..."

    # PM registers the APK as a priv-app only when its context is system_file.
    restorecon -R "$GMS_SYSTEM" 2>/dev/null || true

    echo "On-disk verification:"
    echo
    echo "--- GMS ---"
    global ls -laZ "$GMS_SYSTEM"

    echo
    echo "--- GSF ---"
    global ls -la "$GSF_SYSTEM"


    echo
    echo "=========================================="
    echo "PREPARATION COMPLETE."
    echo
    echo "microG will be registered as a priv-app on the next boot."
    echo
    echo "Now perform a SOFT REBOOT."
    echo
    echo "Run:"
    echo
    echo "  su -c '/data/local/tmp/microg-session.sh install'"
    echo
    echo "=========================================="
}


install_microg()
{
    check_root

    echo "===== POST-BOOT microG CONFIGURATION ====="
    echo

    echo "[1/7] Checking that GMS is a priv-app..."

    GMS_NOW="$(pkg_path "$GMS_PKG")"

    if echo "$GMS_NOW" | grep -q "/data/app/"; then
        echo "$GMS_NOW"
        die "GMS is a user app, which causes \
SingletonComponentRouterProvider (FLAG_SINGLE_USER sem \
INTERACT_ACROSS_USERS) and breaks FCM. Run prep + soft reboot."
    fi

    echo "$GMS_NOW"

    echo "$GMS_NOW" | grep -q "$GMS_SYSTEM" ||
        die "GMS is not registered from $GMS_SYSTEM. Did prep fail?"

    echo "OK: GMS registered as a priv-app."


    echo
    echo "[2/7] Checking that stock GSF is gone..."

    GSF_NOW="$(pkg_path "$GSF_PKG")"

    if [ -n "$GSF_NOW" ]; then
        echo "$GSF_NOW"
        die "com.google.android.gsf is still registered. Masking failed."
    fi

    echo "OK: GSF is missing."


    echo
    echo "[3/7] Restoring GMS data (FCM identity)..."

    restore_gms


    echo
    echo "[4/7] Ensuring required permissions..."

    # Location
    pm grant "$GMS_PKG" android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true
    pm grant "$GMS_PKG" android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
    pm grant "$GMS_PKG" android.permission.ACCESS_BACKGROUND_LOCATION 2>/dev/null || true

    # Phone / contacts
    pm grant "$GMS_PKG" android.permission.READ_PHONE_STATE 2>/dev/null || true
    pm grant "$GMS_PKG" android.permission.READ_CONTACTS 2>/dev/null || true

    # Notifications
    pm grant "$GMS_PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

    # Camera
    pm grant "$GMS_PKG" android.permission.CAMERA 2>/dev/null || true

    # Legacy storage, when applicable
    pm grant "$GMS_PKG" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
    pm grant "$GMS_PKG" android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true


    echo
    echo "[5/7] Allowing background execution..."

    # Exclude microG from aggressive battery optimization
    cmd deviceidle whitelist +"$GMS_PKG" 2>/dev/null || true

    # Relevant app ops
    cmd appops set "$GMS_PKG" RUN_IN_BACKGROUND allow 2>/dev/null || true
    cmd appops set "$GMS_PKG" RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true

    # Background location, if available in this Android version
    cmd appops set "$GMS_PKG" ACCESS_BACKGROUND_LOCATION allow 2>/dev/null || true


    echo
    echo "[6/7] Exempting WhatsApp from Doze..."

    cmd deviceidle whitelist +com.whatsapp 2>/dev/null || true
    cmd appops set com.whatsapp RUN_IN_BACKGROUND allow 2>/dev/null || true
    cmd appops set com.whatsapp RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true


    echo
    echo "[7/7] Checking configuration..."

    echo "Granted permissions:"
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
    echo "microG PRIV-APP CONFIGURED."
    echo
    echo "Abra:"
    echo "  microG Settings -> Self-check"
    echo
    echo "=========================================="
}


restore()
{
    check_root

    echo "===== RESTORING STOCK ENVIRONMENT ====="
    echo

    echo "[1/4] Removing microG from /data (if present)..."

    if [ -n "$(pkg_path "$GMS_PKG")" ]; then
        pm uninstall "$GMS_PKG" >/dev/null 2>&1 || true
    fi


    echo
    echo "[2/4] Removing APK from the mask..."

    rm -f "$MASK_GMS/GmsCore.apk" || true


    echo
    echo "[3/4] Revealing stock GMS..."

    if is_global_mount "$GMS_SYSTEM"; then
        global umount "$GMS_SYSTEM" || true
    fi


    echo
    echo "[4/4] Revealing stock GSF..."

    if is_global_mount "$GSF_SYSTEM"; then
        global umount "$GSF_SYSTEM" || true
    fi


    echo
    echo "Now perform a SOFT REBOOT."
    echo
    echo "The backup at $BACKUP_BASE was kept; a new prep"
    echo "will overwrite it automatically."
    echo
    echo "To re-enable Play Store later:"
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
                echo "Usage:"
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
        echo "Usage:"
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