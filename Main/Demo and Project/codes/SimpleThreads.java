public class SimpleThreads {

    // Display a message, preceded by the name of the current thread
    static void threadMessage(String message) {
        String threadName = Thread.currentThread().getName();
        System.out.format("%s: %s%n", threadName, message);
    }

    private static class MessageLoop implements Runnable {
        public void run() {
            String importantInfo[] = {
                "First man eats an orange",
                "Second woman eats an orange",
                "Third girl eats an orange",
                "Forth boy will eat an orange too."
            };
            try {
                for (int i = 0; i < importantInfo.length; i++) {
                    Thread.sleep(3000);  // ...?
                    threadMessage(importantInfo[i]); // Print a message
                }
            } catch (InterruptedException e) {
                threadMessage("I wasn't done! But got the " + e);
            }
        }
    }

    public static void main(String args[])
        throws InterruptedException {

        // Delay, in milliseconds before we interrupt MessageLoop thread 
        // (default one second).
        // Try changing patience to 5000 and 20000, and compare the results
        long patience = 1000 * 5;

        // If command line argument present, gives patience in seconds.
        if (args.length > 0) {
            try {
                patience = Long.parseLong(args[0]) * 1000;
            } catch (NumberFormatException e) {
                System.err.println("Argument must be an integer.");
                System.exit(1);
            }
        }

        threadMessage("Starting MessageLoop thread");
        long startTime = System.currentTimeMillis();
        Thread t = new Thread(new MessageLoop());
        t.start();

        threadMessage("Waiting for MessageLoop thread to finish");

        // loop until MessageLoop thread exits
        int count = 1;
        while (t.isAlive()) {
            threadMessage("Still waiting... " + count++);

            try {
                Thread.currentThread().sleep(1000);
            } catch (InterruptedException e) { }

            if ( (System.currentTimeMillis() - startTime > patience)
                  && t.isAlive() ) {
                threadMessage("Tired of waiting!");
                t.interrupt();
                t.join();  // still wait child terminates
            }
        }
        threadMessage("Finally completed!");
    }
}