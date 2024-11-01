// File: CustomNodeInfo.groovy
package utilities

@groovy.transform.Canonical
class CustomNodeInfo {
    String nodeId           // Unique node identifier in the new 7-digit format
    String initialType      // Initial type (sink or data)
    String currentRole      // Current role (sink, coordinator, data), allowing dynamic role changes
    double depth            // Node depth (0 for sink, 200, 400, 600 for regular nodes)
    int index               // Node index for naming
    double batteryLevel     // Battery level, used for reassignment decisions
    boolean isActive        // Node status (active/inactive)
    boolean isCoordinator   // Flag to indicate if the node is a coordinator

    /**
     * Updates the current role of the node. If it was initially a different role, we log the change.
     */
    void updateRole(String newRole) {
        if (this.currentRole != newRole) {
            println "Node ${nodeId} role updated from ${this.currentRole} to ${newRole}"
            this.currentRole = newRole
            this.isCoordinator = (newRole == "coordinator")
        }
    }

    /**
     * Returns a string representation of the node, helpful for logging and debugging.
     */
    @Override
    String toString() {
        return "Node ID: ${nodeId}, Role: ${currentRole}, Depth: ${depth}m, Index: ${index}, Battery: ${batteryLevel}%, Active: ${isActive}, Coordinator: ${isCoordinator}"
    }
}