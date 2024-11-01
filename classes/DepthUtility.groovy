// File: DepthUtility.groovy
package utilities

import java.util.logging.Logger
import utilities.CustomNodeInfo

class DepthUtility {
    private static final Logger logger = Logger.getLogger(DepthUtility.class.name)

    // Constants to represent node types
    static final int SINK_TYPE = 1
    static final int REGULAR_NODE_TYPE = 2

    // Depth levels in meters with corresponding codes
    static final Map<Integer, Integer> DEPTH_CODES = [0: 0, 200: 1, 400: 2, 600: 3]
    static final List<Integer> ALLOWED_DEPTHS = [200, 400, 600]

    private static final Map<Integer, List<CustomNodeInfo>> nodesByDepth = [:].withDefault { [] }
    private static final Map<Integer, CustomNodeInfo> currentCoordinators = [:]

    /**
     * Generate node name based on the new format:
     * "T{typeCode}D{depthCode}I{index}"
     */
    static String generateNodeName(int typeCode, int depth, int index) {
        if (typeCode != SINK_TYPE && typeCode != REGULAR_NODE_TYPE) {
            throw new IllegalArgumentException("Invalid node type: ${typeCode}")
        }

        if (typeCode == SINK_TYPE && depth != 0) {
            throw new IllegalArgumentException("Sink node must have depth 0")
        } else if (typeCode == REGULAR_NODE_TYPE && !ALLOWED_DEPTHS.contains(depth)) {
            throw new IllegalArgumentException("Invalid depth for regular node: ${depth}")
        }

        if (!DEPTH_CODES.containsKey(depth)) {
            throw new IllegalArgumentException("Invalid depth: ${depth}")
        }

        int depthCode = DEPTH_CODES[depth]
        return "T${typeCode}D${depthCode}I${index}"
    }

    /**
     * Parse node name and return a CustomNodeInfo object.
     */
    static CustomNodeInfo parseNodeName(String nodeName) {
        def matcher = nodeName =~ /T(\d)D(\d)I(\d+)/
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid node name format: ${nodeName}")
        }
        int typeCode = matcher[0][1].toInteger()
        int depthCode = matcher[0][2].toInteger()
        int index = matcher[0][3].toInteger()

        int depth = DEPTH_CODES.find { it.value == depthCode }?.key
        if (depth == null) {
            throw new IllegalArgumentException("Invalid depth code in node name: ${depthCode}")
        }

        String initialType = (typeCode == SINK_TYPE) ? "sink" : "data"
        boolean isCoordinator = (index == 1) // Coordinator if index is 1

        int address = computeNodeAddress(typeCode, depthCode, index)

