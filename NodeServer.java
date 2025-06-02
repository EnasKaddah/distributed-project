import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class NodeServer {
    private final String nodeId;
    private final int port;
    private final String storagePath;
    private boolean running = true;

    public NodeServer(String nodeId, int port) {
        this.nodeId = nodeId;
        this.port = port;
        this.storagePath = "node_storage_" + nodeId;
        initializeStorage();
    }

    private void initializeStorage() {
        try {
            Path path = Paths.get(storagePath);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
            System.out.println("[" + nodeId + "] Storage initialized at: " + 
                            path.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("[" + nodeId + "] Failed to initialize storage: " + 
                            e.getMessage());
            System.exit(1);
        }
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))) {
            System.out.println("[" + nodeId + "] Started on " + 
                            serverSocket.getLocalSocketAddress());
            
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("[" + nodeId + "] Connection from: " + 
                                    clientSocket.getInetAddress());
                    new Thread(() -> handleClient(clientSocket)).start();
                } catch (IOException e) {
                    System.err.println("[" + nodeId + "] Accept error: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[" + nodeId + "] Server error: " + e.getMessage());
        }
    }

    private void handleClient(Socket clientSocket) {
        try (ObjectInputStream in = new ObjectInputStream(clientSocket.getInputStream());
             ObjectOutputStream out = new ObjectOutputStream(clientSocket.getOutputStream())) {
            
            String command = (String) in.readObject();
            System.out.println("[" + nodeId + "] Received command: " + command);
            
            switch (command) {
                case "UPLOAD":
                    handleUpload(in, out);
                    break;
                case "DOWNLOAD":
                    handleDownload(in, out);
                    break;
                case "VERIFY":
                    handleVerify(in, out);
                    break;
                case "SYNC":
                    handleSync(in, out);
                    break;
                case "UPDATE":
                    handleUpdate(in, out);
                    break;
                case "VERIFY_CONTENT":
                    handleVerifyContent(in, out);
                    break;
                case "DELETE":
                    handleDelete(in, out);
                    break;


                default:
                    out.writeObject("ERROR: Unknown command: " + command);
            }
        } catch (Exception e) {
            System.err.println("[" + nodeId + "] Client handling error: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                System.err.println("[" + nodeId + "] Error closing socket: " + e.getMessage());
            }
        }
    }

    private void handleUpdate(ObjectInputStream in, ObjectOutputStream out)
            throws IOException, ClassNotFoundException {
        FileData fileData = (FileData) in.readObject();
        System.out.println("[" + nodeId + "] Updating: " + fileData.getFilename() +
                " in " + fileData.getDepartment());

        Path deptPath = Paths.get(storagePath, fileData.getDepartment());
        Path filePath = deptPath.resolve(fileData.getFilename());

        if (!Files.exists(filePath)) {
            out.writeObject("ERROR: File not found");
            return;
        }

        Files.write(filePath, fileData.getContent(),
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);

        System.out.println("[" + nodeId + "] Updated: " + filePath.toAbsolutePath());
        out.writeObject("OK: File updated");
    }

    private void handleDelete(ObjectInputStream in, ObjectOutputStream out)
            throws IOException, ClassNotFoundException {

        String department = (String) in.readObject();
        String filename = (String) in.readObject();
        System.out.println("[" + nodeId + "] Delete request: " + department + "/" + filename);

        Path filePath = Paths.get(storagePath, department, filename);
        if (!Files.exists(filePath)) {
            out.writeObject("ERROR: File not found");
            return;
        }

        try {
            Files.delete(filePath);
            out.writeObject("OK: File deleted");
            System.out.println("[" + nodeId + "] Deleted: " + filePath);
        } catch (IOException e) {
            System.err.println("[" + nodeId + "] Delete error: " + e.getMessage());
            out.writeObject("ERROR: Could not delete file");
        }
    }


    private void handleVerifyContent(ObjectInputStream in, ObjectOutputStream out)
            throws IOException, ClassNotFoundException {
        String department = (String) in.readObject();
        String filename = (String) in.readObject();
        byte[] expectedContent = (byte[]) in.readObject();

        Path filePath = Paths.get(storagePath, department, filename);
        if (!Files.exists(filePath)) {
            out.writeObject(false);
            return;
        }

        byte[] actualContent = Files.readAllBytes(filePath);
        out.writeObject(Arrays.equals(expectedContent, actualContent));
    }


    private void handleUpload(ObjectInputStream in, ObjectOutputStream out)
            throws IOException, ClassNotFoundException {

        FileData fileData = (FileData) in.readObject();
        System.out.println("[" + nodeId + "] Creating: " + fileData.getFilename() +
                " in " + fileData.getDepartment());

        Path deptPath = Paths.get(storagePath, fileData.getDepartment());
        if (!Files.exists(deptPath)) {
            Files.createDirectories(deptPath);
        }

        Path filePath = deptPath.resolve(fileData.getFilename());
        Files.write(filePath, fileData.getContent(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        System.out.println("[" + nodeId + "] Created: " + filePath.toAbsolutePath());
        out.writeObject("OK: File created");  // Make sure this matches exactly
    }


    private void handleDownload(ObjectInputStream in, ObjectOutputStream out) 
        throws IOException, ClassNotFoundException {
        
        String department = (String) in.readObject();
        String filename = (String) in.readObject();
        System.out.println("[" + nodeId + "] Download request: " + 
                         department + "/" + filename);
        
        Path filePath = Paths.get(storagePath, department, filename);
        if (!Files.exists(filePath)) {
            System.err.println("[" + nodeId + "] File not found: " + filePath);
            out.writeObject("ERROR: File not found");
            return;
        }
        
        try {
            byte[] content = Files.readAllBytes(filePath);
            FileData fileData = new FileData(filename, department, content, "system");
            out.writeObject(fileData);
            System.out.println("[" + nodeId + "] Sent file: " + filename + 
                            " (" + content.length + " bytes)");
        } catch (IOException e) {
            System.err.println("[" + nodeId + "] Read error: " + e.getMessage());
            out.writeObject("ERROR: Could not read file");
        }
    }

    private void handleVerify(ObjectInputStream in, ObjectOutputStream out) 
        throws IOException, ClassNotFoundException {
        
        String department = (String) in.readObject();
        String filename = (String) in.readObject();
        System.out.println("[" + nodeId + "] Verifying: " + 
                         department + "/" + filename);
        
        Path filePath = Paths.get(storagePath, department, filename);
        boolean exists = Files.exists(filePath);
        
        System.out.println("[" + nodeId + "] Verification result: " + exists);
        out.writeObject(exists);
    }

    private void handleSync(ObjectInputStream in, ObjectOutputStream out) 
        throws IOException, ClassNotFoundException {
        
        String department = (String) in.readObject();
        String filename = (String) in.readObject();
        System.out.println("[" + nodeId + "] Sync request: " + 
                         department + "/" + filename);
        
        Path filePath = Paths.get(storagePath, department, filename);
        if (Files.exists(filePath)) {
            byte[] content = Files.readAllBytes(filePath);
            out.writeObject(content);
            System.out.println("[" + nodeId + "] Sent sync data: " + 
                              filename + " (" + content.length + " bytes)");
        } else {
            out.writeObject(null);
            System.out.println("[" + nodeId + "] No file to sync");
        }
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: java NodeServer <nodeId> <port>");
            System.out.println("Example: java NodeServer node1 8001");
            return;
        }
        
        String nodeId = args[0];
        int port = Integer.parseInt(args[1]);
        
        NodeServer node = new NodeServer(nodeId, port);
        node.start();
    }
}