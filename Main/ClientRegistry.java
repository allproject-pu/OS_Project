import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.*;

public class ClientRegistry {
    // เก็บ reply queue ของแต่ละ client
    private final List<User> connectedClients = new CopyOnWriteArrayList<>();

    // เพิ่ม client เข้าสู่ระบบ
    public void registerClient(User user) {
        connectedClients.add(user);
    }

    // ลบ client ออกจากระบบ
    public void unregisterClient(User user) {
        connectedClients.remove(user);
    }

    // ดึงรายชื่อ client ทั้งหมด
    public List<User> getAllUsers() {
        return connectedClients;
    }

    // เช็คว่ามี Client คนนี้อยู่ในระบบหรือไม่ (โดยใช้ clientId)
    public synchronized boolean hasClientId(String clientId) {
        for (User user : connectedClients) {
            if (user.getClientId().equals(clientId)) {
                return true;
            }
        }
        return false;
    }

    // ดึง User object โดยใช้ clientId
    public User getUserById(String id) {
        for (User u : connectedClients) {
            if (u.getClientId().equals(id))
                return u;
        }
        return null;
    }

    // ส่งข้อความตรงไปยัง client ที่ระบุ
    public void sendDirectMessage(User user, String message) {
        synchronized (user) {
            PrintWriter userOutput = user.getClientPrintWriter(); // ดึง PrintWriter ของ client คนที่จะส่งข้อความไปหา
            if (userOutput != null) {
                userOutput.println(message);
                userOutput.flush();
            } else {
                System.out.println("[System]: Not found receiver: " + user.getClientId());
            }
        }
    }
}
