// File: SinkNodeAgent.groovy
import org.arl.unet.*
import org.arl.fjage.*
import utilities.DepthUtility
import utilities.CustomNodeInfo

class SinkNode extends UnetAgent {
    String nodeName  // Decimal-based node name in the 7-digit format
    String nodeId    // Unique identifier following the new 7-digit format
    int nodeAddress  // Node address as an integer
    double depth = 0.0
    AgentID phy
    double batteryCharge = 100.0
    final double RX_BATTERY_COST = 0.1
    final int NODE_TYPE = 1  // Set as a sink node

    @Override
    void startup() {
        register(Services.NODE_INFO)
        phy = agentForService(Services.PHYSICAL)
        subscribeForService(Services.DATAGRAM)

        // Generate nodeName and nodeId following the new naming scheme (type: 1 for sink)
        nodeName = DepthUtility.generateNodeName(NODE_TYPE, 0, 0)
        nodeId = nodeName  // Assign nodeId as a string
        nodeAddress = 1    // Set a unique address for the sink node
        depth = 0  // Surface depth for the sink node

        // Register sink node in the DepthUtility
        CustomNodeInfo nodeInfo = new CustomNodeInfo(
            nodeId: nodeId,               // Use nodeId as a String
            initialType: "sink",          // Setting type as "sink"
            currentRole: "sink",          // Initial role as "sink"
            depth: depth,
            index: 0,
            batteryLevel: batteryCharge,
            isActive: true
        )
        DepthUtility.registerNode(nodeInfo)

        log.info "Sink Node ${nodeName} initialized at the surface with depth ${depth} meters and address ${nodeAddress}."
    }

    // Update battery level upon receiving data and log status
    private void updateBattery(double cost) {
        batteryCharge = Math.max(0, batteryCharge - cost)
        log.info "Sink Node ${nodeName} battery updated to ${batteryCharge}%."
    }

    // Process incoming DatagramNtf messages
    @Override
    void processMessage(Message msg) {
        if (msg instanceof DatagramNtf) {
            updateBattery(RX_BATTERY_COST)
            log.info "Sink Node ${nodeName} received data from ${msg.sender}."
            updateReceivedDataMetrics()
        }
    }

    // Update metrics for data received by the sink node
    private void updateReceivedDataMetrics() {
        log.info "Sink Node metrics updated after data reception."
    }

    // Log shutdown event and battery status
    @Override
    void shutdown() {
        log.info "Sink Node ${nodeName} shutting down with final battery=${batteryCharge}%."
    }
}

agent.add(new SinkNode())