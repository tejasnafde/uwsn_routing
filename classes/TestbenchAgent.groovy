//TestbenchAgent.groovy
import org.arl.unet.*
import org.arl.unet.addr.AddressResolution
import org.arl.unet.net.Router
import org.arl.unet.net.RouteDiscoveryProtocol
import org.arl.unet.phy.*
import org.arl.fjage.*
import utilities.DepthUtility
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

// Node setup functions
setupSinkNode = { c -> 
    c.add 'sinkNode', new SinkNode()
    c.add 'arp', new AddressResolution()  // Adding AddressResolution service
}

setupCoordinatorNode = { c ->
    c.add 'coordinatorNode', new CoordinatorNode()
    c.add 'arp', new AddressResolution()
    c.add 'routing', new Router()
    c.add 'rdp', new RouteDiscoveryProtocol()
}

setupDataNode = { c ->
    c.add 'dataNode', new DataNode()
    c.add 'arp', new AddressResolution()
    c.add 'routing', new Router()
    c.add 'rdp', new RouteDiscoveryProtocol()
}

// Simulation logic
simulate T, {                
    int nodeCounter = 1

    // Sink node initialization
    node "1000001", location: [0, 0, 0], web: 8080, stack: { c ->
        def sinkNode = new SinkNode()
        sinkNode.nodeName = "1000001"
        sinkNode.nodeId = "1000001"
        sinkNode.nodeAddress = Integer.parseInt(sinkNode.nodeName)  // Set address based on nodeName
        c.add 'sinkNode', sinkNode
        c.add 'arp', new AddressResolution()
        c.add 'routing', new Router()
        c.add 'rdp', new RouteDiscoveryProtocol()

        // Register this address in AddressResolution
        def arpService = c.agentForService("org.arl.unet.addr.AddressResolution")
        if (arpService != null) {
            arpService.register(sinkNode.nodeName, sinkNode.nodeAddress)
        }
    }

    // Configure data and coordinator nodes for each depth
    def depths = [200, 400, 600]
    depths.each { depthValue ->
        (1..4).each { index ->
            def dataNodeName = DepthUtility.generateNodeName(2, depthValue, index)
            double x = Math.random() * 2000 - 1000
            double y = Math.random() * 2000 - 1000

            node dataNodeName, location: [x, y, -depthValue], web: 8080 + nodeCounter, stack: { c ->
                def dataNode = new DataNode()
                dataNode.nodeName = dataNodeName
                dataNode.nodeId = dataNodeName
                dataNode.nodeAddress = Integer.parseInt(dataNode.nodeName)  // Parse address from nodeName
                c.add 'dataNode', dataNode
                c.add 'arp', new AddressResolution()
                c.add 'routing', new Router()
                c.add 'rdp', new RouteDiscoveryProtocol()

                def arpService = c.agentForService("org.arl.unet.addr.AddressResolution")
                if (arpService != null) {
                    arpService.register(dataNode.nodeName, dataNode.nodeAddress)
                }
            }
            nodeCounter++
        }

        // Coordinator node for each depth
        def coordinatorName = DepthUtility.generateNodeName(2, depthValue, 1)
        double x = Math.random() * 2000 - 1000
        double y = Math.random() * 2000 - 1000

        node coordinatorName, location: [x, y, -depthValue], web: 8080 + nodeCounter, stack: { c ->
            def coordinatorNode = new CoordinatorNode()
            coordinatorNode.nodeName = coordinatorName
            coordinatorNode.nodeId = coordinatorName
            coordinatorNode.nodeAddress = Integer.parseInt(coordinatorNode.nodeName)  // Parse address from nodeName
            c.add 'coordinatorNode', coordinatorNode
            c.add 'arp', new AddressResolution()
            c.add 'routing', new Router()
            c.add 'rdp', new RouteDiscoveryProtocol()

            def arpService = c.agentForService("org.arl.unet.addr.AddressResolution")
            if (arpService != null) {
                arpService.register(coordinatorNode.nodeName, coordinatorNode.nodeAddress)
            }
        }
        nodeCounter++
    }

    println "All nodes initialized with addresses based on nodeName. Simulation starting..."
} // End of simulate block


// Final trace logging for simulation results
println '''
TX Count\tRX Count\tOffered Load\tThroughput
--------\t--------\t------------\t----------'''
println sprintf('%6d\t\t%6d\t\t%7.3f\t\t%7.3f',
    [trace.txCount, trace.rxCount, trace.offeredLoad, trace.throughput])
