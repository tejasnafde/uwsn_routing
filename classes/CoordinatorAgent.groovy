//CoordinatorNode.groovy
import org.arl.unet.*
import org.arl.fjage.*
import org.arl.unet.DatagramReq
import org.arl.unet.DatagramNtf
import org.arl.unet.phy.RxFrameNtf
import org.arl.unet.phy.TxFrameReq
import org.arl.unet.net.Router
import org.arl.unet.RefuseRsp
import utilities.DepthUtility
import utilities.CustomNodeInfo

class CoordinatorNode extends UnetAgent {
    String nodeName
    String nodeId
    int nodeAddress
    double depth = 0.0
    AgentID phy
    AgentID router
    long aggregationInterval = 60000
    long initialDelay = 50000
    List<byte[]> dataBuffer = []
    double batteryCharge = 100.0
    int txCount = 0

    final double TX_BATTERY_COST = 0.3
    final double RX_BATTERY_COST = 0.1
    final double CRITICAL_BATTERY = 10.0
    final int MAX_RETRIES = 10
    final int RETRY_DELAY_MS = 5000

    @Override
    void startup() {
        log.info "Coordinator Node startup initiated for ${nodeName}"
        phy = agentForService(Services.PHYSICAL)
        router = agentForService(org.arl.unet.Services.ROUTING)

        if (router == null) {
            log.severe "Router agent unavailable for ${nodeName}. Initialization failed."
            shutdown()
            return
        }

        subscribe(topic(router))
        subscribe(topic(phy))

        if (!subscribeToDatagramWithRetry()) {
            log.severe "Failed DATAGRAM service subscription after maximum retries for Coordinator ${nodeName}"
            shutdown()
            return
        }

        // Initialize the node using the new naming scheme
        log.info "Coordinator Node address set to: ${nodeName}"
        CustomNodeInfo nodeInfo = DepthUtility.parseNodeName(nodeName)
        depth = nodeInfo?.depth ?: 0.0
        DepthUtility.registerNode(nodeInfo)
        log.info "Coordinator Node ${nodeName} initialized at depth ${depth}m with battery ${batteryCharge}%."

        add new WakerBehavior(initialDelay, {
            add new TickerBehavior(aggregationInterval) {
                @Override
                void onTick() {
                    log.info "Forwarding aggregated data for Coordinator ${nodeName}."
                    forwardAggregatedData()
                }
            }
        })
    }

    private boolean subscribeToDatagramWithRetry() {
        int retries = MAX_RETRIES
        while (retries > 0) {
            try {
                register(Services.DATAGRAM)
                if (agentForService(Services.DATAGRAM) != null) {
                    log.info "DATAGRAM service successfully subscribed for Coordinator ${nodeName}"
                    return true
                }
            } catch (Exception e) {
                log.warning "DATAGRAM registration failed: ${e.message}"
            }
            retries--
            sleep(RETRY_DELAY_MS)
        }
        return false
    }

    @Override
    void processMessage(Message msg) {
        log.info "Coordinator ${nodeName} received message: ${msg.getClass().getSimpleName()}"

        if (msg instanceof DatagramNtf) {
            handleDatagramNtf(msg)
        } else if (msg instanceof DatagramDeliveryNtf) {
            log.info "Delivery notification for ${msg.to} - ${msg.success ? 'SUCCESS' : 'FAILED'}"
        } else if (msg instanceof RefuseRsp) {
            log.warning "RefuseRsp received - Reason: ${msg.getReason() ?: 'No reason specified'}"
        } else {
            log.info "Unhandled message type: ${msg.getClass().getSimpleName()}"
        }
    }

    private void handleDatagramNtf(DatagramNtf msg) {
        log.info "DatagramNtf received - From: ${msg.from}, Data: ${new String(msg.data)}"

        if (msg.to != router.address) {
            log.warning "Received DatagramNtf not intended for this node (${nodeName}). Discarding."
            return
        }

        try {
            dataBuffer.add(msg.data)
            updateBattery(RX_BATTERY_COST)
            log.info "Data added to buffer. Buffer size for ${nodeName} after addition: ${dataBuffer.size()}"
        } catch (Exception e) {
            log.severe "Error adding data to buffer for ${nodeName}: ${e.message}"
        }
    }

    private void forwardAggregatedData() {
        log.info "Processing buffer for ${nodeName} with size ${dataBuffer.size()}"
        if (dataBuffer.isEmpty()) {
            log.info "Data buffer empty. No data to forward for ${nodeName}"
            return
        }

        def upstreamCoordinator = DepthUtility.getUpstreamCoordinator(depth)
        if (!upstreamCoordinator) {
            upstreamCoordinator = DepthUtility.getSinkNode()
            if (!upstreamCoordinator) {
                log.warning "No upstream or sink node found. Forwarding aborted."
                return
            }
        }

        try {
            // Using the new naming scheme to convert the nodeId directly to an integer for address consistency.
            def upstreamAddress = Integer.parseInt(upstreamCoordinator.nodeId)
            log.info "Forwarding to upstream ${upstreamCoordinator.nodeId} (Address: ${upstreamAddress})"
            def aggregatedData = dataBuffer.collect { new String(it) }.join('|')
            def req = new DatagramReq(to: upstreamAddress, data: aggregatedData.bytes)
            router << req
            txCount++
            updateBattery(TX_BATTERY_COST)
            log.info "Data successfully forwarded to ${upstreamAddress}. txCount=${txCount}"
        } catch (Exception e) {
            log.severe "Error forwarding data: ${e.message}"
            e.printStackTrace()
        } finally {
            dataBuffer.clear()
            log.info "Data buffer cleared for ${nodeName}"
        }
    }

    private void updateBattery(double consumption) {
        batteryCharge -= consumption
        log.info "Battery level for ${nodeName}: ${batteryCharge}%"
        if (batteryCharge < CRITICAL_BATTERY) log.warning "Critical battery level: ${batteryCharge}%"
    }
}

agent.add(new CoordinatorNode())