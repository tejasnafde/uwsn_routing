// File: NodeInitAgent.groovy
import org.arl.unet.*
import org.arl.fjage.*
import utilities.DepthUtility
import utilities.CustomNodeInfo

class NodeInitialization extends UnetAgent {
    String nodeName
    double depth
    boolean isCoordinator
    boolean isSink
    int index
    AgentID phy
    double batteryCharge = 100.0
    int hopCount = 99
    long beaconInterval = 30000  // Interval for sending beacons (30 seconds)

    final double BEACON_BATTERY_COST = 0.05

    @Override
    void startup() {
        register(Services.NODE_INFO)
        phy = agentForService(Services.PHYSICAL)
        subscribeForService(Services.DATAGRAM)

        // Initialize and parse nodeName as per new convention
        CustomNodeInfo nodeInfo = DepthUtility.parseNodeName(nodeName)
        isCoordinator = nodeInfo.currentRole == 'coordinator'
        isSink = nodeInfo.currentRole == 'sink'
        depth = nodeInfo.depth
        index = nodeInfo.index

        // Register node in DepthUtility and log
        DepthUtility.registerNode(nodeInfo)
        trace.log("Node ${nodeName} initialized as ${nodeInfo.currentRole} at depth ${depth}m, index ${index}, with battery=${batteryCharge}%", traceLevel: "INFO")

        // Coordinators find and assign the initial coordinator if not a sink node
        if (!isSink) {
            findCoordinator()
            add new TickerBehavior(beaconInterval) {
                @Override
                void onTick() { sendBeacon() }
            }
        }
    }

    // Attempt to find a coordinator for the node at the same depth level
    void findCoordinator() {
        if (!isCoordinator) {
            CustomNodeInfo coordinator = DepthUtility.getCoordinatorForDepth(depth)
            if (coordinator) {
                trace.log("Node ${nodeName} found Coordinator at ${coordinator.nodeId}", traceLevel: "INFO")
            } else {
                trace.log("Node ${nodeName} could not find a Coordinator at depth ${depth}m; will retry periodically.", traceLevel: "WARN")
                // Optionally, schedule a retry if dynamic coordinator changes are expected
            }
        }
    }

    // Sends a beacon message containing current node information
    void sendBeacon() {
        if (!isSink) {
            def beaconData = [nodeName: nodeName, depth: depth, hopCount: hopCount, batteryCharge: batteryCharge]
            def beaconBytes = beaconData.toString().bytes
            phy << new DatagramReq(to: null, data: beaconBytes)

            updateBattery(BEACON_BATTERY_COST)
            trace.log("Node ${nodeName} broadcasted beacon with battery: ${batteryCharge}%", traceLevel: "INFO")
        }
    }

    // Updates battery level and logs critical level warnings if necessary
    private void updateBattery(double cost) {
        batteryCharge = Math.max(0, batteryCharge - cost)
        DepthUtility.updateBatteryLevel(nodeName, batteryCharge)
        if (batteryCharge <= BEACON_BATTERY_COST && !isCoordinator) {
            trace.log("Node ${nodeName} at critical battery level: ${batteryCharge}%. Reducing communication frequency.", traceLevel: "WARN")
            // Optional: lower beacon frequency or take other actions when battery is critical
        } else if (batteryCharge == 0) {
            trace.log("Node ${nodeName} has depleted its battery and may cease operations.", traceLevel: "WARN")
        }
    }

    @Override
    void processMessage(Message msg) {
        if (msg instanceof DatagramNtf && msg.data != null) {
            try {
                def receivedData = msg.data.decodeJSON()
                handleBeacon(receivedData)
            } catch (Exception e) {
                trace.log("Node ${nodeName} received malformed beacon data: ${msg.data}. Ignoring message.", traceLevel: "WARN")
            }
        }
    }

    // Handles received beacon data, updating hop count if necessary
    void handleBeacon(Map data) {
        if (!data.containsKey('nodeName') || !data.containsKey('hopCount') || !data.containsKey('depth')) {
            trace.log("Node ${nodeName} received incomplete beacon data from neighbor. Ignoring message.", traceLevel: "WARN")
            return
        }

        String neighborName = data.nodeName
        int neighborHopCount = data.hopCount
        double neighborDepth = data.depth

        if (neighborHopCount + 1 < hopCount) {
            hopCount = neighborHopCount + 1
            trace.log("Node ${nodeName} updated hop count to ${hopCount} after receiving beacon from ${neighborName}", traceLevel: "INFO")
        }

        if (neighborDepth < depth && !isCoordinator) {
            trace.log("Node ${nodeName} detected a neighbor at a lower depth: ${neighborName}", traceLevel: "INFO")
        }
    }

    @Override
    void shutdown() {
        trace.log("Node ${nodeName} shutting down with final battery=${batteryCharge}%", traceLevel: "INFO")
    }
}

agent.add(new NodeInitialization())