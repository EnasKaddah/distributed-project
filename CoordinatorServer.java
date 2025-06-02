import java.io.File;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CoordinatorServer {
    private static final List<Process> nodeProcesses = new ArrayList<>();
    private static final List<NodeInfo> testNodes = new ArrayList<>();
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    public static void main(String[] args) {
        try {
            // Start RMI registry
            LocateRegistry.createRegistry(1099);

            // Initialize test nodes
            initializeTestNodes();

            // Start the coordinator with test nodes
            CoordinatorInterface coordinator = new CoordinatorImpl();
            addTestNodesToCoordinator(coordinator);

            Naming.rebind("Coordinator", coordinator);
            System.out.println("Coordinator server is running...");

            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                shutdownNodes();
            }));

        } catch (Exception e) {
            System.err.println("Coordinator server exception: " + e);
            shutdownNodes();
            System.exit(1);
        }
    }

    private static void initializeTestNodes() throws Exception {
        // Define test nodes
        testNodes.add(new NodeInfo("node1", "localhost", 8001));
        testNodes.add(new NodeInfo("node2", "localhost", 8002));
        testNodes.add(new NodeInfo("node3", "localhost", 8003));

        // Start node processes
        for (NodeInfo node : testNodes) {
            startNodeProcess(node);
        }

        // Wait for nodes to initialize
        Thread.sleep(2000);
        System.out.println("Initialized test nodes:");
        testNodes.forEach(node ->
                System.out.println("- " + node.getNodeId() + " at " + node.getAddress() + ":" + node.getPort()));
    }

    private static void startNodeProcess(NodeInfo node) {
        executor.submit(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "java", "NodeServer", node.getNodeId(), String.valueOf(node.getPort()));
                pb.redirectOutput(new File(node.getNodeId() + "_output.log"));
                pb.redirectError(new File(node.getNodeId() + "_error.log"));
                Process process = pb.start();
                nodeProcesses.add(process);
            } catch (Exception e) {
                System.err.println("Error starting " + node.getNodeId() + ": " + e.getMessage());
            }
        });
    }

    private static void addTestNodesToCoordinator(CoordinatorInterface coordinator) throws RemoteException {
        for (NodeInfo node : testNodes) {
            coordinator.addNode("MANAGER_TOKEN", node);
        }
    }

    private static void shutdownNodes() {
        System.out.println("Shutting down node servers...");
        nodeProcesses.forEach(p -> {
            if (p.isAlive()) p.destroy();
        });
        executor.shutdown();
    }
}