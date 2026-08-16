# DeGoogle

DeGoogle é um aplicativo Android com root que transforma o procedimento shell de substituição temporária do Google Play Services / GSF / Play Store por **microG Services + microG Companion** em um processo quase one-click, com validações explícitas, rollback e verificação do estado real do sistema.

> [!WARNING]
> O DeGoogle altera estado privilegiado do Android e ainda é experimental.
>
> Ele foi testado apenas com **KernelSU** no **Samsung Galaxy S24 Ultra (SM-S928x)**. Nenhuma alteração é aplicada sem validar o acesso root e um perfil de dispositivo compatível.

## Status do projeto

**v0.2**

O ciclo principal foi validado em um **SM-S928B** real com **Android 16 / One UI 8.5**:

```text
STOCK
  ↓
PREPARED
  ↓
soft reboot
  ↓
MICROG_ACTIVE
  ↓
backup
  ↓
MICROG_ACTIVE_BACKED_UP
  ↓
Restaurar Google
  ↓
STOCK
```

Status dos testes:

- ✅ Backend shell: 46/46 cenários no harness de host
- ✅ Testes unitários Android: 19/19
- ✅ Ciclo principal validado em aparelho real
- ✅ Backup do microG validado no aparelho
- ✅ Retorno ao estado stock validado
- ⏳ Hardening adicional
- ⏳ Teste de reinstalação com restauração automática do backup

A interface usa Material 3 / Material You e possui suporte a inglês e português do Brasil.

<img width="280" alt="Screenshot_20260815_213426_DeGoogle" src="https://github.com/user-attachments/assets/5113741f-5378-4504-b864-5530cd1d4ff8" />
<img width="280"  alt="Screenshot_20260815_213424_DeGoogle" src="https://github.com/user-attachments/assets/5e44c62c-3a4e-4071-a0fa-7b472c734a3e" />
<img width="280"  alt="Screenshot_20260815_213351_DeGoogle" src="https://github.com/user-attachments/assets/a5f3905b-370d-4a19-8805-238708fdb0e1" />

**English:** see [`README.md`](README.md).

## Perfil de dispositivo suportado

A V1 atualmente suporta um único perfil:

- **Samsung Galaxy S24 Ultra (SM-S928x)**
- **Root via KernelSU**
- Público principal: aparelhos com root via [Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy)

O Root-My-Galaxy depende de um exploit volátil para carregar o KernelSU. Um **reboot completo do kernel perde tanto o root quanto o ambiente temporário do microG**.

Por isso, o DeGoogle usa somente **soft reboot de userspace**:

```text
sys.powerctl reboot,userspace
```

O aplicativo não oferece uma ação de reboot completo.

## Como funciona

O DeGoogle trata o **estado real do Android como fonte de verdade**, em vez de confiar em uma flag interna do aplicativo.

O backend inspeciona caminhos do PackageManager, bind mounts, flags de pacotes, estado SELinux, presença de backup e outros fatos do sistema para derivar o estado atual do aparelho.

Sequência da operação:

1. Validar root e compatibilidade do dispositivo.
2. Baixar e validar os APKs necessários do microG.
3. Preparar transacionalmente o ambiente temporário.
4. Executar um soft reboot de userspace.
5. Derivar novamente o estado do Android e finalizar a ativação do microG.
6. Opcionalmente criar um backup dos dados do microG.
7. Restaurar o ambiente stock do Google quando solicitado.

Se uma operação `prepare` falhar parcialmente, o backend tenta fazer rollback e retorna um código específico de falha parcial/rollback.

## Modelo de segurança

Quando uma verificação falha, o app interrompe a operação ou executa rollback em vez de continuar com um estado incerto:

- **O estado real do Android é a fonte de verdade**: o DataStore guarda apenas dados auxiliares.
- **Sem validação, sem root copy**: o aplicativo Android valida package/version, assinatura e SHA-256 dos APKs contra o índice oficial do F-Droid antes de passá-los ao backend privilegiado.
- **Prepare transacional**: falha parcial aciona rollback com exit code `4`.
- **Validação no namespace global**: `restorecon` e `ls -Z` são executados no namespace global e o sucesso é validado.
- **Destino explícito de backup**: a restauração do backup do microG usa `com.google.android.gms`, com UID e SELinux derivados em runtime.
- **Reboot nunca é assumido**: o estado é derivado novamente sempre que o app é aberto.

## Mensageiros e notificações push

Se aplicativos como WhatsApp ou Telegram já estavam instalados antes da ativação do microG, as notificações push podem precisar de novo registro.

Fluxo observado:

