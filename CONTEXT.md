# CONTEXT.md — Handoff Dual Space Livre

> Documento de passagem de contexto. Escrito em 2026-08-06.
> **Convenção de confiabilidade:** `[FATO]` = verificado no repositório ou no
> aparelho nesta sessão · `[INFERÊNCIA]` = deduzido, não provado ·
> `[PENDENTE]` = discutido/planejado, **não** implementado.
> Este arquivo complementa `CLAUDE.md` (contexto técnico permanente) e
> `RELEASE_NOTES.md` (histórico de mudanças). Onde houver conflito, o código vence.

---

## Visão geral do projeto

**Dual Space Livre** (`com.dualspace.livre`, versionName `0.1.0`, versionCode
`401`) — engine Android de virtualização que roda cópias isoladas de apps em
"espaços" (virtual user ids) dentro de um único processo host, sem criar perfis
Android extras. Fork do **BlackBox** (`top.niunaijun.blackbox` / app module
`top.niunaijun.blackboxa`), derivado do VirtualApp. `[FATO]`

- **Objetivo atual do dono:** rodar várias contas do Instagram, uma por espaço,
  sem que o Instagram consiga **ligar as contas entre si** nem **deslogá-las**.
  Uso legítimo de multi-conta — **NÃO é evasão de ban**. `[FATO]`
- **Stack:** Kotlin (módulo `app/` = launcher/UI), Java (módulo `Bcore/` =
  runtime de virtualização + hooks de system service), C++ (`Bcore/src/main/cpp/`
  = hooks nativos via JniHook). `targetSdk 31` (Android 12), `compileSdk 35`,
  `minSdk 21`. `[FATO]`
- **Aparelho de teste:** Moto G50 / Android 12 / arm64, rodando dentro do
  **usuário Android secundário 11** (`u11_a304`), não user 0. `[FATO]`
- **Build:** exige **JDK 21** (JBR do Android Studio). Comando no CLAUDE.md. `[FATO]`

---

## Estado atual

### Funcionando e verificado no aparelho `[FATO]`
- **6 identificadores virtualizados por espaço**, persistidos em
  `.dual-space-identity` dentro de `blackbox/data/user/<id>/`:
  Advertising ID, App Set ID, ANDROID_ID, GSF ID, Serial, Widevine device id.
- **Widevine** (o último adicionado): valores distintos entre si e do aparelho
  real, estáveis após reinício completo do app. Medições desta sessão:
  - Host real: Advertising `eefc4360…` · Widevine `27e75753…`
  - Sasa (espaço 0): Advertising `68af566c…` · Widevine `645c29fc…`
  - Carolina (espaço 1): Advertising `35683759…` · Widevine `25d44f76…`
- **Instagram abre sem crash** com o hook nativo do MediaDrm ativo; nenhum erro
  de DRM de conteúdo (os `DRMDEV/waitNextVsync` no log são do driver de display,
  não relacionados).
- **Troca de espaço não derruba mais o app** (correção de `remove task`,
  commit `3f84add`).
- **UI redesenhada**: tela de boas-vindas com cards horizontais (2 por coluna),
  grade adaptativa de apps, cor ambiente por espaço, seletor em bottom sheet.

### Onde o desenvolvimento parou `[FATO]`
O código do **Widevine está commitado localmente** (HEAD = `44bf371`) na branch
`snapshot`, **mas ainda NÃO foi enviado para `mine/main`** — está 1 commit à
frente. Existe também como branch remota `mine/widevine`. O dono ainda não
decidiu se commita/pusha para `main`.

---

## Arquitetura e arquivos importantes

### Engine — identidade por espaço
- **`Bcore/src/main/java/top/niunaijun/blackbox/core/identity/VirtualIdentityManager.java`**
  — dono de todos os identificadores resettáveis. Chaves persistidas (`[FATO]`,
  verificadas no arquivo): `advertising_id`, `app_set_seed`, `android_id`,
  `gsf_id`, `serial`, `widevine_seed`. Getters:
  `getAdvertisingId(userId)`, `getAppSetId(userId, pkg)`, `getAndroidId(userId)`,
  `getGsfId(userId)`, `getSerial(userId)`, `getWidevineDeviceId(userId, length)`.
  Gera valores com `SecureRandom` no `readOrCreate()` e grava via `AtomicFile`.
  `deriveBytes(seed, length)` deriva o Widevine por SHA-256 a partir do seed,
  preservando o tamanho que o aparelho real reportou.
