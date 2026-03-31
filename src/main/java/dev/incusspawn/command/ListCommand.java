package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Command(
        name = "list",
        description = "List all incus-spawn environments, grouped by project",
        mixinStandardHelpOptions = true
)
public class ListCommand implements Runnable {

    @Inject
    IncusClient incus;

    @Override
    public void run() {
        var instances = incus.list();
        if (instances.isEmpty()) {
            System.out.println("No incus-spawn environments found.");
            System.out.println("Run 'incus-spawn build golden-minimal' to create your first golden image.");
            return;
        }

        // Collect metadata for each instance
        var entries = new ArrayList<InstanceInfo>();
        for (var instance : instances) {
            var name = instance.get("name");
            var type = getConfigOrDefault(name, Metadata.TYPE, "");
            // Only show incus-spawn managed instances
            if (type.isEmpty()) continue;

            entries.add(new InstanceInfo(
                    name,
                    instance.get("status"),
                    type,
                    getConfigOrDefault(name, Metadata.PROJECT, "-"),
                    getConfigOrDefault(name, Metadata.PROFILE, "-"),
                    getConfigOrDefault(name, Metadata.CREATED, ""),
                    instance.get("type")
            ));
        }

        if (entries.isEmpty()) {
            System.out.println("No incus-spawn environments found.");
            return;
        }

        // Group by project
        Map<String, List<InstanceInfo>> grouped = new LinkedHashMap<>();
        // Show base images first, then projects, then clones
        for (var entry : entries) {
            if (entry.type.equals(Metadata.TYPE_BASE)) {
                grouped.computeIfAbsent("Base Images", k -> new ArrayList<>()).add(entry);
            }
        }
        for (var entry : entries) {
            if (entry.type.equals(Metadata.TYPE_PROJECT)) {
                grouped.computeIfAbsent("Project: " + entry.name, k -> new ArrayList<>()).add(entry);
            }
        }
        for (var entry : entries) {
            if (entry.type.equals(Metadata.TYPE_CLONE)) {
                grouped.computeIfAbsent("Project: " + entry.project, k -> new ArrayList<>()).add(entry);
            }
        }

        // Print table
        var nameWidth = Math.max(20, entries.stream().mapToInt(e -> e.name.length()).max().orElse(20));
        var fmt = "  %-" + nameWidth + "s  %-10s  %-10s  %-10s  %s%n";

        for (var group : grouped.entrySet()) {
            System.out.println("\n" + group.getKey());
            System.out.printf(fmt, "NAME", "STATUS", "TYPE", "RUNTIME", "AGE");
            System.out.printf(fmt,
                    "-".repeat(nameWidth), "----------", "----------", "----------", "---");

            for (var entry : group.getValue()) {
                var age = entry.created.isEmpty() ? "-" : Metadata.ageDescription(entry.created);
                System.out.printf(fmt, entry.name, entry.status, entry.type, entry.runtime, age);
            }
        }
        System.out.println();
    }

    private String getConfigOrDefault(String name, String key, String defaultValue) {
        try {
            var value = incus.configGet(name, key);
            return value.isEmpty() ? defaultValue : value;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private record InstanceInfo(
            String name, String status, String type,
            String project, String profile, String created,
            String runtime
    ) {}
}
