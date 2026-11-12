# Eve

Assistente de IA pessoal para Android, por voz.

## O que a Eve faz hoje (versão 0.2)

- Você toca na Eve, fala, e ela responde falando (português do Brasil). A velocidade da voz é ajustável.
- **Lembretes:** "Eve, me lembra de ligar pro fornecedor amanhã às 9". Na hora, chega uma notificação.
  Também dá para pedir "quais são meus lembretes?" e "cancela o lembrete do fornecedor".
- **Agenda:** "o que eu tenho amanhã?" ou "marca dentista sexta às 15h". Usa a agenda do celular,
  que já sincroniza com o Google Agenda.
- **Telas personalizadas:** quando a Eve mostra a agenda ou os lembretes, ela vai para o canto
  e a informação aparece no meio da tela.
- A IA é "trocável": Google Gemini ou OpenRouter, escolhidos nas configurações.

O rosto da Eve é uma folhinha de agenda violeta com olhos, que pisca, respira e muda de expressão
(ouvindo, pensando, falando). O desenho usa o [open-bot-avatar](https://github.com/DominikScholz/open-bot-avatar) (licença MIT).
Funciona com o celular em pé ou deitado.

Ainda não faz: Mercado Livre e modo VR. Isso vem nos próximos passos.

## Como instalar no celular

1. No celular, abra: **https://github.com/lucas15122008-ux/eve/releases/latest/download/Eve.apk**
2. O arquivo `Eve.apk` vai baixar. Toque nele para instalar.
3. Se o Android pedir, permita "instalar apps desconhecidos" para o navegador ou o app de arquivos.
4. Abra a Eve. Na primeira vez ela pede a chave da API (veja abaixo) e a permissão do microfone.

Cada vez que o código muda na branch `main`, o GitHub gera uma versão nova automaticamente.
É só baixar e instalar de novo pelo mesmo link: instala por cima, sem perder as configurações.

## Como pegar a chave da API do Gemini (grátis)

1. Entre em **https://aistudio.google.com/apikey** com sua conta Google.
2. Toque em **Create API key** (Criar chave de API).
3. Copie a chave e cole na tela de configurações da Eve (ícone de engrenagem).

A chave fica guardada só no seu celular. Nunca coloque ela no código ou no GitHub.

## Ou usar a OpenRouter (modelos grátis)

1. Crie uma chave em **https://openrouter.ai/keys** (começa com `sk-or-`).
2. Nas configurações da Eve, escolha **OpenRouter** e cole a chave.
3. O modelo padrão é `nvidia/nemotron-3.5-super-vl:free` (grátis, aceita imagem).
4. Se der erro de modelo não disponível, ative os modelos grátis em **https://openrouter.ai/settings/privacy**.

> No plano grátis do Gemini, o Google pode usar as conversas para melhorar os produtos dele.
> Para usar com dados sensíveis (agenda, vendas), o ideal é passar para um plano pago.

## Para quem for mexer no código

- Kotlin + Jetpack Compose, `minSdk 26` (Android 8).
- `app/src/main/java/app/eve/assistente/`
  - `ai/` a "tomada" de IA (`AiProvider`) e o provedor Gemini.
  - `voice/` ouvir (reconhecimento de voz do Android) e falar (TTS do Android).
  - `ui/` o visual da Eve (`EveOrb`) e as configurações.
  - `EveViewModel.kt` junta tudo: ouvir, pensar, falar.
- O APK é gerado pelo GitHub Actions (`.github/workflows/build.yml`).
- `app/eve-debug.keystore` é uma chave de **teste**, pública de propósito, só para as atualizações
  instalarem por cima. Para uma chave privada, crie os secrets `EVE_KEYSTORE_BASE64`,
  `EVE_KEYSTORE_PASSWORD`, `EVE_KEY_ALIAS` e `EVE_KEY_PASSWORD` no repositório.