- **`Bcore/.../core/identity/VirtualAdvertisingIdService.java`** — shim binder do
  GMS Advertising ID (pré-existente).

### Engine — pontos de hook de cada identificador `[FATO]`
- **ANDROID_ID** → `fake/service/context/providers/SystemProviderStub.java`,
  método `getVirtualAndroidId(args)`. Intercepta o `call()` do provider de
  Settings (`GET_*` + nome `android_id`), responde com `Bundle{value=…}`.
- **GSF ID** → `fake/service/context/providers/ContentProviderStub.java`,
  método `getVirtualGsfId(args)`. Intercepta `query()` no provider
  `com.google.android.gsf.gservices` pedindo a chave `android_id`, responde com
  `MatrixCursor{key,value}`.
- **Serial** → `fake/service/IDeviceIdentifiersPolicyProxy.java`, inner class
  `GetSerialForPackage` (`@ProxyMethod("getSerialForPackage")`). Antes retornava
  constante `md5(hostPkg)`; agora `VirtualIdentityManager.getSerial(userId)`.
- **Widevine** → hook **nativo**:
  - `Bcore/src/main/cpp/Hook/MediaDrmHook.cpp` + `.h` — engancha
    `android.media.MediaDrm.getPropertyByteArray` via `JniHook::HookJniFun`.
    Só troca as propriedades `deviceUniqueId` / `provisioningUniqueId`; o resto
    do MediaDrm (nível de segurança, HDCP, provisioning) passa intacto.
  - `Bcore/src/main/cpp/BoxCore.cpp/.h` — expõe `BoxCore::getWidevineDeviceId`,
    registra o método estático e chama `MediaDrmHook::init` no `nativeHook`.
  - `Bcore/src/main/cpp/Android.mk` — adiciona `Hook/MediaDrmHook.cpp` à build.
  - `Bcore/.../core/NativeCore.java` — `@Keep byte[] getWidevineDeviceId(byte[])`
    é o ponto de entrada Java chamado pelo C++; delega a
    `BlackBoxCore.getVirtualWidevineDeviceId(userId, length)`.
  - `Bcore/.../BlackBoxCore.java` — `getVirtualWidevineDeviceId(int,int)`.

### Engine — troca de espaço (correção crítica) `[FATO]`
- **`Bcore/.../core/system/am/ActivityStack.java`** — `clearAllTasks()` e
  `removeTaskLocked()` só finalizam tasks cuja activity base começa com
  `ProxyManifest.PROXY_ACTIVITY_PREFIX` (`isRemovableGuestTask()`). Antes
  removiam a task do próprio launcher, derrubando o app inteiro.
- **`Bcore/.../proxy/ProxyManifest.java`** — constante
  `PROXY_ACTIVITY_PREFIX = "top.niunaijun.blackbox.proxy.ProxyActivity$P"`.
- **`Bcore/.../core/system/am/BActivityManagerService.java`** — `stopUser(userId)`
  chama `clearAllTasks()` + `killAllByUserId()`. Só é invocado explicitamente
  agora (menu do espaço), não mais na troca de página.

### App (launcher) — telas principais `[FATO]`
- **`app/.../view/main/MainActivity.kt`** — funções verificadas:
  `showWelcome()` (tela de boas-vindas na abertura), `showSpacePicker()`
  (bottom sheet), `applySpaceIdentity()` (cor ambiente por espaço),
  `createNewSpace()`, `confirmStopSpace()` (parada explícita do espaço),
  `showColorPicker()`, `confirmDeleteSpace()`.
- **`app/.../view/apps/AppsFragment.kt`** — `applyGridFor(count)` (grade
  adaptativa: 1 app grande e centralizado, divide conforme adiciona),
  `setAccentColor(color)`, indicador "abrindo" (`tileIconDp`).
