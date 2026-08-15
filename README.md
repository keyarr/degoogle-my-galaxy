# DeGoogle

Aplicativo Android (root) que transforma o procedimento shell de substituição
temporária de Google Play Services / GSF / Play Store por **microG Services +
microG Companion** em uma experiência segura, verificável e quase one-click.

**v0.1 — status:** backend shell completo e testado (46/46 cenários no harness
de host) **e ciclo principal validado em aparelho real** (SM-S928B, One UI 16):
`STOCK → PREPARED → soft reboot → MICROG_ACTIVE → backup →
MICROG_ACTIVE_BACKED_UP → Restaurar Google → STOCK verificado`. Faltam
hardening e o teste de reinstalação com restauração automática do backup.

> ⚠️ Destinado exclusivamente a dispositivos com root (KernelSU/Magisk/APatch).
> Nenhuma alteração é feita sem provar root e perfil compatível. V1 suporta um
> único perfil: **Samsung Galaxy S24 Ultra (SM-S928x)**.
>
> 🚨 **Root via exploit:** o público-alvo inclui aparelhos cujo root vem de um
> exploit volátil. Nesse caso, um **reboot completo (kernel) perde o root e o
> microG**. O app usa **apenas soft reboot (userspace)** — `sys.powerctl
> reboot,userspace` — que preserva o kernel, os mounts e o root. O app **nunca**
> oferece reboot completo.

## Estrutura

```text
degoogle.sh                     backend shell (probe/prepare/finalize/backup/
                                restore-backup/restore-stock/soft-reboot/status/test)
microg-session.sh               script original (referência; não usado pelo app)
docs/ANALISE.md                 análise da Fase 1 (bugs, arquitetura, decisões)
docs/INCIDENTE-RESCUE-PARTY.md  registro do reboot completo via Rescue Party (15/08)
tests/backend_test.sh           harness do backend (roda em host, sem Android)
app/                            projeto Android (módulo app)
  src/main/assets/root/degoogle.sh   backend empacotado no APK
  src/main/java/dev/degoogle/app/
    domain/     DeviceState, StateDetector, DeviceProfile, SystemFacts
    root/       RootExecutor, BackendInstaller, BackendRunner
    microg/     ReleaseRepository (F-Droid oficial), ApkValidator, MicrogManager
    backup/     BackupManager (exclusão; operações no backend shell)
    reboot/     RebootController
    ui/         home, diagnostics, backup, components (Compose M3)
    boot/       BootReceiver (só detecta e notifica)
  src/test/     testes da máquina de estados, parser e backend mockado
```

## Build

Requer Android SDK (compileSdk 35, minSdk 26) e JDK 17+ (testado com JDK 21):

```bash
# aponta para o SDK (ou use ANDROID_HOME)
printf 'sdk.dir=%s\n' "$HOME/android-sdk" > local.properties

./gradlew :app:assembleDebug       # APK em app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest   # testes unitários (19/19)
```

O wrapper (`gradlew` + `gradle/wrapper/gradle-wrapper.jar`) já está versionado;
não é preciso instalar Gradle. O `local.properties` é local e não deve ser
commitado. Para o release, configure uma keystore própria no `build.gradle.kts`
(não use a debug key).

## Backend

Testável em host (não toca no aparelho):

```bash
sh -n degoogle.sh
sh degoogle.sh test                    # self-test
sh tests/backend_test.sh               # 46 cenários (rollback, backup, falhas)
```

Protocolo: stdout = `DEGOOGLE_KEY=value` (máquina); stderr = texto legível.
Exit codes: `0 ok · 1 erro · 2 uso · 3 pré-condição · 4 parcial/rollback · 5 incompatível`.

## Princípios

- **Estado real do Android é a fonte de verdade** (pm path, mounts, flags,
  SELinux, backup) — DataStore guarda só dados auxiliares.
