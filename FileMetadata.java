import java.io.Serializable;

public class FileMetadata implements Serializable {
    private String filename;
    private String department;
    private String nodeAddress;
    private int nodePort;
    private boolean isLocked;
    private String lockedBy;  // username of user who locked the file
    private long lockTimestamp;

    // Add these to constructor
    public FileMetadata(String filename, String department, String nodeAddress, int nodePort) {
        this.filename = filename;
        this.department = department;
        this.nodeAddress = nodeAddress;
        this.nodePort = nodePort;
        this.isLocked = false;
        this.lockedBy = null;
        this.lockTimestamp = 0;
    }

    // Add getters and setters
    public boolean isLocked() { return isLocked; }
    public String getLockedBy() { return lockedBy; }
    public long getLockTimestamp() { return lockTimestamp; }

    public void lock(String username) {
        this.isLocked = true;
        this.lockedBy = username;
        this.lockTimestamp = System.currentTimeMillis();
    }

    public void unlock() {
        this.isLocked = false;
        this.lockedBy = null;
        this.lockTimestamp = 0;
    }

    // Add method to check if lock is expired (e.g., after 5 minutes)
    public boolean isLockExpired() {
        return isLocked && (System.currentTimeMillis() - lockTimestamp) > (5 * 60 * 1000);
    }

    public String getFilename() { return filename; }
    public String getDepartment() { return department; }
    public String getNodeAddress() { return nodeAddress; }
    public int getNodePort() { return nodePort; }
}
