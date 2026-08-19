# Notas técnicas: análise do microg-session.sh e arquitetura do DeGoogle

Notas de trabalho e decisões técnicas tomadas durante a substituição do script manual pelo backend e aplicativo DeGoogle.

## 1. O que o microg-session.sh original fazia

O `microg-session.sh` implementava um ciclo manual em 2 ou 3 fases:

| Comando | O que faz |
|---|---|
| `prep [APK]` | Para Play Store + GMS, faz backup dos dados do GMS, remove atualização de `/data/app`, confere GMS base em `/product/priv-app/GmsCore`, cria máscaras, copia APK, aplica bind mounts globais (GMS + GSF), `restorecon` e verifica com `ls -laZ`. |
| (soft reboot manual) | Usuário reinicia o framework. |
| `install` | Pós-boot: confere GMS registrado de `/product/priv-app/GmsCore` (priv-app), confere GSF ausente, restaura dados do GMS, concede permissões, Doze/appops, WhatsApp. |
| `backup` | Copia `/data/user/0/com.google.android.gms` e `user_de` para `/data/local/tmp/microg-backup`. |
| `store install [APK]` | Fase 1: monta máscara vazia sobre `/product/priv-app/Phonesky`. Fase 2 (após reboot): copia FakeStore para a máscara. |
| `store enable` | Habilita `com.android.vending` + permissões + Doze. |
| `store restore` | Remove APK da máscara e desmonta. |
| `status` | Dump legível de paths, flags, mounts, backup, versões. |
| `restore` | `pm uninstall` do GMS, remove APK da máscara, desmonta GMS + GSF. **Não trata a loja.** |

### Como funciona a técnica central

- Máscaras em `/data/local/tmp/microg-mask/{gms,gsf,store}`.
- `global()` = `nsenter --mount=/proc/1/ns/mnt -- "$@"` — aplica/inspeciona mounts no namespace do PID 1 (global).
- Bind mount da máscara sobre o diretório priv-app stock. Após soft reboot, o PackageManager (que roda no namespace do init) enxerga a máscara e registra o APK como **priv-app**, ganhando permissões privileged (ex.: `INTERACT_ACROSS_USERS`) — requisito para o microG não crashar (`SingletonComponentRouterProvider`) e para FCM/push.
- GSF é mascarado com diretório vazio → some do registro após boot.
- O soft reboot é **obrigatório**: processos em namespaces antigos (launcher, system_server antigo) continuam vendo o diretório original até o framework reiniciar.

## 2. Bugs e problemas encontrados

### 2.1 CRÍTICO — restauração de dados copia para o nome errado

`restore_gms()` faz:

```sh
rm -rf /data/user/0/com.google.android.gms
cp -a "$BACKUP_GMS_USER" /data/user/0/    # BACKUP_GMS_USER = .../gms-user0
```

`cp -a` de um diretório chamado `gms-user0` para `/data/user/0/` cria
`/data/user/0/gms-user0` — **não** restaura `/data/user/0/com.google.android.gms`.
Resultado: o diretório legítimo foi apagado, o backup foi parar no nome errado,
o `chown`/`chcon` seguintes operam sobre um caminho inexistente e o microG fica
**sem dados** (identidade FCM perdida). É exatamente o bug que a especificação
do app alerta ("não copiar para `/data/user/0/` com nome diferente do package").
O mesmo vale para `user_de` (`gms-userde`).

### 2.2 Restauração não é transacional

O `rm -rf` do destino acontece **antes** do `cp`. Se o `cp` falhar, os dados
originais já foram destruídos e não há rollback. A correção: copiar para
destino temporário e renomear, ou validar a cópia antes de remover o original.

### 2.3 SELinux com contexto hardcoded

`chcon -R u:object_r:privapp_data_file:s0:c512,c768` é específico do aparelho/build.
As categorias MLS de dados de app são atribuídas por `seapp_contexts` por app/uid;
hardcodá-las quebra em qualquer outro perfil e não é verificável. Correção:
derivar o contexto do diretório de dados recém-criado pelo PackageManager
(`stat -c %C`) e aplicá-lo; validar com `ls -Z`; nunca declarar sucesso sem validar.

### 2.4 Rollback da Play Store

