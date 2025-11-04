import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.*;

/*
ChatClientTester คือคลาสสำหรับทำ Load Test ไปยัง ChatServer
วัตถุประสงค์:
    1. จำลอง client (NUM_CLIENTS) เชื่อมต่อพร้อมกัน
    2. ทดสอบประสิทธิภาพของ Server (Latency, Throughput)
    3. ทดสอบผลกระทบของการเปลี่ยนจำนวน Thread ของ Broadcaster ฝั่ง Server (ผ่านคำสั่ง SET_THREADS)
 */
public class ChatClientTester {
    // ---------------- CONFIG ----------------
    private static final ServerConnection serverConnection = new ServerConnection();
    private static final String ROOM = "#os-lab"; // สร้างห้องแชตที่จะใช้ทดสอบ
    private static final int NUM_CLIENTS = 250; // จำนวน client ที่จะจำลอง (ยิงพร้อมกัน)
    private static final int MESSAGES_PER_CLIENT = 1; // จำนวนข้อความที่ client จำลองแต่ละตัวจะส่ง
    private static final int[] THREAD_COUNTS = {1, 2, 4, 8, 12, 16, 24}; // จำนวน threads ของ Broadcaster (ฝั่ง Server) ที่จะลองเปลี่ยนไปเรื่อยๆ
    private static final int ROUND_TEST_PER_THREAD = 1; // จำนวนรอบทดสอบซ้ำต่อ thread setting
    private static final int SLEEPTIME_MS = 1000; // เวลารอ (ms) หลังส่งคำสั่ง SET_THREADS ให้ Server (รอให้ Server ตั้งตัว)
    private static final int JOIN_ACK_TIMEOUT_MS = 5000; // เวลารอ (ms) ที่ Server ต้องตอบว่า JOIN สำเร็จ
    private static final int MESSAGE_READ_TIMEOUT_MS = 2000; // เวลารอ (ms) ที่ข้อความ broadcast ต้องส่งกลับมาถึง
    // -----------------------------------------
    public static void main(String[] args) throws Exception {
        System.out.println("====== ChatClientTester started ======");
        System.out.println("Connecting to " + serverConnection.getAddress() + ":" + serverConnection.getPort());

        List<TestResult> results = new ArrayList<>(); // List สำหรับเก็บผลลัพธ์ของทุกรอบ

        // Loop หลักสำหรับวนทดสอบตามจำนวน threads ที่กำหนดใน THREAD_COUNTS
        for (int threads : THREAD_COUNTS) {
            System.out.println("\n======================================");
            System.out.println(">>> Testing Broadcaster Threads = " + threads);
            System.out.println("======================================");

            // ส่งคำสั่งพิเศษ "SET_THREADS" ไปบอก Server ให้เปลี่ยนจำนวน Thread
            sendThreadChangeCommand(threads);
            
            // รอให้ Server ปรับปรุงตัวเอง
            Thread.sleep(SLEEPTIME_MS);

            // วนลูปทดสอบซ้ำ
            for (int round = 1; round <= ROUND_TEST_PER_THREAD; round++) {
                TestResult r = runTestRound(threads); // รันการทดสอบ 1 รอบ
                results.add(r); // เก็บผลลัพธ์
            }
        }

        // ---------------- SUMMARY ----------------
        // พิมพ์สรุปผลทั้งหมดเป็นตาราง
        System.out.println("\nClient number: " + NUM_CLIENTS);
        System.out.println("Messages per client: " + MESSAGES_PER_CLIENT);
        System.out.println("======== PERFORMANCE SUMMARY =========");
        System.out.printf("%-10s %-15s %-20s %-15s%n",
                "Threads", "Total Time (s)", "Avg Latency (ms/msg)", "Throughput (msg/s)"); // หัวตาราง
        for (TestResult r : results) {
            System.out.printf("%-10d %-15.3f %-20.3f %-15.3f%n",
                    r.threads, r.totalTime / 1000.0, r.avgLatency, r.throughput); // ผลลัพธ์แต่ละรอบ
        }
        System.out.println("======================================");
    }

    // สร้าง "Admin" client ชั่วคราว เพื่อส่งคำสั่งพิเศษ (SET_THREADS) ไปยัง Server
    private static void sendThreadChangeCommand(int threads) {
        // ใช้ try-with-resources เพื่อปิด socket, in, out อัตโนมัติ
        try (Socket socket = new Socket(serverConnection.getAddress(), serverConnection.getPort());
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            out.println("HELLO Admin"); // บอก Server ว่าตัวเองคือ Admin
            out.println("SET_THREADS " + threads); // ส่งคำสั่งเปลี่ยนจำนวน Thread ของ Broadcaster
            out.println("QUIT");
            out.flush();

        } catch (Exception e) {
            System.err.println("[Admin] Error: " + e.getMessage());
        }
    }

