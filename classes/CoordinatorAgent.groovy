// File: CoordinatorAgent.groovy
import org.arl.unet.*
import org.arl.fjage.*
import org.arl.unet.DatagramReq
import org.arl.unet.DatagramNtf
import org.arl.unet.DatagramDeliveryNtf
import org.arl.unet.net.Router
import org.arl.unet.RefuseRsp
import utilities.DepthUtility
import utilities.CustomNodeInfo
import java.util.concurrent.ThreadLocalRandom  // Import for randomization

class CoordinatorNode extends UnetAgent {
    String nodeName
    String nodeId
    int nodeAddress
    double depth
    AgentID phy
    AgentID router
    long aggregationInterval = 60000
    long beaconInterval = 30000
    List<byte[]> dataBuffer = []
    double batteryCharge = 100.0
    int txCount = 0
    int hopCount = 99  // Initialize to a high value
    static Map<String, Integer> globalNodeAddressMap  // Global map of node names to addresses

    final double TX_BATTERY_COST = 0.3
    final double RX_BATTERY_COST = 0.1
    final double BEACON_BATTERY_COST = 0.05
    final double CRITICAL_BATTERY = 10.0
    final int MAX_RETRIES = 10
    final int RETRY_DELAY_MS = 5000

    @Override
    void startup() {
        log.info "Coordinator Node startup initiated for ${nodeName}"
        phy = agentForService(Services.PHYSICAL)
        router = agentForService(Services.ROUTING)

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

        // Register the node in DepthUtility
        CustomNodeInfo nodeInfo = new CustomNodeInfo(
            nodeId: nodeId,
            initialType: "data",
            currentRole: "coordinator",
            depth: depth,
            index: DepthUtility.getIndexFromNodeName(nodeName),
            batteryLevel: batteryCharge,
            isActive: true,
            isCoordinator: true,
            address: nodeAddress
        )
        DepthUtility.registerNode(nodeInfo)

        log.info "Coordinator Node ${nodeName} initialized at depth ${depth}m with address ${nodeAddress} and battery ${batteryCharge}%."

        // Beaconing Behavior with random initial delay
        long initialBeaconDelay = ThreadLocalRandom.current().nextLong(0, beaconInterval)
        add new WakerBehavior(initialBeaconDelay) {
            @Override
            void onWake() {
                add new TickerBehavior(beaconInterval) {
                    @Override
                    void onTick() {
                        sendBeacon()
                        updateBattery(BEACON_BATTERY_COST)
                    }
                }
            }
        }

        // Data Aggregation Behavior with random initial delay
        long initialAggregationDelay = ThreadLocalRandom.current().nextLong(0, aggregationInterval)
        add new WakerBehavior(initialAggregationDelay) {
            @Override
            void onWake() {
                add new TickerBehavior(aggregationInterval) {
                    @Override
                    void onTick() {
                        log.info "Forwarding aggregated data for Coordinator ${nodeName}."
                        forwardAggregatedData()
                    }
                }
            }
        }
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
        if (msg instanceof DatagramNtf) {
            if (msg.from == Address.BROADCAST) {
                handleBeacon(new String(msg.data))
            } else {
                handleDatagramNtf(msg)
            }
        } else if (msg instanceof DatagramDeliveryNtf) {
            log.info "Delivery notification for ${msg.to} - ${msg.success ? 'SUCCESS' : 'FAILED'}"
        } else if (msg instanceof RefuseRsp) {
            log.warning "RefuseRsp received - Reason: ${msg.getReason() ?: 'No reason specified'}"
            // Optionally implement additional handling here
        } else {
            log.info "Unhandled message type: ${msg.getClass().getSimpleName()}"
        }
    }

    private void handleDatagramNtf(DatagramNtf msg) {
        log.info "Coordinator ${nodeName} received DatagramNtf from ${msg.from} to ${msg.to}"
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
            log.warning "No upstream coordinator found. Forwarding aborted."
            return
        }

