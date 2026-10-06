package dev.incusspawn.proxy;

import com.sun.management.HotSpotDiagnosticMXBean;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The tests here run real TLS on every hop (client, MITM server, upstream client, mock
 * upstream), all in one JVM. On GraalVM 25's JIT, AESCrypt.makeSessionKey sometimes gives
 * one cipher a wrong AES-256 key schedule while warming up, and that connection fails with
 * bad_record_mac (#940, upstream:
 * <a href="https://github.com/oracle/graal/issues/14599">oracle/graal#14599</a>). The root
 * pom's {@code argLine} property keeps the method interpreted; if that is lost (a module
 * setting surefire's own {@code <argLine>}, say), these tests go back to failing once in a
 * while, on whatever test was running, and nothing points here.
 */
class JitWorkaroundTest {

    @Test
    void aesKeyExpansionIsNeverJitCompiled() {
        var args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        assertTrue(args.contains("-XX:CompileCommand=exclude,com.sun.crypto.provider.AESCrypt::makeSessionKey"),
                "the test JVM must keep AESCrypt.makeSessionKey out of the JIT (see the root pom); it ran with " + args);
    }

    @Test
    void excludedMethodExistsWhereTheJitBugIs() throws Exception {
        // An exclude naming a method that no longer exists is silently ignored (the flag is quiet).
        // Only the Graal JIT needs it: JDK 27 renamed the class to AES_Crypt, without makeSessionKey (#1050)
        var hotspot = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        assumeTrue(runsGraalJit(name -> hotspot.getVMOption(name).getValue()),
                "the JIT bug is GraalVM's only; this JVM does not compile with it");
        Class.forName("com.sun.crypto.provider.AESCrypt").getDeclaredMethod("makeSessionKey", byte[].class);
    }

    @Test
    void onlyAGraalJitNeedsTheExclude() {
        assertTrue(runsGraalJit(name -> "true"));
        assertFalse(runsGraalJit(name -> "false"), "GraalVM told to run C2");
        assertFalse(runsGraalJit(name -> { throw new IllegalArgumentException(name); }),
                "a stock JDK has no JVMCI flags at all");
    }

    /** {@code vmOption} reads a VM flag and throws IllegalArgumentException for one this JVM does not have. */
    static boolean runsGraalJit(Function<String, String> vmOption) {
        try {
            return Boolean.parseBoolean(vmOption.apply("UseJVMCICompiler"));
        } catch (IllegalArgumentException noJvmci) {
            return false;
        }
    }
}
