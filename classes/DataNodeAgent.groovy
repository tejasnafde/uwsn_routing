//DataNodeAgent.groovy
import org.arl.fjage.*;
import org.arl.unet.*;
import org.arl.fjage.param.Parameter;
import org.arl.fjage.WakerBehavior;
import org.arl.unet.phy.*;

class DataNode extends UnetAgent {

  final String title = 'Data Node';
  final String description = 'Serves as data collection nodes in the network';

  final double rxBttryUsage = 0.1;
  final double txSameLvlBttryUsage = 0.2;
  final double txDiffLvlBttryUsage = 0.5;
  final double critBttryLvl = 5;

  enum DataParams implements Parameter {
    tdmaSlot,
    delayLength
  }

  class Protocols {
    final static int INIT = 32;
    final static int ACK = 33;
    final static int BASE = 34;
    final static int RELAY = 35;
    final static int CORRECTION = 36;
  }

  PDU init = PDU.withFormat {
    int16('address');
    uint16('delayLength');
    int16('depth');
  };

  PDU ack = PDU.withFormat {
    int16('address');
    uint32('battery');
  };

  PDU correction = PDU.withFormat {
    int16('address');
  };

  int delayLength = 0;
  int baseAddress = -1;
  int localBaseAddress = -1;
  boolean subBaseNode = false;

  AgentID phy;
  AgentID node;
  AgentLocalRandom rnd;

  TreeMap neighbours;
  double bttryLvl;

  DataNode(double bttryLvl)
  {
    this.bttryLvl = bttryLvl
  }

  @Override
  void startup() {
    rnd = AgentLocalRandom.current();
    subscribeForService Services.DATAGRAM;
    phy = agentForService Services.PHYSICAL;
    node = agentForService Services.NODE_INFO;
    subscribe(topic(phy));

    // add new TickerBehavior(600000, {
    
    //   if(baseAddress != -1)
    //   {
    //     phy << new ClearReq();
    //     phy << new DatagramReq(
    //       to: baseAddress,
    //       protocol: Protocols.RELAY
    //     );
    //   }

    // });
  }

  @Override
  void processMessage(Message msg) {
    if (bttryLvl > critBttryLvl)
    {
      if (msg instanceof DatagramNtf && msg.protocol == Protocols.INIT) {
        bttryLvl -= rxBttryUsage;

        def bytes = init.decode(msg.data);
        delayLength = bytes.delayLength;
        long backoff = rnd.nextDouble(0, delayLength - 5000);
        
        if(bytes.depth >= node.location[2])
          subBaseNode = false;
        
        if(bytes.depth > node.location[2])
        {
          baseAddress = bytes.address;

          add new WakerBehavior(backoff, {
            bttryLvl -= txDiffLvlBttryUsage;
            bytes = ack.encode(address:node.address, battery:bttryLvl*10000);

            phy << new ClearReq();
            phy << new DatagramReq(
              recipient: msg.sender,
              to: msg.from,
              protocol: Protocols.ACK,
              data: bytes
            );
          });
        }
      }
      else if (msg instanceof DatagramNtf && msg.protocol == Protocols.BASE && msg.to == node.address) {
        bttryLvl -= rxBttryUsage;
        
        subBaseNode = true;
        neighbourBroadcast();
        log.info("Acting as Sub Base Node for Base Address: " + baseAddress);
      }
      else if (msg instanceof DatagramNtf && msg.protocol == Protocols.BASE && msg.to != node.address) {
        bttryLvl -= rxBttryUsage;
        
        subBaseNode = false;
        localBaseAddress = msg.to;
      }
      else if (msg instanceof DatagramNtf && msg.protocol == Protocols.ACK && msg.to == node.address) {
        bttryLvl -= rxBttryUsage;
        
        def bytes = ack.decode(msg.data);
        neighbours.put(bytes.battery/10000, bytes.address);
      }
      else if (msg instanceof DatagramNtf && msg.protocol == Protocols.RELAY && msg.to == node.address && subBaseNode) {
        bttryLvl -= rxBttryUsage;
        log.info("Relaying data to Base Node: " + baseAddress);
        
        //log.info("Relayed to Node:"+baseAddress)
        
        add new OneShotBehavior({
            bttryLvl -= txDiffLvlBttryUsage;

            phy << new ClearReq();
            phy << new DatagramReq(
              to: baseAddress,
              protocol: Protocols.RELAY
            );
        });
      }

      else if (msg instanceof DatagramNtf && msg.protocol == Protocols.RELAY && msg.to == node.address && !subBaseNode) {
        // Handle re-routing when voids are detected
        log.warning("Rerouting required! Sub Base Node unavailable.");
      }
      // else if (msg instanceof DatagramNtf && msg.protocol == Protocols.RELAY && msg.to == node.address && !subBaseNode) {
      //   bttryLvl -= rxBttryUsage;
        
      //   log.info("Corrected info for Node:"+msg.from)
        
      //   add new OneShotBehavior({
      //       bttryLvl -= txDiffLvlBttryUsage;

      //       def bytes = correction.encode(address:localBaseAddress);

      //       phy << new ClearReq();
      //       phy << new DatagramReq(
      //         to: msg.from,
      //         protocol: Protocols.CORRECTION,
      //         data: bytes
      //       );
      //   });
      // }
      // else if(msg instanceof DatagramNtf && msg.protocol == Protocols.CORRECTION && msg.to == node.address)
      // {
      //   def bytes = correction.decode(msg.data);
      //   baseAddress = bytes.address;
      // }
    }
  }

  void neighbourBroadcast() {
    neighbours = new TreeMap<Double, Integer>(Collections.reverseOrder());
    def bytes = init.encode(address:node.address, delayLength:delayLength, depth:node.location[2]);
    add new OneShotBehavior({
      bttryLvl -= txDiffLvlBttryUsage;

      phy << new ClearReq();
      phy << new DatagramReq(
        protocol: Protocols.INIT,
        data: bytes
      );
    });

    add new WakerBehavior(delayLength, {
      bttryLvl -= txDiffLvlBttryUsage;

      if(!neighbours.isEmpty())
      {
        log.info(String.valueOf(neighbours));
        log.info(String.valueOf(neighbours.get(neighbours.firstKey())))

        add new OneShotBehavior({
          phy << new ClearReq();
          phy << new DatagramReq(
            to: neighbours.get(neighbours.firstKey()),
            protocol: Protocols.BASE,
          );
        });
      }
      else{
        add new OneShotBehavior({
          phy << new ClearReq();
          phy << new DatagramReq(
            to: baseAddress,
            protocol: Protocols.RELAY,
          );
        });
      }
    });
  }

  List<Parameter> getParameterList() {
    allOf(DataParams);
  }
}