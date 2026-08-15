# DeGoogle

Android (root) app that turns the shell procedure for temporarily replacing Google Play Services / GSF / Play Store with **microG Services + microG Companion** into a safe, verifiable, and near one-click experience.

**v0.1 (status):** complete and tested shell backend (46/46 scenarios in host harness) **and main lifecycle validated on real device** (SM-S928B, One UI 16): `STOCK → PREPARED → soft reboot → MICROG_ACTIVE → backup → MICROG_ACTIVE_BACKED_UP → Restore Google → STOCK verified`. Hardening and backup reinstallation tests remain pending.

> [!WARNING]
> **Tested only with KernelSU:** no changes are made without verified root access and a compatible device profile. V1 supports a single profile: **Samsung Galaxy S24 Ultra (SM-S928x)**.
>
> **Focus on Root-My-Galaxy:** the main focus of this project is for devices rooted via [Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy). Because this method relies on a volatile exploit to load KernelSU, a **full reboot (kernel) loses both root and microG**. Therefore, the app uses **soft reboot (userspace) only**, via `sys.powerctl reboot,userspace`, which preserves the kernel, mounts, and root. The app **never** offers full reboot.
>
> **Messaging apps and push notifications (FCM / GCM):** if messaging apps such as WhatsApp and Telegram were already installed before microG, push notifications will not work immediately.
>
> - **Why this happens:** when a messaging app is launched for the first time, it requests an exclusive push token from Google Play Services. If microG was not active during that initial launch, the token is not registered through microG. These apps do not automatically renegotiate a token when microG is introduced later, so a reinstallation is required to trigger a fresh registration.
> - **Initial reinstallation:** after installing microG for the first time, reinstall WhatsApp and Telegram so they generate a new token.
> - **Registration confirmation:** open microG Settings and verify under the Cloud Messaging (GCM) section that both apps appear as registered.
> - **Backup creation:** as soon as both apps appear as registered, create a backup of microG data via the DeGoogle app.
>
> This ensures that if a full reboot clears the volatile environment, restoring the backup upon reinstalling microG will preserve the registered tokens, removing the need to reinstall messaging apps again.

## Structure

```text
degoogle.sh                     shell backend (probe/prepare/finalize/backup/
                                restore-backup/restore-stock/soft-reboot/status/test)
microg-session.sh               original script (reference; not used by the app)
docs/ANALISE.md                 Phase 1 analysis (bugs, architecture, decisions)
docs/INCIDENTE-RESCUE-PARTY.md  log of full reboot triggered by Rescue Party (15/08)
tests/backend_test.sh           backend harness (runs on host, without Android)
app/                            Android project (app module)
  src/main/assets/root/degoogle.sh   backend bundled in APK
  src/main/java/dev/degoogle/app/
    domain/     DeviceState, StateDetector, DeviceProfile, SystemFacts
    root/       RootExecutor, BackendInstaller, BackendRunner
    microg/     ReleaseRepository (official F-Droid), ApkValidator, MicrogManager
    backup/     BackupManager (exclusion; operations in shell backend)
    reboot/     RebootController
    ui/         home, diagnostics, backup, components (Compose M3)
    boot/       BootReceiver (detection and notification only)
  src/test/     tests for state machine, parser, and mocked backend
```

## Build

Requires Android SDK (compileSdk 35, minSdk 26) and JDK 17+ (tested with JDK 21):

```bash
# point to SDK (or use ANDROID_HOME)
printf 'sdk.dir=%s\n' "$HOME/android-sdk" > local.properties

./gradlew :app:assembleDebug       # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest   # unit tests (19/19)
```

The wrapper (`gradlew` + `gradle/wrapper/gradle-wrapper.jar`) is already tracked in git; Gradle installation is not required. The `local.properties` file is local and must not be committed. For release builds, configure a dedicated keystore in `build.gradle.kts` (do not use the debug key).

## Backend

Testable on host (does not modify the device):

```bash
sh -n degoogle.sh
sh degoogle.sh test                    # self-test
sh tests/backend_test.sh               # 46 scenarios (rollback, backup, failures)
```

Protocol: stdout = `DEGOOGLE_KEY=value` (machine-readable); stderr = human-readable text.
Exit codes: `0 ok · 1 error · 2 usage · 3 precondition · 4 partial/rollback · 5 incompatible`.

## Principles

- **Android real state is the source of truth** (pm path, mounts, flags, SELinux, backup); DataStore stores auxiliary data only.
- **No validation, no root copy**: APKs enter the backend only after parsing, package/version, signature, and SHA-256 verification against the official F-Droid index.
- **`prepare` is transactional**: partial failure leads to rollback with exit code 4.
- **`restorecon`/`ls -Z` always execute in global namespace**; success requires validation.
- **microG → microG backup** in the same format as `microg-session.sh` (`gms-user0` and `gms-userde`); restoration to explicit destination `com.google.android.gms` with UID and SELinux derived at runtime.
- **Reboot is never assumed**: state is re-derived on every launch.

