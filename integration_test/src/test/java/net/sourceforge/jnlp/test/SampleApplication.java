package net.sourceforge.jnlp.test;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

import org.apache.cxf.jaxrs.client.JAXRSClientFactory;
import org.apache.cxf.jaxrs.client.WebClient;

import net.sourceforge.jnlp.test.client.TestService;

import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;

/**
 * Sample client-side application for integration testing.
 * This application uses Apache CXF REST client to communicate with a server.
 */
public class SampleApplication {
    
    private static final Logger logger = Logger.getLogger(SampleApplication.class.getName());
    private static JFrame frame;
    private static JTextArea logArea;
    private static final String SERVER_URL = "http://localhost:8080";
    
    public static void main(String[] args) {
        System.out.println("=== SampleApplication.main() called ===");
        System.out.println("Arguments: " + java.util.Arrays.toString(args));
        System.out.println("Thread: " + Thread.currentThread().getName());
        
        // Check for headless mode
        String headless = System.getProperty("java.awt.headless");
        System.out.println("java.awt.headless: " + headless);
        
        try {
            java.awt.GraphicsEnvironment ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
            boolean isHeadless = ge.isHeadlessInstance();
            System.out.println("Graphics environment is headless: " + isHeadless);
            if (isHeadless) {
                System.err.println("ERROR: Running in headless mode - cannot display GUI!");
                System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("ERROR checking graphics environment: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("Invoking SwingUtilities.invokeLater() to create GUI...");
        SwingUtilities.invokeLater(() -> {
            System.out.println("Inside SwingUtilities.invokeLater() - creating GUI...");
            try {
                createGUI();
                System.out.println("GUI created successfully");
                log("=== IcedTea-Web Client Application ===");
                log("Application started");
                log("Server URL: " + SERVER_URL);
                log("\nClick the 'Test REST Calls' button to initiate REST communication.");
                
                if (args.length > 0) {
                    log("Arguments received: " + String.join(" ", args));
                }
                System.out.println("GUI initialization complete");
            } catch (Exception e) {
                System.err.println("ERROR creating GUI: " + e.getMessage());
                e.printStackTrace();
            }
        });
        System.out.println("main() method completed");
    }
    
    private static void createGUI() {
        System.out.println("createGUI() called");
        try {
            System.out.println("Creating JFrame...");
            frame = new JFrame("IcedTea-Web Client Application");
            System.out.println("JFrame created");
            
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                    log("Window closing - exiting application");
                    System.exit(0);
                }
            });
            frame.setSize(800, 600);
            frame.setLocationRelativeTo(null);
            System.out.println("JFrame configured");
        
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        // Create button panel
        JPanel buttonPanel = new JPanel();
        JButton testButton = new JButton("Test REST Calls");
        testButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        testButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                testButton.setEnabled(false);
                log("\n--- Initiating REST Calls ---");
                new Thread(() -> {
                    try {
                        testRestCalls();
                    } catch (Exception ex) {
                        log("ERROR: " + ex.getMessage());
                        ex.printStackTrace();
                    } finally {
                        SwingUtilities.invokeLater(() -> {
                            testButton.setEnabled(true);
                        });
                    }
                }).start();
            }
        });
        buttonPanel.add(testButton);
        
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setBackground(java.awt.Color.BLACK);
        logArea.setForeground(java.awt.Color.GREEN);
        
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        
        mainPanel.add(buttonPanel, BorderLayout.NORTH);
        mainPanel.add(scrollPane, BorderLayout.CENTER);
        frame.add(mainPanel);
        System.out.println("Components added to frame");
        
        System.out.println("Setting frame visible...");
        frame.setVisible(true);
        System.out.println("Frame is now visible");
        System.out.println("Frame size: " + frame.getSize());
        System.out.println("Frame location: " + frame.getLocation());
        System.out.println("Frame isDisplayable: " + frame.isDisplayable());
        System.out.println("Frame isShowing: " + frame.isShowing());
    } catch (Exception e) {
        System.err.println("ERROR in createGUI(): " + e.getMessage());
        e.printStackTrace();
        throw e;
    }
    }
    
    private static void log(String message) {
        String logMessage = message + "\n";
        System.out.print(logMessage);
        if (logArea != null) {
            SwingUtilities.invokeLater(() -> {
                logArea.append(logMessage);
                logArea.setCaretPosition(logArea.getDocument().getLength());
            });
        }
    }
    
    private static void testRestCalls() {
        try {
            log("\n--- Testing REST Client with Apache CXF ---");
            log("Server URL: " + SERVER_URL);
            
            // Create REST client using CXF
            JacksonJsonProvider jsonProvider = new JacksonJsonProvider();
            TestService service = JAXRSClientFactory.create(SERVER_URL, TestService.class, 
                java.util.Arrays.asList(jsonProvider));
            
            log("REST client created successfully");
            
            // Test ping endpoint
            log("\nCalling /api/test/ping...");
            jakarta.ws.rs.core.Response pingResponse = service.ping();
            log("Response status: " + pingResponse.getStatus());
            if (pingResponse.getStatus() == 200) {
                Map<?, ?> pingData = pingResponse.readEntity(Map.class);
                log("Response: " + pingData);
                log("✓ Ping test successful");
            } else {
                log("✗ Ping test failed with status: " + pingResponse.getStatus());
            }
            
            // Test data endpoint
            log("\nCalling /api/test/data...");
            jakarta.ws.rs.core.Response dataResponse = service.getData();
            log("Response status: " + dataResponse.getStatus());
            if (dataResponse.getStatus() == 200) {
                Map<?, ?> data = dataResponse.readEntity(Map.class);
                log("Response: " + data);
                log("✓ Data test successful");
            } else {
                log("✗ Data test failed with status: " + dataResponse.getStatus());
            }
            
            log("\n=== All REST tests completed ===");
            log("Application will continue running. Close window to exit.");
            
        } catch (Exception e) {
            log("\n✗ ERROR during REST calls: " + e.getClass().getName());
            log("Message: " + e.getMessage());
            e.printStackTrace();
            for (StackTraceElement element : e.getStackTrace()) {
                log("  at " + element.toString());
            }
        }
    }
}



