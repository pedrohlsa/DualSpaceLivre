# CONTEXT.md — Handoff Dual Space Livre

> Reescrito em 2026-08-16. Substitui a versão de 2026-08-12.
> **Convenção:** `[FATO]` = verificado no código ou no aparelho · `[INFERÊNCIA]` =
> deduzido, não provado · `[PENDENTE]` = discutido, **não** implementado.
> Complementa `CLAUDE.md` (contexto técnico permanente). Onde houver conflito, o código vence.

---

## 1. Estado atual

**Dual Space Livre** (`com.dualspace.livre`, versionName `0.1.0`) — fork do
**BlackBox**/VirtualApp. Roda cópias isoladas de apps em "espaços" dentro do
**usuário Android 11** de um Moto G50 / Android 12 / arm64. `[FATO]`

Objetivo do dono: várias contas de Instagram, uma por espaço, sem serem ligadas
entre si nem deslogadas. Uso legítimo de multi-conta. `[FATO]`

**Quatro frentes fecharam desde a última versão deste documento:**

1. **O deslogamento foi resolvido.** Causa encontrada, corrigida e verificada com
   11 cold starts. Seção 2.
2. **A interface foi redesenhada por completo**, de "app Android bonito" para uma
   linguagem visual própria. Seção 5.
3. **A troca de espaço agora libera RAM com segurança.** Após uma janela de 5 s,
   fica somente o convidado do espaço em primeiro plano. `[FATO]`
4. **O perfil Pixel 6 foi corrigido e aplicado também em `Build.*`.** Não mistura
   mais `oriole` com `qcom/lahaina`. `[FATO]`

**Build exige JDK 21.** Instalar com `adb install -r --user 11 <apk>`, sempre a
**debug** — a release não é debuggable e o `run-as`, de que todo diagnóstico
depende, para de funcionar. `[FATO]`

---

## 2. Deslogamento — RESOLVIDO `[FATO]`

### A causa

O keystore do Android é **por uid**, e todos os espaços rodam sob o mesmo uid do
host. O Instagram guarda a chave que cifra o token de sessão sob um alias
**fixo**, sem componente de conta ou de espaço — capturado ao vivo pelo hook:

```
D/KeystoreProxy: keystore alias scoped to space: AuthHeaderPrefs_single
```

Logo, **logar num espaço apagava a chave de todos os outros**. O próximo espaço a
abrir decifrava o próprio token com a chave errada, a autenticação falhava, e o
app descartava a sessão **sem chamar a rede**. O daemon dizia isso o tempo todo:

```
E/keystore2: In finish: KeyMint::finish failed. Error::Km(ErrorCode(-30))
E/keystore2::gc: Trying to invalidate key.
```

### A verificação

| teste | antes | depois |
|---|---|---|
| cold start mantém a sessão | **0 de 5** | **8 de 8** |
| reabrir os primeiros após todos rodarem | não chegou | **3 de 3** |
| crashes / logout forçado na bateria | — | **0 / 0** |

A segunda linha é a que importa: reabrir os espaços 1–3 **depois** que os oito
subiram é exatamente o padrão "só o último a logar sobrevive". Aguentou.

**Estabilidade desde então:** último login em 08-15 20:47; nenhuma transição de
sessão e nenhum `1675002` desde. O log cobre ~5h (rotação rápida). `[FATO]`

### O que foi eliminado antes, e com qual dado

Não reinvestigar sem prova nova. Cada uma destas foi afirmada por mim como causa
e caiu:

| hipótese | como caiu |
|---|---|
| **Cloudflare WARP / IP** | O Instagram físico usa o mesmo túnel e nunca cai |
| **Processo duplicado** | 46/46 amostras sem duplicata no instante da queda |
| **SIGKILL / fechar abas** | Queda sem nenhum kill, `remove task`, ANR ou crash |
| **Regeneração de identidade** | `.dual-space-identity` estável desde 6–10/ago |
| **PackageInfo forjado** | Bug real e corrigido, mas o deslogamento continuou |

### Correções relacionadas que ficam

- **`createFallbackPackageInfo`/`ApplicationInfo` foram apagados.** Devolviam
  `versionName "1.0"` e `signatures` vazio quando o binder estourava, fazendo o
  clone se anunciar como build sem assinatura. **Não recriar.** `[FATO]`
