# CONTEXT.md — Handoff Dual Space Livre

> Reescrito em 2026-08-12. Substitui a versão de 2026-08-06.
> **Convenção:** `[FATO]` = verificado no código ou no aparelho · `[INFERÊNCIA]` =
> deduzido, não provado · `[PENDENTE]` = discutido/planejado, **não** implementado.
> Complementa `CLAUDE.md` (contexto técnico permanente). Onde houver conflito, o código vence.

---

## 1. Visão geral e estado atual

**Dual Space Livre** (`com.dualspace.livre`, versionName `0.1.0`, versionCode `401`) —
fork do **BlackBox**/VirtualApp. Roda cópias isoladas de apps em "espaços" (virtual
user ids) dentro do **usuário Android 11** de um Moto G50 / Android 12 / arm64. `[FATO]`

- Módulos: `app/` (launcher Kotlin), `Bcore/` (runtime Java + hooks), `Bcore/src/main/cpp/`
  (hooks nativos via JniHook). `targetSdk 31`, `compileSdk 35`, `minSdk 21`. `[FATO]`
- Build exige **JDK 21** (JBR do Android Studio). `[FATO]`
- Objetivo do dono: várias contas do Instagram, uma por espaço, sem serem ligadas entre
  si nem deslogadas. Uso legítimo de multi-conta. `[FATO]`

**O que mudou nesta sessão:** cinco bugs reais corrigidos e verificados no aparelho e,
na madrugada de 12/ago, a **causa do deslogamento foi encontrada com stack trace e
confirmação do lado do servidor** — o engine forjava a identidade do pacote quando a
resposta do binder estourava, fazendo o Instagram se anunciar como build 1.0 sem
assinatura. Corrigido, instalado e verificado. **Falta a confirmação de uso real pelo
dono.** `[FATO]` — ver seção 3.

---

## 2. Correções feitas E VERIFICADAS

### 2.1 Crash ao postar — `INotificationManagerProxy` `[FATO]`
Ao postar, o Instagram cria notificação de progresso com miniatura da mídia, servida por
uma URI do FileProvider **do guest**. O host publica a notificação e não tem permissão
nessa URI:

```
FATAL EXCEPTION: IgExecutorV2 #26   Process: com.instagram.android
java.lang.SecurityException: UID 1110304 does not have permission to
  content://com.instagram.fileprovider/cache/images/notification_thumbnail….png
  at INotificationManagerProxy$EnqueueNotificationWithTag.hook
```

A chamada não tinha `try/catch` e o Instagram a faz em executor de background sem handler
→ **o processo inteiro morria** → "fecha e volta pra home" ao tocar em compartilhar.

**Correção:** tenta postar; se recusado, repete sem a mídia inalcançável
(`stripInaccessibleMedia`: `sound`, `EXTRA_LARGE_ICON`, `EXTRA_LARGE_ICON_BIG`,
`EXTRA_PICTURE`, campos `mLargeIcon`/`largeIcon`); se falhar de novo, descarta em silêncio.
Mesma proteção em `cancelNotificationWithTag`.
**Prova:** o dono confirmou explicitamente que o crash parou.

### 2.2 Processo duplicado por espaço — `BProcessManagerService` `[FATO]`
Dois processos `com.instagram.android` vivos no **mesmo** espaço, lendo a mesma pasta de
dados. Provado por `/proc/<pid>/maps`:

```
23468 com.instagram.android → blackbox/data/user/7
32149 com.instagram.android → blackbox/data/user/7
```

E reproduzido por mim: reabrir o launcher levou 2 guests a 4, dois por espaço.

**Causa:** `mProcessMap`/`mPidsSelfLocked` vivem só na memória do `:black`. Quando o
servidor reinicia (rotina sob pressão de memória), o registro é zerado mas os guests
continuam vivos; `getUsingBPidL()` vê o slot como livre e cria um segundo processo.

**Correções (todas em `BProcessManagerService`):**
- `createProc` grava um arquivo `owner` (`userId:processName`) por slot, que **sobrevive à
  morte do `:black`**; `systemReady()` só remove entradas cujo processo morreu
  (`pruneDeadProcEntries`).