        try {
            // Get the upstream coordinator's address
            def upstreamAddress = upstreamCoordinator.address
            if (upstreamAddress == null) {
                log.warning "Upstream coordinator address for ${upstreamCoordinator.nodeId} not found."
                return
            }

            log.info "Forwarding to upstream ${upstreamCoordinator.nodeId} (Address: ${upstreamAddress})"
            def aggregatedData = dataBuffer.collect { new String(it) }.join('|')
            def req = new DatagramReq(to: upstreamAddress, data: aggregatedData.bytes)

            // Use request-response pattern to handle RefuseRsp
            int retries = 0
            boolean success = false
            while (retries < MAX_RETRIES && !success) {
                def rsp = request(req, 5000)  // Wait up to 5 seconds for a response
                if (rsp instanceof RefuseRsp) {
                    retries++
                    log.warning "Data transmission refused by router. Retrying in ${RETRY_DELAY_MS} ms... (Attempt ${retries}/${MAX_RETRIES})"
                    sleep(RETRY_DELAY_MS)
                } else {
                    success = true
                    txCount++
                    updateBattery(TX_BATTERY_COST)
                    log.info "Data successfully forwarded to ${upstreamAddress}. txCount=${txCount}"
                }
            }
            if (!success) {
                log.severe "Failed to send data after ${MAX_RETRIES} retries."
            }
        } catch (Exception e) {
            log.severe "Error forwarding data: ${e.message}"
            e.printStackTrace()
        } finally {
            dataBuffer.clear()
            log.info "Data buffer cleared for ${nodeName}"
        }
    }

    private void sendBeacon() {
        def beaconData = [nodeName: nodeName, depth: depth, hopCount: hopCount, batteryCharge: batteryCharge]
        def beaconBytes = beaconData.toString().bytes
        def req = new DatagramReq(to: Address.BROADCAST, data: beaconBytes)

        // Use request-response pattern to handle RefuseRsp
        int retries = 0
        boolean success = false
        while (retries < MAX_RETRIES && !success) {
            def rsp = request(req, 5000)  // Wait up to 5 seconds for a response
            if (rsp instanceof RefuseRsp) {
                retries++
                log.warning "Beacon transmission refused by phy. Retrying in ${RETRY_DELAY_MS} ms... (Attempt ${retries}/${MAX_RETRIES})"
                sleep(RETRY_DELAY_MS)
            } else {
                success = true
                log.info "Coordinator Node ${nodeName} broadcasted beacon."
            }
        }
        if (!success) {
            log.severe "Failed to send beacon after ${MAX_RETRIES} retries."
        }
    }

    private void handleBeacon(String dataString) {
        try {
            def data = parseBeaconData(dataString)
            if (!data) return
            String neighborName = data.nodeName
            int neighborHopCount = data.hopCount
            double neighborDepth = data.depth

            if (neighborHopCount + 1 < hopCount) {
                hopCount = neighborHopCount + 1
                log.info "Coordinator Node ${nodeName} updated hop count to ${hopCount} after receiving beacon from ${neighborName}"
            }
        } catch (Exception e) {
            log.warning "Coordinator Node ${nodeName} failed to parse beacon data."
        }
    }

    private Map parseBeaconData(String dataString) {
        try {
            def data = [:]
            dataString = dataString.replaceAll(/[\{\}]/, '')  // Remove braces
            dataString.split(', ').each { pair ->
                def (key, value) = pair.split(':')
                data[key.trim()] = value.trim().isDouble() ? value.trim().toDouble() : (value.trim().isInteger() ? value.trim().toInteger() : value.trim())
            }
            return data
        } catch (Exception e) {
            log.warning "Coordinator Node ${nodeName} failed to parse beacon data string: ${dataString}"
            return null
        }
    }

    private void updateBattery(double consumption) {
        batteryCharge -= consumption
        DepthUtility.updateBatteryLevel(nodeName, batteryCharge)
        log.info "Battery level for ${nodeName}: ${batteryCharge}%"
        if (batteryCharge < CRITICAL_BATTERY) log.warning "Critical battery level: ${batteryCharge}%"
    }
}

agent.add(new CoordinatorNode())