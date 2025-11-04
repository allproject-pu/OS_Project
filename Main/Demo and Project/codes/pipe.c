#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/wait.h>

#define BUFFER_SIZE 256

int main() {
    int parent_to_child_pipe[2]; // Pipe 1: parent writes, child reads
    int child_to_parent_pipe[2]; // Pipe 2: child writes, parent reads
    pid_t pid;
    char message_from_parent[BUFFER_SIZE] = "Hello from the parent!";
    char buffer[BUFFER_SIZE];

    // Create the pipes
    if (pipe(parent_to_child_pipe) == -1 || pipe(child_to_parent_pipe) == -1) {
        perror("pipe");
        return 1;
    }

    pid = fork();

    if (pid < 0) {
        perror("fork");
        return 1;
    }

    if (pid > 0) { // Parent process
        // Close unused pipe ends
        close(parent_to_child_pipe[0]); // Parent doesn't read from pipe 1
        close(child_to_parent_pipe[1]); // Parent doesn't write to pipe 2

        sleep(15);
        // 2. Parent sends message to child
        printf("Parent (PID: %d) sending message to child.\n", getpid());
        write(parent_to_child_pipe[1], message_from_parent, strlen(message_from_parent) + 1);
        close(parent_to_child_pipe[1]); // Close write end of pipe 1

        // 5. Parent displays the obtained message
        read(child_to_parent_pipe[0], buffer, BUFFER_SIZE);
        printf("Parent (PID: %d) received from child: '%s'\n", getpid(), buffer);
        close(child_to_parent_pipe[0]); // Close read end of pipe 2

        wait(NULL); // Wait for the child to finish
    } else { // Child process
        // Close unused pipe ends
        close(parent_to_child_pipe[1]); // Child doesn't write to pipe 1
        close(child_to_parent_pipe[0]); // Child doesn't read from pipe 2

        // 1. Child waits for incoming message
        printf("Child  (PID: %d) waiting for message from parent.\n", getpid());
        read(parent_to_child_pipe[0], buffer, BUFFER_SIZE);
        printf("Child  (PID: %d) received: '%s'\n", getpid(), buffer);

        // 3. Child copies and concatenates the message
        strcat(buffer, buffer);

        // 4. Child sends the message back to its parent
        printf("Child  (PID: %d) sending doubled message back to parent.\n", getpid());
        write(child_to_parent_pipe[1], buffer, strlen(buffer) + 1);
        close(parent_to_child_pipe[0]); // Close read end of pipe 1
        close(child_to_parent_pipe[1]); // Close write end of pipe 2
    }

    return 0;
}