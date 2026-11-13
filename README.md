# Eve

Assistente de IA pessoal para Android, por voz.

## O que a Eve faz hoje (versão 0.3)

- Você toca na Eve, fala, e ela responde falando (português do Brasil). A velocidade da voz é ajustável.
- **Lembretes:** "Eve, me lembra de ligar pro fornecedor amanhã às 9". Na hora, chega uma notificação.
  Também dá para pedir "quais são meus lembretes?" e "cancela o lembrete do fornecedor".
- **Agenda:** "o que eu tenho amanhã?" ou "marca dentista sexta às 15h". Usa a agenda do celular,
  que já sincroniza com o Google Agenda.
- **Mercado Livre:** "quanto eu vendi essa semana?" ou "o que mais vendeu ontem?". A Eve busca as
  vendas no servidor JR Mercado Livre, mostra o total e um gráfico, e responde falando.
- **Telas personalizadas:** quando a Eve mostra a agenda, os lembretes ou as vendas, ela vai para o canto
  e a informação aparece no meio da tela.
- A IA é "trocável": Google Gemini ou OpenRouter, escolhidos nas configurações.

O rosto da Eve é uma folhinha de agenda violeta com olhos, que pisca, respira e muda de expressão
(ouvindo, pensando, falando). É desenhado direto pelo app, inspirado no estilo do
[open-bot-avatar](https://github.com/DominikScholz/open-bot-avatar).
Funciona com o celular em pé ou deitado.

Ainda não faz: modo VR. Isso vem no próximo passo.

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

## Como conectar o Mercado Livre

A Eve usa o mesmo servidor MCP "JR Mercado Livre" que o Claude usa. O login do Mercado Livre
fica no servidor, não no celular.

1. Nas configurações da Eve (engrenagem), desça até **Mercado Livre**.
2. Cole o endereço do servidor (começa com `https://` e termina com `/mcp`).
3. Toque em **Entrar no Mercado Livre**. O navegador abre o mesmo login que você fez no Claude;
   depois de entrar, ele volta para a Eve, que avisa que está conectada.
4. Pergunte: "Eve, quanto eu vendi hoje?"

O endereço e o login ficam só no celular. O acesso se renova sozinho; se o servidor pedir de novo,
é só tocar em "Entrar no Mercado Livre" outra vez. Para a IA vão só números resumidos (totais por dia e
produtos mais vendidos), nunca nome ou apelido de comprador.

Para ver as telas antes de entrar, ligue **Usar vendas de exemplo** na mesma seção. A Eve mostra
números inventados, com o aviso "Vendas de exemplo (não são reais)" no cartão, e diz que são de
exemplo quando fala. Desligue para voltar às vendas reais.

## Para quem for mexer no código

- Kotlin + Jetpack Compose, `minSdk 26` (Android 8).
- `app/src/main/java/app/eve/assistente/`
  - `ai/` a "tomada" de IA (`AiProvider`) e o provedor Gemini.
  - `voice/` ouvir (reconhecimento de voz do Android) e falar (TTS do Android).
  - `assistant/` o "cérebro" (`EveBrain`), lembretes e agenda.
  - `mercadolivre/` cliente MCP (`McpClient`) e o resumo das vendas para o gráfico.
  - `ui/` o visual da Eve (`EveOrb`) e as configurações.
  - `EveViewModel.kt` junta tudo: ouvir, pensar, falar.
- O APK é gerado pelo GitHub Actions (`.github/workflows/build.yml`), que também roda os testes
  (`./gradlew testDebugUnitTest`).
- `screenshots.yml` abre a Eve num celular Android virtual com uma IA e vendas de exemplo e tira prints
  (ficam na branch `ci-screenshots`).
- `app/eve-debug.keystore` é uma chave de **teste**, pública de propósito, só para as atualizações
  instalarem por cima. Para uma chave privada, crie os secrets `EVE_KEYSTORE_BASE64`,
  `EVE_KEYSTORE_PASSWORD`, `EVE_KEY_ALIAS` e `EVE_KEY_PASSWORD` no repositório.
