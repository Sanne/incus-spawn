package dev.incusspawn.graal;

import java.io.IOException;
import java.io.InputStream;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

// SSLContextImpl$DefaultManagersHolder.<clinit> → KeyStore.load()
//     → JceKeyStore.engineLoad() → new ObjectInputStream(...)
// KeyManagerFactory.init() → KeyStore.getKey()
//     → JceKeyStore.engineGetKey() → KeyProtector.unseal() → SealedObject → extObjectInputStream
// Safe: this project never uses JCEKS keystores. With engineLoad throwing, a JCEKS
// KeyStore never initializes, so KeyStore.getKey() throws before it can reach engineGetKey.
// Both keep the JDK's object reader out of the image; the build fails if it comes back
// (-H:AbortOnMethodReachable=java.io.ObjectInputStream.readObject0, design/native-image.md).

@TargetClass(className = "com.sun.crypto.provider.JceKeyStore")
final class Target_com_sun_crypto_provider_JceKeyStore {

    @Substitute
    public void engineLoad(InputStream stream, char[] password)
            throws IOException, NoSuchAlgorithmException, CertificateException {
        throw new UnsupportedOperationException(
                "JCEKS keystore loading is excluded from native image");
    }

    @Substitute
    public Key engineGetKey(String alias, char[] password) {
        throw new UnsupportedOperationException(
                "JCEKS keystore keys are excluded from native image");
    }
}
