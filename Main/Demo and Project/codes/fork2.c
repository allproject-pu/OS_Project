#include <stdio.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/wait.h>

int main() {
    int num_children = 7; // Number of child processes
    pid_t pids[num_children];
     for (int i = 0; i < num_children; i++) {
        pids[i] = fork(); // Create a new child process
         if (pids[i] < 0) {
            perror("fork"); // Fork failed
            return 1;
        }
         if (pids[i] == 0) { // This is the child process
            printf("Child process with PID %d\n", getpid());
            return 0; // Exit the child process
        }
    }
     // Parent process
    for (int i = 0; i < num_children; i++) {
        wait(NULL); // Wait for each child process to finish
    }
    printf("Number of child processes created: %d\n", num_children);
    return 0;
}