import java.io.PrintWriter;
public class User {
    private final String clientId;

    // ช่องทางที่ Server จะใช้ส่งข้อความกลับไปหา client คนนี้
    // นี่คือ Output Stream ของ Socket ของ client คนนั้น
    private final PrintWriter PrintWriter;

    User(String clientId, PrintWriter PrintWriter) {
        this.clientId = clientId;
        this.PrintWriter = PrintWriter;
    }

    public String getClientId() {
        return clientId;
    }

    public PrintWriter getClientPrintWriter() {
        return PrintWriter;
    }
}