- `adoptStrandedSlotForGuest` — **readota** o processo já existente em vez de matar.
- `retireDuplicatesLocked` — varredura em memória. **Estava inerte por erro meu:**
  comparava `record.buid` (só o appId, ex. `10001`) contra o `buid` composto
  (`BUserHandle.getUid(userId, appId)`, ex. `510001`), então nunca casava. Agora compara
  `(userId, appId)`.
- `killAllByUserId` procurava no `mProcessMap` com a mesma chave errada, deixando registro
  zumbi ao parar um espaço. Corrigido. (Bug do upstream, não meu.)

**Prova:** janelas de amostragem de 15s registrando o espaço de cada processo —
**46/46 amostras sem duplicata**, com `killing stranded guest com.instagram.android
(user 5) still holding bPid 0` disparando no log.

### 2.3 ANR do `:black` — `initLock.block()` sem timeout `[FATO]`
`Killing com.dualspace.livre:black (adj 905): bg anr`. A causa: `app.initLock.block()`
sem timeout parava a thread do servidor **para sempre** quando um guest morria antes de se
registrar. E a morte do `:black` é o que gera os órfãos do item 2.2.
**Correção:** `PROCESS_INIT_TIMEOUT_MS = 10_000L`.
**Prova:** `bg anr` do `:black` caiu de 1 para **0** nas janelas seguintes.

### 2.4 App voltando pro menu — regressão minha `[FATO]`
Minha primeira correção de duplicata **matava** o processo. Como esse trecho roda para
**todo** serviço, provider e broadcast que o guest inicia, ela derrubava o app que estava
na tela. **Erro meu, introduzido e corrigido na mesma sessão.**
**Correção:** `killOrphanedGuestProcesses` pula processos em `IMPORTANCE_VISIBLE` ou
melhor, e o caminho principal virou adoção em vez de morte.
**Prova:** `leaving foreground guest process … alone` no log, 4 ocorrências numa janela.

### 2.5 Abas múltiplas no Recents — `ActivityStack` `[FATO]`
`startActivityInNewTaskLocked` usava `FLAG_ACTIVITY_MULTIPLE_TASK`, que significa "nunca
reuse, sempre crie outra". Medido antes: **4 abas, todas com o mesmo `ProxyActivity$P0`**.

**Correção:** removido `MULTIPLE_TASK`; o `shadow` recebe identidade estável
`shadow.setData(Uri.parse("dualspace://space/" + userId + "/" + activityInfo.packageName))`.
O `NEW_DOCUMENT` sozinho reaproveita a task que casa por componente **e** data.

**Prova:** mesmo app aberto 4× seguidas → **1 aba**; espaço diferente → **aba própria**;
tasks marcadas `dualspace://space/0/com.instagram.android` e `…/space/1/…`.
**Não recolocar `MULTIPLE_TASK`.**

---

## 3. Deslogamento do Instagram — NÃO RESOLVIDO

O erro servidor é sempre `GraphQL error … Code: 1675002 … Unauthorized logged out query`.

### O que foi ELIMINADO, e por qual dado

| Hipótese | Como caiu |
|---|---|
| **Processo duplicado** | Amostragem 46/46 sem duplicata **no instante da queda**. `[FATO]` |
| **SIGKILL / fechar as abas** | Na queda das 07:15 o guest subiu limpo (`init bUid = 210001`) e o erro veio **8s depois**, com **zero** kills, `remove task`, ANR ou crash antes. `[FATO]` |
| **Sessão velha já invalidada** | O dono relogou **todas** as contas e continuou caindo. `[FATO]` |
| **IP / Cloudflare WARP** | O Instagram **físico** usa o mesmo túnel (uid `1110334` dentro das faixas da VPN, `ip route get 157.240.1.1 uid 1110304` → `tun0`) e **não** desloga. Mesmo IP, comportamento diferente. **Eu insisti nessa hipótese e estava errado — o dono a derrubou.** `[FATO]` |
| **Identificadores vazando** | O ANDROID_ID virtual do espaço (`eb4ff2c9118b40cc`) está gravado nos dados do Instagram; o real do aparelho (`a7f960fd57f4b833`) **não aparece em lugar nenhum**. `[FATO]` |
| **Corrupção local de sessão** | 185 arquivos em `shared_prefs`, **zero `.xml.bak`**; `AuthHeaderPrefs.xml` reescrito no minuto da queda — é o app limpando o próprio token **depois** da recusa do servidor. `[FATO]` |

