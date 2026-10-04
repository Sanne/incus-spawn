package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tests here run real TLS on every hop (client, MITM server, upstream client, mock
 * upstream), all in one JVM. On GraalVM 25's JIT, AESCrypt.makeSessionKey sometimes gives
 * one cipher a wrong AES-256 key schedule while warming up, and that connection fails with
 * bad_record_mac (#940). The root pom's {@code argLine} property keeps the method
 * interpreted; if that is lost (a module setting surefire's own {@code <argLine>}, say), these
 * tests go back to failing once in a while, on whatever test was running, and nothing points here.
 */
class JitWorkaroundTest {

    @Test
    void aesKeyExpansionIsNeverJitCompiled() throws Exception {
        // An exclude naming a method that no longer exists is silently ignored (the flag is quiet)
        Class.forName("com.sun.crypto.provider.AESCrypt").getDeclaredMethod("makeSessionKey", byte[].class);
        var args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        assertTrue(args.contains("-XX:CompileCommand=exclude,com.sun.crypto.provider.AESCrypt::makeSessionKey"),
                "the test JVM must keep AESCrypt.makeSessionKey out of the JIT (see the root pom); it ran with " + args);
    }
}
