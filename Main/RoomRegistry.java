import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class RoomRegistry {
    // Map<ชื่อห้อง, สมาชิกในห้อง>
    private final Map<String, Set<User>> rooms = new HashMap<>();
    // ตัวล็อกอ่าน–เขียน เพื่อกันหลาย thread
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // ----------------------------- เข้าร่วมห้อง -----------------------------
    public void joinRoom(String roomName, User user) {
        // ล็อค writeLock เพื่อเขียน จะทำให้มีแค่ 1 thread ที่เขียนได้ และบล็อกการอ่าน
        lock.writeLock().lock();
        try {
            rooms.putIfAbsent(roomName, new HashSet<>()); // ถ้ายังไม่มีห้องให้สร้างห้องใหม่
            rooms.get(roomName).add(user); // เพิ่มสมาชิกเข้าไปในห้อง
            System.out.println("[System]: " + user.getClientId() + " joined " + roomName);
        } finally {
            lock.writeLock().unlock(); // ปลดล็อก writeLock
        }
    }

    // ----------------------------- ออกจากห้อง -----------------------------
    public void leaveRoom(String roomName, User user) {
        lock.writeLock().lock();
        try {
            if (rooms.containsKey(roomName)) { // เช็คว่ามีห้องนี้อยู่จริง
                rooms.get(roomName).remove(user);
                System.out.println("[System]: " + user.getClientId() + " left " + roomName);

                // ถ้าห้องว่างให้ลบห้องทิ้ง
                if (rooms.get(roomName).isEmpty()) {
                    rooms.remove(roomName);
                    System.out.println("[System]: Room " + roomName + " is now empty -> deleted.");
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ----------------------------- ดูสมาชิกในห้อง -----------------------------
    public Set<User> getMembers(String roomName) {
        // ล็อก readLock เพื่ออ่าน จะอนุญาตให้หลาย thread อ่านได้พร้อมกัน
        // แต่บล็อกการเขียน
        lock.readLock().lock();
        try {
            // คืนชุดสมาชิกในห้อง หรือคืนชุดว่างถ้าไม่มีห้องนี้
            return new HashSet<>(rooms.getOrDefault(roomName, Collections.emptySet()));
        } finally {
            lock.readLock().unlock(); // ปลดล็อก readLock
        }
    }

    // ------------------------- เช็กสมาชิกใน RoomRegistry ----------------------
    public boolean isMember(String roomName, User user) {
        lock.readLock().lock();
        try {
            // เช็กว่ามีห้องนี้อยู่และ user เป็นสมาชิกในห้องนี้หรือไม่
            return rooms.containsKey(roomName) && rooms.get(roomName).contains(user);
        } finally {
            lock.readLock().unlock();
        }
    }

    // ----------------------- ลบ user ออกจากทุกห้อง ------------------------
    public void removeUserFromAllRooms(User user, BroadcasterPool broadcasterPool) {
        lock.writeLock().lock();
        try {
            // ใช้ it ให้ roomItem เป็น <ชื่อห้อง, สมาชิกในห้อง> และวนลูปทีละห้อง
            Iterator<Map.Entry<String, Set<User>>> it = rooms.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Set<User>> roomItem = it.next(); // ดึงห้องปัจจุบัน
                if (roomItem.getValue().remove(user)) { // ลบ user ออกจากห้อง ถ้าลบได้ให้ส่งข้อความแจ้งสมาชิกในห้อง
                    System.out.println("[System]: " + user.getClientId() + " removed from " + roomItem.getKey());
                    String text = ": " + user.getClientId() + " left the room.";
                    broadcasterPool.submitTask(new BroadcastTask(roomItem.getKey(), text));
                    if (roomItem.getValue().isEmpty()) {
                        it.remove();
                        System.out.println("[System]: Room " + roomItem.getKey() + " is now empty -> deleted.");
                    }
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
}