### CAUSA ENCONTRADA — 2026-08-12, madrugada `[FATO]`

O engine estava dizendo ao Instagram que ele era um build **1.0 sem assinatura**.

No cold start, o Instagram lê o próprio `PackageInfo` dentro de
`InstagramApplicationForMainProcess.initializeAllColdStartJobs` e manda versão e
assinatura ao servidor. Essa chamada passa por `IPackageManagerProxy$GetPackageInfo`
→ `BPackageManager.getPackageInfo` → `:black`. Quando ela falhava, o engine chamava
`createFallbackPackageInfo`, que **inventava**:

```java
info.versionCode = 1;
info.versionName = "1.0";
info.signatures = new Signature[]{};   // nenhum certificado
info.firstInstallTime = System.currentTimeMillis();
```

Perfil idêntico ao de um cliente repackaged → o servidor revoga a sessão com `1675002`.

**A falha que estava sendo mascarada não é servidor morto.** O cliente recebe
`DeadObjectException: ... remote process probably died`, mas no mesmo milissegundo o
`:black` registra:

```
W/Binder: reply too large data on java level:
  InterfaceDescriptor = ...pm.IBPackageManagerService, code = 6
```

O `PackageInfo` não cabe no buffer de resposta do binder. A mensagem do binder **nomeia
a causa errada** — foi ela que me fez caçar servidor morto por dias. O `:black` de fato
morre o tempo todo (`adj 985 ... empty #19`, dezenas de vezes por dia), o que fez a
história errada encaixar.

Correlação nos logs retidos — **todo deslogamento precedido de resposta estourada**:

| resposta estourada | deslogamento |
|---|---|
| 06:34:58 | 06:36:13 |
| 06:41:36 | 06:41:48 (rajada até 06:42:20) |
| 07:37:17 (já com a correção) | **nenhum** |

### Correções aplicadas e verificadas no aparelho `[FATO]`

Commit `eb0ecf4`, build debug instalada em 2026-08-12 07:44.

1. `getPackageInfo`/`getApplicationInfo` não inventam mais nada: tentam de novo contra
   um servidor ressuscitado e depois devolvem `null`. `createFallbackPackageInfo` e
   `createFallbackApplicationInfo` foram **apagados** — não recriar.
2. `BlackManager.reviveService()` limpa o binder em cache **e** o back-off, para o
   retry não ser engolido pelo rate limiter.
3. `IPackageManagerProxy` cai para o framework real **só para o pacote do próprio
   convidado** (o espaço é sempre instalado a partir do pacote físico, então lá está a
   verdade). Manter estreito: cair para pacotes arbitrários vazaria pacotes do host.
4. `BPackageManagerService.reportOversizedReply` mede toda resposta e avisa acima de
   200 KB.

Verificado: estouro reproduzido às 07:37 com a correção no ar → app seguiu vivo, sem
crash e **sem deslogamento**; três cold starts seguintes limpos.

### O que ainda NÃO está resolvido `[PENDENTE]`

O estouro é **intermitente** — depende das flags de quem chama, e não reproduziu nos
três cold starts seguintes. A sonda ficou instalada para trazer número e flags. **Não
"otimizar" o tamanho da resposta no chute.** Uma divergência real do AOSP já está
visível em `PackageManagerCompat.generatePackageInfo`: `requestedPermissions` é
preenchido sempre, quando o framework só preenche sob `GET_PERMISSIONS`.

**Falta a confirmação que só o dono pode dar:** usar as contas normalmente por alguns
dias. Sessões já invalidades antes desta correção **não voltam** — cada conta precisa de
um login novo.

**Erros meus nesta investigação:** afirmei como causa, em momentos diferentes, o WARP, o
processo duplicado e o SIGKILL por fechar abas. Os três caíram com dado. Uma quarta
hipótese minha desta madrugada — regeneração da identidade do espaço — também caiu:
os `.dual-space-identity` estão estáveis desde 6–10/ago. Não repetir sem prova.

---

## 4. Push / FCM — investigado a fundo, não corrigido

### Onde falha exatamente `[FATO]`
O bind ao Play Services **funciona**. A chamada seguinte é recusada:

