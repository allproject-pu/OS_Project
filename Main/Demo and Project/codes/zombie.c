#include <stdio.h>
#include <unistd.h>
#include <sys/types.h>
//#include <sys/wait.h>

int main() {
    pid_t pid = fork(); // Create a new process

    if (pid < 0) { // Fork failed
        perror("fork"); return 1;
    }
    if (pid == 0) {  // This is the child process
        printf("Child process (PID %d) exiting\n", getpid());
        return 0;
    } else {  // This is the parent process
        printf("Parent process (PID %d) sleeping for 10 seconds\n", getpid());
        sleep(20); // allowing the child process to become a zombie
        printf("Parent process (PID %d) exiting\n", getpid());
    }

    return 0;
}