        logger.info("Parsed node name '${nodeName}' into type=${initialType}, depth=${depth}, index=${index}, isCoordinator=${isCoordinator}, address=${address}")
        return new CustomNodeInfo(
            nodeId: nodeName,
            initialType: initialType,
            currentRole: initialType,
            depth: depth,
            index: index,
            batteryLevel: 100.0,
            isActive: true,
            isCoordinator: isCoordinator,
            address: address
        )
    }

    /**
     * Compute node address based on the formula:
     * address = (typeCode * 64) + (depthCode * 16) + index
     */
    static int computeNodeAddress(int typeCode, int depthCode, int index) {
        int address = (typeCode * 64) + (depthCode * 16) + index
        if (address < 1 || address > 255) {
            throw new IllegalArgumentException("Computed address ${address} is out of valid range (1-255)")
        }
        return address
    }

    /**
     * Helper method to extract index from nodeName.
     */
    static int getIndexFromNodeName(String nodeName) {
        def matcher = nodeName =~ /T\dD\dI(\d+)/
        if (matcher.matches()) {
            return matcher[0][1].toInteger()
        } else {
            throw new IllegalArgumentException("Invalid node name format: ${nodeName}")
        }
    }

    /**
     * Register a node within the system, ensuring coordinators exist for each depth.
     */
    static synchronized void registerNode(CustomNodeInfo node) {
        if (node == null || (!ALLOWED_DEPTHS.contains((int) node.depth) && node.depth != 0)) {
            logger.warning("Attempted to register node with invalid depth or null: ${node}")
            return
        }

        logger.info("Registering node with ID ${node.nodeId}, type ${node.initialType}, depth ${node.depth}, address ${node.address}")

        int depthKey = (int) node.depth

        if (node.initialType == "sink") {
            node.updateRole("sink")
            node.isCoordinator = false
            currentCoordinators[0] = node
            logger.info("Registered sink node at depth ${node.depth} with ID ${node.nodeId}")
        } else {
            // Ensure the depth map is initialized
            if (!nodesByDepth.containsKey(depthKey)) {
                nodesByDepth[depthKey] = []
            }

            // Add node to depth map if not already present
            if (!nodesByDepth[depthKey].find { it.nodeId == node.nodeId }) {
                nodesByDepth[depthKey] << node
            }

            // Check and assign coordinator if none exists or current has lower battery
            if (!currentCoordinators.containsKey(depthKey) ||
                currentCoordinators[depthKey]?.batteryLevel < node.batteryLevel) {

                if (currentCoordinators[depthKey]) {
                    currentCoordinators[depthKey].updateRole("data")
                    currentCoordinators[depthKey].isCoordinator = false
                }

                node.updateRole("coordinator")
                node.isCoordinator = true
                currentCoordinators[depthKey] = node
                logger.info("Assigned new coordinator at depth ${node.depth} with ID ${node.nodeId}")
            } else {
                node.updateRole("data")
                node.isCoordinator = false
                logger.info("Registered data node at depth ${node.depth} with ID ${node.nodeId}")
            }
        }
    }

    /**
     * Get the coordinator for a specific depth.
     */
    static synchronized CustomNodeInfo getCoordinatorForDepth(double depth) {
        int depthKey = (int) depth
        CustomNodeInfo coordinator = currentCoordinators[depthKey]
        if (coordinator == null) {
            logger.warning("No coordinator found at depth ${depth}")
        }
        return coordinator
    }

    /**
     * Find the coordinator node at the next higher depth, or the sink if none available.
     */
    static synchronized CustomNodeInfo getUpstreamCoordinator(double currentDepth) {
        List<Integer> sortedDepths = ALLOWED_DEPTHS.sort()
        int depthKey = (int) currentDepth
        int depthIndex = sortedDepths.indexOf(depthKey)

        if (depthIndex < 0) {
            logger.warning("Invalid depth specified: ${currentDepth}")
            return null
        }

        if (depthIndex > 0) {
            Integer nextDepth = sortedDepths[depthIndex - 1]
            CustomNodeInfo upstreamCoordinator = currentCoordinators[nextDepth]
            if (upstreamCoordinator) {
                logger.info("Found upstream coordinator at depth ${nextDepth} for depth ${currentDepth}")
                return upstreamCoordinator
            }
        }

        CustomNodeInfo sinkCoordinator = currentCoordinators[0]
        if (sinkCoordinator) {
            logger.info("No upstream coordinator found above depth ${currentDepth}. Using sink node as fallback.")
            return sinkCoordinator
        }

        logger.warning("No upstream or sink coordinator available for depth ${currentDepth}")
        return null
    }

    /**
     * Update the battery level of a node.
     */
    static synchronized void updateBatteryLevel(String nodeId, double newLevel) {
        CustomNodeInfo node = findNodeById(nodeId)
        if (node) {
            double oldLevel = node.batteryLevel
            node.batteryLevel = newLevel
            logger.info("Updated battery level for node ${nodeId} from ${oldLevel} to ${newLevel}")

            // If this is a coordinator and battery is low, check for reassignment
            if (node.isCoordinator && newLevel < 50.0) {
                checkAndReassignCoordinator((int) node.depth)
            }
        } else {
            logger.warning("Node with ID ${nodeId} not found for battery update.")
        }
    }

    /**
     * Find a node by its ID across all depths.
     */
    private static synchronized CustomNodeInfo findNodeById(String nodeId) {
        // First check coordinators
        CustomNodeInfo node = currentCoordinators.values().find { it.nodeId == nodeId }

        // Then check all nodes at all depths
        if (!node) {
            node = nodesByDepth.values().flatten().find { it.nodeId == nodeId }
        }

        if (node == null) {
            logger.warning("Node with ID ${nodeId} could not be located in findNodeById.")
        } else {
            logger.info("Node with ID ${nodeId} found at depth ${node.depth}.")
        }
        return node
    }

    /**
     * Reassign coordinator based on battery level if the current coordinator is low on battery.
     */
    private static synchronized void checkAndReassignCoordinator(int depthKey) {
        List<CustomNodeInfo> depthNodes = nodesByDepth[depthKey]
        if (!depthNodes) return

        CustomNodeInfo bestCandidate = depthNodes.findAll { it.isActive }.max { it.batteryLevel }
        if (bestCandidate && bestCandidate != currentCoordinators[depthKey]) {
            CustomNodeInfo previousCoordinator = currentCoordinators[depthKey]
            if (previousCoordinator != null) previousCoordinator.updateRole("data")
            bestCandidate.updateRole("coordinator")
            currentCoordinators[depthKey] = bestCandidate
            logger.info("Coordinator reassigned at depth ${depthKey}m: ${bestCandidate.nodeId}")
        }
    }

    /**
     * Get the network status including nodes and coordinators.
     */
    static synchronized Map<String, Object> getNetworkStatus() {
        return [
            nodeCount: nodesByDepth.values().flatten().size(),
            coordinators: currentCoordinators.collect { depth, node ->
                [depth: depth, nodeId: node.nodeId, batteryLevel: node.batteryLevel]
            },
            averageBatteryByDepth: nodesByDepth.collectEntries { depth, nodes ->
                [depth, nodes.sum { it.batteryLevel } / nodes.size()]
            }
        ]
    }
}