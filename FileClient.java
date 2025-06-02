import java.io.*;
import java.net.*;
import java.rmi.*;
import java.nio.file.*;
import java.util.*;

public class FileClient {
    private CoordinatorInterface coordinator;
    private String token;
    private Scanner scanner = new Scanner(System.in);

    public FileClient() {
        try {
            coordinator = (CoordinatorInterface) Naming.lookup("rmi://localhost/Coordinator");
        } catch (Exception e) {
            System.err.println("Error connecting to coordinator: " + e.getMessage());
            System.exit(1);
        }
    }

    public void start() {
        System.out.println("1. Login");
        System.out.println("2. Register (Manager only)");
        System.out.print("Choose option: ");
        int option = scanner.nextInt();
        scanner.nextLine(); // consume newline
        
        try {
            if (option == 1) {
                login();
            } else if (option == 2) {
                registerUser();
            } else {
                System.out.println("Invalid option");
                return;
            }
            
            if (token != null) {
                showMainMenu();
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    private void login() throws RemoteException {
        System.out.print("Username: ");
        String username = scanner.nextLine();
        System.out.print("Password: ");
        String password = scanner.nextLine();
        
        token = coordinator.login(username, password);
        System.out.println("Login successful. Your token: " + token);
    }

    private void registerUser() throws RemoteException {
        System.out.print("Manager token: ");
        String managerToken = scanner.nextLine();
        
        System.out.print("New username: ");
        String username = scanner.nextLine();
        System.out.print("New password: ");
        String password = scanner.nextLine();
        System.out.print("Department: ");
        String department = scanner.nextLine();
        
        User newUser = new User(username, password, department);
        if (coordinator.registerUser(managerToken, newUser)) {
            System.out.println("User registered successfully");
        }
    }

    private void showMainMenu() throws RemoteException, IOException, ClassNotFoundException {
        while (true) {
            System.out.println("\nMain Menu:");
            System.out.println("1. Create new file");
            System.out.println("2. Download file");
            System.out.println("3. Read file");
            System.out.println("4. Update file");
            System.out.println("5. Delete file");

            System.out.println("6. Exit");
            User currentUser = coordinator.getUserByToken(token);
            if (currentUser != null && currentUser.hasPermission("manage_nodes")) {
                System.out.println("7. Manager Options");
            }


            int option = scanner.nextInt();
            scanner.nextLine(); // consume newline
            
            switch (option) {
                case 1:
                    uploadFile();
                    break;
                case 2:
                    downloadFile();
                    break;
                case 3:
                    readFile();
                    break;
                case 4:
                    updateFile();
                    break;
                case 5:
                    deleteFile();
                    break;

                case 6:
                    return;
                case 7:
                    if (currentUser != null && currentUser.hasPermission("manage_nodes")) {
                        showManagerMenu();
                    } else {
                        System.out.println("Invalid option");
                    }
                    break;

                default:
                    System.out.println("Invalid option");
            }
        }
    }

    private void showManagerMenu() throws RemoteException {
        while (true) {
            System.out.println("\nManager Menu:");
            System.out.println("1. Sync all nodes");
            System.out.println("2. Back to main menu");

            int option = scanner.nextInt();
            scanner.nextLine(); // consume newline

            switch (option) {
                case 1:
                    System.out.println("Starting full node synchronization...");
                    if (coordinator.syncAllNodes(token)) {
                        System.out.println("Synchronization completed successfully");
                    } else {
                        System.out.println("Synchronization failed");
                    }
                    break;
                case 2:
                    return;
                default:
                    System.out.println("Invalid option");
            }
        }
    }


    private void deleteFile() {
        try {
            System.out.print("File name to delete: ");
            String filename = scanner.nextLine();
            System.out.print("Department: ");
            String department = scanner.nextLine();

            if (coordinator.deleteFile(token, filename, department)) {
                System.out.println("File deleted successfully");
            } else {
                System.out.println("Failed to delete file");
            }
        } catch (Exception e) {
            System.err.println("Error deleting file: " + e.getMessage());
        }
    }


    private void readFile() {
        try {
            System.out.print("File name: ");
            String filename = scanner.nextLine();
            System.out.print("Department: ");
            String department = scanner.nextLine();

            byte[] content = coordinator.readFile(token, filename, department);
            System.out.println("\nFile content:");
            System.out.println(new String(content));
            System.out.println("\n--- End of file ---");
        } catch (Exception e) {
            System.err.println("Error reading file: " + e.getMessage());
        }
    }


    private void updateFile() {
        try {
            System.out.print("File name to update: ");
            String filename = scanner.nextLine();
            System.out.print("Department: ");
            String department = scanner.nextLine();

            // Try to lock the file first
            if (!coordinator.lockFile(token, filename, department)) {
                System.out.println("File is currently locked by another user. Try again later.");
                return;
            }

            try {
                // First read the current file to show to user
                byte[] currentContent = coordinator.readFile(token, filename, department);
                System.out.println("\nCurrent file content:");
                System.out.println(new String(currentContent));
                System.out.println("\n--- End of current content ---");

                System.out.println("Enter new content (type 'END' on a new line to finish):");
                StringBuilder contentBuilder = new StringBuilder();
                String line;
                while (!(line = scanner.nextLine()).equals("END")) {
                    contentBuilder.append(line).append("\n");
                }

                // Remove the last newline character
                if (contentBuilder.length() > 0) {
                    contentBuilder.setLength(contentBuilder.length() - 1);
                }

                byte[] newContent = contentBuilder.toString().getBytes();
                FileData fileData = new FileData(filename, department, newContent, token);

                if (coordinator.updateFile(token, fileData)) {
                    System.out.println("File updated successfully");
                } else {
                    System.out.println("Failed to update file");
                }
            } finally {
                // Always unlock the file when done
                coordinator.unlockFile(token, filename, department);
            }
        } catch (Exception e) {
            System.err.println("Error updating file: " + e.getMessage());
        }
    }




    private void uploadFile() throws RemoteException, IOException {
        System.out.print("Enter new file name: ");
        String filename = scanner.nextLine();
        System.out.print("Department: ");
        String department = scanner.nextLine();
        System.out.println("Enter file content (type 'END' on a new line to finish):");

        StringBuilder contentBuilder = new StringBuilder();
        String line;
        while (!(line = scanner.nextLine()).equals("END")) {
            contentBuilder.append(line).append("\n");
        }

        // Remove the last newline character
        if (contentBuilder.length() > 0) {
            contentBuilder.setLength(contentBuilder.length() - 1);
        }

        byte[] content = contentBuilder.toString().getBytes();
        FileData fileData = new FileData(filename, department, content, token);

        if (coordinator.createFile(token, fileData)) {
            System.out.println("File created successfully");
        } else {
            System.out.println("Failed to create file");
        }
    }

    private void downloadFile() throws RemoteException {
        System.out.print("File name: ");
        String filename = scanner.nextLine();
        System.out.print("Department: ");
        String department = scanner.nextLine();
        
        try {
            FileMetadata metadata = coordinator.searchFile(token, filename, department);
            if (metadata == null) {
                System.out.println("File not found in index");
                return;
            }

            System.out.println("Attempting download from: " + 
                            metadata.getNodeAddress() + ":" + metadata.getNodePort());
            
            try (Socket socket = new Socket(metadata.getNodeAddress(), metadata.getNodePort());
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
                
                out.writeObject("DOWNLOAD");
                out.writeObject(department);
                out.writeObject(filename);
                
                Object response = in.readObject();
                if (response instanceof FileData) {
                    FileData fileData = (FileData) response;
                    System.out.print("Save as: ");
                    String savePath = scanner.nextLine();
                    Files.write(Paths.get(savePath), fileData.getContent());
                    System.out.println("Download successful!");
                } else {
                    System.out.println("Error: " + response);
                }
            } catch (java.net.ConnectException e) {
                System.err.println("\u001B[31mNode unavailable\u001B[0m: " + e.getMessage());
                System.err.println("Please ensure node " + metadata.getNodeAddress() + 
                                " is running on port " + metadata.getNodePort());
            }
        } catch (Exception e) {
            System.err.println("Download failed: " + e.getMessage());
        }
    }

    private void searchFile() throws RemoteException {
        System.out.print("File name: ");
        String filename = scanner.nextLine();
        System.out.print("Department: ");
        String department = scanner.nextLine();
        
        FileMetadata metadata = coordinator.searchFile(token, filename, department);
        if (metadata != null) {
            System.out.println("File found on node: " + metadata.getNodeAddress() + ":" + metadata.getNodePort());
        } else {
            System.out.println("File not found");
        }
    }

    public static void main(String[] args) {
        new FileClient().start();
    }
}