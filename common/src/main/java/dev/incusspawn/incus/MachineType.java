package dev.incusspawn.incus;

import io.hyperfoil.tools.jjq.value.JqValue;

public enum MachineType {
    CONTAINER,
    VM;

    public String incusApiType() {
        return this == VM ? "virtual-machine" : "container";
    }

    public static MachineType fromIncus(String apiType) {
        return "virtual-machine".equals(apiType) ? VM : CONTAINER;
    }

    public static MachineType fromIncus(JqValue instanceMetadata) {
        if (instanceMetadata == null) return CONTAINER;
        return fromIncus(instanceMetadata.path("type").asText(""));
    }
}