    // runTestRound -> รันการทดสอบ 1 รอบเต็ม
    private static TestResult runTestRound(int threads) throws Exception {
        // สร้าง Thread Pool ฝั่ง Client เพื่อจำลอง Client พร้อมๆ กัน
        ExecutorService pool = Executors.newFixedThreadPool(NUM_CLIENTS);
        List<Future<ClientResult>> futures = new ArrayList<>(); // List เก็บผลลัพธ์จากแต่ละ client

        long globalStart = System.currentTimeMillis(); // เริ่มจับเวลารวมของการทดสอบ

        // สร้าง task (runClient) ตามจำนวน NUM_CLIENTS แล้วส่งให้ pool รัน
        for (int i = 0; i < NUM_CLIENTS; i++) {
            final int id = i;
            String clientName = "User" + threads + "T_" + id; 
            futures.add(pool.submit(() -> runClient(clientName)));
        }

        // ปิดการสร้างงานใหม่ (ไม่รับ task เพิ่ม)
        pool.shutdown();
        // รอจนกว่า runClient ทุกตัวทำงานเสร็จ (หรือถ้าเกิน 300 วิ ก็ฆ่าทิ้ง)
        pool.awaitTermination(300, TimeUnit.SECONDS);

        long totalLatencyNs = 0L;
        int totalMessagesSent = 0; // จำนวนข้อความที่ส่งสำเร็จ
        int totalReceived = 0; // จำนวนข้อความที่ได้รับกลับมา

        // รวมผลจากทุก client
        for (Future<ClientResult> f : futures) {
            try {
                ClientResult cr = f.get(); // ดึงผลลัพธ์จาก client 1 ตัว
                totalLatencyNs += cr.totalLatencyNs;
                totalMessagesSent += cr.messagesSent;
                totalReceived += cr.messagesReceived;
            } catch (Exception e) {
                System.err.println("[Test] Error getting future: " + e.getMessage());
                e.printStackTrace();
            }
        }

        long totalTimeMs = System.currentTimeMillis() - globalStart; // หยุดจับเวลารวมของการทดสอบ

        // คำนวณค่าเฉลี่ย
        double avgLatencyMs = (totalMessagesSent > 0)
                ? (totalLatencyNs / (double) totalMessagesSent) / 1_000_000.0 // (ns -> ms)
                : 0.0;
        
        // คำนวณ Throughput (ข้อความต่อวินาที)
        double throughput = (totalTimeMs > 0)
                ? (totalMessagesSent / (totalTimeMs / 1000.0)) // (total msg / total seconds)
                : 0.0;

        // พิมพ์สรุปผลของรอบนี้
        System.out.printf(
                "[%dThreads]: Time = %.3fs, AvgLatency = %.3fms, Throughput = %.3f msg/s, TotalSent=%d, TotalReceived=%d%n",
                threads, totalTimeMs / 1000.0, avgLatencyMs, throughput, totalMessagesSent, totalReceived);

        return new TestResult(threads, totalTimeMs, avgLatencyMs, throughput);
    }

