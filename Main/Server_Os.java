import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

// ClientCommand คือคลาสสำหรับ Wrap คำสั่งที่มาจาก client โดยจับคู่ User กับ command เพื่อใช้ส่งต่อใน BlockingQueue
class ClientCommand {
    public final User user;
    public final String command;

    public ClientCommand(User user, String command) {
        this.user = user;
        this.command = command;
    }
}

//ใช้ Single-Threaded (ผ่าน controlQueue) และ Worker Pool (BroadcasterPool)
public class Server_Os {
    // ---------------- CONFIG ----------------
    private final int MAX_SOCKET_SIZE = 250; // จำนวน client สูงสุดที่ค้างใน backlog ของ ServerSocket
    private final int MAX_CONTROLQUEUE_SIZE = 5000; // ขนาดคิวสูงสุดสำหรับเก็บคำสั่ง
    private final int START_THREADS = 12; // จำนวน thread เริ่มต้นของ BroadcasterPool
    private final ServerConnection serverConnection = new ServerConnection();
    private final RoomRegistry roomRegistry = new RoomRegistry();
    private final ClientRegistry clientRegistry = new ClientRegistry();

    // Thread Pool ที่ broadcast ข้อความ(SAY)
    private final BroadcasterPool broadcasterPool = new BroadcasterPool(START_THREADS, roomRegistry, clientRegistry);
    
    // โยนคำสั่ง (JOIN, SAY, DM) เข้าคิวนี้
    private final BlockingQueue<ClientCommand> controlQueue = new LinkedBlockingQueue<>(MAX_CONTROLQUEUE_SIZE);
    
    // โยนคำสั่ง PING เข้าคิวนี้
    private final BlockingQueue<ClientCommand> heartbeatQueue = new LinkedBlockingQueue<>();
    public static void main(String[] args) {
        new Server_Os().startServer();
    }

    public void startServer() {
        new Thread(this::routerLoop, "RouterThread").start();
        new Thread(this::heartbeatWorker, "HeartbeatWorker").start();

        try (ServerSocket serverSocket = new ServerSocket(serverConnection.getPort(), MAX_SOCKET_SIZE)) {
            System.out.println("[System]: Server started on port " + serverConnection.getPort());
            while (true) {
                Socket socket = serverSocket.accept(); // Block รอ client ใหม่
                
                // พอ client ใหม่เข้ามาจะสร้าง ClientHandler 1 thread ไปจัดการ
                new Thread(new ClientHandler(socket), "ClientHandler").start();
                
                System.out.println("[System]: New client connected: " + socket.getInetAddress().getHostName() + " ("
                        + socket.getLocalSocketAddress() + ")");
            }
        } catch (IOException e) {
            System.out.println("[System]: Server error!");
            e.printStackTrace();
        }
    }