```
E/GoogleApiManager: Failed to get service from broker.
java.lang.SecurityException: Unknown calling package name 'com.instagram.android'.
    at com.google.android.gms.common.internal.BaseGmsClient
W/GCM: Invalid caller: com.instagram.android 1110304
E/IgFcmTokenRegistrar: Failed to get FCM token — java.io.IOException: SERVICE_NOT_AVAILABLE
```

O cliente chama `getService(callback, GetServiceRequest)` declarando o pacote do guest; o
GMS resolve o uid do binder (o do host) e recusa. 22 falhas numa sessão curta.

### O que foi tentado e descartado
- **`GmsProxy` é inerte e sempre foi.** `[FATO]` Engancha um serviço `"gms"` do
  ServiceManager que **não existe** (`adb shell service check gms` → `Service gms: not
  found`); `getService("gms")` devolve null. Zero linhas de log com guest rodando. É o 17º
  proxy inerte do projeto. Sua lógica também só trocava a string literal
  `"com.google.android.gms"`, que o guest nunca envia. Corrigido e **documentado como
  inerte** — a correção não muda nada em execução.
- **Embrulhar o binder** (`GmsBrokerDelegate`, novo). Intercepta na entrega e responde
  `queryLocalInterface`. **Dispara no momento certo, confirmado no aparelho.** Mas o
  `IGmsServiceBroker` do Instagram está ofuscado e **não expõe `asInterface` alcançável**
  (tentado: classes aninhadas, sufixos `$Stub`/`$a`/`$zza`, varredura por assinatura), então
  a interface real não pode ser remontada para encaminhar. `[FATO]`
- **Achar o stub pela pilha de execução** — funcionou: `broker stub is X.0mh1`. Mas
  `X.0mh1` é o `ServiceConnection` do `BaseGmsClient`, não o stub. `[FATO]`
- **Patch de campo no `BaseGmsClient`.** A classe **mantém o nome real** sob ofuscação e a
  instância **é alcançável** (`X.0YI6`, `X.0YJ0`) percorrendo coleções a partir do
  `ServiceConnection` do app. Porém ela **não tem nenhum campo `String`** — o pacote não é
  armazenado, é **calculado do `Context`** quando a requisição é montada. Patch de campo é
  impossível. `[FATO]`

### Caminhos restantes `[PENDENTE]`
1. Fazer o `Context` lido pelo cliente GMS responder com o pacote do host. Conflita com a
   virtualização de `getPackageName()`; escopo estreito é difícil.
2. Reescrever no nível do Parcel. **Inseguro:** a transação carrega um binder e um parcel
   remontado o destrói.

Estado do código: `GmsBrokerDelegate` **passa o binder intacto** quando não resolve. Sem
mudança de comportamento e sem regressão (`0 FATAL`, 1 guest por espaço, 2 abas). `[FATO]`

---

## 5. Estado do aparelho (verificado agora, 2026-08-12)

- **Restrição de fundo do Instagram físico: NÃO está aplicada.** `[FATO]`
  `cmd appops get --user 11 com.instagram.android RUN_ANY_IN_BACKGROUND` →
  `No operations. Default mode: allow`; `am get-standby-bucket` → `10` (ACTIVE).
  Ou seja, foi revertida — meu comando de reversão foi recusado, mas o estado atual é o
  padrão.
- **Instagram físico rodando:** pid `25660` (`u11_a334`). `[FATO]`
- **Nenhum guest rodando** no momento (`u11_a304` tem só `com.dualspace.livre` e `:black`). `[FATO]`
- **RAM disponível:** ~1091 MB. `[FATO]`
- **Gravador de log permanente ATIVO:** pid `24456`, escrevendo `/sdcard/ds_watch.log`
  com rotação `-r 16384 -n 20` (20 × 16 MB = 320 MB). Sobrevive a desconexão do PC. `[FATO]`
  Puxar com `adb pull /sdcard/ds_watch.log`.

### O episódio da restrição — erro meu `[FATO]`
Apliquei `RUN_ANY_IN_BACKGROUND ignore` + `set-standby-bucket restricted` no Instagram
físico para recuperar RAM (medido: **+238 MB**, de 1402 → 1640 MB, e o clone funcionou com
o físico apenas *force-stopped*). Logo depois o guest crashou:

```
FATAL EXCEPTION: main   Process: com.dualspace.livre:p0
java.lang.NullPointerException: Attempt to read from field
  'android.os.Bundle PackageItemInfo.metaData' on a null object reference
  at android.app.ActivityThread.handleBindApplication(ActivityThread.java:6934)
```

