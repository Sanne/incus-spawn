package dev.incusspawn.lifecycle;

import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/** The swap of a rebuilt template against isx processes copying from it (#1212). */
@ExtendWith(TempHome.class)
class TemplateLockTest {

    /** One build or update of a template at a time (#1760), refused rather than waited for, and free again once done. */
    @Test
    void aSecondBuildOfTheSameTemplateIsRefusedWithoutWaiting() throws Exception {
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = TemplateLock.building("tpl").orElseThrow();
            try {
                assertTrue(pool.submit(() -> TemplateLock.building("tpl").isEmpty()).get(5, TimeUnit.SECONDS));
                var other = pool.submit(() -> TemplateLock.building("tpl-other")).get(5, TimeUnit.SECONDS);
                assertTrue(other.isPresent(), "another template builds meanwhile");
                pool.submit(() -> other.get().close()).get(5, TimeUnit.SECONDS);
            } finally {
                first.close();
            }
            var again = pool.submit(() -> TemplateLock.building("tpl")).get(5, TimeUnit.SECONDS);
            assertTrue(again.isPresent());
            pool.submit(() -> again.get().close()).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void theSwapWaitsForACopyUnderWay() throws Exception {
        var incus = mock(IncusClient.class);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var reading = TemplateLock.reading("tpl", RecordingOutput.QUIET);
            try {
                var swap = pool.submit(() -> TemplateLock.replace(incus, "tpl-rebuilding", "tpl", RecordingOutput.QUIET));
                Thread.sleep(200);
                assertFalse(swap.isDone(), "the template was swapped while it was being copied");
                verifyNoInteractions(incus);
                reading.close();
                swap.get(10, TimeUnit.SECONDS);
            } finally {
                reading.close();
            }
        }
        var order = inOrder(incus);
        order.verify(incus).deleteIfExists("tpl");
        order.verify(incus).rename("tpl-rebuilding", "tpl");
    }

    @Test
    void aCopyWaitsOutTheSwapRatherThanFindTheTemplateGone() throws Exception {
        var incus = mock(IncusClient.class);
        var deleted = new CountDownLatch(1);
        var finishSwap = new CountDownLatch(1);
        // Stops the swap in its gap: the old template deleted, the new one not yet renamed
        doAnswer(inv -> {
            deleted.countDown();
            assertTrue(finishSwap.await(10, TimeUnit.SECONDS));
            return null;
        }).when(incus).deleteIfExists("tpl");
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var swap = pool.submit(() -> TemplateLock.replace(incus, "tpl-rebuilding", "tpl", RecordingOutput.QUIET));
                assertTrue(deleted.await(10, TimeUnit.SECONDS));
                var copy = pool.submit(() -> {
                    try (var reading = TemplateLock.reading("tpl", RecordingOutput.QUIET)) {
                        // What a copy would find: renamed into place
                        verify(incus).rename("tpl-rebuilding", "tpl");
                        return true;
                    }
                });
                Thread.sleep(200);
                assertFalse(copy.isDone(), "a copy went ahead while the template was missing");
                finishSwap.countDown();
                swap.get(10, TimeUnit.SECONDS);
                assertTrue(copy.get(10, TimeUnit.SECONDS));
            } finally {
                finishSwap.countDown();
            }
        }
    }

    @Test
    void aNameNoInstanceCanHaveGetsNoLockFile() {
        // An untrusted name (a project-local name:, an agent's argument) must not become a path
        try (var held = TemplateLock.reading("../../escaped", RecordingOutput.QUIET)) {
            assertFalse(java.nio.file.Files.exists(dev.incusspawn.Environment.lockDir().resolve("escaped.lock")));
            assertFalse(java.nio.file.Files.exists(dev.incusspawn.Environment.lockDir().resolve("templates")));
        }
    }
}