- **`app/.../view/base/Ambient.kt`** — helpers de cor ambiente (glow, glass card,
  chip, halo) todos com `GradientDrawable` puro (sem blur, custo baixo no G50).
- **`app/.../view/list/ListActivity.kt`** — adicionar apps (multi-seleção, busca
  com `EditText` nativo — **não** voltar ao `SimpleSearchView`, que duplicava
  acentos).

### App de teste
- **`appsettest/`** (módulo, registrado em `settings.gradle` linha 29:
  `include ':appsettest'`) — lê e loga (tag `APPSETTEST`) Advertising ID,
  App Set ID e **Widevine device id** (`readWidevineDeviceId()` via
  `MediaDrm.PROPERTY_DEVICE_UNIQUE_ID`). Usado para provar isolamento sem tocar
  nas contas reais. `[FATO]`

---

## Regras de negócio

- **Cada espaço = um virtual user id** (`BlackBoxCore.get().users`, ordenado por
  id). Nome e cor são preferências por id em `AppManager.mRemarkSharedPreferences`
  (`Remark<id>` / `Color<id>`). `[FATO]`
- **Identificadores devem ser distintos entre espaços E estáveis por espaço.**
  Gerados uma vez, persistidos, sobrevivem a reinício. `[FATO]`
- **Duas cores de espaço nunca podem ser iguais** — checagem por distância RGB
  perceptual (`MIN_COLOR_DISTANCE = 45` em `SpaceUi.kt`), com migração única
  `palette_v2`. `[FATO]`
- **Troca de página NÃO pode parar o espaço anterior** — parar é ação explícita
  (menu do espaço → "Parar espaço"), senão o SIGKILL derruba a engine. `[FATO]`
- **"Remover deste espaço" = desinstalar = apaga os dados do app naquele espaço.**
  Irreversível. O diálogo avisa. `[FATO]`
- **NÃO desinstalar o pacote físico do Instagram (`u11_a334`)** nem limpar dados
  do host — a engine ainda depende da instalação física original. `[FATO,
  documentado no CLAUDE.md]`
- **Busca de apps usa `EditText` + `TextWatcher`** — voltar ao `SimpleSearchView`
  reintroduz o bug de acento duplicado. `[FATO]`
- **Nunca confiar em classe `*Proxy` pelo nome** — verificar se `getWho()`
  retorna binder real e se o hook loga. 16 stubs eram inertes. `[FATO]`

---

## Decisões tomadas

- **Widevine via hook nativo (JniHook), não binder.** `MediaDrm` é classe Java
  comum que vai direto ao JNI. **Correção de rumo importante:** numa resposta
  anterior eu (assistente) afirmei que o projeto "não tinha framework de inline
  hook" — **estava errado**; existe `JniHook` no C++. Foi por isso que o outro
  Claude conseguiu fazer o Widevine. `[FATO]`
- **Widevine troca só as propriedades de identificador**, deixando o resto do
  MediaDrm intacto → DRM de vídeo continua funcionando. Verificado. `[FATO]`
- **`Build.*` / User-Agent deixado de fora de propósito.** Falsificar modelo por
  espaço faz cada um parecer outro aparelho, mas incoerência com GPU/ABI/sensores
  vira sinal. Só mexer se confirmar que os identificadores não bastaram. `[DECISÃO]`
- **Ordem de validação:** logar de novo em cada conta → usar dias → **não
  empilhar mudanças** para saber qual resolveu. `[DECISÃO]`