- O `DeadObjectException` que o cliente via **mentia**: o `:black` estava vivo e a
  resposta é que não cabia (`reply too large data on java level`). `[FATO]`
- Clone reportava `firstInstallTime = 0` (1970). Corrigido. `[FATO]`

---

## 3. Push / FCM — quebrado, e é problema separado `[FATO]`

O clone nunca registra push: `Unknown calling package name 'com.instagram.android'`
→ `SERVICE_NOT_AVAILABLE`. `token_registration_prefs.xml` vazio.

`GmsProxy` é **inerte** — hooka um serviço `"gms"` que não existe. `BaseGmsClient`
é alcançável mas **não guarda o pacote**, que é calculado do `Context`.

**Não é a causa do deslogamento** — isso ficou provado quando o keystore resolveu
o problema com o push ainda quebrado.

---

## 4. Clipboard — não é bug do engine `[FATO]`

O Android mantém **um clipboard por perfil**. O PC escreve no perfil principal; os
clones vivem no de trabalho. O hook do engine é instalado e nunca é chamado,
porque não há o que ler. Confirmado pelo dono: funciona no perfil pessoal, não no
de trabalho. Resolver exige a ponte escrever de dentro do perfil de trabalho —
trabalho do gerenciador do PC. `[PENDENTE]`

---

## 5. Interface — redesenhada `[FATO]`

Ver `CLAUDE.md` para o sistema completo. O essencial:

**Três pesos de superfície, e o papel decide qual:**

| peso | drawable | para |
|---|---|---|
| painel | `bg_panel` (superfície + hairline) | gerenciamento |
| bloco | `bg_block` (superfície, sem hairline) | seleção |
| nada | transparente + ripple | linha **dentro** de painel ou bloco |

**Nunca aninhar.** Um painel por região. Auditar com
`grep -c bg_panel res/layout/*.xml` — mais de um por layout é cheiro.

**Três papéis de cor:** lavanda `#9684FF` = o produto (ações globais, menus);
cor do espaço = onde você está (ponto, wash, monograma, linha atual); a escada
grafite com subtom violeta = estrutura, e não significa nada.

**Quatro papéis de ícone.** Um controle nunca usa o token de metadata —
`ds_icon` para interativo, `ds_accent` para ação do produto,
`ds_on_surface_muted` para glifo de apoio, `ds_on_surface_faint` para metadata.

**Tipografia: Inter, decidida e fechada.** 400 metadata · 500 corpo/labels ·
600 nomes/ações/overlines · 700 só o título que manda na tela. Os labels de app
foram **medidos em runtime** e são Inter — a tabela está no `CLAUDE.md`. **Não
sair caçando outra família.**

**Regra que custou caro aprender:** todo defeito real do redesign era invisível no
XML e óbvio no screenshot. Compilar, instalar, capturar, criticar, corrigir.

**Fechamento visual de 2026-08-16:** confirmações, entradas e listas de escolha
do host passam por `view/base/DsDialogs.kt`; a dependência antiga
`afollestad/material-dialogs` foi removida. A linha de tema é uma `Preference`
simples, não uma `ListPreference` (ela abria uma segunda janela por baixo), e o
valor é persistido antes de alternar o modo do AppCompat. Claro e Escuro foram
testados no aparelho. O glow da tela de boas-vindas acompanha `scrollY`.

---

## 6. Estado do aparelho `[FATO — verificado 2026-08-16 01:54]`

- **7 espaços logados**, estáveis desde 08-15 20:47.
- **Gravador de logcat ativo** — `/sdcard/ds_watch.log`, 16 MB × 20 rotativos.
  Rotação rápida: cobre ~5h.
- **Vigia de sessão ativo** — `/data/local/tmp/ds_auth_watch.sh` →
  `/sdcard/ds_auth_watch.log`. Registra md5 de 102 arquivos de sessão dos 7
  espaços a cada 15 s e o estado `SESSION uN:0|1`, logando **só o que muda**.
  Matar com `pkill -f ds_auth_watch`.
- Instagram físico (`u11_a334`) sem restrição de fundo aplicada.
- APK de rollback anterior no scratchpad da sessão.

**Restrições permanentes:** não desinstalar nem limpar dados do host ou do
Instagram físico — o engine depende deles.

