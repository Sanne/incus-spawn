package dev.incusspawn;

import io.hyperfoil.tools.jjq.value.JqValue;

/**
 * Whether a workflow step checks out the repository, for the tests that require a step to run
 * after the checkout. Two tests once held their own copy, and one went stale when release.yml
 * moved from {@code git clone} to fetching the run's commit (#2750, #2763).
 */
final class WorkflowCheckout {

    private WorkflowCheckout() {}

    /** {@code actions/checkout}, or release.yml's own step that checks out the run's commit with git. */
    static boolean checksOut(JqValue step) {
        return step.pathText("uses", "").startsWith("actions/checkout@")
                || step.pathText("name", "").equals("Checkout") && step.pathText("run", "").contains("git checkout");
    }
}