- **Sem validação, sem root copy**: APK só entra no backend depois de parse,
  package/versão, assinatura e sha256 conferidos com o índice F-Droid oficial.
- **`prepare` é transacional**: falha parcial → rollback → exit 4.
- **`restorecon`/`ls -Z` sempre no namespace global**; sucesso só com validação.
- **Backup microG → microG** no mesmo formato do `microg-session.sh`
  (`gms-user0` e `gms-userde`); restauração para o destino explícito
  `com.google.android.gms` com UID/SELinux derivados no momento.
- **Reboot nunca é assumido**: estado re-derivado após qualquer abertura.

## Comportamento conhecido (firmware Samsung One UI 16)

### 1. Crash do `system_server` (CachedAppOptimizer)

Em alguns aparelhos, logo após um soft reboot o `system_server` pode crashar
por um bug do próprio firmware — `NullPointerException` em
`com.android.server.am.CachedAppOptimizer.compactApp` (o freezer de apps em
cache). O Android então reinicia o framework sozinho, o que para o usuário
parece **um segundo soft reboot**.

- **Não é causado pelo app**: não há nenhum caminho de código que dispare
  reboot além do botão explícito; o `ksud soft-reboot` executa uma única
  reinicialização por chamada.
- **Diagnóstico**: crashes registrados em `/data/system/dropbox/`
  (`system_server_crash@*` + `SYSTEM_RESTART@*`), presentes neste aparelho
  desde antes do uso do DeGoogle (~25 crashes em 30 h, mesmo stack).

### 2. Escalada para reboot completo via Rescue Party

⚠️ **Incidente observado em campo (15/08/2026):** após um soft reboot da
instalação do microG, o crash do `system_server` (item 1) se repetiu o
suficiente para o Android ativar o mecanismo de autodefesa **Rescue Party**
— um **reboot completo do kernel** para tentar se recuperar de falhas
recorrentes.

Evidência: `getprop ro.boot.bootreason` retornou **`reboot,rescueparty`**
após o evento, e os arquivos `system_server_crash@*`/`SYSTEM_RESTART@*` no
`/data/system/dropbox/` marcam a sequência de crashes.

Consequências para o DeGoogle:

- **Mounts perdidos**: o reboot completo zera os bind mounts dinâmicos — o
  microG "some" do ambiente (o registro stale do PM pode persistir; recuperar
  com `restore-stock` + soft reboot, que invalida o cache de parse).
- **Para root via exploit (público-alvo): perda total** — reboot completo
  destrói root **e** microG.
- **Não quebra o aparelho**: nada físico é alterado; o stock volta. O backup
  do microG (`/data/local/tmp/microg-backup/`) sobrevive.

### Mitigação possível (em avaliação)

Desabilitar o **app freezer** (`cached_apps_freezer`) — o componente que
crasha — via setting global com root:

```bash
settings put global cached_apps_freezer 0
```

Reduziria a frequência do crash e, portanto, a chance do Rescue Party.
Trade-off: muda o comportamento de gerenciamento de memória do sistema (apps
em cache não são congelados). **Não aplicada por padrão** — exige decisão e
consentimento explícito do usuário.

**Registro completo do incidente:** [`docs/INCIDENTE-RESCUE-PARTY.md`](docs/INCIDENTE-RESCUE-PARTY.md)

## Fases

1. ✅ análise (`docs/ANALISE.md`)
2. ✅ backend shell (`degoogle.sh`) + harness
3. ✅ skeleton Android (estado, detecção, UI, testes)
4. ✅ `STOCK → PREPARED` em aparelho real (download + validação)
5. ✅ `PREPARED → MICROG_ACTIVE` (reboot + finalize)
6. ✅ backup microG→microG em aparelho (formato MicroG Session)
7. ✅ `MICROG_ACTIVE → STOCK` (rollback com lazy umount + invalidação de cache do PM)
8. ⏳ hardening + teste de reinstalação com restauração automática do backup
