package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImageDefTest {

    @Test
    void loadAllReturnsBuiltinImages() {
        var defs = ImageDef.loadAll();
        assertTrue(defs.size() >= 3, "Should load at least 3 built-in images");
        assertTrue(defs.containsKey("tpl-minimal"));
        assertTrue(defs.containsKey("tpl-dev"));
        assertTrue(defs.containsKey("tpl-java"));
    }

    @Test
    void minimalIsRoot() {
        var defs = ImageDef.loadAll();
        var minimal = defs.get("tpl-minimal");
        assertTrue(minimal.isRoot());
        assertNotNull(minimal.getImage());
        assertEquals("images:fedora/43", minimal.getImage());
        assertTrue(minimal.getPackages().isEmpty());
        assertTrue(minimal.getTools().isEmpty());
    }

    @Test
    void devExtendsMinimal() {
        var defs = ImageDef.loadAll();
        var dev = defs.get("tpl-dev");
        assertFalse(dev.isRoot());
        assertEquals("tpl-minimal", dev.getParent());
        assertTrue(dev.getTools().contains("podman"));
        assertTrue(dev.getTools().contains("gh"));
        assertTrue(dev.getTools().contains("claude"));
    }

    @Test
    void javaExtendsDev() {
        var defs = ImageDef.loadAll();
        var java = defs.get("tpl-java");
        assertFalse(java.isRoot());
        assertEquals("tpl-dev", java.getParent());
        assertTrue(java.getPackages().contains("java-25-openjdk-devel"));
        assertTrue(java.getTools().contains("maven-3"));
    }

    @Test
    void parentChainIsComplete() {
        var defs = ImageDef.loadAll();
        // java -> dev -> minimal
        var java = defs.get("tpl-java");
        var dev = defs.get(java.getParent());
        assertNotNull(dev);
        var minimal = defs.get(dev.getParent());
        assertNotNull(minimal);
        assertTrue(minimal.isRoot());
    }

    @Test
    void descriptionsAreSet() {
        var defs = ImageDef.loadAll();
        assertFalse(defs.get("tpl-minimal").getDescription().isEmpty());
        assertFalse(defs.get("tpl-dev").getDescription().isEmpty());
        assertFalse(defs.get("tpl-java").getDescription().isEmpty());
    }

    @Test
    void findByNameWorks() {
        var defs = ImageDef.loadAll();
        assertNotNull(ImageDef.findByName("tpl-java", defs));
        assertNull(ImageDef.findByName("nonexistent", defs));
    }
}
