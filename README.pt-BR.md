# DeGoogle

DeGoogle é um aplicativo Android com root que transforma o procedimento shell de substituição temporária do Google Play Services / GSF / Play Store por **microG Services + microG Companion** em um processo praticamente de um clique. Ele prepara um ambiente volátil do microG, executa um soft reboot de userspace e pode fazer backup dos dados do microG para restauração.

> [!WARNING]
> O DeGoogle altera estado privilegiado do Android e ainda é experimental.
>
> Ele foi testado apenas com **KernelSU** no **Samsung Galaxy S24 Ultra (SM-S928x)**. Nenhuma alteração é aplicada sem validar root, capabilities críticas e segurança de rollback; firmware não homologado exige opt-in explícito.

## Status do projeto

O ciclo principal está validado em **Samsung Galaxy S24 Ultra (SM-S928B)** com **Android 16 / One UI 8.5** e KernelSU:

```text
STOCK → PREPARED → (soft reboot de userspace) → MICROG_ACTIVE → (backup) → MICROG_ACTIVE_BACKED_UP → (restauração) → STOCK
```

Status dos testes:

- Backend shell: 94/94 cenários no harness de host
- Testes unitários Android: 52/52
- Ciclo principal validado no aparelho
- Validação de certificado oficial do microG e probe de signature spoofing (FakeGApps)
- Backup e restauração de dados do microG validados no aparelho
- Retorno ao estado stock validado
- Suporte bilíngue (Inglês e Português do Brasil)

Capturas de tela (Material 3):

<img width="280" alt="Início - Prepared" src="https://github.com/user-attachments/assets/5113741f-5378-4504-b864-5530cd1d4ff8" />
<img width="280" alt="Diagnóstico" src="https://github.com/user-attachments/assets/5e44c62c-3a4e-4071-a0fa-7b472c734a3e" />
<img width="280" alt="Backup" src="https://github.com/user-attachments/assets/a5f3905b-370d-4a19-8805-238708fdb0e1" />

**English:** [`README.md`](README.md)

## Perfil de dispositivo suportado

O banco Known-Good inicial contém um único perfil homologado:

- **Samsung Galaxy S24 Ultra (SM-S928x)**
- **Root via KernelSU**
- **Módulo obrigatório: `fakegapps`** (fornece spoofing de assinatura necessário pelo microG). Certifique-se de que este módulo esteja instalado e ativo antes de ativar o microG.
- Público principal: aparelhos com root via [Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy)

Outros Samsung são diagnosticados dinamicamente, mas não são declarados
suportados sem uma entrada correspondente de firmware/root e capabilities
aprovadas. Eles só podem chegar ao fluxo experimental; semelhança de modelo
sozinha nunca é suficiente.

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

Se uma operação `prepare` falhar parcialmente, o backend tenta fazer rollback e retorna um código específico de falha parcial/rollback. Durante a restauração stock, os alvos vêm do snapshot transacional ou do perfil S24 validado; entradas ausentes no Package Manager só viram `REINDEX_PENDING` quando o target stock existe. Os APKs da máscara só são removidos depois que todos os mounts próprios foram desmontados, e o reboot/validação pós-boot conclui a recuperação.

## Modelo de segurança

Quando uma verificação falha, o app interrompe a operação ou executa rollback em vez de continuar com um estado incerto:

- **O estado real do Android é a fonte de verdade**: o DataStore guarda apenas dados auxiliares.
- **Sem validação, sem root copy**: o aplicativo Android valida package/version, assinatura e SHA-256 dos APKs contra o índice oficial do F-Droid antes de passá-los ao backend privilegiado.
- **Prepare transacional**: falha parcial aciona rollback com exit code `4`.
- **Validação no namespace global**: `restorecon` e `ls -Z` são executados no namespace global e o sucesso é validado.
- **Destino explícito de backup**: a restauração do backup do microG usa `com.google.android.gms`, com UID e SELinux derivados em runtime.
- **Reboot nunca é assumido**: o estado é derivado novamente sempre que o app é aberto.

## Compatibilidade híbrida

A lista de modelos deixou de ser a prova principal de compatibilidade. O app combina:

```text
Device Probe + Package Locator
        ↓
Capability Matrix com evidências
        ↓
Known-Good Database versionado
        ↓
Safety Preflight + snapshot/journal
        ↓
execução somente quando o rollback é verificável
```

Os estados da engine são `SUPPORTED`, `PROBABLY_SUPPORTED`, `REQUIRES_PROFILE`, `UNSUPPORTED` e `UNSAFE`. Um match apenas por modelo nunca gera `SUPPORTED`; `FAIL` ou `UNKNOWN` em capability crítica bloqueia a execução. O S24 só é aprovado automaticamente quando a combinação de fingerprint, SDK, backend de root e capacidades coincide com o banco.

O diagnóstico não altera GMS, GSF, Store, dados, cache ou reboot:

```bash
degoogle.sh dry-run
degoogle.sh preflight
```

Além do texto em `stderr`, o backend emite `DEGOOGLE_CAP_*` em `stdout`; o Kotlin transforma isso em um relatório JSON exportável. O opt-in experimental não desativa a detecção: ele só pode autorizar uma classificação `PROBABLY_SUPPORTED` se todas as capabilities críticas estiverem em `PASS`, inclusive rollback e estratégia de soft reboot.

