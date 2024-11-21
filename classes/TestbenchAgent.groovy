//TestbenchAgent.groovy
import org.arl.unet.addr.AddressResolution
import org.arl.fjage.*
import org.arl.unet.*
import org.arl.unet.phy.*
import org.arl.unet.sim.*
import org.arl.unet.sim.channels.*

int countNodesPerLvl = 3
int numberOfLvl = 5
int depthBase = 100
int levelDepthData = 1100
int radius = 200

int countNodes = countNodesPerLvl * numberOfLvl + 1
def nodes = 1..countNodes
def T = 10.hours

def loc = new LocationGen()
def nodeLocation = loc.generate(countNodes, numberOfLvl, radius, depthBase, levelDepthData);

channel.model = ProtocolChannelModel

modem.headerLength = 0
modem.preambleDuration = 0
modem.txDelay = 0

setup1 = { c ->
  c.add 'echo', new BaseNode()
  c.add 'arp', new AddressResolution()
}

simulate T, {
  nodes.each{n ->
    if(n==1)
      node "N", address:n, location: nodeLocation[n], web:8080+n, stack:setup1
    else
      node "N"+(n-1), address:n, location: nodeLocation[n], web:8080+n, stack:{ c ->
        c.add 'echo', new DataNode(AgentLocalRandom.current().nextDouble(95,100))
        c.add 'arp', new AddressResolution()
      }
  }
}