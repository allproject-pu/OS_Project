// ----------- BroadcastTask -------------
// คลาสนี้เป็น "หน่วยงาน(Task)" สำหรับกระจายข้อความไปยังห้อง
// จะถูกสร้างและส่งเข้าไปในคิวของ BroadcasterPool เพื่อให้ thread อื่นนำไปส่งต่อจริง
public class BroadcastTask {

    private final String roomName;
    private final String message;
    private final long enqueueTime;

    // ---------------- Constructor ----------------
    // สร้าง BroadcastTask พร้อมบันทึกเวลาที่ถูกสร้าง
    public BroadcastTask(String roomName, String message) {
        this.roomName = roomName;
        this.message = message;
        this.enqueueTime = System.currentTimeMillis(); // จับเวลาขณะสร้าง task
    }

    public String getRoomName() {
        return roomName;
    }

    public String getMessage() {
        return message;
    }

    public long getEnqueueTime() {
        return enqueueTime;
    }
}