O banco inicial está em [`app/src/main/assets/compatibility/known_good.json`](app/src/main/assets/compatibility/known_good.json). Não há atualização remota automática.

A implementação não declara suporte físico para outra família Samsung.
Signature spoofing e segurança do soft reboot continuam bloqueando quando o
aparelho não fornece evidência funcional; um mantenedor precisa validar o
relatório e adicionar o firmware ao banco antes de ele se tornar `SUPPORTED`.

## Mensageiros e notificações push

Se aplicativos como WhatsApp ou Telegram já estavam instalados antes da ativação do microG, as notificações push podem precisar de novo registro.

Fluxo observado:

1. Ativar o microG.
2. Reinstalar os mensageiros afetados para que solicitem novo registro de push.
3. Abrir **microG Settings → Cloud Messaging (GCM)** e confirmar que os apps aparecem registrados.
4. Criar um backup do microG no DeGoogle depois da confirmação.

Caso o ambiente volátil seja perdido após um reboot completo, a restauração do backup do microG pode preservar os dados registrados do microG e evitar a repetição da configuração inicial [...]

Desde a versão atual, o `BootReceiver` também executa essa verificação sem
exigir que a Activity seja aberta. Se um reboot completo deixar o Package
Manager stale (`RESTORE_PREPARED`) ou uma máscara legada conhecida montada, o
app desmonta apenas fontes conhecidas, preserva o backup, limpa o cache de
parse, remove os dados órfãos do microG e solicita um soft reboot para
reindexar o sistema. Em `STOCK` com apenas payloads temporários, ele remove os
resíduos conhecidos. Mounts externos ou estados ambíguos continuam bloqueados
e geram alerta, sem remoção automática.

## Build

Requisitos:

- Android SDK
- `compileSdk 35`
- `minSdk 26`
- JDK 17+ (testado com JDK 21)

Configure o SDK:

```bash
printf 'sdk.dir=%s\\n' "$HOME/android-sdk" > local.properties
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

Para builds de release, use uma keystore dedicada; o build nunca usa a chave de debug. Copie
`keystore.properties.example` para `keystore.properties`, preencha os valores e mantenha a
keystore privada fora do Git. Em CI, as mesmas propriedades podem ser fornecidas pelas variáveis
`DEGOOGLE_RELEASE_STORE_FILE`, `DEGOOGLE_RELEASE_STORE_PASSWORD`, `DEGOOGLE_RELEASE_KEY_ALIAS` e
`DEGOOGLE_RELEASE_KEY_PASSWORD`.

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
  probe / dry-run / preflight / prepare / finalize /
  backup / restore-backup / restore-stock / rollback /
  post-boot-validate / soft-reboot / status / test

microg-session.sh
  script original de referência; não usado pelo app

docs/
  ANALISE.md
  INCIDENTE-RESCUE-PARTY.md

tests/
  backend_test.sh

app/
  src/main/assets/
    root/degoogle.sh
    compatibility/known_good.json

  src/main/java/dev/degoogle/app/
    domain/
      DeviceFacts, SystemPackageInfo, PackageLocator
      Capability, CompatibilityEngine, KnownGoodDatabase
      TransactionJournal, DeviceState, StateDetector

    root/
      RootExecutor
      RootBackend
      BackendInstaller
      BackendRunner

    security/
      SignatureSpoofingProbe

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
      settings
      components

    boot/
      BootReceiver

  src/test/
    testes de domínio, segurança, máquina de estados, parser e backend mockado
```

## Ressalvas e notas operacionais

### Falha de firmware durante soft reboot (Rescue Party)
Foi observado um crash de `system_server` no componente `CachedAppOptimizer.compactApp` em torno de soft reboots no firmware de teste (Samsung Android 16 / One UI 8.5). Crashes repetidos podem acionar o mecanismo Android Rescue Party, provocando reboot completo do kernel.

Em aparelhos com root volátil via exploit, o reboot completo encerra a sessão de root e remove as máscaras. O aparelho permanece recuperável e o backup em `/data/local/tmp/microg-backup/` é preservado. Relatório de incidente completo e evidências: [`docs/INCIDENTE-RESCUE-PARTY.md`](docs/INCIDENTE-RESCUE-PARTY.md).

Mitigação experimental (desabilita o freezer de apps em cache; altera o gerenciamento de memória):
```bash
settings put global cached_apps_freezer 0
```

### Lojas de terceiros (Aurora Store)
A Aurora Store pode tentar atualizar o microG GmsCore para a versão mais recente do Play Services. Desative as atualizações automáticas para os pacotes do microG na loja.

### Sincronização do Package Manager após reboot completo
Após um reboot completo não planejado, o PackageManager pode manter cache do microG. Use o fluxo de restauração do aplicativo para limpar o cache de pacotes e disparar um soft reboot para reindexar os APKs stock.

## Documentação

- [`docs/ANALISE.md`](docs/ANALISE.md): Análise de arquitetura, substituição do `microg-session.sh` e decisões de segurança
- [`docs/INCIDENTE-RESCUE-PARTY.md`](docs/INCIDENTE-RESCUE-PARTY.md): Registro do incidente de Rescue Party, linha do tempo e validação de recuperação
