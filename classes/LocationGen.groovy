//LocationGen.groovy
class LocationGen{
    public HashMap generate(int nodes, int numberOfLvl, int radius, int depthBase, int levelDepthData)
    {
        Random random = new Random()
        def nodeLocation = [:]

        nodeLocation[1] = [ 0.m, 0.m, -depthBase.m]

        for(int i = 2; i<=nodes; i++)
        {
            nodeLocation[i] = [ random.nextInt(2*radius)-radius.m, random.nextInt(2*radius)-radius.m, -(levelDepthData*(i%numberOfLvl+1)).m]
        }

        println nodeLocation

        return nodeLocation
    }
}