## Known Behavior (Samsung One UI 16 Firmware)

### 1. `system_server` Crash (CachedAppOptimizer)

On some devices, immediately following a soft reboot, `system_server` may crash due to a firmware bug, specifically `NullPointerException` in `com.android.server.am.CachedAppOptimizer.compactApp` (cached app freezer). Android then restarts the framework automatically, which appears to the user as **a second soft reboot**.

- **Not caused by the app**: no code path triggers a reboot other than the explicit button; `ksud soft-reboot` executes a single restart per invocation.
- **Diagnostics**: crashes logged in `/data/system/dropbox/` (`system_server_crash@*` + `SYSTEM_RESTART@*`), present on this device prior to using DeGoogle (~25 crashes in 30 h, identical stack).

### 2. Escalation to Full Reboot via Rescue Party

⚠️ **Field incident observed (15/08/2026):** after a soft reboot during microG installation, the `system_server` crash (item 1) recurred enough times for Android to trigger the **Rescue Party** self-defense mechanism, namely a **full kernel reboot** to recover from recurring failures.

Evidence: `getprop ro.boot.bootreason` returned **`reboot,rescueparty`** after the event, and `system_server_crash@*`/`SYSTEM_RESTART@*` files in `/data/system/dropbox/` record the crash sequence.

Consequences for DeGoogle:

- **Lost mounts**: full reboot clears dynamic bind mounts, causing microG to disappear from the environment (stale PM records may persist; recover with `restore-stock` followed by a soft reboot, which invalidates the parse cache).
- **For exploit-based root (target audience): total loss**: full reboot destroys both root **and** microG.
- **Device remains intact**: no physical changes occur; stock state is restored. The microG backup (`/data/local/tmp/microg-backup/`) survives.

### Possible Mitigation (Under Evaluation)

Disable the **app freezer** (`cached_apps_freezer`), which is the failing component, via global setting with root:

```bash
settings put global cached_apps_freezer 0
```

This reduces crash frequency and subsequent Rescue Party probability. Trade-off: changes system memory management behavior (cached apps are not frozen). **Not enabled by default**, as it requires explicit user decision and consent.

**Full incident log:** [`docs/INCIDENTE-RESCUE-PARTY.md`](docs/INCIDENTE-RESCUE-PARTY.md)

## Phases

1. ✅ analysis (`docs/ANALISE.md`)
2. ✅ shell backend (`degoogle.sh`) + harness
3. ✅ Android skeleton (state, detection, UI, tests)
4. ✅ `STOCK → PREPARED` on real device (download + validation)
5. ✅ `PREPARED → MICROG_ACTIVE` (reboot + finalize)
6. ✅ microG → microG backup on device (MicroG Session format)
7. ✅ `MICROG_ACTIVE → STOCK` (rollback with lazy umount + PM cache invalidation)
8. ⏳ hardening + reinstallation test with automatic backup restore

---

# DeGoogle (Português)

Aplicativo Android (root) que transforma o procedimento shell de substituição temporária de Google Play Services / GSF / Play Store por **microG Services + microG Companion** em uma experiência segura, verificável e quase one-click.

**v0.1 (status):** backend shell completo e testado (46/46 cenários no harness de host) **e ciclo principal validado em aparelho real** (SM-S928B, One UI 16): `STOCK → PREPARED → soft reboot → MICROG_ACTIVE → backup → MICROG_ACTIVE_BACKED_UP → Restaurar Google → STOCK verificado`. Faltam hardening e o teste de reinstalação com restauração automática do backup.

