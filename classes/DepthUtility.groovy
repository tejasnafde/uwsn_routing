package utilities

import java.util.logging.Logger
import utilities.CustomNodeInfo

class DepthUtility {
    private static final Logger logger = Logger.getLogger(DepthUtility.class.name)

    // Constants to represent node types
    static final int SINK_TYPE = 1
    static final int REGULAR_NODE_TYPE = 2

    // Depth levels in meters with corresponding codes
    static final List<Integer> ALLOWED_DEPTHS = [200, 400, 600]
    private static final Map<Integer, List<CustomNodeInfo>> nodesByDepth = [:].withDefault { [] }
    private static final Map<Integer, CustomNodeInfo> currentCoordinators = [:]

    /**
     * Generate a 7-digit node name based on the new format:
     * - 1 digit for type (1 for sink, 2 for regular nodes)
     * - 3 digits for depth (e.g., 200, 400, 600)
     * - 3 digits for index
     */
    static String generateNodeName(int type, int depth, int index) {
        if (type != SINK_TYPE && type != REGULAR_NODE_TYPE) {
            throw new IllegalArgumentException("Invalid node type: ${type}")
        }

        if (type == SINK_TYPE && depth != 0) {
            throw new IllegalArgumentException("Sink node must have depth 0")
        } else if (type == REGULAR_NODE_TYPE && !ALLOWED_DEPTHS.contains(depth)) {
            throw new IllegalArgumentException("Invalid depth for regular node: ${depth}")
        }

        String depthCode = String.format('%03d', depth)
        String indexCode = String.format('%03d', index)
        return "${type}${depthCode}${indexCode}"
    }

    /**
     * Parse a 7-digit node name and return a CustomNodeInfo object.
     */
    static CustomNodeInfo parseNodeName(String nodeName) {
        if (nodeName.length() != 7) throw new IllegalArgumentException("Invalid node name length.")

        int type = nodeName[0].toInteger()
        int depth = nodeName[1..3].toInteger()
        int index = nodeName[4..6].toInteger()

        // Validate sink depth
        if (type == SINK_TYPE && depth != 0) {
            throw new IllegalArgumentException("Sink node depth must be 0.")
        } else if (type == REGULAR_NODE_TYPE && !ALLOWED_DEPTHS.contains(depth)) {
            throw new IllegalArgumentException("Invalid depth for regular node.")
        }

        String initialType = (type == SINK_TYPE) ? "sink" : "data"
        boolean isCoordinator = (index == 1) // or any logic you need to determine if it's coordinator
        logger.info("Parsed node name '${nodeName}' into type=${initialType}, depth=${depth}, index=${index}, isCoordinator=${isCoordinator}")
        return new CustomNodeInfo(nodeId: nodeName, initialType: initialType, currentRole: initialType, depth: depth, index: index, batteryLevel: 100.0, isActive: true, isCoordinator: isCoordinator)
    }


    /**
     * Register a node within the system, keeping track of coordinators and nodes by depth.
     */
    static synchronized void registerNode(CustomNodeInfo node) {
        logger.info("Attempting to register node with ID ${node.nodeId}, type ${node.initialType}, depth ${node.depth}.")

        if (node == null || (!ALLOWED_DEPTHS.contains(node.depth) && node.depth != 0)) {
            logger.warning("Attempted to register node with invalid depth or null: ${node}")
            return
        }

        if (node.initialType == "sink") {
            node.updateRole("sink")
            node.isCoordinator = false
            logger.info("Registered sink node at depth ${node.depth} with ID ${node.nodeId}")
        } else {
            node.updateRole("data")
            nodesByDepth[node.depth as Integer] << node

            // Assign coordinator if none exists for this depth
            if (!currentCoordinators.containsKey(node.depth)) {
                currentCoordinators[node.depth as Integer] = node
                node.updateRole("coordinator")
                logger.info("Assigned initial coordinator at depth ${node.depth} with ID ${node.nodeId}")
            } else {
                logger.info("Registered data node at depth ${node.depth} with ID ${node.nodeId}")
            }
        }
    }

    /**
     * Get the coordinator for a specific depth.
     */
    static synchronized CustomNodeInfo getCoordinatorForDepth(double depth) {
        CustomNodeInfo coordinator = currentCoordinators[depth as Integer]
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
        int depthIndex = sortedDepths.indexOf(currentDepth as Integer)

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
     * Update battery level for a node, and reassign coordinator if needed.
     */
    static synchronized void updateBatteryLevel(String nodeId, double newLevel) {
        CustomNodeInfo node = findNodeById(nodeId)
        if (node) {
            node.batteryLevel = newLevel
            if (node.currentRole == "coordinator") {
                checkAndReassignCoordinator(node.depth as Integer)
            }
        } else {
            logger.warning("Node with ID ${nodeId} not found for battery update.")
        }
    }

    /**
     * Find a node by its ID across all depths.
     */
    private static synchronized CustomNodeInfo findNodeById(String nodeId) {
        logger.info("Searching for node with ID: ${nodeId} across all depths.")
        CustomNodeInfo node = nodesByDepth.values().flatten().find { it.nodeId == nodeId }
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
    private static synchronized void checkAndReassignCoordinator(int depth) {
        List<CustomNodeInfo> depthNodes = nodesByDepth[depth]
        if (!depthNodes) return

        CustomNodeInfo bestCandidate = depthNodes.findAll { it.isActive }.max { it.batteryLevel }
        if (bestCandidate && bestCandidate != currentCoordinators[depth]) {
            CustomNodeInfo previousCoordinator = currentCoordinators[depth]
            if (previousCoordinator != null) previousCoordinator.updateRole("data")
            bestCandidate.updateRole("coordinator")
            currentCoordinators[depth] = bestCandidate
            logger.info("Coordinator reassigned at depth ${depth}m: ${bestCandidate.nodeId}")
        }
    }

    /**
     * Get the network status including nodes and coordinators.
     */
    static synchronized Map<String, Object> getNetworkStatus() {
        return [
            nodeCount: nodesByDepth.values().flatten().size(),
            coordinators: currentCoordinators.collect { depth, node -> [depth: depth, nodeId: node.nodeId, batteryLevel: node.batteryLevel] },
            averageBatteryByDepth: nodesByDepth.collectEntries { depth, nodes -> [depth, nodes.sum { it.batteryLevel } / nodes.size()] }
        ]
    }
}