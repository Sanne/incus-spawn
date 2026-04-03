package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import jakarta.enterprise.context.Dependent;

@Dependent
public class MavenSetup implements ToolSetup {

    @Override
    public String name() {
        return "maven-3";
    }

    @Override
    public void install(Container c) {
        System.out.println("Installing latest Maven from Apache...");
        c.runInteractive("Failed to install Maven", "sh", "-c",
                "MAVEN_VERSION=$(curl -s https://dlcdn.apache.org/maven/maven-3/ " +
                "| grep -oP '3\\.\\d+\\.\\d+' | sort -V | tail -1) && " +
                "echo \"Downloading Maven $MAVEN_VERSION...\" && " +
                "BASE_URL=https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries && " +
                "TARBALL=apache-maven-${MAVEN_VERSION}-bin.tar.gz && " +
                "curl -sL -o /tmp/${TARBALL} ${BASE_URL}/${TARBALL} && " +
                "curl -sL -o /tmp/${TARBALL}.sha512 ${BASE_URL}/${TARBALL}.sha512 && " +
                "echo \"$(cat /tmp/${TARBALL}.sha512)  /tmp/${TARBALL}\" | sha512sum -c - && " +
                "tar xzf /tmp/${TARBALL} -C /opt && " +
                "ln -sf /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn && " +
                "rm -f /tmp/${TARBALL} /tmp/${TARBALL}.sha512");

        // Verify
        var result = c.exec("mvn", "--version");
        if (result.success()) {
            System.out.println("  Maven installed: " + result.stdout().lines().findFirst().orElse(""));
        } else {
            System.err.println("  Warning: Maven installation may have failed.");
        }
    }
}
