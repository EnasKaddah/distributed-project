import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class NodeSyncClient {
    private List<NodeInfo> nodes;
    private String nodeId;
    private String storagePath;

    public NodeSyncClient(String nodeId, List<NodeInfo> nodes) {
        this.nodeId = nodeId;
        this.nodes = nodes;
        this.storagePath = "node_storage_" + nodeId;
    }

    public void sync() {
        for (NodeInfo node : nodes) {
            if (!node.getNodeId().equals(nodeId)) { // Don't sync with self
                syncWithNode(node);
            }
        }
    }

    private void syncWithNode(NodeInfo node) {
        try {
            // Get list of departments
            File storageDir = new File(storagePath);
            if (!storageDir.exists()) return;
            
            for (File deptDir : storageDir.listFiles(File::isDirectory)) {
                String department = deptDir.getName();
                
                for (File file : deptDir.listFiles()) {
                    syncFile(node, department, file.getName());
                }
            }
        } catch (Exception e) {
            System.err.println("Sync error with node " + node.getNodeId() + ": " + e.getMessage());
        }
    }

    private void syncFile(NodeInfo node, String department, String filename) {
        try (Socket socket = new Socket(node.getAddress(), node.getPort());
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
            
            out.writeObject("SYNC");
            out.writeObject(department);
            out.writeObject(filename);
            
            byte[] remoteContent = (byte[]) in.readObject();
            byte[] localContent = Files.readAllBytes(Paths.get(storagePath, department, filename));
            
            if (remoteContent == null) {
                // File doesn't exist on remote node, send our copy
                sendFile(node, department, filename);
            } else if (!Arrays.equals(remoteContent, localContent)) {
                // Files are different, implement conflict resolution
                // For simplicity, we'll just keep both versions with timestamps
                resolveConflict(node, department, filename, remoteContent, localContent);
            }
        } catch (Exception e) {
            System.err.println("Error syncing file " + filename + ": " + e.getMessage());
        }
    }

    private void sendFile(NodeInfo node, String department, String filename) throws IOException {
        try (Socket socket = new Socket(node.getAddress(), node.getPort());
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
            
            byte[] content = Files.readAllBytes(Paths.get(storagePath, department, filename));
            FileData fileData = new FileData(filename, department, content, "sync");
            
            out.writeObject("UPLOAD");
            out.writeObject(fileData);
            
            String response = (String) in.readObject();
            if (!"OK: File uploaded".equals(response)) {
                System.err.println("Failed to sync file " + filename + ": " + response);
            }
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public String getNodeId() {
        return nodeId;
    }


    private void resolveConflict(NodeInfo node, String department, String filename, 
                               byte[] remoteContent, byte[] localContent) throws IOException {
        // Simple conflict resolution - keep both versions with timestamps
        String timestamp = String.valueOf(System.currentTimeMillis());
        String newFilename = filename + "_conflict_" + timestamp;
        
        Files.write(Paths.get(storagePath, department, newFilename), localContent);
        System.out.println("Resolved conflict for " + filename + " by creating " + newFilename);
    }
}