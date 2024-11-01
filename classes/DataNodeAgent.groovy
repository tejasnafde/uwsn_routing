// File: DataNodeAgent.groovy
import org.arl.unet.*
import org.arl.unet.Services.*
import org.arl.fjage.*
import utilities.DepthUtility
import utilities.CustomNodeInfo
import org.arl.unet.net.Router
import org.arl.unet.RefuseRsp
import org.arl.unet.DatagramReq
import org.arl.unet.DatagramNtf
import java.util.concurrent.ThreadLocalRandom  // Import for randomization

class DataNode extends UnetAgent {
    String nodeName
    String nodeId
    int nodeAddress
    double depth
    CustomNodeInfo currentCoordinator
    AgentID phy
    AgentID router
    double batteryCharge = 100.0
    int hopCount = 99  // Initialize to a high value
    final double TX_BATTERY_COST = 0.2
    final double RX_BATTERY_COST = 0.1
    final double BEACON_BATTERY_COST = 0.05
    final double CRITICAL_BATTERY = 15.0
    final long DATA_INTERVAL = 60000
    final long BEACON_INTERVAL = 30000
    final int MAX_RETRIES = 10
    final int RETRY_DELAY_MS = 5000
    static Map<String, Integer> globalNodeAddressMap  // Global map of node names to addresses

    @Override
    void startup() {
        if (!validateInitialization()) return

        register(Services.DATAGRAM)
        phy = agentForService(Services.PHYSICAL)
        router = agentForService(Services.ROUTING)
        if (router == null) {
            log.severe "Router service not available for Data Node ${nodeName}. Shutting down."
            shutdown()
            return
        } else {
            log.info "Router service assigned to Data Node ${nodeName}."
        }

        subscribeForService(Services.DATAGRAM)
        subscribe(topic(phy))

        // Register the node in DepthUtility
        CustomNodeInfo nodeInfo = new CustomNodeInfo(
            nodeId: nodeId,
            initialType: "data",
            currentRole: "data",
            depth: depth,
            index: DepthUtility.getIndexFromNodeName(nodeName),
            batteryLevel: batteryCharge,
            isActive: true,
            isCoordinator: false,
            address: nodeAddress
        )
        DepthUtility.registerNode(nodeInfo)

        currentCoordinator = DepthUtility.getCoordinatorForDepth(depth)
        logCoordinatorStatus("on startup")

        // Beaconing Behavior with random initial delay
        long initialBeaconDelay = ThreadLocalRandom.current().nextLong(0, BEACON_INTERVAL)
        add new WakerBehavior(initialBeaconDelay) {
            @Override
            void onWake() {
                add new TickerBehavior(BEACON_INTERVAL) {
                    @Override
                    void onTick() {
                        sendBeacon()
                        updateBattery(BEACON_BATTERY_COST)
                    }
                }
            }
        }

        // Data Sending Behavior with random initial delay
        long initialDataDelay = ThreadLocalRandom.current().nextLong(0, DATA_INTERVAL)
        add new WakerBehavior(initialDataDelay) {
            @Override
            void onWake() {
                add new TickerBehavior(DATA_INTERVAL) {
                    @Override
                    void onTick() {
                        if (batteryCharge > CRITICAL_BATTERY) {
                            currentCoordinator = DepthUtility.getCoordinatorForDepth(depth)
                            sendDataToCoordinator()
                            updateBattery(TX_BATTERY_COST)
                        }
                    }
                }
            }
        }

        log.info "Data Node ${nodeName} initialized at depth ${depth}m with address ${nodeAddress} and battery ${batteryCharge}%."
    }

    private boolean validateInitialization() {
        if (!nodeName || nodeAddress == null || depth == null) {
            log.severe "Data Node's nodeName, nodeAddress, or depth is not set. Initialization aborted."
            shutdown()
            return false
        }
        return true
    }

    private void sendDataToCoordinator() {
        if (router && currentCoordinator) {
            try {
                // Get the coordinator's address from currentCoordinator.address
                def coordinatorAddress = currentCoordinator.address
                if (coordinatorAddress == null) {
                    log.warning "Coordinator address for ${currentCoordinator.nodeId} not found."
                    return
                }
                long sendTimestamp = System.currentTimeMillis()
                String formattedData = "${nodeName}|${sendTimestamp}|${depth}|SAMPLE_DATA"
                def data = formattedData.bytes

                log.info "Sending data at ${sendTimestamp}: ${formattedData} to coordinator ${currentCoordinator.nodeId} (Address: ${coordinatorAddress})"

                def req = new DatagramReq(to: coordinatorAddress, data: data)
                req.reliability = true

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
                        log.info "Data successfully sent to coordinator."
                    }
                }
                if (!success) {
                    log.severe "Failed to send data after ${MAX_RETRIES} retries."
                }
            } catch (Exception e) {
                log.severe "Data formatting/sending error: ${e.message}"
                e.printStackTrace()
            }
        } else {
            log.warning "No coordinator available at depth ${depth}"
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
                log.info "Data Node ${nodeName} broadcasted beacon."
            }
        }
        if (!success) {
            log.severe "Failed to send beacon after ${MAX_RETRIES} retries."
        }
    }

    private void updateBattery(double cost) {
        batteryCharge = Math.max(0, batteryCharge - cost)
        DepthUtility.updateBatteryLevel(nodeName, batteryCharge)

        if (batteryCharge <= CRITICAL_BATTERY) {
            log.warning "Data Node ${nodeName} at critical battery: ${batteryCharge}%."
            currentCoordinator = DepthUtility.getCoordinatorForDepth(depth)
            logCoordinatorStatus("at critical battery level")
        }
    }

    private void logCoordinatorStatus(String context) {
        if (currentCoordinator) {
            log.info "Data Node ${nodeName} found coordinator ${currentCoordinator.nodeId} at depth ${depth} ${context}."
        } else {
            log.warning "Data Node ${nodeName} has no coordinator at depth ${depth} ${context}."
        }
    }

    @Override
    void processMessage(Message msg) {
        if (msg instanceof DatagramNtf && msg.data != null) {
            if (msg.from == Address.BROADCAST) {
                handleBeacon(new String(msg.data))
            } else {
                updateBattery(RX_BATTERY_COST)
                log.info "Data Node ${nodeName} received data: ${new String(msg.data)}"
            }
        } else if (msg instanceof RefuseRsp) {
            log.warning "RefuseRsp received for DataNode - Reason: ${msg.getReason() ?: 'No reason specified'}"
            // Optionally implement additional handling here
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
                log.info "Data Node ${nodeName} updated hop count to ${hopCount} after receiving beacon from ${neighborName}"
            }
        } catch (Exception e) {
            log.warning "Data Node ${nodeName} failed to parse beacon data."
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
            log.warning "Data Node ${nodeName} failed to parse beacon data string: ${dataString}"
            return null
        }
    }
}

agent.add(new DataNode())