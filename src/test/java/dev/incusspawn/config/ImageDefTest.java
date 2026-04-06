package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImageDefTest {

    @Test
    void loadBuiltinsReturnsAllImages() {
        var defs = ImageDef.loadBuiltins();
        assertEquals(3, defs.size());
        assertTrue(defs.containsKey("golden-minimal"));
        assertTrue(defs.containsKey("golden-dev"));
        assertTrue(defs.containsKey("golden-java"));
    }

    @Test
    void minimalIsRoot() {
        var defs = ImageDef.loadBuiltins();
        var minimal = defs.get("golden-minimal");
        assertTrue(minimal.isRoot());
        assertNotNull(minimal.getImage());
        assertEquals("images:fedora/43", minimal.getImage());
        assertTrue(minimal.getPackages().isEmpty());
        assertTrue(minimal.getTools().isEmpty());
    }

    @Test
    void devExtendsMinimal() {
        var defs = ImageDef.loadBuiltins();
        var dev = defs.get("golden-dev");
        assertFalse(dev.isRoot());
        assertEquals("golden-minimal", dev.getParent());
        assertTrue(dev.getTools().contains("podman"));
        assertTrue(dev.getTools().contains("gh"));
        assertTrue(dev.getTools().contains("claude"));
    }

    @Test
    void javaExtendsDev() {
        var defs = ImageDef.loadBuiltins();
        var java = defs.get("golden-java");
        assertFalse(java.isRoot());
        assertEquals("golden-dev", java.getParent());
        assertTrue(java.getPackages().contains("java-25-openjdk-devel"));
        assertTrue(java.getTools().contains("maven-3"));
    }

    @Test
    void parentChainIsComplete() {
        var defs = ImageDef.loadBuiltins();
        // java -> dev -> minimal
        var java = defs.get("golden-java");
        var dev = defs.get(java.getParent());
        assertNotNull(dev);
        var minimal = defs.get(dev.getParent());
        assertNotNull(minimal);
        assertTrue(minimal.isRoot());
    }

    @Test
    void descriptionsAreSet() {
        var defs = ImageDef.loadBuiltins();
        assertFalse(defs.get("golden-minimal").getDescription().isEmpty());
        assertFalse(defs.get("golden-dev").getDescription().isEmpty());
        assertFalse(defs.get("golden-java").getDescription().isEmpty());
    }

    @Test
    void findByNameWorks() {
        var defs = ImageDef.loadBuiltins();
        assertNotNull(ImageDef.findByName("golden-java", defs));
        assertNull(ImageDef.findByName("nonexistent", defs));
    }
}
