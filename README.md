# C-F — Coleção de Jogos para Android

Projeto Android organizado diretamente na raiz do repositório, com o módulo Gradle `app/` no caminho padrão.

## Estrutura
```
C-F/
├── settings.gradle
├── build.gradle
├── gradle.properties
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/br/com/colecaocf/MainActivity.java
│       ├── assets/
│       └── res/
└── .github/workflows/android-build.yml
```

## Recursos
- Seletor Android para Frutris e Cobrinha.
- Aplicativo em tela cheia e sem zoom.
- Jogos empacotados localmente e sem acesso de rede pelo WebView.
- O botão SAIR dos jogos retorna à coleção.
- Ícone adaptativo com fundo vermelho, C amarela e F roxa.

## Compilação
JDK 17, Android SDK API 36, Build Tools 36.0.0 e Gradle 8.13:
```bash
gradle --no-daemon :app:assembleDebug :app:bundleDebug
```
O workflow publica APK e AAB na aba Actions como `CF-Collection-Android`.
Os jogos e as suas marcas permanecem sujeitos aos direitos dos respectivos titulares.
