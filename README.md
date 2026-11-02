# C-F — Coleção de Jogos Android

Projeto Android Gradle organizado diretamente na raiz do repositório.

## Estrutura
- `settings.gradle`, `build.gradle` e `gradle.properties` na raiz.
- Código Android e recursos em `app/src/main/`.
- `.github/` reservado ao workflow de compilação em `.github/workflows/android-build.yml`.

## Compilar
Com JDK 17, Android SDK API 36 e Gradle 8.13:
```bash
gradle --no-daemon :app:assembleDebug :app:bundleDebug
```

O workflow **Android build** gera APK e AAB na aba Actions.

**Pacote dos jogos:** o workflow reconstrói e valida o pacote original a partir dos segmentos ordenados em `app/src/main/assets/bundle_parts/` antes de compilar.

## Ajuste do botão SAIR

No Frutris, o botão SAIR fica em uma pequena faixa superior, sem cobrir o painel de próximas peças. O ajuste é aplicado durante a extração local no Android e não altera o motor, os controles ou os gráficos do jogo. A Cobrinha mantém o botão integrado ao cabeçalho.


## Pontuação e salvamento automático

A tela inicial exibe o placar geral, que soma os pontos de Frutris e Cobrinha separadamente. Cada incremento de pontuação é recebido pelo aplicativo uma única vez e gravado em SharedPreferences. Os jogos também mantêm recordes e configurações no armazenamento local do WebView (DOM Storage/localStorage). O backup Android está habilitado para os dados compatíveis.

## Atualizações seguras dos jogos

O botão **VERIFICAR ATUALIZAÇÕES** consulta um manifesto hospedado neste repositório. O app valida uma assinatura RSA/SHA-256 com a chave pública embarcada e depois confere o SHA-256 do pacote antes de substituir os arquivos locais. O WebView continua sem acesso à rede; somente o atualizador nativo acessa os três endereços HTTPS fixos de `raw.githubusercontent.com`. O workflow publica um novo pacote OTA assinado somente depois que a validação e a compilação do Android terminam com sucesso.

Para habilitar a assinatura automática das próximas versões, adicione o segredo de repositório **CF_UPDATE_PRIVATE_KEY_PEM** em **Settings → Secrets and variables → Actions** com o conteúdo do arquivo privado fornecido fora do repositório. Nunca envie essa chave ao GitHub como arquivo comum, commit ou variável pública. Sem esse segredo, a compilação dos APKs continua funcionando, mas o workflow não publica novos pacotes OTA assinados.

**Limite do Android:** atualizações OTA deste mecanismo substituem os arquivos dos jogos e preservam pontuação/recordes sem reinstalar o app. Alterações no código nativo ou na interface Android ainda exigem uma atualização do APK/AAB assinado com a mesma chave de instalação; o Android sempre pode pedir confirmação antes da instalação.


## Assinatura permanente do APK (obrigatória)

Todas as variantes instaláveis são assinadas com a mesma keystore permanente, fornecida ao workflow por GitHub Actions Secrets. O workflow interrompe a compilação se a chave estável não estiver configurada; não publica APKs assinados com chaves debug efêmeras.

Configure o único segredo `CF_ANDROID_SIGNING_PACKAGE_B64` conforme o arquivo privado `CF-Android-Signing-One-Secret-Instructions.txt` fornecido ao mantenedor. Nunca coloque a keystore ou senhas em commits.

**Migração inicial:** APKs anteriores foram gerados como debug e assinados pela keystore temporária do runner. A nova keystore estável é uma identidade de assinatura nova, portanto o Android exigirá remover a instalação anterior uma única vez. Essa remoção apaga o armazenamento privado do app. Depois da migração, futuras versões com o mesmo applicationId, versionCode crescente e a mesma keystore atualizam por cima. 
