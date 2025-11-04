import java.util.Set;
import java.util.concurrent.*;

// ----------- BroadcasterPool: จัดการ thread pool สำหรับกระจายข้อความแบบ concurrent --------------
// หน้าที่หลัก: สร้าง worker threads (Broadcaster) หลายตัว เพื่อรับงาน (BroadcastTask)
public class BroadcasterPool {

    // คิวเก็บงานที่จะให้ Broadcaster ทำ
    private final BlockingQueue<BroadcastTask> taskQueue = new LinkedBlockingQueue<>();

    // สำหรับเข้าถึงข้อมูล client และ room ที่มีอยู่ในระบบ
    private final ClientRegistry clientRegistry;
    private final RoomRegistry roomRegistry;

    private ExecutorService pool;
    private int numThreads;

    // ---------------- Initialization ----------------
    public BroadcasterPool(int numThreads, RoomRegistry roomRegistry, ClientRegistry clientRegistry) {
        this.numThreads = numThreads;
        this.roomRegistry = roomRegistry;
        this.clientRegistry = clientRegistry;
        startPool(numThreads);
    }

    // สร้างและเริ่ม Broadcaster threads
    private void startPool(int threads) {
        pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(new Broadcaster(taskQueue, roomRegistry, clientRegistry));
        }
    }

    // ---------------- ปรับจำนวน threads ระหว่างรันไทม์ ----------------
    public synchronized void setThreadCount(int threads) {
        if (threads == this.numThreads)
            return;

        int diff = threads - this.numThreads;
        this.numThreads = threads;

        if (diff > 0) {
            // เพิ่ม threads ใหม่
            for (int i = 0; i < diff; i++) {
                pool.submit(new Broadcaster(taskQueue, roomRegistry, clientRegistry));
            }
        } else if (diff < 0) {
            // ลด threads ส่ง "STOP_THREAD" เพื่อสั่งให้บาง thread หยุดทำงาน
            for (int i = 0; i < -diff; i++) {
                taskQueue.offer(new BroadcastTask("__CONTROL__", "STOP_THREAD"));
            }
        }
    }

    // ---------------- ฟังก์ชันรับงาน broadcast จากภายนอก ----------------
    public void submitTask(BroadcastTask task) {
        taskQueue.offer(task);
    }

    // --------------------------- Broadcaster class ---------------------------
    // แต่ละ Broadcaster เป็น worker thread ที่วนรอรับงานจาก taskQueue
    public class Broadcaster implements Runnable {
        private final BlockingQueue<BroadcastTask> taskQueue;
        private final RoomRegistry roomRegistry;
        private final ClientRegistry clientRegistry;

        public Broadcaster(BlockingQueue<BroadcastTask> taskQueue, RoomRegistry roomRegistry,
                ClientRegistry clientRegistry) {
            this.taskQueue = taskQueue;
            this.roomRegistry = roomRegistry;
            this.clientRegistry = clientRegistry;
        }

        @Override
        public void run() {
            try {
                while (true) {
                    // ดึงงานออกจากคิว (block จนกว่าจะมีงานใหม่เข้ามา)
                    BroadcastTask task = taskQueue.take();

                    if ("__CONTROL__".equals(task.getRoomName()) && "STOP_THREAD".equals(task.getMessage())) {
                        return;
                    }

                    String room = task.getRoomName();
                    String message = task.getMessage();

                    // ดึงรายชื่อสมาชิก
                    Set<User> members = roomRegistry.getMembers(room);
                    if (members == null || members.isEmpty())
                        continue;

                    // ส่งข้อความไปยังสมาชิกแต่ละคนในห้องนั้น
                    for (User member : members) {
                        clientRegistry.sendDirectMessage(member, "[" + room + "] " + message);
                    }
                }
            } catch (InterruptedException e) {
                // ถ้า thread ถูก interrupt ให้หยุดการทำงานอย่างปลอดภัย
                Thread.currentThread().interrupt();
            }
        }
    }
}
