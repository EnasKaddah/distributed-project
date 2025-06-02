import java.io.*;
import java.net.*;  // Add this import for Socket
import java.rmi.*;
import java.rmi.server.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public class CoordinatorImpl extends UnicastRemoteObject implements CoordinatorInterface {
    private Map<String, User> users = new HashMap<>();
    private Map<String, User> tokenToUser = new HashMap<>();
    private List<NodeInfo> nodes = new ArrayList<>();
    private AtomicInteger currentNodeIndex = new AtomicInteger(0);
    private Map<String, List<FileMetadata>> fileIndex = new HashMap<>();
    private String managerToken = "MANAGER_TOKEN"; // In real system, generate properly

    public CoordinatorImpl() throws RemoteException {
        super();
        // Initialize with manager user
        User manager = new User("manager", "manager123", "management");
        manager.addPermission("manage_users");
        manager.addPermission("manage_nodes");
        manager.setToken(managerToken);
        users.put("manager", manager);
        tokenToUser.put(managerToken, manager);
    }

    @Override
    public boolean createFile(String token, FileData file) throws RemoteException {
        // 1. Validate user authentication
        User user = tokenToUser.get(token);
        if (user == null) {
            throw new RemoteException("Authentication failed: Invalid token");
        }

        // 2. Validate department permissions
        if (!user.getDepartment().equals(file.getDepartment())) {
            throw new RemoteException("Permission denied: You can only create files in your department (" +
                    user.getDepartment() + ")");
        }

        // 3. Validate filename
        if (file.getFilename() == null || file.getFilename().trim().isEmpty()) {
            throw new RemoteException("Invalid filename: Filename cannot be empty");
        }
        if (file.getFilename().contains("/") || file.getFilename().contains("\\")) {
            throw new RemoteException("Invalid filename: Cannot contain path separators");
        }

        // 4. Validate content
        if (file.getContent() == null) {
            throw new RemoteException("Invalid content: File content cannot be null");
        }
        if (file.getContent().length > 10 * 1024 * 1024) { // 10MB limit
            throw new RemoteException("File too large: Maximum size is 10MB");
        }

        // 5. Check for available nodes
        if (nodes.isEmpty()) {
            throw new RemoteException("System error: No storage nodes available");
        }

        // 6. Select node using round-robin with retry logic
        int attempts = 0;
        int maxAttempts = nodes.size();
        NodeInfo node = null;

        while (attempts < maxAttempts) {
            node = nodes.get(currentNodeIndex.getAndIncrement() % nodes.size());

            if (node.isActive()) {
                try {
                    // 7. Connect to node and send file
                    try (Socket socket = new Socket(node.getAddress(), node.getPort());
                         ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                         ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

                        // Set timeout for node communication
                        socket.setSoTimeout(5000); // 5 seconds timeout

                        out.writeObject("UPLOAD");
                        out.writeObject(file);

                        // 8. Verify node response
                        String response = (String) in.readObject();
                        if (!"OK: File created".equals(response)) {
                            throw new RemoteException("Node error: " + response);
                        }

                        // 9. Verify file was actually created
                        if (!verifyFileOnNode(node, file.getDepartment(), file.getFilename())) {
                            throw new RemoteException("Verification failed: File not found on node");
                        }

                        // 10. Update file index
                        FileMetadata metadata = new FileMetadata(
                                file.getFilename(),
                                file.getDepartment(),
                                node.getAddress(),
                                node.getPort()
                        );

                        synchronized (fileIndex) {
                            fileIndex.computeIfAbsent(file.getDepartment(), k -> new ArrayList<>())
                                    .add(metadata);
                        }

                        return true;
                    }
                } catch (SocketTimeoutException e) {
                    System.err.println("Timeout with node " + node.getNodeId() + ", trying next node");
                    node.setActive(false); // Mark node as inactive
                } catch (IOException | ClassNotFoundException e) {
                    System.err.println("Error with node " + node.getNodeId() + ": " + e.getMessage());
                    node.setActive(false); // Mark node as inactive
                }
            }
            attempts++;
        }

        throw new RemoteException("Failed after " + maxAttempts + " attempts: No responsive nodes available");
    }

    @Override
    public byte[] readFile(String token, String filename, String department) throws RemoteException {
        // 1. Validate user authentication
        User user = tokenToUser.get(token);
        if (user == null) {
            throw new RemoteException("Authentication failed: Invalid token");
        }

        // Check if file is locked (and not by current user)
        boolean isLocked = isFileLocked(token, filename, department);
        if (isLocked) {
            FileMetadata metadata = getFileMetadata(filename, department);
            if (metadata != null && !metadata.getLockedBy().equals(user.getUsername())) {
                throw new RemoteException("File is locked by another user");
            }
        }


        // 2. Check if user has access to this department
      /*  if (!user.getDepartment().equals(department)) {
            throw new RemoteException("Permission denied: You can only read files in your department");
        }*/

        // 3. Find the file in the index
        synchronized (fileIndex) {
            List<FileMetadata> deptFiles = fileIndex.get(department);
            if (deptFiles != null) {
                for (FileMetadata metadata : deptFiles) {
                    if (metadata.getFilename().equals(filename)) {
                        // 4. Retrieve file from node
                        try (Socket socket = new Socket(metadata.getNodeAddress(), metadata.getNodePort());
                             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

                            out.writeObject("DOWNLOAD");
                            out.writeObject(department);
                            out.writeObject(filename);

                            Object response = in.readObject();
                            if (response instanceof FileData) {
                                return ((FileData) response).getContent();
                            } else {
                                throw new RemoteException("Node error: " + response);
                            }
                        } catch (Exception e) {
                            throw new RemoteException("Failed to read file from node: " + e.getMessage());
                        }
                    }
                }
            }
        }

        throw new RemoteException("File not found");
    }

    private FileMetadata getFileMetadata(String filename, String department) {
        synchronized (fileIndex) {
            List<FileMetadata> deptFiles = fileIndex.get(department);
            if (deptFiles != null) {
                for (FileMetadata metadata : deptFiles) {
                    if (metadata.getFilename().equals(filename)) {
                        return metadata;
                    }
                }
            }
        }
        return null;
    }


    @Override
    public boolean lockFile(String token, String filename, String department) throws RemoteException {
        User user = tokenToUser.get(token);
        if (user == null) {
            throw new RemoteException("Authentication failed: Invalid token");
        }

        synchronized (fileIndex) {
            List<FileMetadata> deptFiles = fileIndex.get(department);
            if (deptFiles != null) {
                for (FileMetadata metadata : deptFiles) {
                    if (metadata.getFilename().equals(filename)) {
                        // If file is already locked (and not by current user)
                        if (metadata.isLocked() && !metadata.getLockedBy().equals(user.getUsername())) {
                            if (metadata.isLockExpired()) {
                                // Auto-unlock expired locks
                                metadata.unlock();
                            } else {
                                return false; // File is locked by someone else
                            }
                        }
                        metadata.lock(user.getUsername());
                        return true;
                    }
                }
            }
        }
        throw new RemoteException("File not found");
    }

    @Override
    public boolean unlockFile(String token, String filename, String department) throws RemoteException {
        User user = tokenToUser.get(token);
        if (user == null) {
            throw new RemoteException("Authentication failed: Invalid token");
        }

        synchronized (fileIndex) {
            List<FileMetadata> deptFiles = fileIndex.get(department);
            if (deptFiles != null) {
                for (FileMetadata metadata : deptFiles) {
                    if (metadata.getFilename().equals(filename)) {
                        // Only unlock if locked by the same user
                        if (metadata.isLocked() && metadata.getLockedBy().equals(user.getUsername())) {
                            metadata.unlock();
                            return true;
                        }
                        return false;
                    }
                }
            }
        }
        throw new RemoteException("File not found");
    }

    @Override
    public boolean isFileLocked(String token, String filename, String department) throws RemoteException {
        synchronized (fileIndex) {
            List<FileMetadata> deptFiles = fileIndex.get(department);
            if (deptFiles != null) {
                for (FileMetadata metadata : deptFiles) {
                    if (metadata.getFilename().equals(filename)) {
                        // Check if lock is expired
                        if (metadata.isLockExpired()) {
                            metadata.unlock();
                            return false;
                        }
                        return metadata.isLocked();
                    }
                }
            }
        }
        throw new RemoteException("File not found");
    }




    private void addTestNodes() {
    try {
        // Add 3 test nodes
        nodes.add(new NodeInfo("node1", "localhost", 8001));
        nodes.add(new NodeInfo("node2", "localhost", 8002));
        nodes.add(new NodeInfo("node3", "localhost", 8003));

        System.out.println("Added 3 test nodes:");
        for (NodeInfo node : nodes) {
            System.out.println("- " + node.getNodeId() + " at " +
                            node.getAddress() + ":" + node.getPort());
        }
        } catch (Exception e) {
            System.err.println("Error adding test nodes: " + e.getMessage());
        }
    }


    @Override
    public String login(String username, String password) throws RemoteException {
        User user = users.get(username);
        if (user != null && user.getPassword().equals(password)) {
            String token = UUID.randomUUID().toString();
            user.setToken(token);
            tokenToUser.put(token, user);
            return token;
        }
        throw new RemoteException("Invalid credentials");
    }

    @Override
    public boolean registerUser(String managerToken, User user) throws RemoteException {
        User manager = tokenToUser.get(managerToken);
        if (manager == null || !manager.hasPermission("manage_users")) {
            throw new RemoteException("Permission denied");
        }
        users.put(user.getUsername(), user);
        return true;
    }

    @Override
    public FileMetadata searchFile(String token, String filename, String department) throws RemoteException {
        User user = tokenToUser.get(token);
        if (user == null) {
            throw new RemoteException("Invalid token");
        }

        // Check if there are any nodes
        if (nodes.isEmpty()) {
            throw new RemoteException("No storage nodes available");
        }

        // Load balancing: round-robin approach
        int attempts = 0;
        while (attempts < nodes.size()) {
            int index = currentNodeIndex.getAndIncrement() % nodes.size();
            NodeInfo node = nodes.get(index);
            
            if (node.isActive()) {
                // In real system, would check if file exists on this node
                // For simplicity, we'll just return the first active node
                return new FileMetadata(filename, department, node.getAddress(), node.getPort());
            }
            attempts++;
        }
        
        throw new RemoteException("No active nodes available");
    }

    @Override
    public boolean uploadFile(String token, FileData file) throws RemoteException {
        User user = tokenToUser.get(token);
        if (user == null) throw new RemoteException("Invalid token");
        if (!user.getDepartment().equals(file.getDepartment())) 
            throw new RemoteException("You can only upload to your department");

        if (nodes.isEmpty()) throw new RemoteException("No storage nodes available");

        NodeInfo node = nodes.get(currentNodeIndex.getAndIncrement() % nodes.size());
        
        try (Socket socket = new Socket(node.getAddress(), node.getPort());
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
            
            out.writeObject("UPLOAD");
            out.writeObject(file);
            
            String response = (String) in.readObject();
            if (!"OK: File uploaded".equals(response)) {
                throw new RemoteException("Node upload failed: " + response);
            }

            // Verify file actually exists on node
            if (!verifyFileOnNode(node, file.getDepartment(), file.getFilename())) {
                throw new RemoteException("File verification failed after upload");
            }
            
            FileMetadata metadata = new FileMetadata(file.getFilename(), 
                                                file.getDepartment(),
                                                node.getAddress(), 
                                                node.getPort());
            fileIndex.computeIfAbsent(file.getDepartment(), k -> new ArrayList<>())
                    .add(metadata);
            return true;
        } catch (Exception e) {
            throw new RemoteException("Upload failed: " + e.getMessage());
        }
    }

    private boolean verifyFileOnNode(NodeInfo node, String department, String filename) {
        try (Socket socket = new Socket(node.getAddress(), node.getPort());
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
            
            out.writeObject("VERIFY");
            out.writeObject(department);
            out.writeObject(filename);
            
            return (boolean) in.readObject();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean updateFile(String token, FileData file) throws RemoteException {
        // 1. Validate user authentication
        User user = tokenToUser.get(token);
        if (user == null) {
            throw new RemoteException("Authentication failed: Invalid token");
        }

        // 2. Validate department permissions
        if (!user.getDepartment().equals(file.getDepartment())) {
            throw new RemoteException("Permission denied: You can only update files in your department (" +
                    user.getDepartment() + ")");
        }

        // 3. Find the file in the index
        FileMetadata metadata = null;
        synchronized (fileIndex) {
            List<FileMetadata> deptFiles = fileIndex.get(file.getDepartment());
            if (deptFiles != null) {
                for (FileMetadata m : deptFiles) {
                    if (m.getFilename().equals(file.getFilename())) {
                        metadata = m;
                        break;
                    }
                }
            }
        }

        if (metadata == null) {
            throw new RemoteException("File not found in index");
        }

        // 4. Connect to the node where the file is stored
        try (Socket socket = new Socket(metadata.getNodeAddress(), metadata.getNodePort());
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            out.writeObject("UPDATE");
            out.writeObject(file);

            // 5. Verify node response
            String response = (String) in.readObject();
            if (!"OK: File updated".equals(response)) {
                throw new RemoteException("Node error: " + response);
            }

            // 6. Verify file was actually updated
            if (!verifyFileOnNode(metadata, file.getContent())) {
                throw new RemoteException("Verification failed: File not properly updated on node");
            }

            return true;
        } catch (Exception e) {
            throw new RemoteException("Failed to update file: " + e.getMessage());
        }
    }

    @Override
    public boolean syncAllNodes(String managerToken) throws RemoteException {
        User manager = tokenToUser.get(managerToken);
        if (manager == null || !manager.hasPermission("manage_nodes")) {
            throw new RemoteException("Permission denied");
        }

        System.out.println("Starting full node synchronization...");

        // Create sync clients for each node
        Map<String, NodeSyncClient> syncClients = new HashMap<>();
        for (NodeInfo node : nodes) {
            syncClients.put(node.getNodeId(), new NodeSyncClient(node.getNodeId(), nodes));
        }

        // Run sync for each node
        for (NodeSyncClient syncClient : syncClients.values()) {
            try {
                System.out.println("Syncing node " + syncClient.getNodeId() + "...");
                syncClient.sync();
                System.out.println("Sync completed for node " + syncClient.getNodeId());
            } catch (Exception e) {
                System.err.println("Error syncing node " + syncClient.getNodeId() + ": " + e.getMessage());
            }
        }

        System.out.println("Full node synchronization completed");
        return true;
    }
    @Override
    public User getUserByToken(String token) throws RemoteException {
        return tokenToUser.get(token);
    }


    private boolean verifyFileOnNode(FileMetadata metadata, byte[] expectedContent) {
        try (Socket socket = new Socket(metadata.getNodeAddress(), metadata.getNodePort());
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            out.writeObject("VERIFY_CONTENT");
            out.writeObject(metadata.getDepartment());
            out.writeObject(metadata.getFilename());
            out.writeObject(expectedContent);

            return (boolean) in.readObject();
        } catch (Exception e) {
            return false;
        }
    }



    @Override
    public List<NodeInfo> getActiveNodes() throws RemoteException {
        List<NodeInfo> activeNodes = new ArrayList<>();
        for (NodeInfo node : nodes) {
            if (node.isActive()) {
                activeNodes.add(node);
            }
        }
        return activeNodes;
    }

    @Override
    public boolean addNode(String managerToken, NodeInfo node) throws RemoteException {
        User manager = tokenToUser.get(managerToken);
        if (manager == null || !manager.hasPermission("manage_nodes")) {
            throw new RemoteException("Permission denied");
        }
        nodes.add(node);
        return true;
    }

    @Override
    public boolean deleteFile(String token, String filename, String department) throws RemoteException {
        // 1. Validate user authentication
        User user = tokenToUser.get(token);
        if (user == null) {
            throw new RemoteException("Authentication failed: Invalid token");
        }

        // 2. Check if user has access to this department
        if (!user.getDepartment().equals(department)) {
            throw new RemoteException("Permission denied: You can only delete files in your department");
        }

        // 3. Find the file in the index
        FileMetadata metadata = null;
        synchronized (fileIndex) {
            List<FileMetadata> deptFiles = fileIndex.get(department);
            if (deptFiles != null) {
                Iterator<FileMetadata> iterator = deptFiles.iterator();
                while (iterator.hasNext()) {
                    FileMetadata m = iterator.next();
                    if (m.getFilename().equals(filename)) {
                        metadata = m;
                        iterator.remove();
                        break;
                    }
                }
            }
        }

        if (metadata == null) {
            throw new RemoteException("File not found in index");
        }

        // 4. Connect to the node where the file is stored and delete it
        try (Socket socket = new Socket(metadata.getNodeAddress(), metadata.getNodePort());
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            out.writeObject("DELETE");
            out.writeObject(department);
            out.writeObject(filename);

            // 5. Verify node response
            String response = (String) in.readObject();
            if (!"OK: File deleted".equals(response)) {
                throw new RemoteException("Node error: " + response);
            }

            // 6. Verify file was actually deleted
            if (verifyFileOnNode(metadata.getNodeAddress(), metadata.getNodePort(), department, filename)) {
                throw new RemoteException("Verification failed: File still exists on node");
            }

            return true;
        } catch (Exception e) {
            throw new RemoteException("Failed to delete file: " + e.getMessage());
        }
    }

    private boolean verifyFileOnNode(String nodeAddress, int nodePort, String department, String filename) {
        try (Socket socket = new Socket(nodeAddress, nodePort);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            out.writeObject("VERIFY");
            out.writeObject(department);
            out.writeObject(filename);

            return (boolean) in.readObject();
        } catch (Exception e) {
            return false;
        }
    }

}