O `restore-stock` contempla GMS + GSF + Store. Primeiro resolve os targets pelo
snapshot ou pelo perfil S24 validado, usando `mountinfo` mesmo quando GSF/Store
sumiram temporariamente do Package Manager. Só remove os APKs da máscara depois
de desmontar todos os mounts próprios. Se o PM ainda estiver stale, registra
`REINDEX_PENDING`, deixa o enable para o re-scan pós-boot e exige reboot antes de
confirmar `RESTORED` na UI.

### 2.5 Estado da loja durante `prepare`

O estado disabled/enabled da Store é capturado antes do cleanup. APKs inválidos,
falha de preflight, falha de metadados ou falha de mount não deixam a Store
desabilitada silenciosamente; o rollback tenta restaurar o estado anterior e o
journal registra a recuperação necessária se isso falhar.

### 2.6 Namespace inconsistente no `restorecon`

O backend atual usa `global restorecon -R ...` e valida os três contextos com
`global ls -Z`; antes do mount também clona owner, mode e contexto SELinux do
diretório original e aborta se o contexto não puder ser reproduzido.

### 2.7 `is_global_mount` valida só o mountpoint

O backend atual parseia `mountinfo` (mountpoint e root/fonte), compara a fonte
com a máscara e, durante rollback, recusa desmontar qualquer origem externa.

### 2.8 `store install` em duas fases = 3 reboots

Fase 1 monta máscara vazia (loja some), reboot, copia APK, reboot, habilita.
Não há motivo técnico: montar a máscara **já com o APK dentro** exige um único
reboot. A separação era só para depuração manual.

### 2.9 Falta validação do APK fonte

`prep`/`store install` aceitam qualquer arquivo. O app novo valida em Kotlin
(package, versão, assinatura, sha256) **antes** de chamar o backend; o backend
revalida magic bytes e registra hash nos logs.

### 2.10 `gms_uid` frágil

Parse de `dumpsys package` para `userId=` depende do formato da versão. Correção:
cadeia de fallback (`stat` do diretório de dados → `dumpsys` → erro).

### 2.11 `pm uninstall` no rollback é inócuo para system app

`pm uninstall com.google.android.gms` falha (ignorado) quando o pacote é
priv-app; quem "desinstala" é o desmonte + reboot. O importante é limpar a
máscara e desmontar. Os dados órfãos do microG em `/data` devem ser removidos
explicitamente (com confirmação no app), senão ficam lixo sem dono.

### 2.12 Sem lock e sem exit codes úteis

Duas execuções concorrentes de `prep` podem corromper as máscaras. O script usa
`exit 1` genérico e `die` misturado com `|| true` silencioso (ex.: `restorecon`
sempre "ok" mesmo falhando). O novo backend exige: lock (mkdir atômico), exit
codes por classe de erro, e verificação real do resultado de cada passo.

### 2.13 `prep` remove atualização de GMS de `/data/app` e depois restaura dados da versão nova sobre a base

O backup é feito **antes** do `pm uninstall`, então os dados são da versão
atualizada. Após reboot, a base registra com dados de outra versão. Em geral o
GMS tolera, mas é uma restauração cross-version sem validação. **Decisão v1**:
o app DeGoogle não fará carryover stock→microG (privacidade + a especificação
proíbe); o backup do app é microG→microG, criado após a configuração.

## 3. Pressupostos específicos do aparelho (perfil)

O script assume, hardcoded:

```text
GMS_SYSTEM   = /product/priv-app/GmsCore
GSF_SYSTEM   = /system_ext/priv-app/GoogleServicesFramework
STORE_SYSTEM = /product/priv-app/Phonesky
```

Isso corresponde ao layout Samsung One UI (S24 Ultra, SM-S928B). O app novo
mantém isso num **device profile** com confirmação por `pm path` + atributos do
build (manufacturer, model, fingerprint). Qualquer divergência → `UNSUPPORTED`,
sem tocar no aparelho.

## 4. Arquitetura proposta

### 4.1 Fonte de verdade

- **Estado real do Android** = fonte de verdade, derivado de: `pm path`,
  `dumpsys package`, `/proc/1/mountinfo`, `ls -Z`, `getenforce`, `getprop`,
  presença do backup, exit codes das operações.
- DataStore guarda **apenas** dados auxiliares (última versão consultada,
  preferências, prompts já exibidos, histórico). Nunca `DataStore → estado`.