**Não ficou provado que a restrição causou o crash** — pode ser coincidência. `[PENDENTE]`
O `CLAUDE.md` já registrava que o engine depende da instalação física **habilitada**
(`pm disable-user` quebrava o clone). Restringir fundo é diferente de desabilitar, mas o
crash apareceu na sequência. **Se for testar de novo, teste isolado e com reversão pronta.**

**Não consegui confirmar se o clone abre limpo agora** — as tentativas de abrir caíram no
Instagram **físico** (que estava em primeiro plano com um editor de story aberto) e eu
parei de mexer no aparelho para não atrapalhar o uso do dono. `[PENDENTE — verificar]`

---

## 6. Estado do Git `[FATO — verificado agora]`

- **Branch:** `snapshot` · **Working tree:** limpo (nada pendente)
- **8 commits à frente de `mine/main`**, nenhum pushado
- **Remotos:** `mine` → `github.com/pedrohlsa/DualSpaceLivre.git` (**usar este**) ·
  `origin` → `github.com/ALEX5402/NewBlackbox.git` (upstream, **NÃO pushar**)
- Push, quando autorizado: `git push mine HEAD:main`

Commits desta sessão, do mais novo:

```
7a9098a  Map the Play Services rejection down to the object that decides it
78a3c98  Reach the point where Play Services rejects a cloned app
50f8fe3  Mark GmsProxy as inert and record why push cannot be hooked there
c146ab3  Record what the logout investigation ruled out
1ba111a  Add a clipboard hook and a way to seed the profile clipboard
d1855ba  Give each cloned app one Recents entry instead of many
3621964  Keep one guest process per space across server restarts
c26f696  Stop a rejected notification from killing the guest
```

---

## 7. Clipboard — diagnosticado, não é bug do engine `[FATO]`

Não existia hook nenhum de clipboard. Criei `IClipboardProxy` (reescreve pacote → host,
`attributionTag` → null, userId → host). **Medido: o hook instala nos processos do guest
(`clipboard hook installed`, 4×) e é chamado ZERO vezes.**

Motivo: **o Android mantém um clipboard por perfil**. O que se copia no PC vai para o
perfil principal; os clones vivem no perfil 11, que fica vazio — não há o que ler. Nenhum
hook no engine resolve isso.

Contorno implementado: `SpaceBridge.ACTION_SET_CLIPBOARD`, que escreve no clipboard do
perfil 11 a partir do app host. Testado: `SpaceBridge: clipboard definido (20 caracteres)`
→ `result=OK`.

```bash
adb shell am start --user 11 -n com.dualspace.livre/top.niunaijun.blackboxa.bridge.OpenSpaceActivity \
  -a com.dualspace.livre.action.SET_CLIPBOARD --es text "texto"
```

**Ressalva honesta:** isso **não é** Ctrl+V funcionando — exige rodar um comando a cada
cópia. Para virar Ctrl+V de verdade, o gerenciador do PC precisa chamar a ponte
automaticamente ao detectar cópia. Trabalho no app do PC, não aqui. `[PENDENTE]`

---

## 8. Pendências e próximos passos

### Alta
1. **Relogar cada conta uma vez.** Sessões invalidadas antes da correção de 12/ago não
   voltam sozinhas. Depois disso, usar normalmente por alguns dias — **é a única
   confirmação que falta.** `[PENDENTE]`
2. **Se cair de novo:** puxar `/sdcard/ds_watch.log` e procurar
   `too large for the binder reply` (a sonda nova traz tamanho e flags) e `1675002`. Com
   os dois números dá para atacar o tamanho da resposta com dado, não no chute. `[PENDENTE]`
3. **Vigia de sessão em disco** rodando: `/data/local/tmp/ds_auth_watch.sh` →
   `/sdcard/ds_auth_watch.log`. Registra md5 de 102 arquivos de sessão dos 7 espaços a
   cada 15 s e loga **só o que muda**. Matar com `pkill -f ds_auth_watch` quando não for
   mais preciso. `[FATO]`

### Média
3. **Push/FCM:** decidir se vale seguir por um dos dois caminhos da seção 4. Ambos são
   trabalhosos e podem não dar em nada. `[PENDENTE]`