    // routerLoop (RouterThread) เป็น Thread เดียวที่ประมวลผลคำสั่งหลัก (JOIN, SAY, DM, LEAVE, QUIT)
    // Single-threaded processor เพื่อป้องกัน Race Condition (เช่น การพยายาม join/leave ห้องพร้อมกัน)
    private void routerLoop() {
        while (true) {
            try {
                ClientCommand cmd = controlQueue.take(); // .take() จะ block รอจนกว่าจะมีคำสั่งใหม่เข้ามาใน controlQueue
                processCommand(cmd); // ส่งคำสั่งไปประมวลผล (แบบทีละคำสั่ง)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // Map สำหรับเก็บเวลา ping ล่าสุดของทุกคน
    private final Map<String, Long> heartbeatMap = new ConcurrentHashMap<>(); // <clientId, lastPingTime>

    // heartbeatWorker คือ Thread ที่ทำหน้าที่รับ PING ใหม่จาก heartbeatQueue มาอัปเดตเวลา
    // และเช็กทุก 5 วินาที ว่ามี client คนไหน timeout เกิน 30 วิหรือไม่
    private void heartbeatWorker() {
        final long CHECK_INTERVAL = 5000; // เช็กทุก 5 วินาที
        final long TIMEOUT = 30000; // ตัดการเชื่อมต่อถ้าหายไปเกิน 30 วินาที

        while (true) {
            try {
                // รอ PING ใหม่จากคิว
                // ถ้าครบ 5 วิแล้วไม่มี PING ใหม่ 'command' จะเป็น null
                ClientCommand command = heartbeatQueue.poll(CHECK_INTERVAL, java.util.concurrent.TimeUnit.MILLISECONDS);
                long now = System.currentTimeMillis();

                // ถ้ามี PING ใหม่มา ก็อัปเดตเวลาใน map
                if (command != null) {
                    heartbeatMap.put(command.user.getClientId(), now);
                }

                // ตรวจสอบ Timeout (ทำทุก 5 วินาที)
                for (var entry : heartbeatMap.entrySet()) {
                    String clientId = entry.getKey();
                    long lastPing = entry.getValue();

                    // ถ้าเวลาปัจจุบัน - เวลา ping ล่าสุด > 30 วินาที = ซอมบี้!
                    if (now - lastPing > TIMEOUT) {
                        System.out.println("[Heartbeat]: Client " + clientId + " timed out (zombie). Removing...");

                        // จัดการเตะ client นั้นออกจากระบบ
                        User user = clientRegistry.getUserById(clientId);
                        if (user != null) {
                            clientRegistry.unregisterClient(user);
                            roomRegistry.removeUserFromAllRooms(user, broadcasterPool);
                        }

                        // ลบออกจาก map ที่เช็ก
                        heartbeatMap.remove(clientId);
                        System.out.println("[Heartbeat]: Client " + clientId + " removed.");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void processCommand(ClientCommand cmd) {
        if (cmd.command.isEmpty())
            return;

        // แยกส่วนคำสั่ง
        String[] parts = cmd.command.split(" ", 3);
        String command = parts[0].toUpperCase();
        String param1 = (parts.length > 1) ? parts[1] : "";
        String param2 = (parts.length > 2) ? parts[2] : "";

        if (command.equals("JOIN")) {
            clientRegistry.sendDirectMessage(cmd.user, ">>> Join room: " + param1);
            roomRegistry.joinRoom(param1, cmd.user);
            
            // โยนงานให้ BroadcasterPool ไปประกาศในห้อง
            broadcasterPool.submitTask(new BroadcastTask(param1, ": " + cmd.user.getClientId() + " joined the room."));

        } else if (command.equals("SAY")) {
            if (roomRegistry.isMember(param1, cmd.user)) { // เช็กว่าอยู่ในห้องจริง
                clientRegistry.sendDirectMessage(cmd.user, ">>> Say to " + param1 + ": " + param2);
                BroadcastTask task = new BroadcastTask(param1, cmd.user.getClientId() + ": " + param2);
                
                // RouterThread ไม่ส่งเองแต่โยน task ให้ BroadcasterPool ไปทำ
                broadcasterPool.submitTask(task); 
            } else {
                String text = "[Server]: You are not in room " + param1 + " Can't send message";
                clientRegistry.sendDirectMessage(cmd.user, text);
            }
        } else if (command.equals("DM")) {
            if (clientRegistry.hasClientId(param1)) {
                clientRegistry.sendDirectMessage(cmd.user, ">>> Direct message to " + param1 + ": " + param2);
                String text = "[DM]" + cmd.user.getClientId() + ": " + param2;
                
                // DM ส่งตรงได้เลย ไม่ต้องผ่าน BroadcasterPool
                clientRegistry.sendDirectMessage(clientRegistry.getUserById(param1), text);
            } else {
                clientRegistry.sendDirectMessage(cmd.user, "[Server]: Receiver not found " + param1);
            }
        } else if (command.equals("WHO")) {
            if (roomRegistry.isMember(param1, cmd.user)) {
                Set<User> members = roomRegistry.getMembers(param1);
                Set<String> allClientId = ConcurrentHashMap.newKeySet();
                for (User u : members) {
                    allClientId.add(u.getClientId());
                }
                clientRegistry.sendDirectMessage(cmd.user, ">>> Members in " + param1 + ": " + allClientId);
            } else {
                clientRegistry.sendDirectMessage(cmd.user, "[Server]: You are not in room " + param1);
            }
        } else if (command.equals("LEAVE")) {
            if (roomRegistry.isMember(param1, cmd.user)) {
                clientRegistry.sendDirectMessage(cmd.user, ">>> Leave room " + param1);
                roomRegistry.leaveRoom(param1, cmd.user);

                // โยนงานให้ BroadcasterPool ไปประกาศ
                String text = ": " + cmd.user.getClientId() + " left the room.";
                broadcasterPool.submitTask(new BroadcastTask(param1, text));
            } else {
                clientRegistry.sendDirectMessage(cmd.user, "[Server]: You are not in room " + param1);
            }
        } else if (command.equals("QUIT")) {
            clientRegistry.sendDirectMessage(cmd.user, ">>> Goodbye " + cmd.user.getClientId());
            
            // Cleanup
            roomRegistry.removeUserFromAllRooms(cmd.user, broadcasterPool); // ลบออกจากทุกห้อง
            clientRegistry.unregisterClient(cmd.user); // ลบออกจากระบบ
            heartbeatMap.remove(cmd.user.getClientId()); // ลบออกจาก heartbeatMap

        } else if (command.equals("CLIENT_OVERLOADED")) { // เมื่อ client แจ้งมาว่าตัวเอง overload
            String originalMsg = cmd.command.substring("CLIENT_OVERLOADED".length()).trim();
            String senderName = null;

            // พยายามดึงชื่อผู้ส่งจากข้อความ
            int start = originalMsg.indexOf(']');
            int colon = originalMsg.indexOf(':');
            if (start != -1 && colon != -1 && colon > start) {
                senderName = originalMsg.substring(start + 1, colon).trim();
            }

            if (senderName != null && clientRegistry.hasClientId(senderName)) {
                // ถ้าหา Sender เจอ
                User sender = clientRegistry.getUserById(senderName);
                String text = "[Server]: " + cmd.user.getClientId()
                        + " overloaded. Message failed to deliver. Try later.";
                
                // ส่ง DM กลับไปบอก Sender ว่าเพื่อนคุณรับไม่ทันให้ส่งใหม่ทีหลัง
                clientRegistry.sendDirectMessage(sender, text);
                String textNotified = "[System]: Notified " + senderName + " about overload from "
                        + cmd.user.getClientId();
                System.out.println(textNotified);
            } else {
                System.out
                        .println("[System]: Cannot identify sender in message. Overload notification failed.  Message: "
                                + originalMsg);
            }
        } else if (command.equals("SET_THREADS")) {
            // เช็กสิทธิ์ว่าต้องเป็น "Admin"
            if (!cmd.user.getClientId().equals("Admin")) {
                clientRegistry.sendDirectMessage(cmd.user, "[Server]: Permission denied.");
                return;
            }
            try {
                // สั่งเปลี่ยนจำนวน thread ใน BroadcasterPool
                int threads = Integer.parseInt(param1);
                System.out.println("[System]: Broadcaster threads set to " + threads);
                broadcasterPool.setThreadCount(threads);
            } catch (NumberFormatException e) {
                System.out.println("[System]: Invalid thread count: " + param1);
            }
        } else {
            clientRegistry.sendDirectMessage(cmd.user, "[System]: Unknown command " + command);
        }
    }

    /* ClientHandler 1 Thread ต่อ 1 Client
    หน้าที่:
        1. ตรวจสอบ HELLO และเช็กชื่อซ้ำ
        2. client Register
        3. วนลูปอ่านคำสั่ง(readLine)
        4. คัดแยกคำสั่ง
            - ถ้าเป็น "PING" จะโยนเข้า `heartbeatQueue`
            - ถ้าเป็นคำสั่งอื่น จะโยนเข้า `controlQueue`
        5. จัดการ Cleanup เมื่อ client หลุด (ไม่ว่าจะด้วย QUIT หรือ เน็ตตัด) */
    private class ClientHandler implements Runnable {
        private final Socket socket;
        private User user;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            // ใช้ try-with-resources เพื่อปิด in/out อัตโนมัติ (แต่ socket ต้องปิดใน finally)
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

                // ตรวจสอบว่าต้องส่ง HELLO
                String firstLine = in.readLine();
                if (firstLine == null || !firstLine.startsWith("HELLO ")) {
                    out.println("[Server]: Invalid handshake. Closing connection.");
                    socket.close();
                    return;
                }
                String clientId = firstLine.substring(6).trim();

                // เช็กชื่อซ้ำ
                if (clientRegistry.hasClientId(clientId)) {
                    out.println("[Server]: Error - Name already taken. Choose another.");
                    socket.close();
                    return;
                }

                // ลงทะเบียน client
                user = new User(clientId, out);
                clientRegistry.registerClient(user);
                clientRegistry.sendDirectMessage(user, "[Server]: Welcome " + user.getClientId() + "!");
                System.out.println("[System]: " + user.getClientId() + " connected.");

                // วนลูปอ่านคำสั่งที่เหลือ
                String input;
                while ((input = in.readLine()) != null) {
                    
                    // คัดแยกถ้าเป็น PING
                    if (input.trim().equalsIgnoreCase("PING")) {
                        // โยนเข้า heartbeatQueue
                        heartbeatQueue.offer(new ClientCommand(user, input.trim()));
                    
                    // คัดแยกถ้าเป็นคำสั่งอื่น
                    } else {
                        // โยนเข้า Control Queue
                        if (!controlQueue.offer(new ClientCommand(user, input.trim()))) {
                            // ถ้าคิวเต็ม
                            System.out.println(
                                    "[System]: Control queue full! Dropping command from " + user.getClientId());
                            clientRegistry.sendDirectMessage(user, "[Server]: Server busy. Your command was dropped.");
                        }
                    }
                }

            } catch (IOException e) {
                // เกิดเมื่อ client เน็ตตัด หรือปิดโปรแกรมแบบไม่ QUIT
                System.out.println("[System]: " + (user != null ? user.getClientId() : "unknown") + " disconnected.");
            } finally {
                if (user != null) {  
                    clientRegistry.unregisterClient(user);
                    heartbeatMap.remove(user.getClientId()); // ลบออกจาก heartbeatMap
                    roomRegistry.removeUserFromAllRooms(user, broadcasterPool); // ลบออกจากทุกห้อง
                }
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}