- O backend emite `KEY=VALUE` em stdout (máquina) e progresso legível em stderr.
  O Kotlin parseia o stdout; o stderr aparece na seção técnica expansível.

### 4.2 Camadas

```text
app/
├── ui/          home, diagnostics, backup, components (Compose M3)
├── domain/      DeviceState, StateDetector, DeviceProfile, SystemFacts, validators
├── root/        RootExecutor, RootResult, BackendInstaller, RootManager
├── microg/      ReleaseRepository, ApkValidator, MicrogManager
├── backup/      BackupManager (exclusão do backup MicroG Session)
├── reboot/      RebootController
└── assets/root/ degoogle.sh
```

### 4.3 Backend shell (Fase 2)

```text
degoogle.sh probe             fatos do sistema (máquina + humano)
degoogle.sh prepare <gms> <companion>
degoogle.sh finalize          valida priv-app + grants/appops/doze (idempotente)
degoogle.sh backup            microG → cópia de diretórios MicroG Session
degoogle.sh restore-backup    restaura gms-user0/userde no destino correto
degoogle.sh restore-stock     rollback completo (GMS+GSF+Store), preserva backup
degoogle.sh status            estado derivado
degoogle.sh soft-reboot       userspace reboot com fallback
degoogle.sh test              self-test/failure injection (sem tocar no aparelho)
```

Invariantes do backend:
- `set -u`; sem `set -e` (erros tratados explicitamente), cada passo **verifica**
  o resultado antes de declarar sucesso.
- Lock via `mkdir` atômico; trap limpa o lock.
- Operações de máscara têm rollback; o backup segue deliberadamente o fluxo do
  script original e substitui os diretórios anteriores ao criar uma nova cópia.
- Todos os mounts/inspeções de mount no namespace do PID 1 (`global()`),
  inclusive `restorecon` e `ls -Z`.
- Exit codes: 0 ok · 1 erro geral · 2 uso · 3 pré-condição falhou (nada mudou) ·
  4 rollback falhou (estado parcial) · 5 incompatível (probe).

### 4.4 Máquina de estados (Kotlin)

`StateDetector` converte `SystemFacts` (do `probe`) → `DeviceState`:

```text
NO_ROOT, UNSUPPORTED, STOCK, PREPARING, PREPARED, MICROG_BOOTED,
MICROG_NEEDS_SETUP, MICROG_ACTIVE, MICROG_ACTIVE_BACKED_UP, RESTORE_PREPARED, ERROR
```

Regras de derivação (ordem importa):

1. Sem root → `NO_ROOT`.
2. Root + perfil não confere (manufacturer/model/paths) → `UNSUPPORTED`.
3. Paths stock visíveis + **nenhum** mount das máscaras → `STOCK`.
4. Mounts ativos (GMS/GSF/Store via nossa máscara) + GMS ainda registrado do
   path stock → `PREPARED` (falta soft reboot).
5. Mounts ativos + GMS do path mascarado:
   - GSF ainda presente → `ERROR` (máscara GSF falhou).
   - Companion ausente → `ERROR` (perfil incompleto).
   - finalize incompleto (whitelist/perms ausentes) → `MICROG_BOOTED`
     (app roda `finalize` automaticamente).
   - finalize ok + sem backup → `MICROG_NEEDS_SETUP` (primeira vez; modal de
     configuração manual) / `MICROG_ACTIVE` (prompt já exibido).
   - finalize ok + backup presente → `MICROG_ACTIVE_BACKED_UP`.
6. Sem mounts + GMS ainda do path mascarado (rollback feito, reboot pendente) →
   `RESTORE_PREPARED`.
7. Qualquer combinação inconsistente (ex.: só GMS mascarado, mount de fonte
   desconhecida, GMS de `/data/app`) → `ERROR` com diagnóstico.

`PrivAppValidationResult`: path sob a máscara + `pkgFlags` com `PRIVILEGED` +
`codePath`/`resourcePath` sob a máscara + permissão privileged necessária
(`INTERACT_ACROSS_USERS`) presente nas requested permissions.

### 4.5 Decisões de segurança (resumo)

1. Nenhuma alteração sem provar root (`id -u` = 0) e perfil compatível.
2. APK só entra no backend depois de validado (parse, package, versão,
   assinatura vs. índice F-Droid, sha256). Download inválido → apagado.
