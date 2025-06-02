import java.io.Serializable;

public class FileData implements Serializable {
    private String filename;
    private String department;
    private byte[] content;
    private String owner;

    public FileData(String filename, String department, byte[] content, String owner) {
        this.filename = filename;
        this.department = department;
        this.content = content;
        this.owner = owner;
    }

    // Getters and setters
    public String getFilename() { return filename; }
    public String getDepartment() { return department; }
    public byte[] getContent() { return content; }
    public String getOwner() { return owner; }
}