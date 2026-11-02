package br.com.colecaocf;

import android.util.Base64;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

final class UpdateSignatureVerifier {
    private static final String PUBLIC_KEY_DER = "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEA3QkkIIBYm8FUa5tXLc/wVw4FRWxl+a7U1FpOmA0l6ePMjJmqmLr54+zdz2zfoCpQ5rpyvoeVmLry8M+Nb83i5qlCUUzzow//jmDLc+kDF6AGOPK5QsMWPLnSvTXrBpucnRthc7CzFprUDseQwifkNRVXwLqkNTVK+yfCCugNkz4+c4ScuD7ebpcyEgSBJXMg55PibJwoKMuXsxAcf8v3YUECiO73ObnbTDoTsOOe1FRHMIsu1OebsQeE0Fxemu2+TRfISULRxJMu77nkZ3tenvr8kDf9eBpjSyMnzRVEHrseXr1oz4LMiXTh3YQou0VktMeUMrWurOmEUX5irfALI8KCAO4tPjcs9k3RurvFqh3xHWcJJkub+3syWwsaCNEEVA667HDICz3AdSFO9kslOopSo7yb7IvkdP1O0GhxKM20nDnsWMfJD9wOr5oD1nj6sOOpfY0r9PaLun1x//auP8G565Up9QAQlc6Rao4rJkAxx6bi6O99zpO7GYDhppLTAgMBAAE=";

    private UpdateSignatureVerifier() {}

    static boolean verify(byte[] message, byte[] signatureBytes) {
        try {
            byte[] encodedKey = Base64.decode(PUBLIC_KEY_DER, Base64.DEFAULT);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(encodedKey);
            java.security.PublicKey key = KeyFactory.getInstance("RSA").generatePublic(spec);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(message);
            return verifier.verify(signatureBytes);
        } catch (Exception error) {
            return false;
        }
    }
}
