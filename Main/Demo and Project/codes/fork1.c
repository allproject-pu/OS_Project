#include <stdio.h>
#include <unistd.h>
#include <sys/types.h>

int main() {
    pid_t pid = fork(); // Create a new process

    if (pid < 0) {  // Fork failed
        perror("fork");
        return 1;
    }

    if (pid == 0) { // The child process
        printf("Child process: PID = %d, Parent PID = %d\n", getpid(), getppid());
    } else {  // The parent process
        printf("Parent process: PID = %d, Child PID = %d\n", getpid(), pid);
    }

    return 0;
}