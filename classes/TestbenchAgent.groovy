// File: TestbenchAgent.groovy
import org.arl.unet.*
import org.arl.unet.addr.AddressResolution
import org.arl.unet.net.Router
import org.arl.unet.net.RouteDiscoveryProtocol
import org.arl.unet.phy.*
import org.arl.fjage.*
import utilities.DepthUtility
import utilities.CustomNodeInfo
import org.arl.unet.sim.channels.BasicAcousticChannel
import org.arl.unet.sim.*

// Define the simulation duration (e.g., 2 hours in accelerated time)
def T = 2.hours

// Set a warm-up period to allow conditions to stabilize before collecting stats
trace.warmup = 15.minutes

// Acoustic channel configuration
channel = [
  model: BasicAcousticChannel,
  carrierFrequency: 25.kHz,
  bandwidth: 4096.Hz,
  spreading: 2,
  temperature: 25.C,
  salinity: 35.ppt,
  noiseLevel: 73.dB,
  waterDepth: 3020.m
]

// PHY layer configuration
def phyConfig = [
  frameDuration: 0.5.seconds,
  preambleDuration: 0.1.seconds,
  turnaroundTime: 0.1.seconds,
  maxDataRate: 500.bps,
  mtu: 64.bytes,
  txPowerLevel: -10.dB
]

// Global node address map
def globalNodeAddressMap = [:]

// Simulation logic
simulate T, {
    // Sink node initialization
    def sinkNodeName = DepthUtility.generateNodeName(1, 0, 1)
    def sinkNodeInfo = DepthUtility.parseNodeName(sinkNodeName)
    globalNodeAddressMap[sinkNodeName] = sinkNodeInfo.address

    node sinkNodeName, location: [0, 0, 0], address: sinkNodeInfo.address, web: 8080, phy: phyConfig, stack: { c ->
        def sinkNode = new SinkNode()
        sinkNode.nodeName = sinkNodeName
        sinkNode.nodeId = sinkNodeName
        sinkNode.nodeAddress = sinkNodeInfo.address
        c.add 'sinkNode', sinkNode
        c.add 'arp', new AddressResolution()
        c.add 'routing', new Router()
        c.add 'rdp', new RouteDiscoveryProtocol()
    }

    // Configure data and coordinator nodes for each depth
    def depths = [200, 400, 600]
    depths.each { depthValue ->
        int depthCode = DepthUtility.DEPTH_CODES[depthValue]
        // Coordinator node for each depth
        def coordinatorIndex = 1
        def coordinatorName = DepthUtility.generateNodeName(2, depthValue, coordinatorIndex)
        def coordinatorInfo = DepthUtility.parseNodeName(coordinatorName)
        globalNodeAddressMap[coordinatorName] = coordinatorInfo.address

        double x = Math.random() * 2000 - 1000
        double y = Math.random() * 2000 - 1000

        node coordinatorName, location: [x, y, -depthValue], address: coordinatorInfo.address, web: 8080 + coordinatorInfo.address, phy: phyConfig, stack: { c ->
            def coordinatorNode = new CoordinatorNode()
            coordinatorNode.nodeName = coordinatorName
            coordinatorNode.nodeId = coordinatorName
            coordinatorNode.nodeAddress = coordinatorInfo.address
            coordinatorNode.depth = depthValue
            c.add 'coordinatorNode', coordinatorNode
            c.add 'arp', new AddressResolution()
            c.add 'routing', new Router()
            c.add 'rdp', new RouteDiscoveryProtocol()
        }

        // Data nodes at each depth
        (2..4).each { index ->
            def dataNodeName = DepthUtility.generateNodeName(2, depthValue, index)
            def dataNodeInfo = DepthUtility.parseNodeName(dataNodeName)
            globalNodeAddressMap[dataNodeName] = dataNodeInfo.address

            double dx = Math.random() * 2000 - 1000
            double dy = Math.random() * 2000 - 1000

            node dataNodeName, location: [dx, dy, -depthValue], address: dataNodeInfo.address, web: 8080 + dataNodeInfo.address, phy: phyConfig, stack: { c ->
                def dataNode = new DataNode()
                dataNode.nodeName = dataNodeName
                dataNode.nodeId = dataNodeName
                dataNode.nodeAddress = dataNodeInfo.address
                dataNode.depth = depthValue
                c.add 'dataNode', dataNode
                c.add 'arp', new AddressResolution()
                c.add 'routing', new Router()
                c.add 'rdp', new RouteDiscoveryProtocol()
            }
        }
    }

    // Assign the global address map to node classes
    DataNode.globalNodeAddressMap = globalNodeAddressMap
    CoordinatorNode.globalNodeAddressMap = globalNodeAddressMap
    SinkNode.globalNodeAddressMap = globalNodeAddressMap

    println "All nodes initialized with addresses based on the new naming scheme. Simulation starting..."
} // End of simulate block

// Final trace logging for simulation results
println '''
TX Count\tRX Count\tOffered Load\tThroughput
--------\t--------\t------------\t----------'''
println sprintf('%6d\t\t%6d\t\t%7.3f\t\t%7.3f',
    [trace.txCount, trace.rxCount, trace.offeredLoad, trace.throughput])  