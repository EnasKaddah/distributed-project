import java.io.Serializable;

public class NodeInfo implements Serializable {
    private String nodeId;
    private String address;
    private int port;
    private boolean active;

    public NodeInfo(String nodeId, String address, int port) {
        this.nodeId = nodeId;
        this.address = address;
        this.port = port;
        this.active = true;
    }

    // Getters and setters
    public String getNodeId() { return nodeId; }
    public String getAddress() { return address; }
    public int getPort() { return port; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}