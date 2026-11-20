package dev.incusspawn.config;

import java.util.regex.Pattern;

/**
 * The first line of a YAML file isx writes, which tells an editor's YAML language server the JSON Schema
 * to complete and check it with (site/schema/, design/templates.md "A schema for editors"). isx ignores
 * the line when it reads the file.
 * <p>
 * Every writer of such a file goes through here, so a file isx creates has the line whichever command made it.
 */
public enum SchemaModeline {
    TEMPLATE("template"),
    TOOL("tool"),
    CONFIG("config"),
    MCP("mcp");

    private static final String PREFIX = "# yaml-language-server: $schema=https://isx.run/schema/";
    /** A modeline of any schema, in the comment lines a file opens with. */
    private static final Pattern ANY = Pattern.compile("(?m)\\A(?:[ \\t]*#[^\\n]*\\n|[ \\t]*\\n)*?[ \\t]*# yaml-language-server:");

    private final String line;

    SchemaModeline(String schema) {
        this.line = PREFIX + schema + ".json";
    }

    /** The line, without its newline. */
    public String line() {
        return line;
    }

    /**
     * {@code yaml} with the line first, unless a modeline already opens the file, whichever schema it names: the
     * person who wrote one chose it. Content after a first line that is not a comment is never looked into.
     */
    public String applyTo(String yaml) {
        return ANY.matcher(yaml).find() ? yaml : line + "\n" + yaml;
    }
}
