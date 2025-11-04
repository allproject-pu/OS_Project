public class ServerConnection {
    private static final String SERVER_ADDRESS = "localhost"; // ที่อยู่ของ Server
    private static final int PORT = 5000; // Port ที่ Server เปิดรอรับการเชื่อมต่อ

    public String getAddress() {
        return SERVER_ADDRESS;
    }

    public int getPort() {
        return PORT;
    }
}