# DeGoogle

Oculte temporariamente Google Play Services, Google Services Framework e Play Store em um Android com root, com substituicao opcional pelo microG.

O DeGoogle usa bind mounts para alterar o ambiente ativo do Android sem reescrever os APKs originais do sistema. O app cuida da preparacao, reinicio de userspace, validacao, backup dos dados do microG e restauracao dos componentes originais do Google.

**[English](README.md)**

> [!WARNING]
> Software experimental que altera estado privilegiado do Android.
>
> A validacao em aparelho foi feita em um Samsung Galaxy S24 Ultra (SM-S928B), com Android 16 / One UI 8.5 e KernelSU. Passar nas verificacoes de compatibilidade em outro aparelho nao significa que ele foi testado fisicamente.

## Modos

| Modo | Comportamento |
|---|---|
| Somente DeGoogle | Oculta GMS, GSF e Play Store com diretorios vazios. Nao instala substitutos. |
| Substituir por microG | Substitui GMS pelo microG Services, oculta GSF e substitui Play Store pelo microG Companion. |

O modo Somente DeGoogle nao fornece substitutos para os servicos do Google. Apps que dependem deles podem perder funcionalidades, incluindo notificacoes push via Firebase.

O microG fornece uma camada de compatibilidade, mas nao garante o funcionamento de todos os apps ou recursos dependentes do Google.

O escopo sao esses tres componentes do sistema. O DeGoogle nao remove todos os apps ou servicos do Google do aparelho.

## Requisitos e compatibilidade

- Android com root. O app reconhece KernelSU, Magisk e APatch; a validacao em aparelho foi feita com KernelSU.
- Android 8.0 ou superior para instalar o app. Esse minimo nao garante compatibilidade operacional.
- Acesso funcional ao namespace global de mounts, bind mounts, tratamento de contextos SELinux e uma estrategia utilizavel de reinicio de userspace.
- Para o modo microG, spoofing de assinatura funcional. A configuracao testada usa FakeGApps.
- Acesso a internet para baixar os APKs ao preparar o modo microG.

A compatibilidade e determinada por verificacoes em runtime, incluindo caminhos dos pacotes, acesso a mounts, SELinux, estrategia de reinicio e requisitos de rollback. O modelo do aparelho e as entradas de firmware do banco known-good fornecem contexto, mas nao autorizam a operacao sozinhos.

Verificacoes criticas com falha ou resultado desconhecido bloqueiam a execucao. Um reinicio alternativo do framework exige autorizacao explicita quando nao pode ser totalmente verificado.

O principal caso de uso testado e o [Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy), que carrega o KernelSU por meio de um exploit volatil.

## Uso

1. Abra o DeGoogle e conceda acesso root.
2. Aguarde o fim da verificacao do aparelho. Consulte Diagnostico se a execucao estiver bloqueada.
3. Selecione Somente DeGoogle ou Substituir por microG.
4. Confirme a preparacao e siga a solicitacao de reinicio de userspace.
5. Reabra o app e conclua a validacao ou configuracao restante do microG.

No modo microG, os APKs sao baixados e verificados quanto a identidade do pacote, versao, certificado de assinatura e SHA-256 antes de serem enviados ao backend privilegiado.

O app deriva o estado atual do Android, em vez de depender apenas de uma flag salva. As verificacoes exibem seu progresso; uma atualizacao incompleta bloqueia as operacoes no aparelho ate que o estado seja reconciliado.

A interface esta disponivel em ingles e portugues do Brasil.

## Configuracao e backup do microG

Depois da ativacao, abra as configuracoes do microG e configure os servicos que pretende usar.

Se os mensageiros ja estavam instalados antes da ativacao do microG, pode ser necessario renovar seus registros de push. No fluxo testado, reinstalar os apps afetados permitiu um novo registro. Confirme o registro no Cloud Messaging do microG antes de criar um backup.

A aba Backup permite criar, atualizar, restaurar ou excluir uma copia local dos dados do microG:

```text
/data/local/tmp/microg-backup
```

Esse backup contem dados do microG, nao do aparelho inteiro. Ele pode preservar dados de registro para restauracao posterior, mas a entrega de notificacoes precisa ser conferida apos a restauracao.

As acoes de backup ficam indisponiveis no modo Somente DeGoogle. Os arquivos de backup existentes sao preservados.

## Restauracao do Google

Use a acao de restaurar Google Stock na tela inicial e siga as etapas de reinicio e reindexacao oferecidas pelo app.

A restauracao remove as mascaras temporarias e expoe novamente os componentes originais do Google. O backup do microG e preservado.

Um reboot completo do kernel remove os mounts temporarios. Com root volatil via exploit, ele tambem encerra a sessao de root. O Package Manager ainda pode manter entradas do microG; perder os mounts, por si so, nao garante que a restauracao esteja completa.

Depois do boot, o DeGoogle verifica o estado do sistema. A restauracao stock exige uma acao explicita no app.

## Limitacoes

- Os mounts temporarios nao sobrevivem a um reboot completo do kernel. O DeGoogle solicita reinicios de userspace ou do framework.
- Foi observado um crash de `system_server` envolvendo `CachedAppOptimizer.compactApp` no firmware Samsung testado, durante reinicios de userspace. Crashes repetidos podem acionar o Rescue Party e provocar um reboot completo.
- Lojas de terceiros podem oferecer o Google Play Services como atualizacao do microG. Exclua os pacotes do microG das atualizacoes automaticas.
- A preparacao pode remover atualizacoes ativas do GMS ou da Play Store para expor os APKs originais do sistema e desativar temporariamente a Play Store para impedir atualizacoes imediatas.
- As verificacoes em runtime reduzem a incerteza; elas nao comprovam validacao fisica para todos os aparelhos ou firmwares.

## Compilacao e testes

Requisitos: JDK 17 ou superior e Android SDK com a plataforma 35.

Configure o caminho do SDK em `local.properties`:

```properties
sdk.dir=/absolute/path/to/android-sdk
```

A configuracao atual do Gradle tambem valida a assinatura de release durante a configuracao do projeto. Copie `keystore.properties.example` para `keystore.properties` e configure uma keystore privada existente antes de executar o Gradle. Mantenha os arquivos de assinatura e as credenciais fora do Git.

Compile o APK debug e execute os testes unitarios:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Saida:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Verifique o backend shell no host:

```bash
sh -n degoogle.sh
sh degoogle.sh test
sh tests/backend_test.sh
```

## Estrutura do codigo

- `app/`: app Android, interface, verificacoes de compatibilidade, validacao de APKs e coordenacao das operacoes.
- `degoogle.sh`: backend shell privilegiado principal, copiado para os assets do app durante a compilacao.
- `tests/backend_test.sh`: cenarios do backend executados no host.
- `microg-session.sh`: script original de referencia, nao utilizado pelo app.

O backend escreve registros estruturados `DEGOOGLE_KEY=value` em stdout e diagnosticos em stderr.