3. `RootExecutor` executa **argv** (nunca concatena strings); caminhos de APK
   ficam no filesDir do app e são validados por regex antes de qualquer shell.
4. `prepare` é transacional: se GMS montou e GSF falhou, desfaz tudo.
5. `restorecon` + `ls -Z` sempre no namespace global; sucesso só com validação.
6. Backup no formato do `microg-session.sh`: cópia direta de
   `/data/user/0/com.google.android.gms` e, quando existir, de
   `/data/user_de/0/com.google.android.gms` para
   `/data/local/tmp/microg-backup/gms-user0` e `gms-userde`; restauração para o
   path explícito `com.google.android.gms`, com UID/SELinux derivados no momento.
7. Nada roda em silêncio no boot: `BOOT_COMPLETED` só detecta e notifica.
8. Reboot nunca é assumido: após qualquer reboot o app re-deriva o estado real.
9. Destrutivas exigem confirmação explícita na UI.

### 4.6 Fonte de downloads (microG)

Repo F-Droid oficial: `https://microg.org/fdroid/repo/`.
- `index-v2.json` (JSON) → preferido; fallback `index-v1.jar` (zip com
  `index.xml`), que o Android parseia com `java.util.zip` + `XmlPullParser`
  (sem dependência extra).
- Pacotes: `com.google.android.gms` (GmsCore) e `com.android.vending`
  (microG Companion). Sempre a última `versionCode`; APK em
  `https://microg.org/fdroid/repo/<apkName>`.
- `ReleaseRepository` verifica a fonte a cada execução; nenhuma versão hardcoded.

### 4.7 Soft reboot

- `setprop sys.powerctl reboot,userspace` (AOSP 11+); init falha o setprop se o
  userspace reboot não for suportado → detectável.
- `ksud soft-reboot` é preferido quando o backend KernelSU está disponível.
- Não existe fallback automático para reboot completo: em root volátil ele
  remove o próprio root e os bind mounts. A estratégia é registrada como
  `WARN` até ser homologada para a combinação firmware/backend.
- `RebootController` encapsula a solicitação, mas o commit só ocorre depois de
  `post-boot-validate` confirmar `system_server`, `boot_completed`, packages,
  priv-app, SELinux e ausência de sinal de Rescue Party.

### 4.8 Arquitetura híbrida implementada

O backend agora expõe `dry-run`, `preflight` e `post-boot-validate`, além do
fluxo existente. O `probe` coleta propriedades completas do build, kernel,
PackageManager, paths base/split, updates em `/data/app`, filesystem e
mountinfo. Cada verificação crítica é emitida como:

```text
DEGOOGLE_CAP_<NAME>_STATUS=PASS|WARN|FAIL|UNKNOWN
DEGOOGLE_CAP_<NAME>_EVIDENCE=...
DEGOOGLE_CAP_<NAME>_REASON=...
```

O Kotlin faz o matching contra o banco local versionado, mantém um journal
atômico da transação e nunca converte `UNKNOWN` em aprovação. O opt-in
experimental somente chega ao shell como uma autorização adicional; não
desliga o preflight nem as verificações de caminho, namespace, SELinux,
rollback ou soft reboot.

## 5. Fases de desenvolvimento e validação

| Fase | Escopo | Critério de conclusão |
|---|---|---|
| 1 | Análise de problemas do script original e mapeamento de riscos | Levantamento completo de bugs e modelo de segurança definido |
| 2 | Backend `degoogle.sh` | `sh -n` limpo, suite de testes do backend passando, idempotência verificada |
| 3 | Base Android (`RootExecutor`, `StateDetector`, `DeviceProfile`, UI inicial) | Testes unitários com executor mockado cobrindo transições de estado |
| 4 | Fluxo `STOCK → PREPARED` com download e validação de APKs | Validação ponta a ponta no aparelho alvo |
| 5 | Fluxo `PREPARED → MICROG_ACTIVE` (soft reboot + finalize) | Ciclo operacional completo no aparelho |
| 6 | Backup e restauração microG→microG | Backup de tokens e restauração validados no aparelho |
| 7 | Fluxo `MICROG_ACTIVE → STOCK` (rollback) | Desmonte, limpeza de cache do PM e retorno a stock validados |
| 8 | Hardening: recovery automático pós-boot, journal transacional e testes de host | 94 testes de host passando + suite unitária Android verde |
