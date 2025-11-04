import java.io.*;
import java.net.*;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class ChatClient { // ใช้ multi-threading ในการจัดการการรับ input, รับข้อความจาก server และแสดงผลข้อความ
    private final int MAX_REPLYQUEUE_SIZE = 1000; // ขนาดสูงสุดของ Queue ที่ใช้พักข้อความที่ได้รับจาก server
    private final ServerConnection serverConnection = new ServerConnection();
    public static void main(String[] args) {
        ChatClient chatClient = new ChatClient();
        chatClient.ClientRunning();
    }

    void showInstructions() {
        System.out.println("""
                ============================= All Instructions =============================
                DM <receiver_name> <message>   # send a direct message to a specific friend\r
                JOIN <#room_name>            # join a chat room\r
                WHO <#room_name>             # see who is in the room\r
                SAY <#room_name> <message>   # send a message to everyone in the room\r
                LEAVE <#room_name>           # leave the room\r
                QUIT                         # exit the program \r
                =============================================================================""");
    }

    void ClientRunning() { // method หลักที่ควบคุม logic ทั้งหมดของ client
        try (Scanner scanner = new Scanner(System.in)) {
            boolean hasPrintedConnectedMsg = false;
            boolean connected = false;
            int tryConnect = 0;

            // Loop พยายามเชื่อมต่อ server สูงสุด 5 ครั้ง
            while (!connected && tryConnect <= 5) {
                try {
                    Socket socket = new Socket(serverConnection.getAddress(), serverConnection.getPort());
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

                    // สร้าง Queue สำหรับพักข้อความที่มาจาก server รอให้ displayThread พิมพ์
                    BlockingQueue<String> replyQueue = new LinkedBlockingQueue<>(MAX_REPLYQUEUE_SIZE);

                    if (tryConnect == 0 && !hasPrintedConnectedMsg) {
                        System.out.println("[Client]: Connected to server at " + serverConnection.getAddress() + ":"
                                + serverConnection.getPort());
                        hasPrintedConnectedMsg = true;
                    }

                    System.out.print("[Input] Enter your name: ");
                    String name = scanner.nextLine().trim();

                    // ตรวจสอบชื่อ(ห้ามว่าง)
                    if (name.isEmpty()) {
                        System.out.println("[System]: Name cannot be empty. Please try again.");
                        socket.close();
                        continue;
                    }
                    // ตรวจสอบชื่อ(ห้ามมีเว้นวรรค)
                    if (name.contains(" ")) {
                        System.out.println("[System]: Name cannot contain spaces. Please try again.");
                        socket.close();
                        continue;
                    }

                    out.println("HELLO " + name);
                    String response = in.readLine();
                    
                    // ถ้า server ตอบ Error (เช่น ชื่อซ้ำ)
                    if (response != null && response.contains("Error")) {
                        System.out.println(response);
                        socket.close();
                        continue;
                    }

                    System.out.println(response);
                    connected = true;

                    // สร้าง Thread 0 (heartbeatThread): ส่ง PING ทุก 10 วิ เพื่อรักษาการเชื่อมต่อ
                    Thread heartbeatThread = new Thread(() -> {
                        try {
                            while (true) {
                                Thread.sleep(10000); // 10 วินาที
                                out.println("PING");
                            }
                        } catch (InterruptedException ignored) {
                            // ถ้า thread ถูกขัดจังหวะ (เช่น ตอนปิดโปรแกรม) ก็แค่จบการทำงาน
                        }
                    }, "Heartbeat");
                    heartbeatThread.setDaemon(true); // ตั้งเป็น Daemon เพื่อให้ปิดโปรแกรมได้แม้ thread นี้ยังทำงาน
                    heartbeatThread.start();

                    // สร้าง receiverThread เพื่อรับข้อความจาก server ใส่ replyQueue
                    Thread receiverThread = new Thread(() -> {
                        try {
                            String msg;
                            while ((msg = in.readLine()) != null) {
                                // พยายามเพิ่มข้อความลง Queue
                                if (!replyQueue.offer(msg)) { // ถ้า Queue เต็มให้แจ้งเตือนและบอก server
                                    System.out.println(
                                            "[System]: Reply queue full. Please try again later. Message dropped.");
                                    out.println("CLIENT_OVERLOADED " + msg);
                                }
                            }
                        } catch (IOException ignored) {
                            // เกิด IOException (เช่น socket ปิด) ก็ปล่อยให้ thread จบไป
                        }
                    }, "Receiver");
                    receiverThread.start();

                    // displayThread เพื่อแสดงข้อความจาก replyQueue
                    Thread displayThread = new Thread(() -> {
                        try {
                            // วนลูปดึงข้อความจาก Queue
                            while (true) {
                                String msg = replyQueue.take(); // .take() จะ block รอจนกว่าจะมีข้อความ
                                System.out.print("\r" + " ".repeat(80) + "\r"); // เทคนิคเคลียร์บรรทัดเพื่อให้ข้อความใหม่แสดงได้เต็มบรรทัด
                                System.out.println(msg);

                                if (msg.equals(">>> Goodbye " + name)) {
                                    System.out.println("[System]: Client terminated.");
                                    System.exit(0); // ปิดโปรแกรม
                                }
                                System.out.print("[Input] Instruction: ");
                                System.out.flush(); // เคลียร์ buffer ให้แสดงผลทันที
                            }
                        } catch (InterruptedException ignored) {
                            // ถ้า thread ถูกขัดจังหวะ ก็แค่จบการทำงาน
                        }
                    }, "Display");
                    displayThread.start();
                    showInstructions();

                    // Main Thread เพื่อรับคำสั่งจากผู้ใช้ส่งไปที่ server
                    while (true) {
                        System.out.print("[Input] Instruction: ");
                        String input = scanner.nextLine().trim();
                        String[] parts = input.split(" ", 3); // แบ่ง input เป็น 3 ส่วน

                        if (input.isEmpty()) {
                            System.out.println("[Client]: Command : ERROR");
                            continue;
                        }
                        String command = parts[0].toUpperCase();
                        
                        // ตรวจสอบคำสั่งที่ต้องมีชื่อห้อง (#)
                        if (command.equals("JOIN") || command.equals("WHO") || command.equals("SAY")
                                || command.equals("LEAVE")) {
                            if (parts.length < 2) {
                                System.out.println("[System]: Please specify a room name.");
                                continue;
                            }
                            String room = parts[1];
                            if (!room.startsWith("#")) {
                                System.out.println(
                                        "[System]: Room name must start with '#' (#room_name).");
                                continue;
                            }
                        }

                        // ตรวจสอบ SAY ต้องมีข้อความตามหลัง
                        if (command.equals("SAY") && parts.length < 3) {
                            System.out.println("[System]: Usage: SAY #room_name <message>");
                            continue;
                        }

                        // ตรวจสอบ DM ต้องมีชื่อและข้อความ
                        if (command.equals("DM")) {
                            if (parts.length < 3) {
                                System.out.println("[System]: Usage: DM <receiver_name> <message>");
                                continue;
                            }
                        }
                        out.println(input);

                        // ตรวจสอบคำสั่ง QUIT เพื่อปิด client
                        if (command.equals("QUIT")) {
                            // สร้าง thread ชั่วคราวมาจับเวลา
                            // ถ้า server ไม่ตอบกลับ "Goodbye" ภายใน 5 วิ ก็ปิด client ไปเลย
                            new Thread(() -> { 
                                try {
                                    Thread.sleep(5000);
                                    System.out.println("[System]: No response from server. Closing...");
                                    System.exit(0); // บังคับปิด
                                } catch (InterruptedException ignored) {
                                }
                            }).start();
                            break;
                        }
                    }
                } catch (IOException e) { // การเชื่อมต่อล้มเหลว (เช่น Server ปิด)
                    tryConnect++;
                    System.out.println("[Client]: Connection error! Retrying in 2 seconds...");

                    if (tryConnect >= 5) {
                        System.out.println("[Client]: Failed to connect after 5 attempts. Exiting.");
                        break;
                    }
                    try {
                        Thread.sleep(2000); // รอ 2 วินาทีก่อนลองใหม่
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
    }
}