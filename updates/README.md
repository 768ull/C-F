# Atualizações OTA assinadas

O app fixa a chave pública RSA em `UpdateSignatureVerifier.java` e aceita apenas manifestos assinados com SHA-256/RSA.

- `latest.json`: manifesto assinado.
- `latest.json.sig.b64`: assinatura RSA destacada em Base64.
- `games.bundle.zip.b64`: pacote dos jogos em Base64, publicado pelo workflow quando o segredo estiver configurado.

Para habilitar as próximas publicações, cadastre o segredo de repositório `CF_UPDATE_PRIVATE_KEY_PEM` em **Settings → Secrets and variables → Actions**, colando o conteúdo do arquivo PEM privado fornecido separadamente. Não coloque a chave privada nesta pasta nem em commits.

O manifesto inicial representa o pacote atualmente incorporado ao APK. Ele usa bundleVersion 1; versões futuras serão publicadas com o número da execução do GitHub Actions e o SHA-256 do ZIP recém-compilado.