> [!WARNING]
> **Testado apenas com KernelSU:** nenhuma alteração é feita sem provar root e perfil compatível. V1 suporta um único perfil: **Samsung Galaxy S24 Ultra (SM-S928x)**.
>
> **Foco no Root-My-Galaxy:** o foco principal deste projeto é atender usuários que fizeram root por meio do [Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy). Como o root obtido por esse método decorre de um exploit volátil para carregar o KernelSU, um **reboot completo (kernel) perde o root e o microG**. Dessa forma, o app usa **apenas soft reboot (userspace)**, por meio de `sys.powerctl reboot,userspace`, o que preserva o kernel, os mounts e o root. O app **nunca** oferece reboot completo.
>
> **Aplicativos de mensagens e notificações push (FCM / GCM):** se mensageiros como WhatsApp e Telegram já estiverem instalados antes do microG, as notificações push não funcionarão de imediato.
>
> - **Por que isso acontece:** na primeira inicialização de um mensageiro, ele solicita um token exclusivo de push ao Google Play Services. Caso o microG não estivesse ativo durante essa abertura inicial, o registro não ocorre nele. Como esses aplicativos não tentam registrar um novo token automaticamente quando o microG é adicionado posteriormente, a única forma de forçar uma nova solicitação é reinstalando os apps.
> - **Reinstalação inicial:** após instalar o microG pela primeira vez, desinstale e instale novamente o WhatsApp e o Telegram para forçar o registro de novos tokens.
> - **Confirmação de registro:** abra as configurações do microG e certifique-se de que ambos constam na seção Google Cloud Messaging (GCM).
> - **Criação do backup:** assim que ambos aparecerem como registrados, crie um backup dos dados do microG pelo próprio aplicativo DeGoogle.
>
> Dessa forma, caso uma reinicialização completa desfaça o ambiente volátil, a restauração desse backup na reinstalação seguinte preservará os tokens registrados, dispensando novas reinstalações do WhatsApp e do Telegram.

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

O wrapper (`gradlew` + `gradle/wrapper/gradle-wrapper.jar`) já está versionado; não é preciso instalar Gradle. O `local.properties` é local e não deve ser commitado. Para o release, configure uma keystore própria no `build.gradle.kts` (não use a debug key).

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

- **Estado real do Android é a fonte de verdade** (pm path, mounts, flags, SELinux, backup); o DataStore guarda só dados auxiliares.
- **Sem validação, sem root copy**: APK só entra no backend depois de parse, package/versão, assinatura e sha256 conferidos com o índice F-Droid oficial.
- **`prepare` é transacional**: falha parcial resulta em rollback com exit 4.
- **`restorecon`/`ls -Z` sempre no namespace global**; sucesso só com validação.
- **Backup microG → microG** no mesmo formato do `microg-session.sh` (`gms-user0` e `gms-userde`); restauração para o destino explícito `com.google.android.gms` com UID/SELinux derivados no momento.
- **Reboot nunca é assumido**: estado re-derivado após qualquer abertura.

## Comportamento conhecido (firmware Samsung One UI 16)

### 1. Crash do `system_server` (CachedAppOptimizer)

Em alguns aparelhos, logo após um soft reboot o `system_server` pode crashar por um bug do próprio firmware, especificamente `NullPointerException` em `com.android.server.am.CachedAppOptimizer.compactApp` (o freezer de apps em cache). O Android então reinicia o framework sozinho, o que para o usuário parece **um segundo soft reboot**.

- **Não é causado pelo app**: não há nenhum caminho de código que dispare reboot além do botão explícito; o `ksud soft-reboot` executa uma única reinicialização por chamada.
- **Diagnóstico**: crashes registrados em `/data/system/dropbox/` (`system_server_crash@*` + `SYSTEM_RESTART@*`), presentes neste aparelho desde antes do uso do DeGoogle (~25 crashes em 30 h, mesmo stack).

### 2. Escalada para reboot completo via Rescue Party

⚠️ **Incidente observado em campo (15/08/2026):** após um soft reboot da instalação do microG, o crash do `system_server` (item 1) se repetiu o suficiente para o Android ativar o mecanismo de autodefesa **Rescue Party**, isto é, um **reboot completo do kernel** para tentar se recuperar de falhas recorrentes.

Evidência: `getprop ro.boot.bootreason` retornou **`reboot,rescueparty`** após o evento, e os arquivos `system_server_crash@*`/`SYSTEM_RESTART@*` no `/data/system/dropbox/` marcam a sequência de crashes.

Consequências para o DeGoogle:

- **Mounts perdidos**: o reboot completo zera os bind mounts dinâmicos, de modo que o microG "some" do ambiente (o registro stale do PM pode persistir; recuperar com `restore-stock` seguido de soft reboot, que invalida o cache de parse).
- **Para root via exploit (público-alvo): perda total**: o reboot completo destrói o root **e** o microG.
- **Não quebra o aparelho**: nada físico é alterado; o stock volta. O backup do microG (`/data/local/tmp/microg-backup/`) sobrevive.

### Mitigação possível (em avaliação)

Desabilitar o **app freezer** (`cached_apps_freezer`), que é o componente com falha, via setting global com root:

```bash
settings put global cached_apps_freezer 0
```

Reduziria a frequência do crash e, portanto, a chance do Rescue Party. Trade-off: muda o comportamento de gerenciamento de memória do sistema (apps em cache não são congelados). **Não aplicada por padrão**, pois exige decisão e consentimento explícito do usuário.

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
