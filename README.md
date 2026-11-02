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

**Estado dos jogos:** a estrutura do aplicativo está publicada, mas o pacote `games.bundle.zip` ainda não está no repositório. Até ele ser incluído em `app/src/main/assets/`, o APK não conterá os jogos jogáveis.

## Ajuste do botão SAIR

No Frutris, o botão SAIR fica em uma pequena faixa superior, sem cobrir o painel de próximas peças. O ajuste é aplicado durante a extração local no Android e não altera o motor, os controles ou os gráficos do jogo. A Cobrinha mantém o botão integrado ao cabeçalho.