4. **RAM:** o físico consome ~220 MB e sobe sozinho por trabalho próprio
   (`SystemJobService`, `GetFCMTokenAndRegisterWithIG`, `KeepWarmReceiver` — **não é o
   engine**). Restringir recupera ~238 MB mas precisa ser testado com cuidado (ver 5). `[PENDENTE]`
5. **Push dos 8 commits** para `mine/main` quando o dono autorizar. `[PENDENTE]`

### Baixa / ideias não aprovadas
6. Virtualizar operadora/SIM, `boot_id`. `Build.*`/User-Agent **adiado de propósito**.
7. GSF ID: hook existe mas é **inerte por arquitetura** — o guest não lê gservices (usa o
   GmsCore real, fora do sandbox, e não tem `READ_GSERVICES`). Não gastar tempo. `[FATO]`

---

## 9. Instruções para a próxima IA

- **Fonte de verdade = código + aparelho.** Rode `git log`, `grep`, `adb`, `run-as` antes
  de afirmar qualquer coisa. Esta sessão teve três diagnósticos errados por pular isso.
- **Nunca confie numa classe `*Proxy` pelo nome.** 17 já se provaram inertes. Verifique
  `getWho()` e se ela **loga**.
- **Instrumente antes de corrigir.** Todo avanço real desta sessão veio de log primeiro,
  correção depois. Toda perda de tempo veio do contrário.
- **CUIDADO EXTREMO com automação de UI por coordenada.** Nesta sessão isso entrou num
  compositor de story do dono e, numa sessão anterior, apagou o Instagram de um espaço.
  Tire screenshot e confirme a tela **antes de cada toque**.
- **Não desinstale nem desabilite** o host ou o Instagram físico — o engine depende deles.
- **Se corrigir e o dono relatar quebra logo depois, reverta primeiro** e investigue
  depois. Foi o que salvou a situação duas vezes hoje.
- **Não commite nem pushe sem pedido explícito.**

---

## Contexto essencial pós-compact

1. **Repo** `pedrohlsa/DualSpaceLivre`, branch `snapshot`, working tree limpo,
   **9 commits à frente de `mine/main`, nenhum pushado**. Remote do dono = `mine`;
   `origin` é upstream, **não pushar**.
2. **Causa do deslogamento ENCONTRADA e corrigida** (commit `eb0ecf4`, instalado
   12/ago 07:44). O engine forjava `PackageInfo` (`versionName "1.0"`, `signatures`
   vazio) quando a resposta do binder estourava, e o Instagram reportava isso ao servidor
   no cold start → `1675002`. O `DeadObjectException` que o cliente via **mentia**: o
   `:black` estava vivo, a resposta é que não cabia (`reply too large data on java
   level`). `createFallbackPackageInfo`/`ApplicationInfo` foram apagados — **não
   recriar**. Falta só a confirmação de uso real; **cada conta precisa de um login novo.**
3. **Corrigido antes e verificado:** crash ao postar, processo duplicado por espaço,
   ANR do `:black`, app voltando pro menu, abas de 5 → máx 2. **Não recolocar
   `MULTIPLE_TASK`.**
4. **Aberto:** o estouro da resposta é intermitente e depende das flags de quem chama.
   `BPackageManagerService.reportOversizedReply` está no ar e avisa acima de 200 KB com
   tamanho e flags — **usar esse dado antes de mexer no tamanho da resposta.** Pista já
   confirmada: `PackageManagerCompat.generatePackageInfo` preenche `requestedPermissions`
   sempre, e o AOSP só sob `GET_PERMISSIONS`.
5. **Push/FCM continua quebrado e é problema separado:** GMS recusa com `Unknown calling
   package name`. `GmsProxy` é **inerte**. `BaseGmsClient` não guarda o pacote. Não é a
   causa do deslogamento.
6. **Aparelho:** dois gravadores ativos — logcat permanente (`/sdcard/ds_watch.log`,
   320 MB rotativos) e o vigia de sessão (`/sdcard/ds_auth_watch.log`). Restrição de
   fundo do Instagram físico **não** aplicada. Rollback do APK anterior guardado no
   scratchpad (`rollback_base.apk`).
7. **Build exige JDK 21.** Instalar com `adb install -r --user 11 <apk>`. Usar **debug**:
   a release não é debuggable e o `run-as` (de que os diagnósticos dependem) para de
   funcionar.