1. Ativar o microG.
2. Reinstalar os mensageiros afetados para que solicitem novo registro de push.
3. Abrir **microG Settings → Cloud Messaging (GCM)** e confirmar que os apps aparecem registrados.
4. Criar um backup do microG no DeGoogle depois da confirmação.

Caso o ambiente volátil seja perdido após um reboot completo, a restauração do backup do microG pode preservar os dados registrados do microG e evitar a repetição da configuração inicial no fluxo testado.

## Build

Requisitos:

- Android SDK
- `compileSdk 35`
- `minSdk 26`
- JDK 17+ (testado com JDK 21)

Configure o SDK:

```bash
printf 'sdk.dir=%s\n' "$HOME/android-sdk" > local.properties
```

Compile e execute os testes unitários:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

APK de debug:

```text
app/build/outputs/apk/debug/app-debug.apk
```

O Gradle wrapper já é versionado, então não é necessário instalar Gradle separadamente.

`local.properties` é um arquivo local e não deve ser commitado.

Para builds de release, configure uma keystore dedicada em `build.gradle.kts`; não use a chave de debug.

## Backend

O backend shell pode ser testado em host sem modificar um dispositivo Android:

```bash
sh -n degoogle.sh
sh degoogle.sh test
sh tests/backend_test.sh
```

Protocolo do backend:

```text
stdout  → DEGOOGLE_KEY=value
stderr  → diagnóstico legível
```

Exit codes:

| Código | Significado |
|---:|---|
| `0` | OK |
| `1` | Erro |
| `2` | Uso |
| `3` | Falha de pré-condição |
| `4` | Falha parcial / rollback |
| `5` | Dispositivo incompatível |

## Arquitetura

```text
degoogle.sh
  backend shell:
  probe / prepare / finalize / backup /
  restore-backup / restore-stock /
  soft-reboot / status / test

microg-session.sh
  script original de referência; não usado pelo app

docs/
  ANALISE.md
  INCIDENTE-RESCUE-PARTY.md

tests/
  backend_test.sh

app/
  src/main/assets/root/degoogle.sh

  src/main/java/dev/degoogle/app/
    domain/
      DeviceState
      StateDetector
      DeviceProfile
      SystemFacts

    root/
      RootExecutor
      BackendInstaller
      BackendRunner

    microg/
      ReleaseRepository
      ApkValidator
      MicrogManager

    backup/
      BackupManager

    reboot/
      RebootController

    ui/
      home
      diagnostics
      backup
      components

    boot/
      BootReceiver

  src/test/
    testes de máquina de estados, parser e backend mockado
```

## Problema conhecido no firmware

Foi observado um crash de `system_server` relacionado ao firmware em torno de soft reboots de userspace no firmware Samsung testado.

O stack registrado envolve:

```text
com.android.server.am.CachedAppOptimizer.compactApp
```

A mesma assinatura de crash já existia no aparelho de teste antes do uso do DeGoogle. Em um incidente observado em **15/08/2026**, crashes repetidos do framework escalaram para o mecanismo Android **Rescue Party**, resultando em um reboot completo do kernel.

Boot reason observado:

```text
reboot,rescueparty
```

Para root baseado em exploit, um reboot completo elimina o ambiente de root volátil e os bind mounts dinâmicos usados pelo ambiente temporário do microG.

O aparelho permaneceu recuperável e o backup do microG em:

```text
/data/local/tmp/microg-backup/
```

sobreviveu ao incidente.

Detalhes e evidências:

[`docs/INCIDENTE-RESCUE-PARTY.md`](docs/INCIDENTE-RESCUE-PARTY.md)

### Mitigação em avaliação

Desabilitar o freezer de apps em cache do Android pode reduzir a frequência da falha observada no `CachedAppOptimizer`:

```bash
settings put global cached_apps_freezer 0
```

Essa alteração modifica o comportamento de gerenciamento de memória do sistema e **não é aplicada por padrão**. Deve ser tratada como mitigação experimental e exigir escolha explícita do usuário.

## Roadmap

- ✅ Análise inicial
- ✅ Backend shell + harness de host
- ✅ Detecção de estado Android, UI e testes
- ✅ `STOCK → PREPARED` validado no aparelho
- ✅ `PREPARED → MICROG_ACTIVE` validado
- ✅ Backup do microG validado no aparelho
- ✅ `MICROG_ACTIVE → STOCK` validado
- ⏳ Hardening adicional
- ⏳ Teste de reinstalação com restauração automática do backup

## Documentação

- [`docs/ANALISE.md`](docs/ANALISE.md): análise inicial, arquitetura, bugs e decisões
- [`docs/INCIDENTE-RESCUE-PARTY.md`](docs/INCIDENTE-RESCUE-PARTY.md): registro do incidente com Rescue Party
