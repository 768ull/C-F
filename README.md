# C-F-A — Coleção de Jogos e Aplicativos para Android

Projeto Android Gradle organizado diretamente na raiz do repositório.

## Estrutura
- `settings.gradle`, `build.gradle` e `gradle.properties` na raiz.
- Código Android e recursos em `app/src/main/`.
- `tools/` com scripts de apoio à compilação (`tools/mirror_site.py`).
- `.github/` reservado ao workflow de compilação em `.github/workflows/android-build.yml`.

## Compilar
Com JDK 17, Android SDK API 36 e Gradle 8.13:
```bash
python3 tools/mirror_site.py
gradle --no-daemon :app:assembleDebug
```

Para gerar a versão instalável estável, configure a keystore permanente nos mesmos parâmetros de ambiente usados pelo workflow e execute `:app:assembleRelease :app:bundleRelease`. O workflow **Android build** gera APK Release e AAB quando o segredo permanente está configurado; sem ele, publica apenas um APK Debug de desenvolvimento.

**Pacote dos jogos independentes:** o workflow reconstrói e valida o pacote original Frutris/Cobrinha a partir dos segmentos ordenados em `app/src/main/assets/bundle_parts/` antes de compilar.

**Simulador completo:** a terceira opção da coleção abre o telefone interativo Algorithomus, com Mensagens, Galeria, Telefone, Diário, Calculadora, Configurações, Tetris, Cobrinha e os demais aplicativos internos. O espelho de compilação copia as páginas e mídias primárias do manifesto para `app/src/main/assets/site/` (pasta ignorada no Git). A compilação falha se faltar uma página central ou uma mídia primária declarada pelo manifesto, evitando APKs com simulador parcialmente empacotado.

## Ajuste do botão SAIR

No Frutris, o botão SAIR fica em uma pequena faixa superior, sem cobrir o painel de próximas peças. O ajuste é aplicado durante a extração local no Android e não altera o motor, os controles ou os gráficos do jogo. A Cobrinha mantém o botão integrado ao cabeçalho.


## Pontuação e salvamento automático

A tela inicial exibe o placar geral, que soma os pontos de Frutris e Cobrinha separadamente. Cada incremento de pontuação é recebido pelo aplicativo uma única vez e gravado em SharedPreferences. Os jogos também mantêm recordes e configurações no armazenamento local do WebView (DOM Storage/localStorage). O backup Android está habilitado para os dados compatíveis.

## Simulador Algorithomus (híbrido: local + online)

A coleção mantém três opções separadas: Frutris, Cobrinha e **Simulador completo** (o telefone interativo Algorithomus). O simulador roda no WebView nativo, sem abrir navegador externo. HTML, CSS, JavaScript, imagens, áudios e vídeos são servidos primeiro do espelho incorporado no APK, então a interface e os recursos estáticos disponíveis continuam abrindo sem internet. Com uma conexão validada, as chamadas dinâmicas `/api/` seguem para `https://algorithomus.com.br` por HTTPS, no mesmo contexto de origem e com cookies reais. Somente o domínio original do jogo é autorizado para tráfego do simulador; demais destinos externos continuam bloqueados.

Quando uma conexão cai, as chamadas de API recebem HTTP 503 em vez de um falso HTTP 200. O WebView mantém em cache local algumas respostas de leitura obtidas anteriormente (lista/conteúdo de Mensagens e estado/conteúdo de dias do Diário) para permitir consulta offline do que já foi carregado. Ações que dependem do servidor — como enviar escolhas, validar códigos, concluir etapas e carregar conteúdo protegido nunca aberto — continuam exigindo internet. Isso evita inventar progresso ou conceder desbloqueios que o servidor não confirmou. Na primeira abertura, conecte-se à internet para carregar os dados dinâmicos que poderão ser consultados offline depois.

O espelho é baixado em cada compilação por `tools/mirror_site.py`, validado contra as páginas dos aplicativos e as mídias primárias do `media-manifest.json`, e incorporado ao APK em `app/src/main/assets/site/` (pasta ignorada no Git). Atualizações dessas páginas e mídias chegam com novos APKs; o mecanismo OTA assinado continua reservado aos dois jogos independentes.

## Atualizações seguras dos jogos

O botão **VERIFICAR ATUALIZAÇÕES** consulta um manifesto hospedado neste repositório. O app valida uma assinatura RSA/SHA-256 com a chave pública embarcada e depois confere o SHA-256 do pacote antes de substituir os arquivos locais. O WebView continua sem acesso à rede; somente o atualizador nativo acessa os três endereços HTTPS fixos de `raw.githubusercontent.com`. O workflow publica um novo pacote OTA assinado somente depois que a validação e a compilação do Android terminam com sucesso.

Para habilitar a assinatura automática das próximas versões, adicione o segredo de repositório **CF_UPDATE_PRIVATE_KEY_PEM** em **Settings → Secrets and variables → Actions** com o conteúdo do arquivo privado fornecido fora do repositório. Nunca envie essa chave ao GitHub como arquivo comum, commit ou variável pública. Sem esse segredo, a compilação dos APKs continua funcionando, mas o workflow não publica novos pacotes OTA assinados.

**Limite do Android:** atualizações OTA deste mecanismo substituem os arquivos dos jogos e preservam pontuação/recordes sem reinstalar o app. Alterações no código nativo ou na interface Android ainda exigem uma atualização do APK/AAB assinado com a mesma chave de instalação; o Android sempre pode pedir confirmação antes da instalação.


## Assinatura permanente do APK (obrigatória)

Quando a keystore permanente está configurada, o workflow assina as variantes Release com a identidade estável do C-F-A e publica APK e AAB. Enquanto o segredo não estiver configurado, o workflow ainda gera apenas um APK Debug de desenvolvimento, identificado com o sufixo ".debug" e certificado temporário; ele não substitui uma instalação estável existente.

Configure o único segredo `CF_ANDROID_SIGNING_PACKAGE_B64` conforme o arquivo privado `CF-Android-Signing-One-Secret-Instructions.txt` fornecido ao mantenedor. Nunca coloque a keystore ou senhas em commits.

**Migração inicial:** APKs anteriores de desenvolvimento foram assinados pela keystore temporária do runner. A nova keystore estável é uma identidade de assinatura nova, portanto o Android exigirá remover a instalação anterior de desenvolvimento uma única vez. Essa remoção apaga o armazenamento privado do app. Depois da migração, futuras versões Release com o mesmo applicationId, versionCode crescente e a mesma keystore atualizam por cima.
