// DataNodeAgent.groovy
import org.arl.unet.*
import org.arl.unet.Services.*
import org.arl.fjage.*
import utilities.DepthUtility
import utilities.CustomNodeInfo
import org.arl.unet.net.Router
import org.arl.unet.RefuseRsp
import org.arl.unet.DatagramReq
import org.arl.unet.phy.RxFrameNtf
import org.arl.unet.phy.TxFrameReq
import org.arl.unet.DatagramNtf

class DataNode extends UnetAgent {
    String nodeName
    String nodeId
    int nodeAddress
    double depth
    CustomNodeInfo currentCoordinator
    AgentID phy
    AgentID router
    double batteryCharge = 100.0
    final double TX_BATTERY_COST = 0.2
    final double RX_BATTERY_COST = 0.1
    final double CRITICAL_BATTERY = 15.0
    final long DATA_INTERVAL = 60000

    @Override
    void startup() {
        if (!validateInitialization()) return

        register Services.DATAGRAM
        phy = agentForService(Services.PHYSICAL)
        router = agentForService(org.arl.unet.Services.ROUTING)
        if (router == null) {
            log.severe "Router service not available for Data Node ${nodeName}. Shutting down."
            shutdown()
            return
        } else {
            log.info "Router service assigned to Data Node ${nodeName}."
        }

        subscribeForService(Services.DATAGRAM)
        subscribe(topic(phy))

        CustomNodeInfo nodeInfo = DepthUtility.parseNodeName(nodeName)
        depth = nodeInfo.depth
        DepthUtility.registerNode(nodeInfo)

        currentCoordinator = DepthUtility.getCoordinatorForDepth(depth)
        logCoordinatorStatus("on startup")

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
        log.info "Node ${nodeName} initialized at depth ${depth}m with battery ${batteryCharge}%."
    }

    private boolean validateInitialization() {
        if (!nodeName) {
            log.severe "Data Node's nodeName is not set. Initialization aborted."
            shutdown()
            return false
        }
        return true
    }

    private void sendDataToCoordinator() {
        if (router && currentCoordinator) {
            try {
                // Convert the coordinator nodeId to an integer directly, as per the new naming scheme.
                def coordinatorAddress = Integer.parseInt(currentCoordinator.nodeId)
                long sendTimestamp = System.currentTimeMillis()
                String formattedData = "${nodeName}|${sendTimestamp}|${depth}|SAMPLE_DATA"
                def data = formattedData.bytes

                log.info "Sending data at ${sendTimestamp}: ${formattedData} to coordinator ${currentCoordinator.nodeId} (Address: ${coordinatorAddress})"

                def req = new DatagramReq(to: coordinatorAddress, data: data)
                req.reliability = true
                router << req
            } catch (Exception e) {
                log.severe "Data formatting/sending error: ${e.message}"
                e.printStackTrace()
            }
        } else {
            log.warning "No coordinator available at depth ${depth}"
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
            updateBattery(RX_BATTERY_COST)
            log.info "Data Node ${nodeName} received data: ${new String(msg.data)}"
        } else if (msg instanceof RefuseRsp) {
            log.warning "RefuseRsp received for DataNode - Reason: ${msg.getReason() ?: 'No reason specified'}"
        }
    }
}

agent.add(new DataNode())