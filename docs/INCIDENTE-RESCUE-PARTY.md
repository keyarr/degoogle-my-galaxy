# Incidente: reboot completo via Rescue Party durante instalação do microG

**Data:** 15/08/2026 · **Aparelho:** SM-S928B (Galaxy S24 Ultra) · **Build:** Android 16 / One UI 8.5 (`S928BXXU5DZDP`) · **Root:** KernelSU

## Síntese

Durante o fluxo de instalação do microG (fase `STOCK → PREPARED → soft reboot`),
o aparelho executou um **reboot completo do kernel** logo após o soft reboot.
O reboot completo zerou os bind mounts das máscaras — o microG deixou de estar
ativo. Nada físico foi corrompido e o backup do microG foi preservado; o
aparelho foi recuperado ao STOCK real com o procedimento padrão.

## Linha do tempo

| Hora | Evento |
|---|---|
| ~17:44 | Usuário toca **[ DeGoogle ]** → download + validação OK → máscaras montadas → `PREPARED` |
| ~17:45 | Usuário toca **[ Soft Reboot ]** → `ksud soft-reboot` (1ª reinicialização de framework) |
| ~17:45 | `system_server` crasha com o bug crônico do firmware (`CachedAppOptimizer` NPE) |
| ~17:45 | Crash se repete o suficiente → Android ativa **Rescue Party** |
| ~17:46 | **Reboot completo do kernel** (uptime zerado: `up 1 min` ao verificar) |
| ~17:46 | Boot concluído; KernelSU volta a responder (~1 min depois) |
| ~17:47 | Diagnóstico: mounts 0/0/0; registro do PM stale (microG) |
| ~17:47 | Recuperação: `restore-stock --wipe-data` (moveu cache de parse do PM) + soft reboot |
| ~17:49 | **STOCK real verificado** (GMS 25.49.32, GSF, Phonesky; 0 mounts; backup presente) |

## Evidências

- **`getprop ro.boot.bootreason`** → `reboot,rescueparty`
  (também `sys.boot.reason`). O reason confirma que o reboot foi disparado
  pelo mecanismo Rescue Party do Android, não por usuário/app.
- **`/data/system/dropbox/system_server_crash@*.txt`** — stack do crash:
  ```
  java.lang.NullPointerException
    at com.android.server.am.CachedAppOptimizer.compactApp(...)
    at com.android.server.am.CachedAppOptimizer.onProcessFrozen(...)
    at CachedAppOptimizer$FreezeHandler.handleMessage(...)
  ```
- **`/data/system/dropbox/SYSTEM_RESTART@*.txt`** — múltiplos restarts de
  framework na mesma janela, padrão de falha recorrente que dispara o Rescue
  Party.
- **Crashes pré-existentes:** o mesmo crash aparece no dropbox **desde
  14/08/2026 11:17** (~25 ocorrências em 30 h), antes de qualquer uso do
  DeGoogle — confirma bug do firmware, não do app.

## Por que o Rescue Party é um risco específico do DeGoogle

O DeGoogle depende de bind mounts dinâmicos aplicados no mount namespace do
PID 1. **Soft reboot preserva os mounts; reboot completo (kernel) zera todos.**
O Rescue Party transforma o primeiro no segundo sem aviso, quando o crash do
firmware se repete.

Para o público-alvo (**root via exploit volátil**), o reboot completo é pior
ainda: destrói **o root e o microG** juntos.

## Recuperação (validada)

O aparelho ficou em "STOCK físico + registro stale do PM" (o PM continuava
reportando microG 0.3.15.250932 mesmo com o APK stock físico de volta, porque
não re-parseia system apps com codePath inalterado). O destravamento:

```bash
degoogle.sh restore-stock --wipe-data   # desmonta (se houver), move /data/system/package_cache
degoogle.sh soft-reboot                  # PM re-parseia os APKs físicos
```

Resultado: `DEGOOGLE_STATE=STOCK` com GMS/GSF/Phonesky stock e
`BACKUP_PRESENT=1` (backup no formato MicroG Session).

O fluxo foi incorporado ao app: depois de instalado, o `BootReceiver` roda o
probe no `BOOT_COMPLETED` e executa automaticamente `restore-stock --wipe-data`
quando encontra `RESTORE_PREPARED` ou uma máscara legada conhecida.
Ele preserva `/data/local/tmp/microg-backup/`, invalida o cache do Package
Manager e solicita apenas soft reboot. Resíduos conhecidos desmontados são
limpos separadamente; mounts externos continuam sendo recusados.

## Lições para o design

1. **O soft reboot é necessário mas não suficiente** para garantir a
   persistência: o Rescue Party pode escalar para reboot completo. O app já
   re-deriva o estado real após qualquer boot — comportamento correto, mantido.
2. **Mitigação candidata:** desabilitar o app freezer
   (`settings put global cached_apps_freezer 0`), componente que crasha, para
   reduzir a frequência do crash e a chance do Rescue Party. Em avaliação;
   trade-off: gerenciamento de memória de apps em cache muda.
3. **O backup sobrevive a tudo**: vive em `/data/local/tmp/microg-backup/`,
   que não é tocado por reboots. Restauração microG→microG permanece o caminho
   de recuperação para reinstalações.
4. **Estado stale do PM é esperado e recuperável**: nunca confiar em `pm path`
   sozinho após reboot completo; o app usa a máquina de estados completa
   (mounts + registro + físico).