- **UI ousada com cor ambiente** foi decisão explícita do dono ("Material genérico
  não atende"). Ver memória `prefere-visual-ousado`. `[FATO]`

---

## Alterações realizadas nesta sessão

> Esta sessão = a conversa que precede este documento.

1. **Merge da branch `mine/widevine`** para `snapshot` (fast-forward, HEAD agora
   `44bf371`). Traz: hook nativo MediaDrm, `getWidevineDeviceId` em
   `VirtualIdentityManager`/`BlackBoxCore`/`NativeCore`, wiring em
   `BoxCore.cpp`/`Android.mk`, extensão do `appsettest` para ler o Widevine,
   atualização do CLAUDE.md. **Não commitado por mim — o commit veio pronto da
   branch.** `[FATO]`
2. **Revisão + validação no aparelho** do Widevine (build nativo nas 2 ABIs, IDs
   distintos/estáveis, Instagram sem crash). `[FATO]`
3. **Documentação:** commits anteriores desta sessão já no `mine/main`:
   `ee800d1` (GSF+serial+remoção dos 16 stubs), `28a03a7` (ANDROID_ID),
   `3f84add` (remove task), `e8436ff` (remotes), `5e88eec` (next steps). `[FATO]`
4. **Este `CONTEXT.md`** criado. `[FATO]`

### ⚠️ Efeito colateral não intencional (erro do assistente) `[FATO]`
Durante a limpeza dos apps de teste, **o Instagram do espaço 3 (Lucia) foi
removido por engano** (o ViewPager havia trocado de página e o long-press caiu no
espaço errado). Verificado no disco: `blackbox/data/user/3/com.instagram.android/`
contém só `lib-compressed`, `shared_prefs` = 0 arquivos. **A sessão daquela conta
foi perdida — precisa relogar.** Nenhum outro espaço afetado.

---

## Pendências

### Confirmadas (decisão do dono pendente, código pronto)
- **Commitar/pushar o Widevine para `mine/main`.** Está validado, mas parado 1
  commit à frente em `snapshot` / na branch `mine/widevine`. `[PENDENTE]`

### Confirmadas (ação no aparelho pendente)
- **Relogar em TODAS as contas** — a mudança de identidade (ANDROID_ID, GSF,
  serial, Widevine) invalida o token atual uma vez. `[PENDENTE]`
- **Re-adicionar o Instagram no espaço 3 (Lucia)** e relogar. `[PENDENTE]`
- **Remover o `appsettest` dos espaços 0 (Sasa) e 1 (Carolina)** — sobrou do
  teste. `[PENDENTE]`

### Melhorias desejáveis (discutidas, NÃO aprovadas)
- Virtualizar **operadora/SIM** (`getSimOperator`, `getNetworkOperatorName` —
  legíveis sem permissão; `ITelephonyManagerProxy` já existe e cobre parte).
  `[PENDENTE — ideia]`
- Virtualizar **`/proc/sys/kernel/random/boot_id`** via `addIORule` (arquivo por
  espaço). `[PENDENTE — ideia]`
- Virtualizar **`Build.*` + resolução** (User-Agent) — **explicitamente adiado**,
  risco de incoerência. `[PENDENTE — ideia, baixa prioridade]`

### Problemas conhecidos
- **Deslogamento do Instagram NÃO está comprovadamente resolvido.** Todas as
  correções de identidade são hipótese até o dono relogar e observar por dias.
  O sintoma servidor era GraphQL `1675002 Unauthorized logged out query`. `[FATO]`
- **Correção de diagnóstico:** a hipótese anterior "SIGKILL perde a escrita do
  SharedPreferences" foi **refutada** — inspeção com build debug achou zero
  `.xml.bak` e prefs íntegros nos 7 espaços. O logout é server-side. A correção
  de `remove task` continua válida (impedia a engine de se derrubar), mas não era
  a causa do logout. `[FATO]`
- **Instagram físico (`u11_a334`) sobe junto com o clone e consome ~300-350 MB.**
  Não é causa de logout; não "resolver" desabilitando o pacote. `[FATO]`
- **IP compartilhado** entre todos os espaços — provável sinal mais forte que
  sobra. Fora do alcance da engine (precisaria proxy/VPN por espaço). `[INFERÊNCIA]`

### Resolvido por investigação (2026-08-06)
- **Hook do GSF ID é inerte por arquitetura — provado no aparelho.** Trace ao
  vivo com diagnósticos em `getContentProvider`/`ContentProviderDelegate.update`:
  o guest (`com.instagram.android`, uid host `u11_a304`) adquiriu o provider
  gservices **0 vezes** enquanto leu `ANDROID_ID` **10 vezes** na mesma janela.
  Motivo estrutural: sob o uid virtualizado `u11_a304` só rodam 3 processos
  (launcher, `:black`, e o guest) — **não existe Play Services/gservices
  virtualizado dentro do sandbox**. Todo `com.google.android.gms`,
  `com.google.process.gservices`, `com.google.process.gapps` roda sob o uid real
  do Google `u11_a165`, fora do BlackBox. O guest fala com o **GmsCore real via
  binder**, e qualquer leitura de GSF acontece lá, onde o hook de provider não
  enxerga. Somado a isso: app sem permissão `READ_GSERVICES` (o Instagram não
  tem) **não pode** consultar o gservices — por isso a contagem é zero.
  **Conclusão: o GSF id não é um vetor de linkagem que o Instagram sequer
  alcança por esse caminho.** O hook fica no código (inofensivo, e correto para
  o caso teórico de um guest COM `READ_GSERVICES`), mas **não faz parte do
  conjunto efetivo de identificadores**. Interceptar um GSF id via GmsCore
  exigiria hookar o binder do GmsCore (o mesmo mecanismo que o
  `VirtualAdvertisingIdService` já usa para o ad id), não o content provider —
  só vale a pena se surgir evidência de que o IG usa o GSF id pra ligar contas.
  `[FATO]`

---

## Testes e validações

### Comandos-chave
```bash
# build release (JDK 21 obrigatório)
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat :app:assembleRelease \
  -Dorg.gradle.java.home="C:\Program Files\Android\Android Studio\jbr"

# build debug (necessário para run-as / inspecionar dados do guest)
# ...mesmo comando com :app:assembleDebug

# adb (Git Bash: prefixar paths /sdcard com MSYS_NO_PATHCONV=1)
export ADB="C:/Users/ph969/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" install -r --user 11 <apk>
"$ADB" shell run-as com.dualspace.livre --user 11 ls blackbox/data/user/<id>/
```

### Passou `[FATO]`
- Build release **com o hook nativo** nas 2 ABIs (arm64-v8a, armeabi-v7a).
- Widevine distinto host vs Sasa vs Carolina, estável após reinício completo
  (medido 2×).
- Advertising ID distinto por espaço (idem).
- Instagram abre sem crash (`ProxyActivity$P2`), sem erro de DRM de conteúdo.
- Troca de espaço com clone vivo: processo sobrevive, zero `remove task` no log.
- ANDROID_ID hook servindo o guest (log `SystemProviderStub: ANDROID_ID served`).

### Falhou / não testado
- **GSF ID hook: não observado disparando** (ver validação manual acima).
- **Não testado:** grade adaptativa com 2+ apps num espaço além do Sasa;
  regressão de UI depois do merge do Widevine (o merge não tocou UI, mas não
  reinstalei o release final desta sessão — ver riscos).

### Riscos de regressão `[INFERÊNCIA]`
- O merge `44bf371` altera `Android.mk`/`BoxCore.cpp` — qualquer erro de build
  nativo aparece só no `assembleRelease`/`assembleDebug` completo. Build passou,
  risco baixo.
- O último APK **instalado no aparelho** durante a sessão foi o **debug** (para
  inspecionar dados). Confirmar qual APK ficou instalado antes de entregar ao uso.

---

## Estado do Git `[FATO — verificado nesta sessão]`

- **Branch atual:** `snapshot`
- **HEAD:** `44bf371 feat: virtualizar Widevine device id por espaco`
- **Working tree:** limpo (nada modificado/untracked) — **exceto** este
  `CONTEXT.md` recém-criado (untracked).
- **Remotos:**
  - `mine` → `github.com/pedrohlsa/DualSpaceLivre.git` (fork do dono, **usar este**)
  - `origin` → `github.com/ALEX5402/NewBlackbox.git` (upstream, **NÃO pushar**)
- **Relação com `mine/main`:** `snapshot` está **1 commit à frente** (`44bf371`
  não está em `main`; está em `mine/widevine`). 0 commits atrás.
- **Fluxo de push (nesta máquina):** `git push mine HEAD:main`. Num clone limpo
  o fork vira `origin` e `git push` normal serve.
- Branches locais: `snapshot` (atual), `main`, `dual-space-livre-fixes`.

---

## Próximos passos (priorizado)

1. **[Decisão do dono]** Commitar/pushar o Widevine para `mine/main`
   (`git push mine HEAD:main`) — já validado.
2. **[Aparelho]** Confirmar qual APK ficou instalado; reinstalar o **release**
   se necessário.
3. **[Aparelho]** Re-adicionar Instagram no espaço 3 (Lucia); remover
   `appsettest` dos espaços 0 e 1.
4. **[Aparelho, dono]** Relogar em todas as contas. Usar por dias **sem empilhar
   novas mudanças de identidade**.
5. ~~Confirmar o hook do GSF ID ao vivo~~ — **feito 2026-08-06: é inerte por
   arquitetura, não alcançável pelo IG** (ver Pendências → Resolvido). Não gastar
   mais tempo nisso.
6. **[Se ainda deslogar]** Só então avaliar operadora/SIM, boot_id e, por último,
   `Build.*`/User-Agent — um de cada vez. Lembrar: o vetor residual mais forte é
   o **IP compartilhado** (fora do alcance do engine, precisaria proxy/VPN por
   espaço).

---

## Instruções para a próxima IA

- **NÃO recriar** o que já existe: os 6 identificadores estão implementados e
  validados (menos o GSF, que falta ver ao vivo). Confira
  `VirtualIdentityManager.java` antes de "adicionar" qualquer identificador.
- **NÃO reverter** decisões aprovadas: hook nativo do Widevine, remoção dos 16
  stubs inertes, parada explícita de espaço (não automática), busca com
  `EditText`, UI de cor ambiente.
- **NÃO assumir que algo está pronto só porque foi discutido:** operadora/SIM,
  boot_id e `Build.*` são **ideias**, não código. `Build.*` foi **adiado de
  propósito**.
- **NÃO fazer mudança grande sem ler o código atual.** Este projeto tem armadilha
  real: classes `*Proxy` registradas que não enganchavam nada. Sempre verificar
  `getWho()` + log.
- **CUIDADO EXTREMO com ações destrutivas no aparelho.** "Remover deste espaço"
  apaga dados irreversivelmente. **Confirmar em qual espaço está ANTES de cada
  long-press** — foi exatamente pular essa checagem que apagou a sessão da Lucia
  nesta sessão. O ViewPager troca de página e as coordenadas mudam.
- **NÃO desinstalar/limpar** o host nem o Instagram físico — a engine depende
  deles.
- **Preferência de UI/UX:** visual ousado com cor ambiente por espaço, já
  aprovado. Não "simplificar" para Material genérico.
- **Fonte de verdade = código + aparelho**, não a conversa. Rode `git log`,
  `grep`, `run-as` antes de afirmar.

---

## Contexto essencial pós-compact

1. **Repo:** `github.com/pedrohlsa/DualSpaceLivre`. Branch `snapshot`, HEAD
   `44bf371` (Widevine). Remote do dono = `mine`; **`origin` é upstream, não
   pushar**. `snapshot` está **1 commit à frente de `mine/main`** — o Widevine
   ainda **não foi pushado para `main`** (aguarda decisão do dono).
2. **6 identificadores virtualizados por espaço** em `VirtualIdentityManager`:
   advertising, app_set, android_id, gsf_id, serial, widevine. Todos persistidos
   em `.dual-space-identity`. **GSF é inerte por arquitetura** (guest não lê
   gservices — usa o GmsCore real fora do sandbox, e não tem `READ_GSERVICES`);
   provado no aparelho, não é vetor de linkagem alcançável. Os outros 5 valem.
3. **Widevine usa hook NATIVO** (`Bcore/src/main/cpp/Hook/MediaDrmHook.cpp` via
   JniHook) — sim, o projeto TEM framework de inline hook.
4. **Deslogamento do Instagram NÃO está confirmado como resolvido.** Hipótese até
   o dono relogar e observar. Erro server-side `1675002`. A teoria antiga de
   "SIGKILL perde escrita de prefs" foi **refutada**.
5. **Erro desta sessão:** apaguei o Instagram do espaço 3 (Lucia) por engano —
   precisa re-adicionar e relogar. `appsettest` sobrou nos espaços 0 e 1.
6. **Build exige JDK 21.** Última instalação no aparelho pode ter sido o APK
   **debug** (verificar).