---

## 7. Git `[FATO]`

Branch `snapshot`, base `d3df721` igual a `mine/main`. As mudanças desta rodada
(UI final, política de RAM e perfil Pixel 6) estão no working tree e ainda não
foram commitadas nem enviadas. `[FATO em 2026-08-16]`

Remote do dono é `mine` (`git push mine HEAD:main`). **`origin` é o upstream
`ALEX5402/NewBlackbox` — nunca pushar lá.**

---

## 8. Pendências

### Alta
1. **Confirmar o deslogamento com uso real** por alguns dias. Os 11 cold starts
   foram meus, em 30 segundos cada; o teste que falta é o uso normal. `[PENDENTE]`

### Média
2. **Ctrl+V** exige a ponte escrever de dentro do perfil de trabalho. `[PENDENTE]`
3. **Push/FCM**: restam só dois caminhos ruins (Context ou Parcel). `[PENDENTE]`
4. **Envio de vários arquivos de uma vez** ainda não foi implementado. `[PENDENTE]`

### Fechado nesta rodada
- **Otimização de RAM:** um usuário virtual ativo por vez, com debounce de 3 s
  e flush final de 2 s. Testado 1 → 2 → 1 → 2 sem perder login. `[FATO]`
- **Pixel 6:** `Build.*` e propriedades de identidade coerentes com
  Pixel 6/oriole/Tensor gs101. Driver gráfico, ABI e SDK permanecem físicos por
  compatibilidade nativa. `[FATO]`

### Baixa
5. `findActualApkPath` em `BPackageManager` ficou órfão após a remoção dos
   forjadores. Inócuo. `[FATO]`

---

## 9. Instruções para a próxima IA

- **Fonte de verdade = código + aparelho.** `git log`, `grep`, `adb`, `run-as`
  antes de afirmar. Esta investigação teve **quatro** diagnósticos errados meus
  por pular isso.
- **Nunca confie numa classe `*Proxy` pelo nome.** 17 já se provaram inertes.
  Verifique `getWho()` e se ela **loga**.
- **Instrumente antes de corrigir.** Todo avanço veio de log primeiro.
- **UI não se termina lendo XML.** Screenshot é o critério.
- **CUIDADO EXTREMO com automação de UI por coordenada.** Já entrou num
  compositor de story do dono e, em sessão anterior, apagou o Instagram de um
  espaço. Screenshot e confirme a tela **antes de cada toque**.
- **Não commite nem pushe sem pedido explícito.**
- **Não reabra decisões fechadas** sem dado novo: família tipográfica, paleta,
  regra de acento, sistema de painéis.

---

## Contexto essencial pós-compact

1. **Repo** `pedrohlsa/DualSpaceLivre`, branch `snapshot`, tree limpo,
   **45 commits à frente de `mine/main`, nenhum pushado**. Remote do dono =
   `mine`; `origin` é upstream, **não pushar**.
2. **Deslogamento RESOLVIDO.** Causa: keystore compartilhado entre espaços
   (alias fixo `AuthHeaderPrefs_single`, uid único). `IKeystoreServiceProxy`
   prefixa o alias por espaço, com fallback de migração e cache de buscas que
   falham — esse cache existe porque sem ele o retry dobrado travava o main
   thread. Verificado: 8/8 cold starts e 3/3 na segunda rodada. **Falta só
   confirmação de uso real.**
3. **Não recriar** `createFallbackPackageInfo`/`ApplicationInfo`: forjavam
   `versionName "1.0"` e assinatura vazia.
4. **Push/FCM continua quebrado e é problema separado** — provado, não inferido.
5. **UI redesenhada.** Sistema em `CLAUDE.md`: três pesos de superfície (painel /
   bloco / nada), três papéis de cor (lavanda = produto, cor do espaço =
   contexto, grafite = estrutura), quatro papéis de ícone, Inter em 400/500/600/700.
   **Não aninhar painéis. Não trocar de fonte.**
6. **Aparelho:** 7 espaços logados e estáveis; dois gravadores ativos
   (`ds_watch.log` e `ds_auth_watch.log`); restrição de fundo não aplicada.
7. **Build exige JDK 21**, instalar **debug** com `adb install -r --user 11`.
   A release quebra o `run-as`.
