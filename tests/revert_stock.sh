#!/system/bin/sh
# revert_stock.sh — desfaz o estado criado pelo microg-session.sh original:
# desmonta as máscaras microg-mask, remove o Companion de /data/app e limpa
# os dados vivos do microG (o backup do script antigo é preservado).
# Uso: su -c 'sh /data/local/tmp/revert_stock.sh'

echo "[1/4] backup do script antigo:"
if [ -d /data/local/tmp/microg-backup ]; then
    echo "  preservado em /data/local/tmp/microg-backup (nao apagado)"
else
    echo "  nao existe"
fi

echo "[2/4] desmontando máscaras microg-mask (namespace global):"
for m in /product/priv-app/GmsCore /system_ext/priv-app/GoogleServicesFramework /product/priv-app/Phonesky; do
    if nsenter --mount=/proc/1/ns/mnt -- umount "$m" 2>/dev/null; then
        echo "  desmontado: $m"
    else
        echo "  FALHA ou ja desmontado: $m"
    fi
done

echo "[3/4] removendo Companion/Play Store de /data/app:"
pm uninstall com.android.vending 2>&1 | head -1

echo "[4/4] limpando dados vivos do microG (stock voltará limpo):"
rm -rf /data/user/0/com.google.android.gms /data/user_de/0/com.google.android.gms && echo "  dados removidos"

echo "--- verificação:"
echo "mounts microg-mask remanescentes: $(grep -c microg-mask /proc/1/mountinfo 2>/dev/null || echo 0)"
if pm path com.android.vending >/dev/null 2>&1; then
    echo "vending: ainda registrado ($(pm path com.android.vending 2>/dev/null))"
else
    echo "vending: ausente (ok)"
fi
