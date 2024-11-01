// File: SinkNodeAgent.groovy
import org.arl.unet.*
import org.arl.fjage.*
import utilities.DepthUtility
import utilities.CustomNodeInfo
import org.arl.unet.DatagramNtf

class SinkNode extends UnetAgent {
    String nodeName
    String nodeId
    int nodeAddress
    double depth = 0.0
    double batteryCharge = 100.0
    final double RX_BATTERY_COST = 0.1
    static Map<String, Integer> globalNodeAddressMap

    @Override
    void startup() {
        register(Services.NODE_INFO)
        subscribeForService(Services.DATAGRAM)

        // Parse nodeName to get nodeInfo, including address
        CustomNodeInfo nodeInfo = DepthUtility.parseNodeName(nodeName)
        nodeAddress = nodeInfo.address
        depth = nodeInfo.depth

        // Register sink node in the DepthUtility
        DepthUtility.registerNode(nodeInfo)

        log.info "Sink Node ${nodeName} initialized at the surface with depth ${depth} meters and address ${nodeAddress}."
    }

    // Update battery level upon receiving data and log status
    private void updateBattery(double cost) {
        batteryCharge = Math.max(0, batteryCharge - cost)
        DepthUtility.updateBatteryLevel(nodeName, batteryCharge)
        log.info "Sink Node ${nodeName} battery updated to ${batteryCharge}%."
    }

    // Process incoming messages
    @Override
    void processMessage(Message msg) {
        if (msg instanceof DatagramNtf) {
            if (msg.from != Address.BROADCAST) {
                updateBattery(RX_BATTERY_COST)
                def receivedData = new String(msg.data)
                log.info "Sink Node ${nodeName} received data: ${receivedData} from ${msg.from}."
                // Process received data as needed
            }
        } else {
            log.info "Unhandled message type at Sink Node: ${msg.getClass().getSimpleName()}"
        }
    }

    // Log shutdown event and battery status
    @Override
    void shutdown() {
        log.info "Sink Node ${nodeName} shutting down with final battery=${batteryCharge}%."
    }
}

agent.add(new SinkNode())