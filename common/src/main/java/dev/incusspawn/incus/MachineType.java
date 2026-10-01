package dev.incusspawn.incus;

import com.fasterxml.jackson.databind.JsonNode;

public enum MachineType {
    CONTAINER,
    VM;

    public String incusApiType() {
        return this == VM ? "virtual-machine" : "container";
    }

    public static MachineType fromIncus(String apiType) {
        return "virtual-machine".equals(apiType) ? VM : CONTAINER;
    }

    public static MachineType fromIncus(JsonNode instanceMetadata) {
        if (instanceMetadata == null) return CONTAINER;
        return fromIncus(instanceMetadata.path("type").asText(""));
    }
}
