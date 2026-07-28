package dev.incusspawn.graal;

import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

// SSLContextImpl$DefaultManagersHolder.<clinit> → KeyStore.load()
//     → JceKeyStore.engineLoad() → new ObjectInputStream(...)
// Safe: this project never uses JCEKS keystores.

@TargetClass(className = "com.sun.crypto.provider.JceKeyStore")
final class Target_com_sun_crypto_provider_JceKeyStore {

    @Substitute
    public void engineLoad(InputStream stream, char[] password)
            throws IOException, NoSuchAlgorithmException, CertificateException {
        throw new UnsupportedOperationException(
                "JCEKS keystore loading is excluded from native image");
    }
}
