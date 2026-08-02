# Dual Space Livre

Aplicativo Android para executar cópias isoladas de outros aplicativos em
espaços virtuais, sem anúncios e sem criar usuários adicionais no Android.

## Estado desta versão

- Nome do pacote: `com.dualspace.livre`
- Versão: `0.1.0`
- Android mínimo: 5.0
- Android de destino: 12
- Arquiteturas geradas: ARM64 e ARMv7
- Aparelho validado: Moto G50 com Android 12
- Cinco espaços virtuais foram criados e executados no mesmo usuário Android.

Cada espaço mantém seus próprios dados de aplicativos. A implementação também
fornece um Advertising ID estável por espaço e mantém o App Set ID isolado pelos
dados virtuais do aplicativo. O teste no aparelho, usando as APIs oficiais dos
SDKs do Google, produziu valores diferentes nos espaços 1 e 2:

| Identificador | Espaço 1 | Espaço 2 |
| --- | --- | --- |
| Advertising ID (hash abreviado) | `D242…8A` | `2827…CE` |
| App Set ID (hash abreviado) | `7121…A9` | `5996…92` |

Os valores completos não são registrados nem enviados para fora do aparelho.

## Privacidade

- Sem SDK de anúncios.
- Sem telemetria própria.
- Sem envio remoto de logs.
- Sem solicitação automática de VPN.
- Sem solicitação automática de acesso a todos os arquivos.

A permissão de internet permanece porque os aplicativos executados dentro dos
espaços precisam acessar a rede.

## Limitações

A compatibilidade depende do aplicativo clonado. Aplicativos bancários, jogos
com proteção contra adulteração e serviços que exigem Play Integrity podem
detectar a virtualização ou recusar a execução. Login do Google dentro de
aplicativos clonados ainda não é garantido nesta versão.

O isolamento foi verificado no Moto G50 para os caminhos oficiais do
Advertising ID e App Set ID. Isso não significa que todo SDK particular ou
identificador proprietário será alterado.

## Compilação

Abra o projeto no Android Studio e use a variante `debug`, ou execute:

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
```

O APK ARM64 é o indicado para o Moto G50.

## Origem e licença

Este trabalho deriva do projeto
[NewBlackbox](https://github.com/ALEX5402/NewBlackbox), sob a licença Apache
2.0. O texto integral da licença está no arquivo `LICENSE`.