    // runClient เพื่อจำลองการทำงานของ client 1 ตัว (ตั้งแต่connect, join, ส่งข้อความ, จน quit)
    private static ClientResult runClient(String clientName) {
        long totalLatencyNs = 0;
        int messagesSent = 0;
        int messagesReceived = 0;

        // ใช้ try-with-resources ปิด socket, in, out อัตโนมัติ
        try (Socket socket = new Socket(serverConnection.getAddress(), serverConnection.getPort());
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            // HELLO เพื่อ register ไปที่ Server
            out.println("HELLO " + clientName);
            out.flush();
            String welcome = readLineWithTimeout(in, socket, 3000); // อ่านข้อความต้อนรับ
            if (welcome == null) { // ถ้า timeout หรือ server ไม่ตอบ
                System.err.println("[" + clientName + "] No welcome from server. Aborting.");
                return new ClientResult(totalLatencyNs, messagesSent, messagesReceived);
            }

            // ใช้คำสั่ง Join Room
            out.println("JOIN " + ROOM);
            out.flush();
            boolean joined = waitForJoinAck(in, socket, JOIN_ACK_TIMEOUT_MS); // รอจนกว่าจะได้เข้าห้องจริงๆ
            if (!joined) { // ถ้าเข้าห้องไม่ได้ (timeout)
                System.err.println("[" + clientName + "] join ack not received within timeout. Aborting.");
                return new ClientResult(totalLatencyNs, messagesSent, messagesReceived);
            }

            // ใช้คำสั่ง SAY เพื่อส่งข้อความตามจำนวนที่ตั้งไว้
            for (int i = 1; i <= MESSAGES_PER_CLIENT; i++) {
                String msg = "Hello_" + i;
                long startNs = System.nanoTime(); // เริ่มจับเวลา Latency

                out.println("SAY " + ROOM + " " + msg);
                out.flush();
                messagesSent++;

                // รอข้อความ broadcast กลับจากห้องเพื่อเป็นการวัด Latency
                long msgDeadline = System.currentTimeMillis() + MESSAGE_READ_TIMEOUT_MS;
                boolean sawBroadcast = false; // flag ว่าเจอข้อความตัวเองกลับมาหรือยัง

                while (System.currentTimeMillis() < msgDeadline) {
                    // อ่านข้อความจาก server
                    String line = readLineWithTimeout(in, socket,
                            Math.max(500, (int) (msgDeadline - System.currentTimeMillis())));
                    if (line == null) // ถ้า timeout (ยังไม่มีข้อความ) ก็วน loop ต่อ
                        continue;

                    // ตรวจว่าเป็นข้อความ broadcast ของตัวเอง
                    if (line.startsWith("[" + ROOM + "]") && line.contains(clientName + ":")) {
                        long latencyNs = System.nanoTime() - startNs; // หยุดจับเวลา Latency
                        totalLatencyNs += latencyNs;
                        messagesReceived++;
                        sawBroadcast = true;
                        break;
                    }
                }

                if (!sawBroadcast) { // ถ้าหมดเวลาแล้วยังไม่เห็นข้อความตัวเอง
                    System.err.println(
                            "[" + clientName + "] Did not see broadcast for message " + i + " within timeout.");
                }
            }

            // ใช้คำสั่ง QUIT เพื่อปิดการเชื่อมต่อ
            out.println("QUIT");
            out.flush();
            readLineWithTimeout(in, socket, 500); // อ่าน "Goodbye" (ถ้า server ส่งมา)
            Thread.sleep(80); // พักเพื่อให้ OS คืนทรัพยากร

        } catch (Exception e) {
            System.err.println("[" + clientName + "] Error: " + e.getMessage());
            e.printStackTrace();
        }

        // คืนผลลัพธ์ของ client ตัวนี้
        return new ClientResult(totalLatencyNs, messagesSent, messagesReceived);
    }

    // readLineWithTimeout เพื่ออ่าน 1 บรรทัดจาก Server แต่ถ้าเกิน timeout จะข้ามไปเลย
    private static String readLineWithTimeout(BufferedReader in, Socket socket, int timeoutMs) throws IOException {
        int originalTimeout = socket.getSoTimeout(); // บันทึกค่า timeout เดิมไว้
        try {
            socket.setSoTimeout(timeoutMs); // ตั้ง timeout ใหม่
            return in.readLine();
        } catch (SocketTimeoutException e) {
            return null; // ถ้า timeout ก็คืนค่า null
        } finally {
            socket.setSoTimeout(originalTimeout); // คืนค่า timeout เดิมให้ socket
        }
    }

    // waitForJoinAck สำหรับรอข้อความ "Join room" เพื่อยืนยันว่า join สำเร็จ
    private static boolean waitForJoinAck(BufferedReader in, Socket socket, int timeoutMs) throws IOException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            // พยายามอ่านโดยมี timeout สั้นๆ
            String line = readLineWithTimeout(in, socket, Math.min(1000, timeoutMs)); 
            if (line == null)
                continue;
            
            if (line.contains(">>> Join room"))
                return true;
        }
        return false; // timeout แล้วยังไม่เจอ
    }

    // ClientResult เพื่อเก็บข้อมูลผลลัพธ์จาก client จำลอง 1 ตัว
    private static class ClientResult {
        final long totalLatencyNs;
        final int messagesSent;
        final int messagesReceived;

        ClientResult(long totalLatencyNs, int messagesSent, int messagesReceived) {
            this.totalLatencyNs = totalLatencyNs;
            this.messagesSent = messagesSent;
            this.messagesReceived = messagesReceived;
        }
    }

    // TestResult จะเก็บผลลัพธ์สรุปของ "การทดสอบ 1 รอบ" (เช่น 8 threads, 250 clients)
    private static class TestResult {
        final int threads; // จำนวน thread ของ Broadcaster ที่ใช้ในรอบนี้
        final long totalTime; // เวลารวม (ms) ที่ใช้ทดสอบรอบนี้
        final double avgLatency; // Latency เฉลี่ย (ms)
        final double throughput; // Throughput (msg/s)

        TestResult(int threads, long totalTime, double avgLatency, double throughput) {
            this.threads = threads;
            this.totalTime = totalTime;
            this.avgLatency = avgLatency;
            this.throughput = throughput;
        }
    }
}