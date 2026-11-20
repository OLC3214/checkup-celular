# Check-up Celular (Android)

O que o app faz:
1. **Apps suspeitos** – lista apps instalados fora da loja, escondidos (sem ícone), com Acessibilidade ligada,
   administradores do aparelho, leitores de SMS/notificações. Botão para desinstalar direto.
2. **Segurança** – senha de tela, depuração USB, opções do desenvolvedor, idade da atualização de segurança.
3. **Limpeza** – acha APKs esquecidos, miniaturas e temporários e apaga com confirmação. Atalho para limpar cache dos apps.

## Como gerar o APK (sem instalar nada no PC)
1. Crie uma conta no github.com e um repositório novo (pode ser privado).
2. Envie esta pasta inteira para o repositório (botão "Add file > Upload files").
3. Vá na aba **Actions** > "Gerar APK" > espere ficar verde (uns 4 minutos).
4. Clique na execução e baixe **CheckupCelular-apk** em "Artifacts". Dentro tem o `app-debug.apk`.

Ou, no Android Studio: File > Open nesta pasta > Build > Build APK.

## Instalar no celular da pessoa
Mande o APK (WhatsApp manda como documento), toque nele e autorize "instalar apps desconhecidos".
Depois de usar, dá pra desinstalar o Check-up e desligar de novo essa permissão.
