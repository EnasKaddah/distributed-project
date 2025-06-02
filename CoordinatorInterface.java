import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

public interface CoordinatorInterface extends Remote {
    String login(String username, String password) throws RemoteException;
    boolean registerUser(String managerToken, User user) throws RemoteException;
    FileMetadata searchFile(String token, String filename, String department) throws RemoteException;
    boolean uploadFile(String token, FileData file) throws RemoteException;
    boolean createFile(String token, FileData file) throws RemoteException;
    byte[] readFile(String token, String filename, String department) throws RemoteException;

    List<NodeInfo> getActiveNodes() throws RemoteException;
    boolean addNode(String managerToken, NodeInfo node) throws RemoteException;
    boolean updateFile(String token, FileData file) throws RemoteException;
    boolean syncAllNodes(String managerToken) throws RemoteException;
    User getUserByToken(String token) throws RemoteException;
    boolean deleteFile(String token, String filename, String department) throws RemoteException;
    boolean lockFile(String token, String filename, String department) throws RemoteException;
    boolean unlockFile(String token, String filename, String department) throws RemoteException;
    boolean isFileLocked(String token, String filename, String department) throws RemoteException;

}